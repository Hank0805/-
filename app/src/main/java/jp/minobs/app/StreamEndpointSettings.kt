package jp.minobs.app

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

object StreamEndpointSettings {
  private const val PREFS = "studio_output"
  private const val KEY_SERVER_URL = "server_url"
  private const val KEY_STREAM_KEY = "stream_key"
  private const val KEY_LEGACY_RTMP = "rtmp"

  data class Values(val serverUrl: String, val streamKey: String)

  fun load(context: Context): Values {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val server = prefs.getString(KEY_SERVER_URL, "").orEmpty().trim()
    val key = prefs.getString(KEY_STREAM_KEY, "").orEmpty().trim()
    if (server.isNotBlank() || key.isNotBlank()) return Values(server, key)

    // Backward compatibility with v0.5.x where URL and key were stored as one endpoint.
    val legacy = prefs.getString(KEY_LEGACY_RTMP, "").orEmpty()
      .lineSequence()
      .map { it.trim() }
      .firstOrNull { it.startsWith("rtmp://", true) || it.startsWith("rtmps://", true) }
      .orEmpty()

    if (legacy.isBlank()) return Values("", "")

    val youtubeMarkers = listOf("/live2/", "/live/")
    val marker = youtubeMarkers.firstOrNull { legacy.contains(it, ignoreCase = true) }
    if (marker != null) {
      val index = legacy.indexOf(marker, ignoreCase = true)
      val serverUrl = legacy.substring(0, index + marker.length - 1).trimEnd('/')
      val streamKey = legacy.substring(index + marker.length).trim('/')
      return Values(serverUrl, streamKey)
    }

    // Generic RTMP endpoint: keep the full endpoint in the URL field.
    return Values(legacy, "")
  }

  fun save(context: Context, serverUrl: String, streamKey: String) {
    val server = serverUrl.trim().trimEnd('/')
    val key = streamKey.trim().trim('/')
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .edit()
      .putString(KEY_SERVER_URL, server)
      .putString(KEY_STREAM_KEY, key)
      .putString(KEY_LEGACY_RTMP, buildEndpoint(server, key))
      .apply()
  }

  fun endpoints(context: Context): List<String> {
    val values = load(context)
    val endpoint = buildEndpoint(values.serverUrl, values.streamKey)
    return if (endpoint.startsWith("rtmp://", true) || endpoint.startsWith("rtmps://", true)) {
      listOf(endpoint)
    } else {
      emptyList()
    }
  }

  private fun buildEndpoint(serverUrl: String, streamKey: String): String {
    val server = serverUrl.trim().trimEnd('/')
    val key = streamKey.trim().trim('/')
    if (server.isBlank()) return ""
    return if (key.isBlank()) server else "$server/$key"
  }

  fun show(context: Context, onSaved: () -> Unit = {}) {
    val current = load(context)
    val pad = (16 * context.resources.displayMetrics.density).toInt()

    val container = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(pad, pad / 2, pad, 0)
    }

    val help = TextView(context).apply {
      text = "YouTube Studioに表示される『ストリームURL』と『ストリームキー』を別々に入力してください。Mini OBSが配信時に自動で結合します。"
      textSize = 13f
    }
    container.addView(help, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    val serverLabel = TextView(context).apply { text = "配信サーバーURL" }
    container.addView(serverLabel)

    val serverInput = EditText(context).apply {
      hint = "例: rtmps://a.rtmps.youtube.com/live2"
      setSingleLine(true)
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
      setText(current.serverUrl)
      selectAll()
    }
    container.addView(serverInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    val keyLabel = TextView(context).apply { text = "ストリームキー" }
    container.addView(keyLabel)

    val keyInput = EditText(context).apply {
      hint = "ストリームキーを貼り付け"
      setSingleLine(true)
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
      setText(current.streamKey)
    }
    container.addView(keyInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    val showKey = CheckBox(context).apply {
      text = "ストリームキーを表示"
      setOnCheckedChangeListener { _, checked ->
        keyInput.inputType = InputType.TYPE_CLASS_TEXT or if (checked) {
          InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
          InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        keyInput.setSelection(keyInput.text.length)
      }
    }
    container.addView(showKey)

    val note = TextView(context).apply {
      text = "※ ストリームキーは他人に見せないでください。"
      textSize = 12f
    }
    container.addView(note)

    AlertDialog.Builder(context)
      .setTitle("配信先設定")
      .setView(container)
      .setPositiveButton("保存") { _, _ ->
        save(context, serverInput.text.toString(), keyInput.text.toString())
        onSaved()
      }
      .setNegativeButton("キャンセル", null)
      .show()
  }
}
