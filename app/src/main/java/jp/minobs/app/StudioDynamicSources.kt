package jp.minobs.app

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient

class StudioDynamicSources(
  private val context: Context,
  private val renderer: StudioRenderer
) {
  private val handler = Handler(Looper.getMainLooper())
  private val webViews = mutableMapOf<String, WebView>()
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
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      settings.mediaPlaybackRequiresUserGesture = false
      settings.loadWithOverviewMode = true
      settings.useWideViewPort = true
      webViewClient = object : WebViewClient() {}
      loadUrl(normalizeUrl(source.data))
    }
    webViews[source.id] = web
    renderer.updateView(source, web)
  }

  fun reloadBrowser(source: StudioSource) {
    webViews[source.id]?.reload() ?: startBrowser(source)
  }

  fun startSlideshow(source: StudioSource) {
    stopTask(source.id)
    val uris = source.data.lines().filter { it.isNotBlank() }
    if (uris.isEmpty()) return
    var index = 0
    val task = object : Runnable {
      override fun run() {
        renderer.loadImage(source, Uri.parse(uris[index % uris.size]))
        index++
        handler.postDelayed(this, 5000)
      }
    }
    slideTasks[source.id] = task
    handler.post(task)
  }

  fun stop(id: String) {
    stopTask(id)
    webViews.remove(id)?.destroy()
  }

  private fun stopTask(id: String) {
    slideTasks.remove(id)?.let { handler.removeCallbacks(it) }
  }

  fun clear() {
    slideTasks.values.forEach { handler.removeCallbacks(it) }
    slideTasks.clear()
    webViews.values.forEach { it.destroy() }
    webViews.clear()
  }

  private fun normalizeUrl(value: String): String =
    if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"
}
