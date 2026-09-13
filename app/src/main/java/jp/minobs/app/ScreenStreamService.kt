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
import com.pedro.library.generic.GenericStream
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class ScreenStreamService : Service(), ConnectChecker {

    enum class SourceKind { TEXT, IMAGE, CAMERA }

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
        private const val CHANNEL_ID = "mini_obs_capture"
        private const val NOTIFICATION_ID = 41
    }

    inner class LocalBinder : Binder() { fun service(): ScreenStreamService = this@ScreenStreamService }

    private val binder = LocalBinder()
    private val projectionManager by lazy { getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager }
    private var mediaProjection: MediaProjection? = null
    private var stream: GenericStream? = null
    private var captureReady = false
    private var recordingPath: String? = null
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

    private var cameraDevice: CameraDevice? = null
    private var cameraSession: CameraCaptureSession? = null
    private var cameraSurface: Surface? = null

    private var floatingView: LinearLayout? = null
    private var windowManager: WindowManager? = null

    override fun onBind(intent: Intent?): android.os.IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onCreate() { super.onCreate(); createNotificationChannel() }

    fun prepareCapture(resultCode: Int, data: Intent, newConfig: StreamConfig): Boolean {
        config = newConfig
        releaseEngine()
        startAsForeground(config.audioMode != StreamConfig.AudioMode.INTERNAL)
        val projection = projectionManager.getMediaProjection(resultCode, data) ?: run {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return false
        }
        mediaProjection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopEverything("画面共有が終了しました") }
        }, Handler(Looper.getMainLooper()))

        val audioSource = when (config.audioMode) {
            StreamConfig.AudioMode.MICROPHONE -> MicrophoneSource()
            StreamConfig.AudioMode.INTERNAL -> InternalAudioSource(projection)
            StreamConfig.AudioMode.MIX -> MixAudioSource(projection)
        }
        val genericStream = GenericStream(applicationContext, this, NoVideoSource(), audioSource).apply {
            getGlInterface().setForceRender(true, config.fps)
        }
        val videoPrepared = try {
            genericStream.prepareVideo(config.width, config.height, config.bitrate, config.fps, 2, config.rotation)
        } catch (_: Exception) { false }
        val audioPrepared = try {
            genericStream.prepareAudio(44_100, true, 128_000, echoCanceler = true, noiseSuppressor = true)
        } catch (_: Exception) { false }
        if (!videoPrepared || !audioPrepared) {
            genericStream.release()
            val p = mediaProjection; mediaProjection = null
            try { p?.stop() } catch (_: Exception) {}
            captureReady = false
            sendStatus("エンコーダーの初期化に失敗しました")
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return false
        }
        return try {
            genericStream.getGlInterface().setCameraOrientation(0)
            genericStream.changeVideoSource(ScreenSource(applicationContext, projection))
            stream = genericStream
            captureReady = true
            setAudioVolumes(1f, 1f)
            applySceneFilters()
            sendStatus("画面共有準備OK")
            true
        } catch (e: Exception) {
            genericStream.release()
            val p = mediaProjection; mediaProjection = null
            try { p?.stop() } catch (_: Exception) {}
            captureReady = false
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

    fun detachPreview() { try { stream?.let { if (it.isOnPreview) it.stopPreview() } } catch (_: Exception) {} }

    fun setActiveScene(scene: String) {
        if (!scenes.containsKey(scene)) return
        activeScene = scene
        selectedSource = null
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
        state.cameraLayer.enabled = !state.cameraLayer.enabled
        selectedSource = SourceKind.CAMERA
        if (captureReady) applySceneFilters()
        return state.cameraLayer.enabled
    }

    fun moveSelectedSource(dxPercent: Float, dyPercent: Float) {
        val layer = selectedLayer(scenes.getValue(activeScene)) ?: return
        layer.x = (layer.x + dxPercent).coerceIn(0f, 92f)
        layer.y = (layer.y + dyPercent).coerceIn(0f, 92f)
        applySelectedTransform(layer)
    }

    fun setSelectedScale(scale: Float) {
        val layer = selectedLayer(scenes.getValue(activeScene)) ?: return
        layer.scale = scale.coerceIn(8f, 80f)
        applySelectedTransform(layer)
    }

    fun setAudioVolumes(microphone: Float, internal: Float) {
        when (val source = stream?.audioSource) {
            is MixAudioSource -> { source.microphoneVolume = microphone.coerceIn(0f, 2f); source.internalVolume = internal.coerceIn(0f, 2f) }
            is MicrophoneSource -> source.microphoneVolume = microphone.coerceIn(0f, 2f)
            is InternalAudioSource -> source.internalVolume = internal.coerceIn(0f, 2f)
        }
    }

    fun setMicrophoneMuted(muted: Boolean) {
        when (val source = stream?.audioSource) {
            is MixAudioSource -> if (muted) source.mute() else source.unMute()
            is MicrophoneSource -> if (muted) source.mute() else source.unMute()
        }
    }

    private fun selectedLayer(state: SceneState): Layer? = when (selectedSource) {
        SourceKind.TEXT -> state.textLayer
        SourceKind.IMAGE -> state.imageLayer
        SourceKind.CAMERA -> state.cameraLayer
        null -> null
    }

    private fun applySelectedTransform(layer: Layer) {
        when (selectedSource) {
            SourceKind.TEXT -> textFilter?.let { applyTransform(it, layer, true) }
            SourceKind.IMAGE -> imageFilter?.let { applyTransform(it, layer) }
            SourceKind.CAMERA -> cameraFilter?.let { applyTransform(it, layer) }
            null -> Unit
        }
    }

    private fun applyTransform(filter: BaseObjectFilterRender, layer: Layer, textMode: Boolean = false) {
        val xScale = if (textMode) min(90f, layer.scale * 1.55f) else layer.scale
        val yScale = if (textMode) max(10f, layer.scale * 0.42f) else layer.scale
        filter.setScale(xScale, yScale)
        filter.setPosition(layer.x, layer.y)
    }

    private fun applySceneFilters() {
        val engine = stream ?: return
        stopOverlayCamera()
        try { engine.getGlInterface().clearFilters() } catch (_: Exception) {}
        textFilter = null; imageFilter = null; cameraFilter = null
        val state = scenes.getValue(activeScene)

        if (state.textLayer.enabled && !state.text.isNullOrBlank()) {
            val filter = TextFilterRender()
            filter.setText(state.text!!, 34f, Color.WHITE, Color.TRANSPARENT)
            applyTransform(filter, state.textLayer, true)
            engine.getGlInterface().addFilter(filter)
            textFilter = filter
        }
        if (state.imageLayer.enabled && state.image != null) {
            val filter = ImageFilterRender()
            filter.setImage(state.image)
            applyTransform(filter, state.imageLayer)
            engine.getGlInterface().addFilter(filter)
            imageFilter = filter
        }
        if (state.cameraLayer.enabled && ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            val filter = SurfaceFilterRender { texture -> startOverlayCamera(texture) }
            applyTransform(filter, state.cameraLayer)
            engine.getGlInterface().addFilter(filter)
            cameraFilter = filter
        }
    }

    private fun startOverlayCamera(surfaceTexture: SurfaceTexture) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        stopOverlayCamera()
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } ?: manager.cameraIdList.firstOrNull() ?: return
        surfaceTexture.setDefaultBufferSize(640, 480)
        val surface = Surface(surfaceTexture)
        cameraSurface = surface
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    }
                    camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            cameraSession = session
                            try { session.setRepeatingRequest(request.build(), null, Handler(Looper.getMainLooper())) } catch (_: Exception) {}
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) = Unit
                    }, Handler(Looper.getMainLooper()))
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); if (cameraDevice === camera) cameraDevice = null }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); if (cameraDevice === camera) cameraDevice = null }
            }, Handler(Looper.getMainLooper()))
        } catch (_: Exception) { surface.release(); cameraSurface = null }
    }

    private fun stopOverlayCamera() {
        try { cameraSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { cameraSurface?.release() } catch (_: Exception) {}
        cameraSession = null; cameraDevice = null; cameraSurface = null
    }

    fun showFloatingControls(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) return false
        if (floatingView != null) return true
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 6, 8, 6)
            setBackgroundColor(0xDD1B1F2A.toInt())
        }
        fun miniButton(label: String, action: () -> Unit): Button = Button(this).apply { text = label; textSize = 11f; setOnClickListener { action() } }
        panel.addView(miniButton("REC") { toggleRecording() })
        panel.addView(miniButton("LIVE") { if (isStreamingNow()) stopRtmp() })
        panel.addView(miniButton("MIC") {
            val source = stream?.audioSource
            val muted = when (source) { is MixAudioSource -> source.isMuted(); is MicrophoneSource -> source.isMuted(); else -> false }
            setMicrophoneMuted(!muted)
        })
        panel.addView(miniButton("×") { hideFloatingControls() })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 18; y = 180 }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        panel.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; false }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX - (event.rawX - downX)).toInt(); params.y = (startY + (event.rawY - downY)).toInt()
                    try { wm.updateViewLayout(panel, params) } catch (_: Exception) {}; true
                }
                else -> false
            }
        }
        return try { wm.addView(panel, params); floatingView = panel; true } catch (_: Exception) { false }
    }

    fun hideFloatingControls() {
        floatingView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        floatingView = null; windowManager = null
    }

    fun startRtmp(url: String): Boolean {
        val engine = stream ?: return false
        if (!captureReady || url.isBlank()) return false
        return try {
            if (!engine.isStreaming) { engine.startStream(url.trim()); sendStatus("RTMPへ接続中…") }
            true
        } catch (e: Exception) { sendStatus("配信開始失敗: ${e.message ?: "不明なエラー"}"); false }
    }

    fun stopRtmp() { stream?.let { if (it.isStreaming) it.stopStream() }; sendStatus("配信停止") }

    fun toggleRecording(): Boolean {
        val engine = stream ?: return false
        if (!captureReady) return false
        return try {
            if (!engine.isRecording) {
                val folder = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "MiniOBS"); folder.mkdirs()
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val path = File(folder, "MiniOBS_$stamp.mp4").absolutePath
                recordingPath = path
                engine.startRecord(path) { _ -> }
                sendStatus("録画中: MiniOBS_$stamp.mp4")
                true
            } else {
                engine.stopRecord(); recordingPath?.let(::publishRecordingToGallery)
                sendStatus("録画保存: ${recordingPath?.substringAfterLast('/') ?: "完了"}"); true
            }
        } catch (e: Exception) { sendStatus("録画エラー: ${e.message ?: "不明なエラー"}"); false }
    }

    private fun publishRecordingToGallery(path: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val file = File(path); if (!file.exists()) return
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MiniOBS")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri: Uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return
            contentResolver.openOutputStream(uri)?.use { out -> FileInputStream(file).use { it.copyTo(out) } }
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0); contentResolver.update(uri, values, null, null)
        } catch (_: Exception) {}
    }

    fun isCaptureReady(): Boolean = captureReady
    fun isStreamingNow(): Boolean = stream?.isStreaming == true
    fun isRecordingNow(): Boolean = stream?.isRecording == true
    fun stopCapture() = stopEverything("画面共有停止")

    private fun stopEverything(message: String) {
        try {
            stream?.let {
                if (it.isRecording) { it.stopRecord(); recordingPath?.let(::publishRecordingToGallery) }
                if (it.isStreaming) it.stopStream()
            }
        } catch (_: Exception) {}
        hideFloatingControls(); releaseEngine(); captureReady = false; sendStatus(message)
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun releaseEngine() {
        stopOverlayCamera()
        try { stream?.release() } catch (_: Exception) {}
        stream = null
        val p = mediaProjection; mediaProjection = null
        try { p?.stop() } catch (_: Exception) {}
        captureReady = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Mini OBS 画面共有", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun startAsForeground(usesMicrophone: Boolean) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online).setContentTitle("Mini OBS")
            .setContentText("画面をキャプチャしています").setOngoing(true).setSilent(true).build()
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (usesMicrophone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
    }

    private fun sendStatus(message: String) {
        sendBroadcast(Intent(ACTION_STATUS).apply {
            setPackage(packageName); putExtra(EXTRA_STATUS, message); putExtra(EXTRA_STREAMING, isStreamingNow())
            putExtra(EXTRA_RECORDING, isRecordingNow()); putExtra(EXTRA_CAPTURE_READY, captureReady); putExtra(EXTRA_SCENE, activeScene)
        })
    }

    private fun sendBitrate(bitrate: Long) {
        sendBroadcast(Intent(ACTION_STATUS).apply {
            setPackage(packageName); putExtra(EXTRA_STATUS, "配信中"); putExtra(EXTRA_BITRATE, bitrate); putExtra(EXTRA_STREAMING, true)
            putExtra(EXTRA_RECORDING, isRecordingNow()); putExtra(EXTRA_CAPTURE_READY, captureReady); putExtra(EXTRA_SCENE, activeScene)
        })
    }

    private fun sceneLabel(scene: String) = when (scene) { "talk" -> "雑談"; "wait" -> "待機"; else -> "ゲーム" }

    override fun onDestroy() { hideFloatingControls(); releaseEngine(); super.onDestroy() }
    override fun onConnectionStarted(url: String) = sendStatus("RTMPへ接続中…")
    override fun onConnectionSuccess() = sendStatus("配信中")
    override fun onConnectionFailed(reason: String) = sendStatus("配信エラー: $reason")
    override fun onNewBitrate(bitrate: Long) = sendBitrate(bitrate)
    override fun onDisconnect() = sendStatus("配信停止")
    override fun onAuthError() = sendStatus("RTMP認証エラー")
    override fun onAuthSuccess() = sendStatus("RTMP認証成功")
}
