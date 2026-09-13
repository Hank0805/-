package jp.minobs.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import jp.minobs.app.databinding.ActivityMainBinding
import org.json.JSONObject

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var profileStore: ProfileStore
    private var profiles: List<ProfileStore.Profile> = emptyList()
    private var captureService: ScreenStreamService? = null
    private var bound = false
    private var pendingConfig: StreamConfig? = null
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var micVolume = 1f
    private var internalVolume = 1f
    private var replayEnabled = true
    private var autoSceneEnabled = false
    private var profileBitrateOverride: Int? = null
    private var profileVertical = false
    private var recoveryShown = false
    private val uiHandler = Handler(Looper.getMainLooper())

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            captureService = (service as ScreenStreamService.LocalBinder).service()
            bound = true
            refreshButtons()
            attachPreviewIfPossible()
            refreshRemoteAndHealth()
            if (!recoveryShown) {
                captureService?.recoveryMessage()?.let { message ->
                    recoveryShown = true
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("前回のセッション")
                        .setMessage(message + "\nリプレイ用の完了済みクリップは可能な範囲で残っています。")
                        .setPositiveButton("確認") { _, _ -> captureService?.clearRecoveryNotice() }
                        .show()
                }
            }
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
            intent.getStringExtra(ScreenStreamService.EXTRA_REMOTE_URL)?.let { binding.txtRemote.text = it }
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
                binding.txtPreviewHint.text = "LIVEプレビュー。選択ソースは画面上をドラッグして移動できます"
                attachPreviewIfPossible()
                service.setAudioVolumes(micVolume, internalVolume)
                replayEnabled = true
                binding.btnReplayToggle.text = "Replay: ON"
                binding.txtRemote.text = service.remoteUrl() ?: "同じWi-Fiに接続するとRemote URLが表示されます"
            }
            refreshButtons()
        }, 300)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        profileStore = ProfileStore(this)
        binding.previewSurface.holder.addCallback(this)
        setupSpinners()
        setupProfiles()
        setupActions()
        setupSceneControls()
        setupSourceEditor()
        setupMixer()
        setupAdvancedControls()
        ensureServiceBound()
    }

    override fun onStart() {
        super.onStart()
        refreshButtons()
        val filter = IntentFilter(ScreenStreamService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        else { @Suppress("DEPRECATION") registerReceiver(statusReceiver, filter) }
        uiHandler.post(healthRunnable)
    }

    override fun onResume() {
        super.onResume()
        attachPreviewIfPossible()
        if (autoSceneEnabled && captureService?.hasUsageAccess() == true) captureService?.setAutoSceneEnabled(true)
    }

    override fun onStop() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        uiHandler.removeCallbacks(healthRunnable)
        super.onStop()
    }

    override fun onDestroy() {
        captureService?.detachPreview()
        if (bound) { unbindService(serviceConnection); bound = false }
        super.onDestroy()
    }

    private fun setupSpinners() {
        binding.spinnerAudio.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("内部音声 + マイク", "内部音声のみ", "マイクのみ"))
        binding.spinnerResolution.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("720p", "1080p", "縦1080p"))
        binding.spinnerFps.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("30 fps", "60 fps"))
        binding.spinnerAudioPreset.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("game", "voice", "broadcast", "flat"))
    }

    private fun setupProfiles() {
        refreshProfileSpinner()
        binding.btnApplyProfile.setOnClickListener {
            val p = profiles.getOrNull(binding.spinnerProfile.selectedItemPosition) ?: return@setOnClickListener
            applyProfile(p)
        }
        binding.btnSaveProfile.setOnClickListener { showSaveProfileDialog() }
    }

    private fun refreshProfileSpinner(selectName: String? = null) {
        profiles = profileStore.all()
        binding.spinnerProfile.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, profiles.map { it.name })
        selectName?.let { name -> profiles.indexOfFirst { it.name == name }.takeIf { it >= 0 }?.let(binding.spinnerProfile::setSelection) }
    }

    private fun applyProfile(p: ProfileStore.Profile) {
        profileBitrateOverride = p.bitrate
        profileVertical = p.vertical
        binding.spinnerResolution.setSelection(if (p.vertical) 2 else if (p.width >= 1920) 1 else 0)
        binding.spinnerFps.setSelection(if (p.fps >= 60) 1 else 0)
        binding.spinnerAudio.setSelection(when (p.audioMode.uppercase()) { "INTERNAL" -> 1; "MICROPHONE" -> 2; else -> 0 })
        val presetIndex = listOf("game", "voice", "broadcast", "flat").indexOf(p.audioPreset).coerceAtLeast(0)
        binding.spinnerAudioPreset.setSelection(presetIndex)
        captureService?.setAudioPreset(p.audioPreset)
        binding.editRtmp.setText(p.endpoints.getOrNull(0).orEmpty())
        binding.editRtmp2.setText(p.endpoints.getOrNull(1).orEmpty())
        binding.editRtmp3.setText(p.endpoints.getOrNull(2).orEmpty())
        toast("${p.name} を適用しました")
    }

    private fun showSaveProfileDialog() {
        val input = EditText(this).apply { hint = "プロファイル名" }
        AlertDialog.Builder(this)
            .setTitle("現在の設定を保存")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) return@setPositiveButton
                val cfg = currentConfig()
                val endpoints = currentEndpoints()
                val preset = binding.spinnerAudioPreset.selectedItem?.toString() ?: "game"
                profileStore.save(ProfileStore.Profile(
                    name = name,
                    width = cfg.width,
                    height = cfg.height,
                    fps = cfg.fps,
                    bitrate = cfg.bitrate,
                    audioMode = cfg.audioMode.name,
                    endpoints = endpoints,
                    vertical = binding.spinnerResolution.selectedItemPosition == 2,
                    replaySeconds = 30,
                    audioPreset = preset
                ))
                refreshProfileSpinner(name)
                toast("プロファイルを保存しました")
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun setupActions() {
        binding.btnCapture.setOnClickListener {
            if (captureService?.isCaptureReady() == true) {
                captureService?.stopCapture()
                binding.txtPreviewHint.text = "「画面共有を開始」でLIVEプレビューを表示"
                binding.txtRemote.text = "画面共有後にRemote Studioを利用できます"
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
                val endpoints = currentEndpoints()
                if (endpoints.isEmpty()) { toast("RTMP / RTMPS URLを1つ以上入力してください"); return@setOnClickListener }
                if (service?.startMultiRtmp(endpoints) != true) toast("先に画面共有を開始してください")
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
                    val width = binding.editOverlay.width.coerceAtLeast(1)
                    val height = binding.editOverlay.height.coerceAtLeast(1)
                    val dx = (event.x - lastTouchX) * 100f / width
                    val dy = (event.y - lastTouchY) * 100f / height
                    service.moveSelectedSource(dx, dy)
                    lastTouchX = event.x; lastTouchY = event.y
                    true
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
                if (text.isNotEmpty()) {
                    captureService?.addTextSource(text)
                    captureService?.selectSource(ScreenStreamService.SourceKind.TEXT)
                    binding.txtSelectedSource.text = "選択中: テキスト"
                    binding.seekSourceScale.progress = 34
                }
            }.setNegativeButton("キャンセル", null).show()
    }

    private fun toggleCameraSource() {
        val enabled = captureService?.toggleCameraSource() ?: false
        captureService?.selectSource(ScreenStreamService.SourceKind.CAMERA)
        binding.txtSelectedSource.text = "選択中: カメラ"
        binding.btnCamera.text = if (enabled) "カメラOFF" else "カメラ"
        binding.seekSourceScale.progress = 28
    }

    private fun setupMixer() {
        binding.seekMic.max = 200; binding.seekInternal.max = 200
        binding.seekMic.progress = 100; binding.seekInternal.progress = 100
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
        binding.spinnerAudioPreset.setSelection(0)
        binding.spinnerAudioPreset.setOnItemSelectedListener(SimpleItemSelectedListener { position ->
            val preset = listOf("game", "voice", "broadcast", "flat").getOrElse(position) { "game" }
            captureService?.setAudioPreset(preset)
        })
    }

    private fun setupAdvancedControls() {
        binding.btnReplayToggle.text = "Replay: ON"
        binding.btnReplayToggle.setOnClickListener {
            replayEnabled = !replayEnabled
            val ok = captureService?.setReplayEnabled(replayEnabled, 30) ?: false
            if (!ok && replayEnabled) { replayEnabled = false; toast("画面共有開始後にReplayを有効化できます") }
            binding.btnReplayToggle.text = "Replay: ${if (replayEnabled) "ON" else "OFF"}"
        }
        binding.btnReplay.setOnClickListener {
            val count = captureService?.saveReplay() ?: 0
            if (count == 0) toast("保存できるReplayがまだありません") else toast("直前を${count}クリップ保存しました")
        }
        binding.btnMarker.setOnClickListener { captureService?.addMarker(); toast("★ マーカーを追加") }
        binding.btnPrivacy.setOnClickListener {
            captureService?.togglePrivacy()
            binding.btnPrivacy.text = if (captureService?.isPrivacyMode() == true) "PRIVACY解除" else "PANIC / 緊急非表示"
        }
        binding.btnRemote.setOnClickListener {
            val url = captureService?.startRemoteStudio()
            if (url.isNullOrBlank()) toast("画面共有開始後、同じWi-Fiで利用してください")
            else {
                binding.txtRemote.text = url
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Mini OBS Remote", url))
                toast("Remote URLをコピーしました。2台目のブラウザで開いてください")
            }
        }
        binding.btnCameraNode.setOnClickListener { startActivity(Intent(this, CameraNodeActivity::class.java)) }
        binding.btnUsageAccess.setOnClickListener { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        binding.btnAutoScene.setOnClickListener {
            val service = captureService
            if (service == null || !service.hasUsageAccess()) {
                toast("最初に「使用状況へのアクセス」でMini OBSを許可してください")
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                return@setOnClickListener
            }
            autoSceneEnabled = !autoSceneEnabled
            service.setAutoSceneEnabled(autoSceneEnabled)
            binding.btnAutoScene.text = "自動シーン: ${if (autoSceneEnabled) "ON" else "OFF"}"
        }
        binding.btnChat.setOnClickListener { showChatDialog() }
    }

    private fun showChatDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 8, 32, 0) }
        val twitch = EditText(this).apply { hint = "Twitch channel（任意）" }
        val liveChat = EditText(this).apply { hint = "YouTube liveChatId（任意）" }
        val apiKey = EditText(this).apply { hint = "YouTube API key（端末内のみ）" }
        box.addView(twitch); box.addView(liveChat); box.addView(apiKey)
        AlertDialog.Builder(this)
            .setTitle("チャット連携")
            .setMessage("Twitchはチャンネル名だけで読み取り。YouTubeはData APIのliveChatIdとAPI keyが必要です。")
            .setView(box)
            .setPositiveButton("接続") { _, _ ->
                val service = captureService ?: return@setPositiveButton
                val channel = twitch.text?.toString()?.trim().orEmpty()
                val chatId = liveChat.text?.toString()?.trim().orEmpty()
                val key = apiKey.text?.toString()?.trim().orEmpty()
                if (channel.isNotEmpty()) service.configureTwitchChat(channel)
                if (chatId.isNotEmpty() && key.isNotEmpty()) service.configureYouTubeChat(chatId, key)
                toast("チャット接続を開始しました")
            }
            .setNegativeButton("キャンセル", null)
            .show()
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
        val defaults = when (resolution) {
            1 -> Triple(1920, 1080, 8_000_000)
            2 -> Triple(1080, 1920, 7_000_000)
            else -> Triple(1280, 720, 5_000_000)
        }
        val bitrate = profileBitrateOverride ?: defaults.third
        val audio = when (binding.spinnerAudio.selectedItemPosition) {
            1 -> StreamConfig.AudioMode.INTERNAL
            2 -> StreamConfig.AudioMode.MICROPHONE
            else -> StreamConfig.AudioMode.MIX
        }
        val vertical = resolution == 2 || profileVertical
        val rotation = if (vertical) 0 else if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) 90 else 0
        return StreamConfig(defaults.first, defaults.second, fps, bitrate, rotation, audio)
    }

    private fun currentEndpoints(): List<String> = listOf(
        binding.editRtmp.text?.toString()?.trim().orEmpty(),
        binding.editRtmp2.text?.toString()?.trim().orEmpty(),
        binding.editRtmp3.text?.toString()?.trim().orEmpty()
    ).filter { it.startsWith("rtmp://", true) || it.startsWith("rtmps://", true) }.distinct()

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
        binding.btnRecord.isEnabled = ready
        binding.btnStream.isEnabled = ready
        binding.btnFloating.isEnabled = ready
        binding.btnReplay.isEnabled = ready
        binding.btnReplayToggle.isEnabled = ready
        binding.btnMarker.isEnabled = ready
        binding.btnPrivacy.isEnabled = ready
        binding.btnRecord.text = if (recording) "録画停止" else "録画開始"
        binding.btnStream.text = if (streaming) "配信停止" else "配信開始"
        binding.txtLive.text = when {
            captureService?.isPrivacyMode() == true -> "PRIVACY"
            streaming -> "LIVE"
            recording -> "REC"
            ready -> "READY"
            else -> "OFFLINE"
        }
        binding.txtLive.setTextColor(when {
            captureService?.isPrivacyMode() == true -> Color.rgb(255, 184, 77)
            streaming || recording -> Color.rgb(255, 92, 108)
            ready -> Color.rgb(67, 209, 122)
            else -> Color.rgb(170, 178, 192)
        })
    }

    private val healthRunnable = object : Runnable {
        override fun run() {
            refreshRemoteAndHealth()
            uiHandler.postDelayed(this, 1500)
        }
    }

    private fun refreshRemoteAndHealth() {
        val service = captureService ?: return
        if (!service.isCaptureReady()) return
        runCatching {
            val s = service.remoteStatus()
            binding.txtRemote.text = s.optString("remoteUrl", service.remoteUrl() ?: "Remote Studio準備中")
            binding.txtHealth.text = formatHealth(s)
        }
    }

    private fun formatHealth(s: JSONObject): String {
        return buildString {
            append("${s.optString("resolution")}  ${s.optInt("fps")}fps  ${s.optLong("bitrateKbps")} kbps")
            append("\nNetwork: ${s.optString("network")}  Up:${s.optInt("upKbps")} kbps")
            append("\nCPU:${s.optDouble("cpu")} %  Temp:${s.optDouble("temperature")}℃  Battery:${s.optInt("battery")}%")
            append("\nDropped V/A: ${s.optLong("droppedVideo")}/${s.optLong("droppedAudio")}  Free:${s.optLong("storageFreeMb")} MB")
            append("\nReplay: ${s.optString("replay")}  Destinations:${s.optInt("destinations")}")
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) = attachPreviewIfPossible()
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = attachPreviewIfPossible()
    override fun surfaceDestroyed(holder: SurfaceHolder) { captureService?.detachPreview() }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private class SimpleItemSelectedListener(private val onSelected: (Int) -> Unit) : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = onSelected(position)
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }
}
