package com.example.howl

import android.os.SystemClock
import focstim_rpc.Constants.AxisType
import focstim_rpc.Constants.OutputMode
import focstim_rpc.FocstimRpc
import focstim_rpc.Messages
import focstim_rpc.request as buildRequest
import focstim_rpc.requestAxisMoveTo as buildAxisMoveTo
import focstim_rpc.requestSignalStart as buildSignalStart
import focstim_rpc.rpcMessage as buildRpcMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class FocException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * TCP transport and RPC framing for the FOC Stim. All payloads are
 * [FocstimRpc.RpcMessage] protos wrapped in [FocHdlc] frames.
 *
 * The reusable core is [sendRequest] (send any request, await and validate its
 * response) plus [processMessage] (match an inbound message to its request or
 * notification handler). Future device features only need small typed
 * wrappers like [requestAxisMoveTo].
 */
class FocApi(
    private val host: String,
    val port: Int = FOC_TCP_PORT,
    private val scope: CoroutineScope,
    private val onDisconnect: (failed: FocApi, cause: Throwable?) -> Unit = { _, _ -> },
    private val onDeviceStopped: (stopped: FocApi) -> Unit = {},
) {
    private var socket: Socket? = null
    private val writeMutex = Mutex()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<FocstimRpc.Response>>()
    private val hdlc = FocHdlc()
    private var readJob: Job? = null
    private val closed = AtomicBoolean(false)

    @Volatile
    var lastReceivedMs: Long = 0L
        private set

    // Key request IDs cycle 1..4096
    private var requestId = Random.nextInt(1, 4097)

    @Synchronized
    private fun nextRequestId(): Int {
        requestId = requestId % 4096 + 1
        return requestId
    }

    // We use a separate 4097..8192 space for fire-and-forget streaming requests.
    // Responses to these are untracked and ignored.
    private var ffRequestId = Random.nextInt(4097, 8193)

    @Synchronized
    private fun nextFireAndForgetId(): Int {
        val id = ffRequestId
        ffRequestId = if (ffRequestId >= 8192) 4097 else ffRequestId + 1
        return id
    }

    suspend fun connect() {
        val newSocket = Socket()
        try {
            withContext(Dispatchers.IO) {
                newSocket.tcpNoDelay = true
                newSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            }
        } catch (e: IOException) {
            try {
                newSocket.close()
            } catch (_: IOException) {
            }
            throw FocException("Could not connect to FOC Stim at $host:$port", e)
        }
        socket = newSocket
        lastReceivedMs = SystemClock.elapsedRealtime()
        readJob = scope.launch { readLoop(newSocket) }
    }

    /** Idempotent, non-suspending: safe to call from `stop()`/`destroy()`. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        readJob?.cancel()
        readJob = null
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        val err = FocException("Connection closed")
        pending.values.forEach { it.completeExceptionally(err) }
        pending.clear()
    }

    val isClosed: Boolean get() = closed.get()

    // ── Reusable request/response core ────────────────────────────────────

    /**
     * Send any [FocstimRpc.Request], await its response and throw [FocException]
     * on timeout or device-reported error. Cancellation-safe: a timed-out
     * request is removed from the pending map.
     */
    suspend fun sendRequest(rpcRequest: FocstimRpc.Request, timeoutMs: Long): FocstimRpc.Response {
        val frame = FocHdlc.encode(buildRpcMessage { request = rpcRequest }.toByteArray())
        val awaiting = CompletableDeferred<FocstimRpc.Response>()
        pending[rpcRequest.id] = awaiting
        try {
            writeFrame(frame)
        } catch (e: IOException) {
            pending.remove(rpcRequest.id)
            handleTransportError(e)
            throw FocException("Failed to send request ${rpcRequest.id}", e)
        }
        try {
            return checkResponse(withTimeout(timeoutMs) { awaiting.await() })
        } catch (e: TimeoutCancellationException) {
            pending.remove(rpcRequest.id)
            throw FocException("FOC Stim request ${rpcRequest.id} timed out", e)
        }
    }

    /**
     * Match one inbound [FocstimRpc.RpcMessage] to its pending request, or
     * dispatch it to [processNotification]. Unknown response IDs are ignored
     * (they belong to already-timed-out requests, or to untracked
     * fire-and-forget streaming updates).
     */
    fun processMessage(message: FocstimRpc.RpcMessage) {
        when {
            message.hasResponse() -> {
                val response = message.response
                pending.remove(response.id)?.complete(response)
                    //?: HLog.v(TAG, "No pending request for response id ${response.id}")
            }
            message.hasNotification() -> processNotification(message.notification)
            message.hasRequest() -> HLog.v(TAG, "Ignoring inbound request from device")
        }
    }

    /** Throw [FocException] if the device reported an error for this response. */
    fun checkResponse(response: FocstimRpc.Response): FocstimRpc.Response {
        if (response.hasError()) {
            throw FocException("Device returned error ${response.error.code}")
        }
        return response
    }

    fun processNotification(notification: FocstimRpc.Notification) {
        when {
            notification.hasNotificationBoot() ->
                HLog.w(TAG, "Device rebooted; output will stop")
            /*notification.hasNotificationDeviceVolume() ->
                HLog.v(TAG, "Device volume: ${notification.notificationDeviceVolume.volume}")*/
            notification.hasNotificationDebugString() -> {
                val text = notification.notificationDebugString.message
                HLog.w(TAG, "Device: $text")
                // The firmware stops the signal itself when it considers comms
                // lost. Surface that so the output drops to Disconnected instead
                // of streaming into a dead signal forever.
                if (text.contains("Comms lost", ignoreCase = true)) {
                    try {
                        onDeviceStopped(this)
                    } catch (cbError: Exception) {
                        HLog.w(TAG, "Device-stopped handler failed: ${cbError.message}")
                    }
                }
            }
            //else -> Log.v(TAG, "Notification: ${notification.allFields.keys.joinToString { it.name }}")
        }
    }

    // ── Typed request wrappers ────────────────────────────────────────────

    suspend fun requestFirmwareVersion(): FocstimRpc.Response =
        sendRequest(
            buildRequest {
                id = nextRequestId()
                requestFirmwareVersion = Messages.RequestFirmwareVersion.getDefaultInstance()
            },
            SETUP_TIMEOUT_MS
        )

    suspend fun requestCapabilitiesGet(): FocstimRpc.Response =
        sendRequest(
            buildRequest {
                id = nextRequestId()
                requestCapabilitiesGet = Messages.RequestCapabilitiesGet.getDefaultInstance()
            },
            SETUP_TIMEOUT_MS
        )

    suspend fun requestSignalStart(outputMode: OutputMode): FocstimRpc.Response =
        sendRequest(
            buildRequest {
                id = nextRequestId()
                requestSignalStart = buildSignalStart { mode = outputMode }
            },
            SETUP_TIMEOUT_MS
        )

    suspend fun requestSignalStop(): FocstimRpc.Response =
        sendRequest(
            buildRequest {
                id = nextRequestId()
                requestSignalStop = Messages.RequestSignalStop.getDefaultInstance()
            },
            STOP_TIMEOUT_MS
        )

    /**
     * Fire-and-forget axis update for the 40Hz streaming path: the frame is
     * written and the device's response (if any) is ignored. Uses a separate
     * ID space so streaming updates never collide with tracked setup/teardown
     * requests. No per-axis allocations or timeout coroutines — backpressure
     * is handled upstream by the conflated pulse channel (stale pulses are
     * overwritten when the network lags) plus TCP errors surfacing via
     * [handleTransportError].
     */
    suspend fun requestAxisMoveTo(axisType: AxisType, axisValue: Float, intervalMs: Int) {
        val axisRequest = buildRequest {
            id = nextFireAndForgetId()
            requestAxisMoveTo = buildAxisMoveTo {
                axis = axisType
                value = axisValue
                interval = intervalMs
            }
        }
        val frame = FocHdlc.encode(buildRpcMessage { request = axisRequest }.toByteArray())
        try {
            writeFrame(frame)
        } catch (e: IOException) {
            handleTransportError(e)
            throw FocException("Failed to send axis update", e)
        }
    }

    suspend fun requestAxesMoveToBatch(updates: List<Triple<AxisType, Float, Int>>) {
        if (updates.isEmpty()) return

        // 1. Build all HDLC frames
        val frames = updates.map { (axisType, axisValue, intervalMs) ->
            val axisRequest = buildRequest {
                id = nextFireAndForgetId()
                requestAxisMoveTo = buildAxisMoveTo {
                    axis = axisType
                    value = axisValue
                    interval = intervalMs
                }
            }
            FocHdlc.encode(buildRpcMessage { request = axisRequest }.toByteArray())
        }

        // 2. Combine into a single ByteArray
        val totalSize = frames.sumOf { it.size }
        val combined = ByteArray(totalSize)
        var offset = 0
        for (frame in frames) {
            System.arraycopy(frame, 0, combined, offset, frame.size)
            offset += frame.size
        }

        // 3. Single write and flush (Guaranteed single TCP segment)
        writeFrame(combined)
    }

    // ── Transport internals ───────────────────────────────────────────────

    // Callers already run on Dispatchers.IO (stream consumer, runConnect), so
    // write directly without another withContext hop per frame.
    private suspend fun writeFrame(frame: ByteArray) {
        val current = socket ?: throw FocException("Not connected")
        writeMutex.withLock {
            current.getOutputStream().apply {
                write(frame)
                flush()
            }
        }
    }

    private suspend fun readLoop(activeSocket: Socket) {
        val input = try {
            withContext(Dispatchers.IO) { activeSocket.getInputStream() }
        } catch (e: IOException) {
            handleTransportError(e)
            return
        }
        val buffer = ByteArray(256)
        try {
            while (scope.isActive && !closed.get()) {
                val read = withContext(Dispatchers.IO) { input.read(buffer) }
                if (read < 0) throw EOFException("Connection closed by device")
                if (read == 0) continue
                lastReceivedMs = SystemClock.elapsedRealtime()
                for (frame in hdlc.parse(buffer.copyOf(read))) {
                    try {
                        processMessage(FocstimRpc.RpcMessage.parseFrom(frame))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // A single malformed frame or notification shouldn't
                        // kill the reader loop.
                        HLog.w(TAG, "Dropping bad frame: ${e.message}")
                    }
                }
            }
        } catch (_: CancellationException) {
            // Normal teardown via close().
        } catch (e: IOException) {
            handleTransportError(e)
        }
    }

    private fun handleTransportError(e: Throwable?) {
        if (closed.get()) return
        HLog.w(TAG, "Connection error: ${e?.message}")
        close()
        try {
            onDisconnect(this, e)
        } catch (cbError: Exception) {
            HLog.w(TAG, "Disconnect handler failed: ${cbError.message}")
        }
    }

    companion object {
        const val TAG = "FocApi"
        const val FOC_TCP_PORT = 55533
        const val CONNECT_TIMEOUT_MS = 4000
        const val SETUP_TIMEOUT_MS = 4000L
        const val STOP_TIMEOUT_MS = 2000L
    }
}

