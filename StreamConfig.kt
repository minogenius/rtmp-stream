package com.example.screenstream

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Audio capture strategy chosen by the user. */
enum class AudioSource {
    MIC_ONLY,
    INTERNAL_ONLY,
    BOTH
}

/**
 * All the settings needed to start a stream. Passed from MainActivity to
 * ScreenStreamService as a single Parcelable extra so the service is fully
 * self-contained and survives process restarts with the same intent.
 */
@Parcelize
data class StreamConfig(
    val rtmpUrl: String,
    val streamKey: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val videoBitrateBps: Int,
    val audioBitrateBps: Int,
    val audioSource: AudioSource
) : Parcelable {

    /** Final destination URL with the key concatenated, trimming stray slashes. */
    fun fullUrl(): String {
        val base = rtmpUrl.trimEnd('/')
        val key = streamKey.trimStart('/')
        return if (key.isBlank()) base else "$base/$key"
    }

    companion object {
        val RESOLUTIONS = listOf(
            "1080p (1920x1080)" to Pair(1920, 1080),
            "720p (1280x720)" to Pair(1280, 720),
            "480p (854x480)" to Pair(854, 480)
        )
        val FPS_OPTIONS = listOf(30, 60)
        val VIDEO_BITRATES_KBPS = listOf(1500, 2500, 4000, 6000)
        val AUDIO_BITRATES_KBPS = listOf(96, 128, 160)
    }
}
