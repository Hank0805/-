package jp.minobs.app

import android.content.Context
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID

class StudioWorkspace(private val context: Context) {
  private val prefs = context.getSharedPreferences("studio_workspace", Context.MODE_PRIVATE)
  private val undoStack = ArrayDeque<String>()
  private val redoStack = ArrayDeque<String>()
  var project: StudioProject = load()
    private set
  var selectedSceneId: String = project.programSceneId
  var selectedSourceId: String? = null

  fun scene(id: String = selectedSceneId) = project.scenes.firstOrNull { it.id == id }
  fun source(id: String? = selectedSourceId): StudioSource? {
    if (id == null) return null
    return project.globalSources.firstOrNull { it.id == id } ?: project.scenes.asSequence().flatMap { it.sources.asSequence() }.firstOrNull { it.id == id }
  }

  fun checkpoint() {
    undoStack.addLast(project.toJson().toString())
    while (undoStack.size > 40) undoStack.removeFirst()
    redoStack.clear()
  }

  fun autosave() = prefs.edit().putString("autosave", project.toJson().toString()).apply()
  fun mutate(block: (StudioProject) -> Unit) { checkpoint(); block(project); autosave() }

  fun undo(): Boolean {
    if (undoStack.isEmpty()) return false
    redoStack.addLast(project.toJson().toString())
    project = StudioProject.fromJson(JSONObject(undoStack.removeLast()))
    normalize(); autosave(); return true
  }

  fun redo(): Boolean {
    if (redoStack.isEmpty()) return false
    undoStack.addLast(project.toJson().toString())
    project = StudioProject.fromJson(JSONObject(redoStack.removeLast()))
    normalize(); autosave(); return true
  }

  fun addScene(name: String): StudioScene {
    checkpoint()
    val s = StudioScene(name = name.ifBlank { "Scene ${project.scenes.size + 1}" })
    s.sources += StudioSource(name = "Screen Capture", type = StudioSourceType.SCREEN, locked = true, transform = StudioTransform(0f, 0f, 100f, 100f))
    project.scenes += s
    selectedSceneId = s.id; selectedSourceId = null; autosave(); return s
  }

  fun duplicateScene(id: String): StudioScene? {
    val src = project.scenes.firstOrNull { it.id == id } ?: return null
    checkpoint(); val c = src.deepCopy(true).also { it.name = src.name + " Copy" }
    project.scenes += c; selectedSceneId = c.id; selectedSourceId = null; autosave(); return c
  }

  fun removeScene(id: String): Boolean {
    if (project.scenes.size <= 1) return false
    checkpoint(); project.scenes.removeAll { it.id == id }
    if (project.programSceneId == id) project.programSceneId = project.scenes.first().id
    if (project.previewSceneId == id) project.previewSceneId = project.scenes.first().id
    normalize(); autosave(); return true
  }

  fun addSource(type: StudioSourceType, name: String, data: String = "", global: Boolean = false): StudioSource {
    checkpoint(); val s = StudioSource(name = name.ifBlank { type.name }, type = type, data = data, global = global)
    s.transform = when (type) {
      StudioSourceType.SCREEN -> StudioTransform(0f,0f,100f,100f)
      StudioSourceType.TEXT, StudioSourceType.CLOCK, StudioSourceType.TIMER, StudioSourceType.CHAT -> StudioTransform(8f,8f,42f,16f)
      StudioSourceType.CAMERA, StudioSourceType.EXTERNAL -> StudioTransform(68f,5f,28f,28f)
      StudioSourceType.IMAGE -> StudioTransform(8f,58f,28f,28f)
      else -> StudioTransform(15f,15f,70f,55f)
    }
    if (global) project.globalSources += s else scene()?.sources?.add(s)
    selectedSourceId = s.id; autosave(); return s
  }

  fun duplicateSource(id: String): StudioSource? {
    val src = source(id) ?: return null
    checkpoint(); val c = src.deepCopy(true).also { it.name += " Copy"; it.transform.x = (it.transform.x + 3f).coerceAtMost(92f); it.transform.y = (it.transform.y + 3f).coerceAtMost(92f) }
    if (src.global) project.globalSources += c else project.scenes.firstOrNull { sc -> sc.sources.any { it.id == id } }?.sources?.add(c)
    selectedSourceId = c.id; autosave(); return c
  }

  fun removeSource(id: String): Boolean {
    val src = source(id) ?: return false
    if (src.type == StudioSourceType.SCREEN) return false
    checkpoint(); project.globalSources.removeAll { it.id == id }; project.scenes.forEach { it.sources.removeAll { s -> s.id == id } }
    if (selectedSourceId == id) selectedSourceId = null; autosave(); return true
  }

  fun moveSource(id: String, delta: Int): Boolean {
    val list = project.scenes.firstOrNull { sc -> sc.sources.any { it.id == id } }?.sources ?: project.globalSources.takeIf { g -> g.any { it.id == id } } ?: return false
    val i = list.indexOfFirst { it.id == id }; if (i < 0) return false
    val n = (i + delta).coerceIn(0, list.lastIndex); if (i == n) return false
    checkpoint(); val item = list.removeAt(i); list.add(n, item); autosave(); return true
  }

  fun toggleGlobal(id: String): Boolean {
    val src = source(id) ?: return false
    if (src.type == StudioSourceType.SCREEN) return false
    checkpoint()
    if (src.global) { project.globalSources.removeAll { it.id == id }; src.global = false; scene()?.sources?.add(src) }
    else { project.scenes.forEach { it.sources.removeAll { s -> s.id == id } }; src.global = true; project.globalSources += src }
    autosave(); return true
  }

  fun group(ids: Set<String>): String? {
    if (ids.size < 2) return null
    checkpoint(); val g = UUID.randomUUID().toString(); ids.forEach { source(it)?.groupId = g }; autosave(); return g
  }

  fun savePreset(name: String, src: StudioSource) {
    val root = JSONObject(prefs.getString("presets", "{}") ?: "{}"); root.put(name, src.toJson()); prefs.edit().putString("presets", root.toString()).apply()
  }
  fun presetNames(): List<String> = JSONObject(prefs.getString("presets", "{}") ?: "{}").keys().asSequence().toList().sorted()
  fun loadPreset(name: String): StudioSource? = JSONObject(prefs.getString("presets", "{}") ?: "{}").optJSONObject(name)?.let { StudioSource.fromJson(it).deepCopy(true) }
  fun exportJson() = project.toJson().toString(2)
  fun importJson(text: String) { checkpoint(); project = StudioProject.fromJson(JSONObject(text)); normalize(); autosave() }
  fun reset() { checkpoint(); project = StudioProject.defaultProject(); normalize(); autosave() }

  private fun load(): StudioProject = runCatching { prefs.getString("autosave", null)?.let { StudioProject.fromJson(JSONObject(it)) } }.getOrNull() ?: StudioProject.defaultProject()
  private fun normalize() {
    if (project.scenes.none { it.id == selectedSceneId }) selectedSceneId = project.programSceneId.takeIf { id -> project.scenes.any { it.id == id } } ?: project.scenes.first().id
    if (selectedSourceId != null && source(selectedSourceId) == null) selectedSourceId = null
  }
}
