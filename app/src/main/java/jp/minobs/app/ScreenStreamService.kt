package jp.minobs.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.`object`.BaseObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.ImageFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.TextFilterRender
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.MixAudioSource
import com.pedro.encoder.input.sources.video.NoVideoSource
import com.pedro.encoder.input.sources.video.ScreenSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class ScreenStreamService : Service(), ConnectChecker, RemoteStudioServer.Controller {

    enum class SourceKind { TEXT, IMAGE, CAMERA, EXTERNAL1, EXTERNAL2, EXTERNAL3 }
    data class Layer(var enabled: Boolean = false, var x: Float = 8f, var y: Float = 8f, var scale: Float = 28f)
    data class SceneState(
        var text: String? = null,
        val textLayer: Layer = Layer(false, 8f, 8f, 34f),
        var image: Bitmap? = null,
        val imageLayer: Layer = Layer(false, 8f, 58f, 28f),
        val cameraLayer: Layer = Layer(false, 68f, 5f, 28f)
    )

    companion object {
        const val ACTION_STATUS = "jp.minobs.app.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_STREAMING = "streaming"
        const val EXTRA_RECORDING = "recording"
        const val EXTRA_CAPTURE_READY = "capture_ready"
        const val EXTRA_SCENE = "scene"
        const val EXTRA_REMOTE_URL = "remote_url"
        private const val CHANNEL_ID = "mini_obs_capture"
        private const val NOTIFICATION_ID = 41
    }

    inner class LocalBinder : Binder() { fun service(): ScreenStreamService = this@ScreenStreamService }
    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val projectionManager by lazy { getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager }
    private var mediaProjection: MediaProjection? = null
    private var stream: MultiRtmpStream? = null
    private var captureReady = false
    private var config = StreamConfig(1280, 720, 30, 5_000_000, 0, StreamConfig.AudioMode.MIX)

    private val scenes = mutableMapOf(
        "game" to SceneState(),
        "talk" to SceneState(text = "雑談配信", textLayer = Layer(true, 8f, 8f, 34f)),
        "wait" to SceneState(text = "まもなく開始", textLayer = Layer(true, 24f, 42f, 44f))
    )
    private var activeScene = "game"
    private var selectedSource: SourceKind? = null
    private var textFilter: TextFilterRender? = null
    private var imageFilter: ImageFilterRender? = null
    private var cameraFilter: SurfaceFilterRender? = null
    private var chatFilter: TextFilterRender? = null

    private val externalImages = mutableMapOf<Int, Bitmap>()
    private val externalFilters = mutableMapOf<Int, ImageFilterRender>()
    private val externalLayers = mutableMapOf(
        1 to Layer(false, 68f, 58f, 27f),
        2 to Layer(false, 4f, 58f, 27f),
        3 to Layer(false, 37f, 5f, 25f)
    )

    private var cameraDevice: CameraDevice? = null
    private var cameraSession: CameraCaptureSession? = null
    private var cameraSurface: Surface? = null
    private var floatingView: LinearLayout? = null
    private var windowManager: WindowManager? = null

    private val audioEffect = StudioAudioEffect()
    private var micVolume = 1f
    private var internalVolume = 1f
    private var micMuted = false
    private var privacyMode = false
    private var micBeforePrivacy = false

    private lateinit var replay: ReplayBufferManager
    private var replayWasEnabled = false
    private var normalRecording = false
    private var recordingFile: File? = null
    private var recordingStartedAt = 0L
    private val journal by lazy { SessionJournal(this) }
    private val health by lazy { HealthMonitor(this) }

    private var remoteServer: RemoteStudioServer? = null
    private val chatLines = ArrayDeque<String>()
    private var chatOverlayEnabled = true
    private lateinit var chatManager: ChatManager
    private lateinit var autoScene: AutoSceneController

    private var lastEndpoints: List<String> = emptyList()
    private var intentionalStreamStop = false
    private var autoReconnect = true
    private val reconnectPending = AtomicBoolean(false)
    private var reconnectAttempt = 0
    private var adaptiveBitrate = true
    private var currentTargetBitrate = 5_000_000
    private var lowBitrateTicks = 0
    private var goodBitrateTicks = 0
    private var lastBitrate = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        replay = ReplayBufferManager(
            context = this,
            startRecord = { file -> internalStartRecord(file) },
            stopRecord = { internalStopRecord() },
            canRecord = { captureReady && !normalRecording && stream?.isRecording != true }
        )
        chatManager = ChatManager { author, message -> pushChat(author, message) }
        autoScene = AutoSceneController(
            this,
            onScene = { scene -> if (!privacyMode) setActiveScene(scene) },
            onSensitive = { sensitive, pkg -> if (sensitive) enterPrivacy("Sensitive app: $pkg") else if (privacyMode) exitPrivacy() }
        )
    }

    override fun onBind(intent: Intent?): android.os.IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    fun prepareCapture(resultCode: Int, data: Intent, newConfig: StreamConfig): Boolean {
        config = newConfig
        currentTargetBitrate = config.bitrate
        releaseEngine()
        startAsForeground(config.audioMode != StreamConfig.AudioMode.INTERNAL)
        val projection = projectionManager.getMediaProjection(resultCode, data) ?: run {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return false
        }
        mediaProjection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopEverything("画面共有が終了しました") }
        }, mainHandler)

        val audioSource = when (config.audioMode) {
            StreamConfig.AudioMode.MICROPHONE -> MicrophoneSource().also { it.setAudioEffect(audioEffect) }
            StreamConfig.AudioMode.INTERNAL -> InternalAudioSource(projection).also { it.setAudioEffect(audioEffect) }
            StreamConfig.AudioMode.MIX -> MixAudioSource(projection).also { it.setAudioEffect(audioEffect) }
        }
        val engine = MultiRtmpStream(applicationContext, this, NoVideoSource(), audioSource).apply {
            getGlInterface().setForceRender(true, config.fps)
        }
        val videoPrepared = runCatching { engine.prepareVideo(config.width, config.height, config.bitrate, config.fps, 2, config.rotation) }.getOrDefault(false)
        val audioPrepared = runCatching { engine.prepareAudio(44_100, true, 128_000, echoCanceler = true, noiseSuppressor = true) }.getOrDefault(false)
        if (!videoPrepared || !audioPrepared) {
            engine.release(); mediaProjection = null; runCatching { projection.stop() }; captureReady = false
            sendStatus("エンコーダーの初期化に失敗しました")
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return false
        }
        return try {
            engine.getGlInterface().setCameraOrientation(0)
            engine.changeVideoSource(ScreenSource(applicationContext, projection))
            stream = engine
            captureReady = true
            setAudioVolumes(micVolume, internalVolume)
            applySceneFilters()
            startRemoteStudio()
            // Replay buffer also keeps the capture/GL pipeline alive after the control Activity goes to background.
            mainHandler.postDelayed({ if (captureReady && !normalRecording) replay.start(30) }, 500)
            sendStatus("画面共有準備OK")
            true
        } catch (e: Exception) {
            engine.release(); mediaProjection = null; runCatching { projection.stop() }; captureReady = false
            sendStatus("画面共有の開始に失敗: ${e.message ?: "不明なエラー"}")
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); false
        }
    }

    fun attachPreview(surfaceView: SurfaceView): Boolean {
        val engine = stream ?: return false
        if (!captureReady || !surfaceView.holder.surface.isValid) return false
        return try {
            if (!engine.isOnPreview) engine.startPreview(surfaceView)
            engine.getGlInterface().setPreviewResolution(max(1, surfaceView.width), max(1, surfaceView.height))
            true
        } catch (_: Exception) { false }
    }

    fun detachPreview() { runCatching { stream?.let { if (it.isOnPreview) it.stopPreview() } } }

    fun setActiveScene(scene: String) {
        if (!scenes.containsKey(scene) || scene == activeScene) return
        activeScene = scene; selectedSource = null
        if (captureReady) applySceneFilters()
        sendStatus("シーン: ${sceneLabel(scene)}")
    }

    fun addTextSource(text: String) {
        val state = scenes.getValue(activeScene)
        state.text = text; state.textLayer.enabled = true; selectedSource = SourceKind.TEXT
        if (captureReady) applySceneFilters()
    }

    fun addImageSource(bitmap: Bitmap) {
        val state = scenes.getValue(activeScene)
        state.image = bitmap; state.imageLayer.enabled = true; selectedSource = SourceKind.IMAGE
        if (captureReady) applySceneFilters()
    }

    fun toggleCameraSource(): Boolean {
        val state = scenes.getValue(activeScene)
        state.cameraLayer.enabled = !state.cameraLayer.enabled; selectedSource = SourceKind.CAMERA
        if (captureReady) applySceneFilters()
        return state.cameraLayer.enabled
    }

    fun selectSource(source: SourceKind?) { selectedSource = source }

    fun moveSelectedSource(dxPercent: Float, dyPercent: Float) {
        val layer = selectedLayer() ?: return
        layer.x = (layer.x + dxPercent).coerceIn(0f, 92f); layer.y = (layer.y + dyPercent).coerceIn(0f, 92f)
        applySelectedTransform(layer)
    }

    fun setSelectedScale(scale: Float) {
        val layer = selectedLayer() ?: return
        layer.scale = scale.coerceIn(8f, 80f); applySelectedTransform(layer)
    }

    private fun selectedLayer(): Layer? = when (selectedSource) {
        SourceKind.TEXT -> scenes.getValue(activeScene).textLayer
        SourceKind.IMAGE -> scenes.getValue(activeScene).imageLayer
        SourceKind.CAMERA -> scenes.getValue(activeScene).cameraLayer
        SourceKind.EXTERNAL1 -> externalLayers[1]
        SourceKind.EXTERNAL2 -> externalLayers[2]
        SourceKind.EXTERNAL3 -> externalLayers[3]
        null -> null
    }

    private fun applySelectedTransform(layer: Layer) {
        when (selectedSource) {
            SourceKind.TEXT -> textFilter?.let { applyTransform(it, layer, true) }
            SourceKind.IMAGE -> imageFilter?.let { applyTransform(it, layer) }
            SourceKind.CAMERA -> cameraFilter?.let { applyTransform(it, layer) }
            SourceKind.EXTERNAL1 -> externalFilters[1]?.let { applyTransform(it, layer) }
            SourceKind.EXTERNAL2 -> externalFilters[2]?.let { applyTransform(it, layer) }
            SourceKind.EXTERNAL3 -> externalFilters[3]?.let { applyTransform(it, layer) }
            null -> Unit
        }
    }

    private fun applyTransform(filter: BaseObjectFilterRender, layer: Layer, textMode: Boolean = false) {
        val xScale = if (textMode) min(90f, layer.scale * 1.55f) else layer.scale
        val yScale = if (textMode) max(10f, layer.scale * 0.42f) else layer.scale
        filter.setScale(xScale, yScale); filter.setPosition(layer.x, layer.y)
    }

    private fun applySceneFilters() {
        val engine = stream ?: return
        stopOverlayCamera()
        runCatching { engine.getGlInterface().clearFilters() }
        textFilter = null; imageFilter = null; cameraFilter = null; chatFilter = null; externalFilters.clear()
        val state = scenes.getValue(activeScene)
        if (state.textLayer.enabled && !state.text.isNullOrBlank()) {
            TextFilterRender().also {
                it.setText(state.text!!, 34f, Color.WHITE, Color.TRANSPARENT); applyTransform(it, state.textLayer, true)
                engine.getGlInterface().addFilter(it); textFilter = it
            }
        }
        if (state.imageLayer.enabled && state.image != null) {
            ImageFilterRender().also {
                it.setImage(state.image); applyTransform(it, state.imageLayer); engine.getGlInterface().addFilter(it); imageFilter = it
            }
        }
        if (state.cameraLayer.enabled && ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            SurfaceFilterRender { texture -> startOverlayCamera(texture) }.also {
                applyTransform(it, state.cameraLayer); engine.getGlInterface().addFilter(it); cameraFilter = it
            }
        }
        for (id in 1..3) {
            val bitmap = externalImages[id] ?: continue
            val layer = externalLayers[id] ?: continue
            if (!layer.enabled) continue
            ImageFilterRender().also {
                it.setImage(bitmap); applyTransform(it, layer); engine.getGlInterface().addFilter(it); externalFilters[id] = it
            }
        }
        if (chatOverlayEnabled && chatLines.isNotEmpty()) {
            val text = chatLines.takeLast(4).joinToString("\n")
            TextFilterRender().also {
                it.setText(text, 24f, Color.WHITE, 0x77000000); it.setScale(88f, 24f); it.setPosition(5f, 73f)
                engine.getGlInterface().addFilter(it); chatFilter = it
            }
        }
    }

    private fun startOverlayCamera(surfaceTexture: SurfaceTexture) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        stopOverlayCamera()
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
            ?: manager.cameraIdList.firstOrNull() ?: return
        surfaceTexture.setDefaultBufferSize(640, 480)
        val surface = Surface(surfaceTexture); cameraSurface = surface
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface); set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    }
                    camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) { cameraSession = session; runCatching { session.setRepeatingRequest(request.build(), null, mainHandler) } }
                        override fun onConfigureFailed(session: CameraCaptureSession) = Unit
                    }, mainHandler)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); if (cameraDevice === camera) cameraDevice = null }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); if (cameraDevice === camera) cameraDevice = null }
            }, mainHandler)
        } catch (_: Exception) { surface.release(); cameraSurface = null }
    }

    private fun stopOverlayCamera() {
        runCatching { cameraSession?.close() }; runCatching { cameraDevice?.close() }; runCatching { cameraSurface?.release() }
        cameraSession = null; cameraDevice = null; cameraSurface = null
    }

    fun setAudioVolumes(microphone: Float, internal: Float) {
        micVolume = microphone.coerceIn(0f, 2f); internalVolume = internal.coerceIn(0f, 2f)
        when (val source = stream?.audioSource) {
            is MixAudioSource -> { source.microphoneVolume = micVolume; source.internalVolume = internalVolume }
            is MicrophoneSource -> source.microphoneVolume = micVolume
            is InternalAudioSource -> source.internalVolume = internalVolume
        }
    }

    fun setMicrophoneMuted(muted: Boolean) {
        micMuted = muted
        when (val source = stream?.audioSource) {
            is MixAudioSource -> if (muted) source.mute() else source.unMute()
            is MicrophoneSource -> if (muted) source.mute() else source.unMute()
        }
    }

    fun setAudioPreset(name: String) { audioEffect.applyPreset(name); sendStatus("Audio preset: $name") }

    fun startRtmp(url: String): Boolean = startMultiRtmp(listOf(url))

    fun startMultiRtmp(endpoints: List<String>): Boolean {
        val engine = stream ?: return false
        val clean = endpoints.map { it.trim() }.filter { it.startsWith("rtmp://", true) || it.startsWith("rtmps://", true) }.distinct().take(4)
        if (!captureReady || clean.isEmpty()) return false
        return try {
            if (!engine.isStreaming) {
                lastEndpoints = clean; intentionalStreamStop = false; reconnectAttempt = 0; reconnectPending.set(false)
                engine.startMulti(clean); sendStatus("${clean.size}配信先へ接続中…")
            }
            true
        } catch (e: Exception) { sendStatus("配信開始失敗: ${e.message ?: "不明なエラー"}"); false }
    }

    fun stopRtmp() {
        intentionalStreamStop = true; reconnectPending.set(false); mainHandler.removeCallbacksAndMessages(RECONNECT_TOKEN)
        stream?.let { if (it.isStreaming) runCatching { it.stopStream() } }
        sendStatus("配信停止")
    }

    fun setReconnectEnabled(enabled: Boolean) { autoReconnect = enabled }
    fun setAdaptiveBitrateEnabled(enabled: Boolean) { adaptiveBitrate = enabled }

    fun toggleRecording(): Boolean {
        val engine = stream ?: return false
        if (!captureReady) return false
        return try {
            if (!normalRecording) {
                replayWasEnabled = replay.pauseForNormalRecord()
                val folder = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "MiniOBS").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val file = File(folder, "MiniOBS_$stamp.mp4")
                engine.startRecord(file.absolutePath) { _ -> }
                normalRecording = true; recordingFile = file; recordingStartedAt = System.currentTimeMillis(); journal.beginRecording(file.absolutePath)
                sendStatus("録画中: ${file.name}")
            } else {
                if (engine.isRecording) engine.stopRecord()
                normalRecording = false; journal.finishRecording(); recordingStartedAt = 0L
                recordingFile?.let { exportVideoToGallery(it, it.name) }
                sendStatus("録画保存: ${recordingFile?.name ?: "完了"}")
                recordingFile = null
                if (replayWasEnabled) { replay.resumeAfterNormalRecord(); replayWasEnabled = false }
            }
            true
        } catch (e: Exception) { sendStatus("録画エラー: ${e.message ?: "不明なエラー"}"); false }
    }

    private fun internalStartRecord(file: File): Boolean {
        val engine = stream ?: return false
        if (engine.isRecording || normalRecording) return false
        return runCatching { engine.startRecord(file.absolutePath) { _ -> }; true }.getOrDefault(false)
    }

    private fun internalStopRecord(): Boolean {
        val engine = stream ?: return false
        return runCatching { if (engine.isRecording) engine.stopRecord(); true }.getOrDefault(false)
    }

    fun setReplayEnabled(enabled: Boolean, seconds: Int = 30): Boolean {
        return if (enabled) replay.start(seconds) else { replay.stop(); true }
    }

    fun saveReplay(): Int {
        if (!captureReady) return 0
        val clips = replay.snapshot(); if (clips.isEmpty()) { sendStatus("リプレイバッファが空です"); return 0 }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        clips.forEachIndexed { index, file -> exportVideoToGallery(file, "MiniOBS_Replay_${stamp}_${index + 1}.mp4") }
        sendStatus("直前リプレイを${clips.size}クリップ保存")
        return clips.size
    }

    fun addMarker(label: String = "MARK"): Long {
        val elapsed = if (normalRecording) journal.addMarker(label) else 0L
        if (!normalRecording) {
            val dir = File(filesDir, "markers").apply { mkdirs() }
            File(dir, "replay_marks.csv").appendText("${System.currentTimeMillis()},${label.replace(',', '_')}\n")
        }
        sendStatus("★ マーカー ${if (elapsed > 0) "${elapsed / 1000}s" else "追加"}")
        return elapsed
    }

    private fun exportVideoToGallery(file: File, displayName: String) {
        if (!file.exists() || file.length() == 0L) return
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MiniOBS"); put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri: Uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return
            contentResolver.openOutputStream(uri)?.use { out -> FileInputStream(file).use { it.copyTo(out) } }
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0); contentResolver.update(uri, values, null, null)
        }
    }

    fun enterPrivacy(reason: String = "Emergency") {
        if (privacyMode) return
        privacyMode = true; micBeforePrivacy = micMuted
        runCatching { stream?.getGlInterface()?.muteVideo() }; setMicrophoneMuted(true)
        sendStatus("PRIVACY: $reason")
    }

    fun exitPrivacy() {
        if (!privacyMode) return
        privacyMode = false; runCatching { stream?.getGlInterface()?.unMuteVideo() }; setMicrophoneMuted(micBeforePrivacy)
        sendStatus("Privacy解除")
    }

    fun togglePrivacy() { if (privacyMode) exitPrivacy() else enterPrivacy() }
    fun isPrivacyMode(): Boolean = privacyMode

    fun setAutoSceneEnabled(enabled: Boolean) { if (enabled) autoScene.start() else autoScene.stop() }
    fun hasUsageAccess(): Boolean = autoScene.hasUsageAccess()
    fun setSensitiveApps(packages: Set<String>) { autoScene.setSensitivePackages(packages) }

    fun configureTwitchChat(channel: String) = chatManager.startTwitch(channel)
    fun configureYouTubeChat(liveChatId: String, apiKey: String) = chatManager.startYouTube(liveChatId, apiKey)
    fun setChatOverlayEnabled(enabled: Boolean) { chatOverlayEnabled = enabled; if (captureReady) applySceneFilters() }

    private fun pushChat(author: String, message: String) {
        val clean = "${author.take(20)}: ${message.replace('\n', ' ').take(90)}"
        synchronized(chatLines) { chatLines.addLast(clean); while (chatLines.size > 12) chatLines.removeFirst() }
        mainHandler.post {
            if (chatOverlayEnabled) {
                val text = synchronized(chatLines) { chatLines.takeLast(4).joinToString("\n") }
                if (chatFilter != null) chatFilter?.setText(text, 24f, Color.WHITE, 0x77000000) else if (captureReady) applySceneFilters()
            }
        }
    }

    fun startRemoteStudio(): String? {
        if (remoteServer == null) remoteServer = RemoteStudioServer(this)
        val server = remoteServer ?: return null
        if (!server.start()) return null
        return server.localUrl()
    }

    fun remoteUrl(): String? = remoteServer?.localUrl()

    override fun remotePreviewJpeg(): ByteArray? {
        val engine = stream ?: return null
        if (!captureReady) return null
        val latch = CountDownLatch(1); var bytes: ByteArray? = null
        runCatching {
            engine.getGlInterface().takePhoto { bitmap ->
                val out = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.JPEG, 55, out); bytes = out.toByteArray(); latch.countDown()
            }
        }.onFailure { latch.countDown() }
        latch.await(1200, TimeUnit.MILLISECONDS)
        return bytes
    }

    override fun remoteMonitorWav(): ByteArray = audioEffect.monitorWav(3)

    override fun remoteCameraFrame(cameraId: Int, jpeg: ByteArray) {
        val bitmap = runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }.getOrNull() ?: return
        externalImages[cameraId] = bitmap; externalLayers[cameraId]?.enabled = true
        mainHandler.post {
            val filter = externalFilters[cameraId]
            if (filter != null) filter.setImage(bitmap) else if (captureReady) applySceneFilters()
        }
    }

    override fun remoteCommand(command: String, args: Map<String, String>): String {
        return when (command.lowercase()) {
            "record" -> if (toggleRecording()) "record toggled" else "record failed"
            "stream" -> { if (isStreamingNow()) stopRtmp() else if (lastEndpoints.isNotEmpty()) startMultiRtmp(lastEndpoints); "stream toggled" }
            "replay" -> "saved ${saveReplay()} replay clips"
            "replaytoggle" -> { val enabled = args["value"] != "0" && args["value"] != "false"; setReplayEnabled(enabled, args["seconds"]?.toIntOrNull() ?: 30); replay.status() }
            "marker" -> "marker ${addMarker()}"
            "privacy" -> { togglePrivacy(); "privacy=$privacyMode" }
            "mute" -> { setMicrophoneMuted(!micMuted); "muted=$micMuted" }
            "scene" -> { setActiveScene(args["value"].orEmpty()); activeScene }
            "select" -> { selectedSource = when (args["value"]?.lowercase()) { "text" -> SourceKind.TEXT; "image" -> SourceKind.IMAGE; "camera" -> SourceKind.CAMERA; "external1" -> SourceKind.EXTERNAL1; "external2" -> SourceKind.EXTERNAL2; "external3" -> SourceKind.EXTERNAL3; else -> null }; "selected=$selectedSource" }
            "move" -> { moveSelectedSource(args["dx"]?.toFloatOrNull() ?: 0f, args["dy"]?.toFloatOrNull() ?: 0f); "moved" }
            "scale" -> { setSelectedScale(args["value"]?.toFloatOrNull() ?: 28f); "scaled" }
            "micvolume" -> { setAudioVolumes((args["value"]?.toFloatOrNull() ?: 100f) / 100f, internalVolume); "mic=$micVolume" }
            "internalvolume" -> { setAudioVolumes(micVolume, (args["value"]?.toFloatOrNull() ?: 100f) / 100f); "internal=$internalVolume" }
            "chat", "alert" -> { pushChat(if (command == "alert") "ALERT" else "REMOTE", args["text"].orEmpty()); "sent" }
            "autoscene" -> { setAutoSceneEnabled(args["value"] != "false" && args["value"] != "0"); "auto scene updated" }
            "adaptive" -> { adaptiveBitrate = args["value"] != "false" && args["value"] != "0"; "adaptive=$adaptiveBitrate" }
            else -> "unknown command"
        }
    }

    override fun remoteStatus(): JSONObject {
        val engine = stream
        val droppedVideo = runCatching { engine?.getStreamClient()?.getDroppedVideoFrames() ?: 0L }.getOrDefault(0L)
        val droppedAudio = runCatching { engine?.getStreamClient()?.getDroppedAudioFrames() ?: 0L }.getOrDefault(0L)
        val recordingSec = if (normalRecording && recordingStartedAt > 0) (System.currentTimeMillis() - recordingStartedAt) / 1000 else 0L
        val extra = linkedMapOf<String, Any?>(
            "capture" to captureReady, "live" to isStreamingNow(), "recording" to normalRecording, "recordingSeconds" to recordingSec,
            "scene" to activeScene, "privacy" to privacyMode, "micMuted" to micMuted, "bitrateKbps" to lastBitrate / 1000,
            "targetBitrateKbps" to currentTargetBitrate / 1000, "fps" to config.fps, "resolution" to "${config.width}x${config.height}",
            "destinations" to lastEndpoints.size, "replay" to replay.status(), "droppedVideo" to droppedVideo, "droppedAudio" to droppedAudio,
            "remoteUrl" to remoteUrl()
        )
        return health.snapshot(extra).apply { put("chat", JSONArray(synchronized(chatLines) { chatLines.toList() })) }
    }

    fun showFloatingControls(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) return false
        if (floatingView != null) return true
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager; windowManager = wm
        val panel = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(6, 4, 6, 4); setBackgroundColor(0xE31B1F2A.toInt()) }
        fun b(label: String, action: () -> Unit) = Button(this).apply { text = label; textSize = 9f; minWidth = 0; setOnClickListener { action() } }
        panel.addView(b("REC") { toggleRecording() }); panel.addView(b("SAVE") { saveReplay() }); panel.addView(b("PANIC") { togglePrivacy() })
        panel.addView(b("MIC") { setMicrophoneMuted(!micMuted) }); panel.addView(b("SCENE") { setActiveScene(if (activeScene == "game") "talk" else if (activeScene == "talk") "wait" else "game") }); panel.addView(b("×") { hideFloatingControls() })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 18; y = 180 }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        panel.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; false }
                MotionEvent.ACTION_MOVE -> { params.x = (startX - (event.rawX - downX)).toInt(); params.y = (startY + (event.rawY - downY)).toInt(); runCatching { wm.updateViewLayout(panel, params) }; true }
                else -> false
            }
        }
        return try { wm.addView(panel, params); floatingView = panel; true } catch (_: Exception) { false }
    }

    fun hideFloatingControls() { floatingView?.let { runCatching { windowManager?.removeView(it) } }; floatingView = null; windowManager = null }

    fun isCaptureReady(): Boolean = captureReady
    fun isStreamingNow(): Boolean = stream?.isStreaming == true
    fun isRecordingNow(): Boolean = normalRecording
    fun replayStatus(): String = replay.status()
    fun recoveryMessage(): String? = journal.recoveryMessage()
    fun clearRecoveryNotice() = journal.markRecovered()
    fun stopCapture() = stopEverything("画面共有停止")

    private fun stopEverything(message: String) {
        replay.stop(); autoScene.stop(); chatManager.stopAll(); remoteServer?.stop(); remoteServer = null
        runCatching { if (normalRecording && stream?.isRecording == true) stream?.stopRecord() }
        if (normalRecording) journal.finishRecording()
        normalRecording = false; recordingFile = null; recordingStartedAt = 0
        runCatching { stream?.let { if (it.isStreaming) { intentionalStreamStop = true; it.stopStream() } } }
        hideFloatingControls(); stopOverlayCamera(); releaseEngine(); captureReady = false; sendStatus(message)
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun releaseEngine() {
        runCatching { stream?.release() }; stream = null
        val p = mediaProjection; mediaProjection = null; runCatching { p?.stop() }; captureReady = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Mini OBS Studio", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun startAsForeground(usesMicrophone: Boolean) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online).setContentTitle("Mini OBS Studio")
            .setContentText("画面キャプチャ / Remote Studio 稼働中").setOngoing(true).setSilent(true).build()
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (usesMicrophone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
    }

    private fun sceneLabel(scene: String) = when (scene) { "talk" -> "雑談"; "wait" -> "待機"; else -> "ゲーム" }

    private fun sendStatus(message: String) {
        sendBroadcast(Intent(ACTION_STATUS).apply {
            setPackage(packageName); putExtra(EXTRA_STATUS, message); putExtra(EXTRA_BITRATE, lastBitrate)
            putExtra(EXTRA_STREAMING, isStreamingNow()); putExtra(EXTRA_RECORDING, normalRecording); putExtra(EXTRA_CAPTURE_READY, captureReady)
            putExtra(EXTRA_SCENE, activeScene); putExtra(EXTRA_REMOTE_URL, remoteUrl())
        })
    }

    override fun onDestroy() {
        remoteServer?.stop(); chatManager.stopAll(); autoScene.stop(); replay.stop(); hideFloatingControls(); stopOverlayCamera(); releaseEngine(); super.onDestroy()
    }

    override fun onConnectionStarted(url: String) = sendStatus("RTMPへ接続中…")
    override fun onConnectionSuccess() { reconnectPending.set(false); reconnectAttempt = 0; sendStatus("配信中 (${lastEndpoints.size}先)") }

    override fun onConnectionFailed(reason: String) {
        sendStatus("配信エラー: $reason")
        if (!autoReconnect || intentionalStreamStop || lastEndpoints.isEmpty() || !captureReady) return
        if (!reconnectPending.compareAndSet(false, true)) return
        reconnectAttempt++
        val delay = (1500L * reconnectAttempt.coerceAtMost(10)).coerceAtMost(15_000L)
        mainHandler.postAtTime({
            reconnectPending.set(false)
            if (!captureReady || intentionalStreamStop) return@postAtTime
            val engine = stream ?: return@postAtTime
            runCatching { if (engine.isStreaming) engine.stopStream() }
            runCatching { engine.startMulti(lastEndpoints); sendStatus("自動再接続 #$reconnectAttempt") }
        }, RECONNECT_TOKEN, android.os.SystemClock.uptimeMillis() + delay)
    }

    override fun onNewBitrate(bitrate: Long) {
        lastBitrate = bitrate
        if (adaptiveBitrate && isStreamingNow()) {
            if (bitrate < currentTargetBitrate * 0.62) { lowBitrateTicks++; goodBitrateTicks = 0 } else if (bitrate > currentTargetBitrate * 0.88) { goodBitrateTicks++; lowBitrateTicks = 0 } else { lowBitrateTicks = 0; goodBitrateTicks = 0 }
            if (lowBitrateTicks >= 3) {
                currentTargetBitrate = (currentTargetBitrate * 0.82).toInt().coerceAtLeast(1_500_000)
                runCatching { stream?.setVideoBitrateOnFly(currentTargetBitrate) }; lowBitrateTicks = 0; sendStatus("回線に合わせ ${currentTargetBitrate / 1000}kbps")
            } else if (goodBitrateTicks >= 10 && currentTargetBitrate < config.bitrate) {
                currentTargetBitrate = min(config.bitrate, (currentTargetBitrate * 1.10).toInt())
                runCatching { stream?.setVideoBitrateOnFly(currentTargetBitrate) }; goodBitrateTicks = 0
            }
        }
        sendStatus(if (isStreamingNow()) "配信中" else "READY")
    }

    override fun onDisconnect() { if (!intentionalStreamStop) sendStatus("配信切断") else sendStatus("配信停止") }
    override fun onAuthError() = sendStatus("RTMP認証エラー")
    override fun onAuthSuccess() = sendStatus("RTMP認証成功")

    private companion object { private val RECONNECT_TOKEN = Any() }
}
