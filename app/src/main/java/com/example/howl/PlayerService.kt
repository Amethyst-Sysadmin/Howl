package com.example.howl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

class PlayerService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playerJob: Job? = null
    private var smootherJob: Job? = null
    private val pulseQueueMutex = Mutex()
    private val pulseQueue = CircularBuffer<Pulse>(capacity = 100)

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "PlayerServiceChannel"
        const val NOTIFICATION_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        //Log.d("PlayerService", "onStartCommand")
        startForegroundService()
        startSmootherJob()
        startPlayerLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        HLog.d("PlayerService", "Foreground service destroyed")
    }

    private fun startForegroundService() {
        createNotificationChannel()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
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

    private fun startSmootherJob() {
        if (smootherJob?.isActive == true) return
        Log.d("PlayerService", "Starting smoother job")
        smootherJob = serviceScope.launch {
            try {
                while (isActive) {
                    val pulse = pulseQueueMutex.withLock {
                        pulseQueue.removeFirstOrNull()
                    }
                    if (pulse != null) {
                        DataRepository.addPulsesToHistory(listOf(pulse))
                        val waitTime = Player.output.pulseTime
                        delay(waitTime.seconds)
                    } else {
                        delay(10) // Avoid busy waiting
                    }
                }
            } catch (e: CancellationException) {
                Log.d("PlayerService", "Smoother job cancelled")
                // Normal cancellation
            } catch (e: Exception) {
                Log.d("PlayerService", "Smoother job exception")
                // Log or handle exception
            } finally {
                pulseQueueMutex.withLock {
                    if (!pulseQueue.isEmpty) {
                        Log.d("PlayerService", "Adding ${pulseQueue.size} remaining pulses")
                        val remainingPulses = pulseQueue.toList()
                        DataRepository.addPulsesToHistory(remainingPulses)
                        pulseQueue.clear()
                    }
                }
            }
        }
    }

    private fun startPlayerLoop() {
        if (playerJob?.isActive == true) return
        HLog.d("PlayerService", "Starting foreground service loop")
        playerJob = serviceScope.launch {
            try {
                while (isActive) {
                    val startTime = System.nanoTime()
                    //Log.d("PlayerService", "Player loop running in service, start time=$startTime")
                    val output = Player.output
                    val pulseBatchSize = output.pulseBatchSize
                    val playerState = DataRepository.playerState.value
                    val advancedControlState = DataRepository.playerAdvancedControlsState.value
                    if (!playerState.isPlaying) break

                    val currentSource = playerState.activePulseSource
                    val currentPosition = Player.getCurrentPosition()

                    if (currentSource == null) {
                        Player.stopPlayer()
                        break
                    }

                    if (currentSource.duration != null && currentSource.duration!! > 0) {
                        if (currentPosition > currentSource.duration!!) {
                            if (currentSource.shouldLoop) {
                                Player.startPlayer(0.0)
                                continue
                            } else {
                                Player.stopPlayer()
                                break
                            }
                        }
                    }

                    val mainOptionsState = DataRepository.mainOptionsState.value
                    val times = Player.getNextTimes(currentPosition)
                    val pulses = times.map { Player.getPulseAtTime(it) }

                    if (output.ready) {
                        val pulsesToSend = when {
                            !mainOptionsState.globalMute -> pulses
                            output.sendSilenceWhenMuted -> List(pulseBatchSize) { Pulse() }
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

                    val nextPosition =
                        currentPosition + (output.timerDelay * advancedControlState.playbackSpeed)
                    DataRepository.setPlayerPosition(nextPosition)
                    currentSource.updateState(nextPosition)

                    Player.handlePowerAutoIncrement()

                    val smootherCharts = DataRepository.miscOptionsState.value.smootherCharts

                    if(smootherCharts && pulseBatchSize > 1) {
                        pulseQueueMutex.withLock {
                            // Clear existing pulses in queue
                            if (!pulseQueue.isEmpty) {
                                val oldPulses = pulseQueue.toList()
                                pulseQueue.clear()
                                DataRepository.addPulsesToHistory(oldPulses)
                            }
                            // Add new pulses to queue
                            pulseQueue.addAll(pulses)
                        }
                    }
                    else {
                        DataRepository.addPulsesToHistory(pulses)
                    }

                    // The loop delay is adjusted slightly to try and hit the target, taking into
                    // account our own processing time. But always waiting for at least 90% of the
                    // configured delay to avoid overwhelming a busy system, or calling Bluetooth
                    // devices faster than intended.
                    val desiredDelay = output.timerDelay.seconds
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
}