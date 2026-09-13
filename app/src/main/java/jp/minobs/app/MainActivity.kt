package jp.minobs.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import jp.minobs.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var captureService: ScreenStreamService? = null
    private var bound = false
    private var pendingConfig: StreamConfig? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            captureService = (service as ScreenStreamService.LocalBinder).service()
            bound = true
            refreshButtons()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            captureService = null
            refreshButtons()
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ScreenStreamService.ACTION_STATUS) return
            val status = intent.getStringExtra(ScreenStreamService.EXTRA_STATUS) ?: return
            val bitrate = intent.getLongExtra(ScreenStreamService.EXTRA_BITRATE, -1L)
            binding.txtStatus.text = status
            if (bitrate >= 0) binding.txtBitrate.text = "送信: ${bitrate / 1000} kbps"
            refreshButtons()
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val micGranted = result[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micGranted) launchCapturePicker() else toast("マイク権限が必要です")
    }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            binding.txtStatus.text = "画面共有がキャンセルされました"
            return@registerForActivityResult
        }

        ensureServiceBound()
        binding.root.postDelayed({
            val service = captureService
            val config = pendingConfig
            if (service == null || config == null) {
                toast("サービス準備に失敗しました。もう一度試してください")
                return@postDelayed
            }
            val ok = service.prepareCapture(result.resultCode, result.data!!, config)
            if (ok) {
                startService(Intent(this, ScreenStreamService::class.java))
                binding.txtStatus.text = "画面共有準備OK"
                binding.txtPreviewHint.text = "キャプチャ中です。ホームへ戻って別アプリを開けます。"
            }
            refreshButtons()
        }, 250)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupSpinners()
        setupActions()
        ensureServiceBound()
    }

    override fun onStart() {
        super.onStart()
        refreshButtons()
        val filter = IntentFilter(ScreenStreamService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onStop() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        super.onStop()
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        super.onDestroy()
    }

    private fun setupSpinners() {
        binding.spinnerAudio.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("内部音声 + マイク", "内部音声のみ", "マイクのみ"))
        binding.spinnerResolution.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("720p", "1080p"))
        binding.spinnerFps.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("30 fps", "60 fps"))
    }

    private fun setupActions() {
        binding.btnCapture.setOnClickListener {
            if (captureService?.isCaptureReady() == true) {
                captureService?.stopCapture()
                binding.txtPreviewHint.text = "「画面共有を開始」でAndroidの画面共有許可を開きます\n共有後は他のアプリへ移動してOK"
                refreshButtons()
            } else requestPermissionsThenCapture()
        }

        binding.btnRecord.setOnClickListener {
            if (captureService?.toggleRecording() != true) toast("先に画面共有を開始してください")
            refreshButtons()
        }

        binding.btnStream.setOnClickListener {
            val service = captureService
            if (service?.isStreamingNow() == true) service.stopRtmp()
            else {
                val url = binding.editRtmp.text?.toString()?.trim().orEmpty()
                if (!url.startsWith("rtmp://") && !url.startsWith("rtmps://")) {
                    toast("RTMP URLを入力してください")
                    return@setOnClickListener
                }
                if (service?.startRtmp(url) != true) toast("先に画面共有を開始してください")
            }
            refreshButtons()
        }
    }

    private fun requestPermissionsThenCapture() {
        pendingConfig = currentConfig()
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) launchCapturePicker() else audioPermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun launchCapturePicker() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun currentConfig(): StreamConfig {
        val resolution = binding.spinnerResolution.selectedItemPosition
        val fps = if (binding.spinnerFps.selectedItemPosition == 1) 60 else 30
        val (width, height, bitrate) = if (resolution == 1) Triple(1920, 1080, 8_000_000) else Triple(1280, 720, 5_000_000)
        val audio = when (binding.spinnerAudio.selectedItemPosition) {
            1 -> StreamConfig.AudioMode.INTERNAL
            2 -> StreamConfig.AudioMode.MICROPHONE
            else -> StreamConfig.AudioMode.MIX
        }
        val rotation = if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) 90 else 0
        return StreamConfig(width, height, fps, bitrate, rotation, audio)
    }

    private fun ensureServiceBound() {
        val intent = Intent(this, ScreenStreamService::class.java)
        if (!bound) bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    private fun refreshButtons() {
        val ready = captureService?.isCaptureReady() == true
        val streaming = captureService?.isStreamingNow() == true
        val recording = captureService?.isRecordingNow() == true
        binding.btnCapture.text = if (ready) "画面共有を停止" else "画面共有を開始"
        binding.btnRecord.isEnabled = ready
        binding.btnStream.isEnabled = ready
        binding.btnRecord.text = if (recording) "録画停止" else "録画開始"
        binding.btnStream.text = if (streaming) "配信停止" else "配信開始"
        binding.txtLive.text = when {
            streaming -> "LIVE"
            recording -> "REC"
            ready -> "READY"
            else -> "OFFLINE"
        }
        binding.txtLive.setTextColor(when {
            streaming || recording -> Color.rgb(255, 92, 108)
            ready -> Color.rgb(67, 209, 122)
            else -> Color.rgb(170, 178, 192)
        })
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
