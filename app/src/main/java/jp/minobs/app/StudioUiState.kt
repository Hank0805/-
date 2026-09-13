package jp.minobs.app

import android.content.Context
import org.json.JSONObject

class StudioUiState(private val context: Context) {
  private val prefs = context.getSharedPreferences("studio_ui_state", Context.MODE_PRIVATE)

  var dockHeightDp: Int
    get() = prefs.getInt("dockHeightDp", 210)
    set(value) { prefs.edit().putInt("dockHeightDp", value.coerceIn(130, 420)).apply() }

  fun isCollapsed(panel: String): Boolean = prefs.getBoolean("collapsed_$panel", false)

  fun toggle(panel: String): Boolean {
    val next = !isCollapsed(panel)
    prefs.edit().putBoolean("collapsed_$panel", next).apply()
    return next
  }

  fun setCollapsed(panel: String, collapsed: Boolean) {
    prefs.edit().putBoolean("collapsed_$panel", collapsed).apply()
  }

  fun saveWorkspace(name: String) {
    val clean = name.trim().ifBlank { "ワークスペース" }
    val root = JSONObject(prefs.getString("workspaces", "{}") ?: "{}")
    root.put(clean, JSONObject().apply {
      put("dockHeightDp", dockHeightDp)
      put("properties", isCollapsed("properties"))
      put("audio", isCollapsed("audio"))
      put("controls", isCollapsed("controls"))
      put("tools", isCollapsed("tools"))
    })
    prefs.edit().putString("workspaces", root.toString()).apply()
  }

  fun workspaceNames(): List<String> = runCatching {
    JSONObject(prefs.getString("workspaces", "{}") ?: "{}").keys().asSequence().toList().sorted()
  }.getOrDefault(emptyList())

  fun loadWorkspace(name: String): Boolean = runCatching {
    val root = JSONObject(prefs.getString("workspaces", "{}") ?: "{}")
    val o = root.optJSONObject(name) ?: return false
    dockHeightDp = o.optInt("dockHeightDp", 210)
    setCollapsed("properties", o.optBoolean("properties", false))
    setCollapsed("audio", o.optBoolean("audio", false))
    setCollapsed("controls", o.optBoolean("controls", false))
    setCollapsed("tools", o.optBoolean("tools", false))
    true
  }.getOrDefault(false)
}
