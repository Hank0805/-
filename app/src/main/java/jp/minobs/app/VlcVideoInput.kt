package jp.minobs.app

import android.content.Context
import android.net.Uri
import android.view.Surface
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * Network media input rendered straight into RootEncoder's GL overlay surface.
 * LibVLC handles RTSP, HLS/DASH and other supported network demuxers. srt:// is accepted as an
 * experimental input; availability depends on the protocol modules bundled by the Android LibVLC build.
 */
class VlcVideoInput(private val context: Context) {
    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null
    private var surface: Surface? = null

    fun createFilter(url: String, onStatus: (String) -> Unit = {}): SurfaceFilterRender {
        stop()
        return SurfaceFilterRender { texture ->
            texture.setDefaultBufferSize(1280, 720)
            val output = Surface(texture)
            surface = output
            start(url.trim(), output, onStatus)
        }
    }

    private fun start(url: String, output: Surface, onStatus: (String) -> Unit) {
        if (url.isBlank()) { onStatus("Network URLが空です"); return }
        try {
            val options = arrayListOf(
                "--network-caching=250",
                "--clock-jitter=0",
                "--clock-synchro=0",
                "--drop-late-frames",
                "--skip-frames"
            )
            val vlc = LibVLC(context.applicationContext, options)
            val mp = MediaPlayer(vlc)
            libVlc = vlc; player = mp
            mp.vlcVout.setVideoSurface(output, null)
            mp.vlcVout.attachViews()
            mp.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.Playing -> onStatus("Network入力: 映像再生中")
                    MediaPlayer.Event.Opening -> onStatus("Network入力: 接続中…")
                    MediaPlayer.Event.Buffering -> onStatus("Network入力: Buffer ${event.buffering}%")
                    MediaPlayer.Event.EncounteredError -> onStatus("Network入力エラー")
                    MediaPlayer.Event.EndReached -> onStatus("Network入力終了")
                }
            }
            val media = Media(vlc, Uri.parse(url)).apply {
                setHWDecoderEnabled(true, false)
                addOption(":network-caching=250")
                addOption(":clock-jitter=0")
            }
            mp.media = media
            media.release()
            if (!mp.play()) onStatus("Network入力を開始できませんでした")
        } catch (e: Exception) {
            onStatus("Network入力エラー: ${e.message ?: e.javaClass.simpleName}")
            stop()
        }
    }

    fun stop() {
        val mp = player; player = null
        runCatching { mp?.stop() }
        runCatching { mp?.vlcVout?.detachViews() }
        runCatching { mp?.release() }
        val vlc = libVlc; libVlc = null
        runCatching { vlc?.release() }
        runCatching { surface?.release() }; surface = null
    }
}
