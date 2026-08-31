package com.example.screenstream

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import androidx.core.app.NotificationCompat
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.pedro.library.rtmp.RtmpDisplay
import com.pedro.common.ConnectChecker

/**
 * Foreground service that owns the MediaProjection screen-capture session and the
 * RootEncoder [RtmpDisplay] instance that reads from it and pushes an RTMP stream.
 *
 * The service keeps running (and the notification stays visible) for as long as the
 * stream is active, independent of MainActivity's lifecycle.
 */
class ScreenStreamService : Service(), ConnectChecker {

    companion object {
        const val NOTIFICATION_ID = 7421
        const val NOTIFICATION_CHANNEL_ID = "screen_stream_channel"

        const val ACTION_START = "com.example.screenstream.action.START"
        const val ACTION_STOP = "com.example.screenstream.action.STOP"

        const val EXTRA_CONFIG = "extra_config"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        // Broadcast actions used to report connection state back to MainActivity.
        const val BROADCAST_STATUS = "com.example.screenstream.broadcast.STATUS"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_BITRATE = "extra_bitrate"
        const val EXTRA_MESSAGE = "extra_message"

        enum class StreamStatus { DISCONNECTED, CONNECTING, LIVE, RECONNECTING, ERROR }
    }

    private var rtmpDisplay: RtmpDisplay? = null
    private var currentConfig: StreamConfig? = null
    private var isStreaming = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
            else -> Log.w(TAG, "Unknown or null action received")
        }
        // START_NOT_STICKY: if the system kills the service while not streaming we don't
        // want it silently restarting without a valid MediaProjection token (which cannot
        // be resumed after death anyway).
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------------
    // Start
    // ---------------------------------------------------------------------

    private fun handleStart(intent: Intent) {
        val config = intent.getParcelableExtraCompat<StreamConfig>(EXTRA_CONFIG) ?: run {
            Log.e(TAG, "Missing StreamConfig, aborting start")
            stopSelf()
            return
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData = intent.getParcelableExtraCompat<Intent>(EXTRA_RESULT_DATA)
        if (resultData == null) {
            Log.e(TAG, "Missing MediaProjection result data, aborting start")
            stopSelf()
            return
        }

        currentConfig = config

        // Must call startForeground() within a few seconds of the service starting,
        // otherwise the OS kills it (strictly enforced on API 31+).
        startForeground(NOTIFICATION_ID, buildNotification(isLive = false))

        rtmpDisplay = RtmpDisplay(applicationContext, /* useOpenGl = */ true, this).apply {
            // Hand the MediaProjection consent result to the encoder.
            setIntentResult(resultCode, resultData)
        }

        prepareAudioForConfig(config)

        val prepared = rtmpDisplay?.prepareVideo(
            config.width,
            config.height,
            config.fps,
            config.videoBitrateBps,
            0 // rotation: 0 keeps the natural screen orientation
        ) ?: false

        if (!prepared) {
            broadcastStatus(StreamStatus.ERROR, message = "Failed to prepare video encoder")
            stopSelf()
            return
        }

        rtmpDisplay?.startStream(config.fullUrl())
        isStreaming = true
        broadcastStatus(StreamStatus.CONNECTING)
    }

    /** Configures mic / internal / mixed audio capture based on user selection. */
    private fun prepareAudioForConfig(config: StreamConfig) {
        val sampleRate = 44100
        val stereo = true
        val echoCanceler = true
        val noiseSuppressor = true

        when (config.audioSource) {
            AudioSource.MIC_ONLY -> {
                rtmpDisplay?.prepareAudio(
                    config.audioBitrateBps, sampleRate, stereo, echoCanceler, noiseSuppressor
                )
            }
            AudioSource.INTERNAL_ONLY -> {
                // Requires API 29+ (AudioPlaybackCapture) and shares the same
                // MediaProjection token used for the video capture.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    rtmpDisplay?.prepareInternalAudio(
                        config.audioBitrateBps, sampleRate, stereo
                    )
                } else {
                    Log.w(TAG, "Internal audio capture requires API 29+, falling back to mic")
                    rtmpDisplay?.prepareAudio(
                        config.audioBitrateBps, sampleRate, stereo, echoCanceler, noiseSuppressor
                    )
                }
            }
            AudioSource.BOTH -> {
                // NOTE: RootEncoder does not natively mix mic + internal audio into a
                // single AAC track on every version. Where unsupported, this falls back
                // to internal-only audio (game/media sound) since that is what most
                // "screen streaming" use cases care about. See RootEncoder issue #661
                // for the upstream limitation; swap in a custom mixed AudioSource here
                // if your target library version exposes one.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    rtmpDisplay?.prepareInternalAudio(
                        config.audioBitrateBps, sampleRate, stereo
                    )
                } else {
                    rtmpDisplay?.prepareAudio(
                        config.audioBitrateBps, sampleRate, stereo, echoCanceler, noiseSuppressor
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Stop
    // ---------------------------------------------------------------------

    private fun handleStop() {
        try {
            if (rtmpDisplay?.isStreaming == true) {
                rtmpDisplay?.stopStream()
            }
            rtmpDisplay?.stopRecord()
        } catch (e: Exception) {
            Log.e(TAG, "Error while stopping stream", e)
        } finally {
            rtmpDisplay = null
            isStreaming = false
            broadcastStatus(StreamStatus.DISCONNECTED)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Safety net: make sure the projection/encoder is torn down even if the
        // service is killed without going through ACTION_STOP.
        if (isStreaming) {
            handleStop()
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Notification
    // ---------------------------------------------------------------------

    private fun buildNotification(isLive: Boolean): Notification {
        val stopIntent = Intent(this, ScreenStreamService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(
                getString(if (isLive) R.string.notif_title_streaming else R.string.notif_title_stopped)
            )
            .setContentText(currentConfig?.fullUrl()?.substringBefore("://").orEmpty())
            .setOngoing(isLive)
            .setContentIntent(contentPendingIntent)
            .addAction(0, getString(R.string.notif_action_stop), stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(isLive: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(isLive))
    }

    // ---------------------------------------------------------------------
    // ConnectChecker callbacks (RootEncoder)
    // ---------------------------------------------------------------------

    override fun onConnectionStarted(url: String) {
        broadcastStatus(StreamStatus.CONNECTING)
    }

    override fun onConnectionSuccess() {
        updateNotification(isLive = true)
        broadcastStatus(StreamStatus.LIVE)
    }

    override fun onConnectionFailed(reason: String) {
        Log.e(TAG, "Connection failed: $reason")
        // Retry once automatically; RootEncoder's own reconnection can also be
        // wired in here if the library version supports reTry().
        broadcastStatus(StreamStatus.ERROR, message = reason)
        handleStop()
    }

    override fun onNewBitrate(bitrate: Long) {
        broadcastStatus(StreamStatus.LIVE, bitrate = bitrate)
    }

    override fun onDisconnect() {
        updateNotification(isLive = false)
        broadcastStatus(StreamStatus.DISCONNECTED)
    }

    override fun onAuthError() {
        broadcastStatus(StreamStatus.ERROR, message = "Authentication error")
        handleStop()
    }

    override fun onAuthSuccess() {
        Log.d(TAG, "RTMP auth success")
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun broadcastStatus(
        status: StreamStatus,
        bitrate: Long? = null,
        message: String? = null
    ) {
        val intent = Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_STATUS, status.name)
            bitrate?.let { putExtra(EXTRA_BITRATE, it) }
            message?.let { putExtra(EXTRA_MESSAGE, it) }
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
}

private const val TAG = "ScreenStreamService"

/** SDK-version-safe Parcelable extra retrieval (deprecated APIs pre-33). */
private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(
    key: String
): T? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key) as? T
    }
}
