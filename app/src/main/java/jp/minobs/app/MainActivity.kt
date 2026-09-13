package jp.minobs.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.ImageDecoder
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import jp.minobs.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var binding: ActivityMainBinding
    private var captureService: ScreenStreamService? = null
    private var bound = false
    private var pendingConfig: StreamConfig? = null
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var micVolume = 1f
    private var internalVolume = 1f

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            captureService = (service as ScreenStreamService.LocalBinder).service()
            bound = true
            refreshButtons()
            attachPreviewIfPossible()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false; captureService = null; refreshButtons()
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

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val micGranted = result[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micGranted) launchCapturePicker() else toast("録音権限が必要です")
    }

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) toggleCameraSource() else toast("カメラソースにはカメラ権限が必要です")
    }

    private val imageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val source = ImageDecoder.createSource(contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
            captureService?.addImageSource(bitmap)
            binding.txtSelectedSource.text = "選択中: 画像"
            binding.seekSourceScale.progress = 28
        } catch (_: Exception) { toast("画像を読み込めませんでした") }
    }

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
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
                binding.txtPreviewHint.text = "LIVEプレビュー中。上の画面をドラッグすると選択ソースを移動できます"
                attachPreviewIfPossible()
                service.setAudioVolumes(micVolume, internalVolume)
            }
            refreshButtons()
        }, 250)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.previewSurface.holder.addCallback(this)
        setupSpinners(); setupActions(); setupSceneControls(); setupSourceEditor(); setupMixer(); ensureServiceBound()
    }

    override fun onStart() {
        super.onStart(); refreshButtons()
        val filter = IntentFilter(ScreenStreamService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        else { @Suppress("DEPRECATION") registerReceiver(statusReceiver, filter) }
    }

    override fun onResume() { super.onResume(); attachPreviewIfPossible() }

    override fun onStop() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        super.onStop()
    }

    override fun onDestroy() {
        captureService?.detachPreview()
        if (bound) { unbindService(serviceConnection); bound = false }
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
                captureService?.stopCapture(); binding.txtPreviewHint.text = "「画面共有を開始」でAndroidの画面共有許可を開きます"; refreshButtons()
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
                if (!url.startsWith("rtmp://") && !url.startsWith("rtmps://")) { toast("RTMP URLを入力してください"); return@setOnClickListener }
                if (service?.startRtmp(url) != true) toast("先に画面共有を開始してください")
            }
            refreshButtons()
        }
        binding.btnFloating.setOnClickListener {
            val service = captureService
            if (service?.isCaptureReady() != true) { toast("先に画面共有を開始してください"); return@setOnClickListener }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                toast("「他のアプリの上に表示」を許可してからもう一度押してください")
            } else if (!service.showFloatingControls()) toast("フローティング操作を表示できませんでした")
        }
    }

    private fun setupSceneControls() {
        binding.sceneGroup.setOnCheckedChangeListener { _, checkedId ->
            val scene = when (checkedId) { binding.sceneTalk.id -> "talk"; binding.sceneWait.id -> "wait"; else -> "game" }
            captureService?.setActiveScene(scene)
            binding.txtSelectedSource.text = "選択中: なし"
        }
    }

    private fun setupSourceEditor() {
        binding.btnAddText.setOnClickListener { showTextDialog() }
        binding.btnAddImage.setOnClickListener { imageLauncher.launch("image/*") }
        binding.btnCamera.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) toggleCameraSource()
            else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        binding.seekSourceScale.max = 80
        binding.seekSourceScale.progress = 28
        binding.seekSourceScale.setOnSeekBarChangeListener(simpleSeekListener { captureService?.setSelectedScale(it.coerceAtLeast(8).toFloat()) })
        binding.editOverlay.setOnTouchListener { _, event ->
            val service = captureService ?: return@setOnTouchListener false
            if (!service.isCaptureReady()) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastTouchX = event.x; lastTouchY = event.y; true }
                MotionEvent.ACTION_MOVE -> {
                    val width = binding.editOverlay.width.coerceAtLeast(1); val height = binding.editOverlay.height.coerceAtLeast(1)
                    val dx = (event.x - lastTouchX) * 100f / width; val dy = (event.y - lastTouchY) * 100f / height
                    service.moveSelectedSource(dx, dy); lastTouchX = event.x; lastTouchY = event.y; true
                }
                else -> true
            }
        }
    }

    private fun showTextDialog() {
        val input = EditText(this).apply { hint = "表示するテキスト"; setSingleLine(false) }
        AlertDialog.Builder(this).setTitle("テキストソース").setView(input)
            .setPositiveButton("追加") { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) { captureService?.addTextSource(text); binding.txtSelectedSource.text = "選択中: テキスト"; binding.seekSourceScale.progress = 34 }
            }.setNegativeButton("キャンセル", null).show()
    }

    private fun toggleCameraSource() {
        val enabled = captureService?.toggleCameraSource() ?: false
        binding.txtSelectedSource.text = "選択中: カメラ"
        binding.btnCamera.text = if (enabled) "カメラOFF" else "カメラ"
        binding.seekSourceScale.progress = 28
    }

    private fun setupMixer() {
        binding.seekMic.max = 200; binding.seekInternal.max = 200; binding.seekMic.progress = 100; binding.seekInternal.progress = 100
        binding.seekMic.setOnSeekBarChangeListener(simpleSeekListener {
            micVolume = it / 100f; binding.txtMicLevel.text = "マイク ${it}%"; captureService?.setAudioVolumes(micVolume, internalVolume)
        })
        binding.seekInternal.setOnSeekBarChangeListener(simpleSeekListener {
            internalVolume = it / 100f; binding.txtInternalLevel.text = "内部音声 ${it}%"; captureService?.setAudioVolumes(micVolume, internalVolume)
        })
        binding.btnMicMute.setOnClickListener {
            val mute = binding.btnMicMute.text != "マイク解除"
            captureService?.setMicrophoneMuted(mute)
            binding.btnMicMute.text = if (mute) "マイク解除" else "マイクミュート"
        }
    }

    private fun simpleSeekListener(onChanged: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) onChanged(progress) }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private fun requestPermissionsThenCapture() {
        pendingConfig = currentConfig()
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) launchCapturePicker() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun launchCapturePicker() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun currentConfig(): StreamConfig {
        val resolution = binding.spinnerResolution.selectedItemPosition
        val fps = if (binding.spinnerFps.selectedItemPosition == 1) 60 else 30
        val (width, height, bitrate) = if (resolution == 1) Triple(1920, 1080, 8_000_000) else Triple(1280, 720, 5_000_000)
        val audio = when (binding.spinnerAudio.selectedItemPosition) { 1 -> StreamConfig.AudioMode.INTERNAL; 2 -> StreamConfig.AudioMode.MICROPHONE; else -> StreamConfig.AudioMode.MIX }
        val rotation = if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) 90 else 0
        return StreamConfig(width, height, fps, bitrate, rotation, audio)
    }

    private fun ensureServiceBound() {
        val intent = Intent(this, ScreenStreamService::class.java)
        if (!bound) bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    private fun attachPreviewIfPossible() {
        val service = captureService ?: return
        if (!service.isCaptureReady()) return
        if (binding.previewSurface.holder.surface.isValid) service.attachPreview(binding.previewSurface)
    }

    private fun refreshButtons() {
        val ready = captureService?.isCaptureReady() == true
        val streaming = captureService?.isStreamingNow() == true
        val recording = captureService?.isRecordingNow() == true
        binding.btnCapture.text = if (ready) "画面共有を停止" else "画面共有を開始"
        binding.btnRecord.isEnabled = ready; binding.btnStream.isEnabled = ready; binding.btnFloating.isEnabled = ready
        binding.btnRecord.text = if (recording) "録画停止" else "録画開始"
        binding.btnStream.text = if (streaming) "配信停止" else "配信開始"
        binding.txtLive.text = when { streaming -> "LIVE"; recording -> "REC"; ready -> "READY"; else -> "OFFLINE" }
        binding.txtLive.setTextColor(when { streaming || recording -> Color.rgb(255, 92, 108); ready -> Color.rgb(67, 209, 122); else -> Color.rgb(170, 178, 192) })
    }

    override fun surfaceCreated(holder: SurfaceHolder) = attachPreviewIfPossible()
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = attachPreviewIfPossible()
    override fun surfaceDestroyed(holder: SurfaceHolder) { captureService?.detachPreview() }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
