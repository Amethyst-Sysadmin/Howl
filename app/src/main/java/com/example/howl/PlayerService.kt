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

    private fun startPlayerLoop() {
        if (playerJob?.isActive == true) return
        HLog.d("PlayerService", "Starting foreground service loop")
        playerJob = serviceScope.launch {
            try {
                while (isActive) {
                    val startTime = System.nanoTime()
                    //Log.d("PlayerService", "Player loop running in service, start time=$startTime")
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
                    val connected =
                        DataRepository.coyoteConnectionStatus.value == ConnectionStatus.Connected

                    val times = Player.getNextTimes(currentPosition)
                    val pulses = times.map { Player.getPulseAtTime(it) }

                    if (connected && !mainOptionsState.globalMute) {
                        DGCoyote.sendPulse(
                            mainOptionsState.channelAPower,
                            mainOptionsState.channelBPower,
                            mainOptionsState.frequencyRange.start,
                            mainOptionsState.frequencyRange.endInclusive,
                            pulses
                        )
                    }

                    val nextPosition =
                        currentPosition + (Player.mainTimerDelay * advancedControlState.playbackSpeed)
                    DataRepository.setPlayerPosition(nextPosition)
                    currentSource.updateState(nextPosition)

                    Player.handlePowerAutoIncrement()

                    // The loop delay is adjusted slightly to try and hit the target, taking into
                    // account our own processing time. But always waiting for at least 90% of the
                    // configured delay to avoid overwhelming a busy system, or calling Bluetooth
                    // devices faster than intended.
                    val smootherCharts = DataRepository.miscOptionsState.value.smootherCharts
                    val desiredDelay = Player.mainTimerDelay.seconds
                    val minDelay = (desiredDelay * 0.9)
                    val elapsed = (System.nanoTime() - startTime).toDuration(DurationUnit.NANOSECONDS)
                    val waitTime = (desiredDelay - elapsed).coerceAtLeast(minDelay)
                    val waitChunk = waitTime / Player.pulseBatchSize
                    //Log.d("Player service", "Wait time - $waitTime     Wait chunk - $waitChunk")
                    if(smootherCharts) {
                        for (pulse in pulses) {
                            DataRepository.addPulsesToHistory(listOf(pulse))
                            delay(waitChunk)
                        }
                    }
                    else {
                        DataRepository.addPulsesToHistory(pulses)
                        delay(waitTime)
                    }
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