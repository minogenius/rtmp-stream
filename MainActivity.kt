package com.example.screenstream

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.screenstream.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private var isStreaming = false
    private var pendingConfig: StreamConfig? = null

    // ---------------------------------------------------------------------
    // Activity Result launchers (replace startActivityForResult)
    // ---------------------------------------------------------------------

    /** Launches the system screen-capture consent dialog. */
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val config = pendingConfig
        if (result.resultCode == RESULT_OK && result.data != null && config != null) {
            startStreamingService(config, result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, R.string.error_projection_denied, Toast.LENGTH_SHORT).show()
            resetToggleButton()
        }
        pendingConfig = null
    }

    /** Requests RECORD_AUDIO (and POST_NOTIFICATIONS on API 33+) at runtime. */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val micGranted = grants[Manifest.permission.RECORD_AUDIO] == true
        if (micGranted) {
            launchScreenCaptureConsent()
        } else {
            Toast.makeText(this, R.string.error_permission_mic, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------------
    // Status broadcast receiver from ScreenStreamService
    // ---------------------------------------------------------------------

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val statusName = intent?.getStringExtra(ScreenStreamService.EXTRA_STATUS) ?: return
            val status = ScreenStreamService.StreamStatus.valueOf(statusName)
            val bitrate = intent.getLongExtra(ScreenStreamService.EXTRA_BITRATE, -1L)
            val message = intent.getStringExtra(ScreenStreamService.EXTRA_MESSAGE)
            applyStatus(status, bitrate, message)
        }
    }

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setupSpinners()
        setupAudioSourceAvailability()

        binding.btnToggleStream.setOnClickListener {
            if (isStreaming) stopStreaming() else beginStreamRequest()
        }
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            statusReceiver, IntentFilter(ScreenStreamService.BROADCAST_STATUS)
        )
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
        super.onStop()
    }

    // ---------------------------------------------------------------------
    // Spinner setup
    // ---------------------------------------------------------------------

    private fun setupSpinners() {
        binding.spResolution.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            StreamConfig.RESOLUTIONS.map { it.first }
        )

        binding.spFps.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            StreamConfig.FPS_OPTIONS.map { "$it fps" }
        )

        binding.spVideoBitrate.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            StreamConfig.VIDEO_BITRATES_KBPS.map { "$it Kbps" }
        )

        binding.spAudioBitrate.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            StreamConfig.AUDIO_BITRATES_KBPS.map { "$it Kbps" }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    /** Internal-audio capture needs API 29+; disable the option below that. */
    private fun setupAudioSourceAvailability() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            binding.rbInternalOnly.isEnabled = false
            binding.rbBoth.isEnabled = false
        }
    }

    // ---------------------------------------------------------------------
    // Start flow: permissions -> MediaProjection consent -> service start
    // ---------------------------------------------------------------------

    private fun beginStreamRequest() {
        val config = buildConfigFromUi() ?: return
        pendingConfig = config

        val neededPermissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            neededPermissions += Manifest.permission.POST_NOTIFICATIONS
        }

        val notGranted = neededPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            launchScreenCaptureConsent()
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun launchScreenCaptureConsent() {
        binding.btnToggleStream.isEnabled = false
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startStreamingService(config: StreamConfig, resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, ScreenStreamService::class.java).apply {
            action = ScreenStreamService.ACTION_START
            putExtra(ScreenStreamService.EXTRA_CONFIG, config)
            putExtra(ScreenStreamService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenStreamService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(this, serviceIntent)

        isStreaming = true
        binding.btnToggleStream.isEnabled = true
        binding.btnToggleStream.text = getString(R.string.btn_stop_stream)
        applyStatus(ScreenStreamService.StreamStatus.CONNECTING, -1, null)
    }

    private fun stopStreaming() {
        val stopIntent = Intent(this, ScreenStreamService::class.java).apply {
            action = ScreenStreamService.ACTION_STOP
        }
        ContextCompat.startForegroundService(this, stopIntent)

        isStreaming = false
        resetToggleButton()
        applyStatus(ScreenStreamService.StreamStatus.DISCONNECTED, -1, null)
    }

    private fun resetToggleButton() {
        binding.btnToggleStream.isEnabled = true
        binding.btnToggleStream.text = getString(R.string.btn_start_stream)
    }

    // ---------------------------------------------------------------------
    // UI <-> StreamConfig mapping
    // ---------------------------------------------------------------------

    private fun buildConfigFromUi(): StreamConfig? {
        val url = binding.etRtmpUrl.text?.toString()?.trim().orEmpty()
        val key = binding.etStreamKey.text?.toString()?.trim().orEmpty()
        if (url.isBlank() || !url.startsWith("rtmp")) {
            Toast.makeText(this, R.string.error_missing_url, Toast.LENGTH_SHORT).show()
            return null
        }

        val (_, resolution) = StreamConfig.RESOLUTIONS[binding.spResolution.selectedItemPosition]
        val fps = StreamConfig.FPS_OPTIONS[binding.spFps.selectedItemPosition]
        val videoBitrateKbps = StreamConfig.VIDEO_BITRATES_KBPS[binding.spVideoBitrate.selectedItemPosition]
        val audioBitrateKbps = StreamConfig.AUDIO_BITRATES_KBPS[binding.spAudioBitrate.selectedItemPosition]

        val audioSource = when (binding.rgAudioSource.checkedRadioButtonId) {
            binding.rbInternalOnly.id -> AudioSource.INTERNAL_ONLY
            binding.rbBoth.id -> AudioSource.BOTH
            else -> AudioSource.MIC_ONLY
        }

        return StreamConfig(
            rtmpUrl = url,
            streamKey = key,
            width = resolution.first,
            height = resolution.second,
            fps = fps,
            videoBitrateBps = videoBitrateKbps * 1000,
            audioBitrateBps = audioBitrateKbps * 1000,
            audioSource = audioSource
        )
    }

    // ---------------------------------------------------------------------
    // Status UI
    // ---------------------------------------------------------------------

    private fun applyStatus(
        status: ScreenStreamService.StreamStatus,
        bitrate: Long,
        message: String?
    ) {
        val text = when (status) {
            ScreenStreamService.StreamStatus.DISCONNECTED -> {
                isStreaming = false
                resetToggleButton()
                getString(R.string.status_disconnected)
            }
            ScreenStreamService.StreamStatus.CONNECTING ->
                getString(R.string.status_connecting)
            ScreenStreamService.StreamStatus.RECONNECTING ->
                getString(R.string.status_reconnecting)
            ScreenStreamService.StreamStatus.LIVE -> {
                val kbps = if (bitrate > 0) " (${bitrate / 1000} Kbps)" else ""
                "${getString(R.string.status_live)}$kbps"
            }
            ScreenStreamService.StreamStatus.ERROR -> {
                isStreaming = false
                resetToggleButton()
                message?.let {
                    Toast.makeText(this, it, Toast.LENGTH_LONG).show()
                }
                getString(R.string.status_error)
            }
        }
        binding.tvStatus.text = text
    }
}
