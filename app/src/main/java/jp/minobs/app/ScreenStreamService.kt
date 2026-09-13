package jp.minobs.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.MixAudioSource
import com.pedro.encoder.input.sources.video.NoVideoSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.generic.GenericStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenStreamService : Service(), ConnectChecker {
    companion object {
        const val ACTION_STATUS = "jp.minobs.app.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_STREAMING = "streaming"
        const val EXTRA_RECORDING = "recording"
        const val EXTRA_CAPTURE_READY = "capture_ready"
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

    override fun onBind(intent: Intent?): IBinder = binder
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
        val videoPrepared = try { genericStream.prepareVideo(config.width, config.height, config.bitrate, config.fps, 2, config.rotation) } catch (_: Exception) { false }
        val audioPrepared = try { genericStream.prepareAudio(44_100, true, 128_000, echoCanceler = true, noiseSuppressor = true) } catch (_: Exception) { false }
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
                val folder = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "MiniOBS")
                folder.mkdirs()
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val path = File(folder, "MiniOBS_$stamp.mp4").absolutePath
                recordingPath = path
                engine.startRecord(path) { _ -> }
                sendStatus("録画中: MiniOBS_$stamp.mp4")
                true
            } else {
                engine.stopRecord(); sendStatus("録画保存: ${recordingPath ?: "完了"}"); true
            }
        } catch (e: Exception) { sendStatus("録画エラー: ${e.message ?: "不明なエラー"}"); false }
    }

    fun isCaptureReady(): Boolean = captureReady
    fun isStreamingNow(): Boolean = stream?.isStreaming == true
    fun isRecordingNow(): Boolean = stream?.isRecording == true
    fun stopCapture() = stopEverything("画面共有停止")

    private fun stopEverything(message: String) {
        try { stream?.let { if (it.isRecording) it.stopRecord(); if (it.isStreaming) it.stopStream() } } catch (_: Exception) {}
        releaseEngine(); captureReady = false; sendStatus(message); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun releaseEngine() {
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
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Mini OBS")
            .setContentText("画面をキャプチャしています")
            .setOngoing(true).setSilent(true).build()
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (usesMicrophone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
    }

    private fun sendStatus(message: String) {
        sendBroadcast(Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, message)
            putExtra(EXTRA_STREAMING, isStreamingNow())
            putExtra(EXTRA_RECORDING, isRecordingNow())
            putExtra(EXTRA_CAPTURE_READY, captureReady)
        })
    }

    private fun sendBitrate(bitrate: Long) {
        sendBroadcast(Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, "配信中")
            putExtra(EXTRA_BITRATE, bitrate)
            putExtra(EXTRA_STREAMING, true)
            putExtra(EXTRA_RECORDING, isRecordingNow())
            putExtra(EXTRA_CAPTURE_READY, captureReady)
        })
    }

    override fun onDestroy() { releaseEngine(); super.onDestroy() }
    override fun onConnectionStarted(url: String) = sendStatus("RTMPへ接続中…")
    override fun onConnectionSuccess() = sendStatus("配信中")
    override fun onConnectionFailed(reason: String) = sendStatus("配信エラー: $reason")
    override fun onNewBitrate(bitrate: Long) = sendBitrate(bitrate)
    override fun onDisconnect() = sendStatus("配信停止")
    override fun onAuthError() = sendStatus("RTMP認証エラー")
    override fun onAuthSuccess() = sendStatus("RTMP認証成功")
}
