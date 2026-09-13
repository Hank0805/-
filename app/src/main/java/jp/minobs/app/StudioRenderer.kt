package jp.minobs.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.BaseObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.ImageFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.TextFilterRender
import com.pedro.library.view.GlInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class StudioRenderer(private val context: Context) {
  private val main = Handler(Looper.getMainLooper())
  private var service: ScreenStreamService? = null
  private val filters = ConcurrentHashMap<String, BaseFilterRender>()
  private val mediaPlayers = ConcurrentHashMap<String, MediaPlayer>()
  private val bitmaps = ConcurrentHashMap<String, Bitmap>()
  private val timers = ConcurrentHashMap<String, Runnable>()

  fun attach(service: ScreenStreamService?) { this.service = service }

  private fun gl(): GlInterface? {
    val s = service ?: return null
    return runCatching {
      val field = ScreenStreamService::class.java.getDeclaredField("stream").apply { isAccessible = true }
      val engine = field.get(s) as? MultiRtmpStream
      engine?.getGlInterface()
    }.getOrNull()
  }

  fun clear() {
    timers.values.forEach { main.removeCallbacks(it) }; timers.clear()
    mediaPlayers.values.forEach { runCatching { it.stop() }; runCatching { it.release() } }; mediaPlayers.clear()
    filters.values.forEach { f -> runCatching { gl()?.removeFilter(f) } }; filters.clear()
  }

  fun rebuild(project: StudioProject, scene: StudioScene) {
    clear()
    if (service?.isCaptureReady() != true) return
    val all = ArrayList<StudioSource>(); all += scene.sources; all += project.globalSources
    all.filter { it.visible && it.type != StudioSourceType.SCREEN }.forEach { add(it) }
  }

  fun add(source: StudioSource) {
    val g = gl() ?: return
    if (!source.visible || source.type == StudioSourceType.SCREEN) return
    val filter: BaseFilterRender? = when (source.type) {
      StudioSourceType.TEXT, StudioSourceType.CHAT -> TextFilterRender().apply { setText(source.data.ifBlank { source.name }, 32f, Color.WHITE, Color.TRANSPARENT) }
      StudioSourceType.CLOCK -> TextFilterRender().apply { setText(nowText(source.data), 32f, Color.WHITE, Color.TRANSPARENT) }
      StudioSourceType.TIMER -> TextFilterRender().apply { setText(timerText(source), 32f, Color.WHITE, Color.TRANSPARENT) }
      StudioSourceType.IMAGE, StudioSourceType.BROWSER, StudioSourceType.SLIDESHOW, StudioSourceType.EXTERNAL -> ImageFilterRender().apply { bitmaps[source.id]?.let { setImage(it) } }
      StudioSourceType.MEDIA -> mediaFilter(source)
      StudioSourceType.CAMERA -> null
      StudioSourceType.SCREEN -> null
    }
    if (source.type == StudioSourceType.CAMERA) {
      service?.selectSource(ScreenStreamService.SourceKind.CAMERA)
      val enabled = runCatching { service?.toggleCameraSource() }.getOrNull()
      if (enabled == false) runCatching { service?.toggleCameraSource() }
      return
    }
    if (filter != null) {
      if (filter is BaseObjectFilterRender) applyTransform(filter, source.transform)
      g.addFilter(filter); filters[source.id] = filter
      if (source.type == StudioSourceType.CLOCK || source.type == StudioSourceType.TIMER) scheduleDynamicText(source)
    }
  }

  fun remove(id: String) { filters.remove(id)?.let { runCatching { gl()?.removeFilter(it) } }; timers.remove(id)?.let { main.removeCallbacks(it) }; mediaPlayers.remove(id)?.let { runCatching { it.stop() }; runCatching { it.release() } } }

  fun updateTransform(source: StudioSource) {
    val f = filters[source.id] as? BaseObjectFilterRender ?: return
    applyTransform(f, source.transform)
  }

  fun updateVisibility(source: StudioSource) {
    val f = filters[source.id]
    if (!source.visible) { if (f != null) remove(source.id) }
    else if (f == null) add(source)
  }

  fun updateText(source: StudioSource) {
    val f = filters[source.id] as? TextFilterRender ?: return
    f.setText(source.data.ifBlank { source.name }, 32f, Color.WHITE, Color.TRANSPARENT)
  }

  fun updateBitmap(source: StudioSource, bitmap: Bitmap) {
    bitmaps[source.id] = bitmap
    val current = filters[source.id] as? ImageFilterRender
    if (current != null) current.setImage(bitmap) else add(source)
  }

  fun loadImage(source: StudioSource, uri: Uri): Boolean = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input -> BitmapFactory.decodeStream(input) }?.let { updateBitmap(source, it) } ?: error("decode")
    true
  }.getOrDefault(false)

  fun selectedFilter(id: String): BaseObjectFilterRender? = filters[id] as? BaseObjectFilterRender

  private fun applyTransform(filter: BaseObjectFilterRender, t: StudioTransform) {
    filter.setScale(t.width.coerceIn(1f, 100f), t.height.coerceIn(1f, 100f))
    filter.setPosition(t.x.coerceIn(-100f, 100f), t.y.coerceIn(-100f, 100f))
    filter.rotation = t.rotation
    filter.alpha = t.alpha.coerceIn(0f, 1f)
  }

  private fun mediaFilter(source: StudioSource): SurfaceFilterRender? {
    if (source.data.isBlank()) return null
    return SurfaceFilterRender { texture ->
      runCatching {
        texture.setDefaultBufferSize(1280, 720)
        val surface = Surface(texture)
        val mp = MediaPlayer().apply {
          setDataSource(context, Uri.parse(source.data)); setSurface(surface); isLooping = true
          setOnPreparedListener { it.start() }; prepareAsync()
        }
        mediaPlayers[source.id] = mp
      }
    }
  }

  private fun scheduleDynamicText(source: StudioSource) {
    val r = object : Runnable {
      override fun run() {
        val f = filters[source.id] as? TextFilterRender ?: return
        val text = if (source.type == StudioSourceType.CLOCK) nowText(source.data) else timerText(source)
        runCatching { f.setText(text, 32f, Color.WHITE, Color.TRANSPARENT) }
        main.postDelayed(this, if (source.type == StudioSourceType.CLOCK) 1000 else 250)
      }
    }
    timers[source.id] = r; main.post(r)
  }

  private fun nowText(pattern: String): String = runCatching { SimpleDateFormat(pattern.ifBlank { "HH:mm:ss" }, Locale.getDefault()).format(Date()) }.getOrDefault(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()))
  private fun timerText(source: StudioSource): String {
    val parts = source.data.split('|'); val started = parts.getOrNull(0)?.toLongOrNull() ?: System.currentTimeMillis(); val duration = parts.getOrNull(1)?.toLongOrNull() ?: 0L
    val elapsed = ((System.currentTimeMillis() - started) / 1000L).coerceAtLeast(0L); val seconds = if (duration > 0) (duration - elapsed).coerceAtLeast(0L) else elapsed
    return "%02d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
  }
}
