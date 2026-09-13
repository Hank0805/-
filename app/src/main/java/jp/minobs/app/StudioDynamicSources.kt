package jp.minobs.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient

class StudioDynamicSources(
  private val context: Context,
  private val renderer: StudioRenderer
) {
  private val handler = Handler(Looper.getMainLooper())
  private val webViews = mutableMapOf<String, WebView>()
  private val webTasks = mutableMapOf<String, Runnable>()
  private val slideTasks = mutableMapOf<String, Runnable>()

  fun hydrate(source: StudioSource) {
    when (source.type) {
      StudioSourceType.IMAGE -> if (source.data.isNotBlank()) renderer.loadImage(source, Uri.parse(source.data))
      StudioSourceType.BROWSER -> startBrowser(source)
      StudioSourceType.SLIDESHOW -> startSlideshow(source)
      else -> Unit
    }
  }

  fun startBrowser(source: StudioSource) {
    stop(source.id)
    val web = WebView(context).apply {
      setBackgroundColor(Color.TRANSPARENT)
      settings.domStorageEnabled = true
      settings.javaScriptEnabled = true
      webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) { capture(source, this@apply) }
      }
      loadUrl(normalizeUrl(source.data))
    }
    webViews[source.id] = web
    val task = object : Runnable {
      override fun run() { capture(source, web); handler.postDelayed(this, 1500) }
    }
    webTasks[source.id] = task; handler.postDelayed(task, 1800)
  }

  private fun capture(source: StudioSource, web: WebView) {
    runCatching {
      val w = 960; val h = 540
      web.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
      web.layout(0, 0, w, h)
      val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
      web.draw(Canvas(bitmap)); renderer.updateBitmap(source, bitmap)
    }
  }

  fun startSlideshow(source: StudioSource) {
    stopTask(source.id)
    val uris = source.data.lines().filter { it.isNotBlank() }; if (uris.isEmpty()) return
    var index = 0
    val task = object : Runnable {
      override fun run() {
        renderer.loadImage(source, Uri.parse(uris[index % uris.size])); index++
        handler.postDelayed(this, 5000)
      }
    }
    slideTasks[source.id] = task; handler.post(task)
  }

  fun stop(id: String) {
    stopTask(id); webViews.remove(id)?.destroy()
  }

  private fun stopTask(id: String) {
    webTasks.remove(id)?.let { handler.removeCallbacks(it) }
    slideTasks.remove(id)?.let { handler.removeCallbacks(it) }
  }

  fun clear() {
    webTasks.values.forEach { handler.removeCallbacks(it) }; slideTasks.values.forEach { handler.removeCallbacks(it) }
    webTasks.clear(); slideTasks.clear(); webViews.values.forEach { it.destroy() }; webViews.clear()
  }

  private fun normalizeUrl(value: String): String = if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"
}