/**
 * HDLC framing for the FOC Stim RPC protocol.
 * Port of Restim's hdlc.py
 */
class FocHdlc(private val maxLen: Int = 1024) {
    private var escapeNext = false
    private var consuming = false
    private val pending = ArrayList<Byte>(maxLen + 2)

    /**
     * Feed newly received bytes, returning any complete, CRC-valid payloads.
     */
    fun parse(data: ByteArray): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        for (raw in data) {
            var c = raw.toInt() and 0xFF
            if (c == FRAME_BOUNDARY_MARKER) {
                if (pending.size >= 2) {
                    val payload = pending.toByteArray()
                    val body = payload.copyOf(payload.size - 2)
                    val packetCrc = (payload[payload.size - 2].toInt() and 0xFF) or
                            ((payload[payload.size - 1].toInt() and 0xFF) shl 8)
                    if (crcX25(body) == packetCrc) {
                        frames.add(body)
                    }
                }
                reset()
            } else if (c == ESCAPE_MARKER) {
                escapeNext = true
            } else {
                if (escapeNext) {
                    c = c xor 0x20
                    escapeNext = false
                }
                if (consuming) {
                    pending.add(c.toByte())
                }
                if (pending.size > maxLen) {
                    overrun()
                }
            }
        }
        return frames
    }

    private fun reset() {
        escapeNext = false
        pending.clear()
        consuming = true
    }

    private fun overrun() {
        escapeNext = false
        pending.clear()
        consuming = false
    }

    companion object {
        const val FRAME_BOUNDARY_MARKER = 0x7E
        const val ESCAPE_MARKER = 0x7D

        fun encode(payload: ByteArray): ByteArray {
            require(payload.size <= 65536) { "Maximum length of payload is 65536" }
            val crc = crcX25(payload)
            val checksum = byteArrayOf((crc and 0xFF).toByte(), ((crc ushr 8) and 0xFF).toByte())
            // Worst case every byte needs escaping, plus 2 boundary markers.
            val out = ArrayList<Byte>(payload.size + 4)
            out.add(FRAME_BOUNDARY_MARKER.toByte())
            escapeInto(out, payload)
            escapeInto(out, checksum)
            out.add(FRAME_BOUNDARY_MARKER.toByte())
            return out.toByteArray()
        }

        private fun escapeInto(out: ArrayList<Byte>, data: ByteArray) {
            for (raw in data) {
                val c = raw.toInt() and 0xFF
                if (c == FRAME_BOUNDARY_MARKER || c == ESCAPE_MARKER) {
                    out.add(ESCAPE_MARKER.toByte())
                    out.add((c xor 0x20).toByte())
                } else {
                    out.add(raw)
                }
            }
        }

        /**
         * CRC-16/X25 (reflected poly 0x8408, init 0xFFFF, xor-out 0xFFFF).
         * Yields 0x906E for "123456789".
         */
        fun crcX25(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
            var crc = 0xFFFF
            for (i in offset until offset + length) {
                crc = crc xor (data[i].toInt() and 0xFF)
                repeat(8) {
                    crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8408 else crc ushr 1
                }
            }
            return crc xor 0xFFFF
        }
    }
}
