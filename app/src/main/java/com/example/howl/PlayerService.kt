package com.example.howl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import android.util.Log
import androidx.core.app.ServiceCompat
import kotlin.time.Duration.Companion.seconds

class PlayerService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playerJob: Job? = null

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "PlayerServiceChannel"
        const val NOTIFICATION_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        //Log.d("PlayerService", "onStartCommand")
        startForegroundService()
        startPlayerLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        HLog.d("PlayerService", "Player service ended")
    }

    private fun startForegroundService() {
        createNotificationChannel()
        val notification = createNotification()

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // Sometimes we might not be allowed elevate to a foreground service, for example if Howl
            // was running in the background and we got a network request to start playback.
            val isFgNotAllowed = e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
            if (isFgNotAllowed) {
                HLog.d("PlayerService", "Not allowed to elevate playback service while Howl is running in the background. Turning off battery optimization for the app might help.")
            }
            Log.e("PlayerService", "Failed to start foreground service: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Player Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Background playback service"
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Howl")
            .setContentText("Playing in the background.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startPlayerLoop() {
        if (playerJob?.isActive == true) return
        HLog.d("PlayerService", "Starting main player loop")
        playerJob = serviceScope.launch {
            try {
                while (isActive) {
                    val startTime = System.nanoTime()
                    //Log.d("PlayerService", "Player loop running in service, start time=$startTime")
                    val outputs = listOf(Player.output, Player.recorder)
                    val playerState = Player.playerState.value

                    if (!playerState.isPlaying) break

                    val currentSource = playerState.activePulseSource
                    val currentPosition = Player.getCurrentPosition()
                    //Log.d("PlayerService", "$currentPosition")

                    if (currentSource == null) {
                        Player.stopPlayer()
                        break
                    }

                    if (currentSource.duration != null && currentSource.duration!! > 0) {
                        if (currentPosition > currentSource.duration!!) {
                            if (currentSource.shouldLoop) {
                                Player.seek(0.0)
                                //Player.startPlayer(0.0)
                                continue
                            } else {
                                Player.stopPlayer()
                                break
                            }
                        }
                    }

                    val mainOptionsState = MainOptions.state.value
                    val playbackSpeed = Prefs.playerPlaybackSpeed.value.toDouble()
                    val timeAdjustment = Player.getTimeAdjustment()

                    val allPulses = collectPulses(
                        outputs,
                        currentPosition,
                        playbackSpeed,
                        timeAdjustment
                    )

                    outputs.forEach { output ->
                        val pulses = allPulses[output] ?: emptyList()

                        if (output.ready && pulses.isNotEmpty()) {
                            val pulsesToSend = when {
                                output == Player.recorder -> pulses
                                !mainOptionsState.globalMute -> pulses
                                output.sendSilenceWhenMuted -> List(output.pulseBatchSize) { Pulse() }
                                else -> null
                            }

                            pulsesToSend?.let {
                                output.sendPulses(
                                    mainOptionsState.channelAPower,
                                    mainOptionsState.channelBPower,
                                    mainOptionsState.minFrequency.toDouble(),
                                    mainOptionsState.maxFrequency.toDouble(),
                                    it
                                )
                            }
                        }
                    }

                    val nextPosition =
                        currentPosition + (OUTPUT_TIMER * playbackSpeed)
                    Player.setPlayerPosition(nextPosition)
                    currentSource.updateState(nextPosition)

                    MainOptions.autoIncreasePower(OUTPUT_TIMER)

                    // The loop delay is adjusted slightly to try and hit the target, taking into
                    // account our own processing time. But always waiting for at least 90% of the
                    // configured delay to avoid overwhelming a busy system, or calling Bluetooth
                    // devices faster than intended.
                    val desiredDelay = OUTPUT_TIMER.seconds
                    val minDelay = (desiredDelay * 0.9)
                    val elapsed = (System.nanoTime() - startTime).toDuration(DurationUnit.NANOSECONDS)
                    val waitTime = (desiredDelay - elapsed).coerceAtLeast(minDelay)
                    //Log.d("Player service", "Wait time - $waitTime")
                    delay(waitTime)
                }
            } catch (_: CancellationException) {
                // Normal cancellation
                HLog.d("PlayerService", "Foreground service cancelled")
            } finally {
                stopSelf()
            }
        }
    }

    private fun collectPulses(
        outputs: List<Output>,
        currentPosition: Double,
        playbackSpeed: Double,
        timeAdjustment: Double
    ): Map<Output, List<Pulse>> {
        /*
        Gets the pulses we need for each output. The logic here is a bit complicated since we have
        to call Player.getPulseAtTime with strictly ascending times, because the real-time
        generators like activities cannot go backwards. So we cannot call our pulse source for
        each output in turn and must instead know all the times we need pulses for in advance.

        This process does allow deduplication efficiencies e.g. since the Coyote 3 and the recorder
        both want 4 pulses per 0.1 second tick, we use exactly the same pulses for both and avoid
        generating twice.
        */

        // Step 1: Collect all requested times from all outputs
        val outputTimesMap = mutableMapOf<Output, List<Double>>()
        val allTimesSet = mutableSetOf<Double>()

        for (output in outputs) {
            val times = output.getNextTimes(currentPosition, playbackSpeed, timeAdjustment)
            outputTimesMap[output] = times
            allTimesSet.addAll(times)
        }

        // Step 2: Sort all our unique times in ascending order
        val sortedTimes = allTimesSet.sorted()

        // Step 3: Get the pulses we need for each timestamp from our pulse source
        val pulseMap = mutableMapOf<Double, Pulse>()
        for (time in sortedTimes) {
            pulseMap[time] = Player.getPulseAtTime(time)
        }

        // Step 4: Distribute pulses back to each output based on what they originally asked for
        val result = mutableMapOf<Output, List<Pulse>>()
        for ((output, times) in outputTimesMap) {
            val pulses = times.mapNotNull { pulseMap[it] }
            result[output] = pulses
        }

        return result
    }
}