package jp.minobs.app

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID

class StudioWorkspace(private val context: Context) {
  private val prefs = context.getSharedPreferences("studio_workspace", Context.MODE_PRIVATE)
  private val undoStack = ArrayDeque<String>()
  private val redoStack = ArrayDeque<String>()
  private var lastBackupAt = prefs.getLong("lastBackupAt", 0L)

  var project: StudioProject = load()
    private set
  var selectedSceneId: String = project.programSceneId
  var selectedSourceId: String? = null

  fun scene(id: String = selectedSceneId) = project.scenes.firstOrNull { it.id == id }

  fun source(id: String? = selectedSourceId): StudioSource? {
    if (id == null) return null
    return project.globalSources.firstOrNull { it.id == id }
      ?: project.scenes.asSequence().flatMap { it.sources.asSequence() }.firstOrNull { it.id == id }
  }

  fun checkpoint() {
    undoStack.addLast(project.toJson().toString())
    while (undoStack.size > 40) undoStack.removeFirst()
    redoStack.clear()
    if (System.currentTimeMillis() - lastBackupAt > 30_000L) saveBackupSnapshot()
  }

  fun autosave() {
    prefs.edit().putString("autosave", project.toJson().toString()).apply()
  }

  fun mutate(block: (StudioProject) -> Unit) {
    checkpoint(); block(project); autosave()
  }

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
    val scene = StudioScene(name = name.ifBlank { "シーン ${project.scenes.size + 1}" })
    scene.sources += StudioSource(
      name = "画面キャプチャ",
      type = StudioSourceType.SCREEN,
      locked = true,
      transform = StudioTransform(0f, 0f, 100f, 100f)
    )
    project.scenes += scene
    selectedSceneId = scene.id
    selectedSourceId = null
    autosave()
    return scene
  }

  fun duplicateScene(id: String): StudioScene? {
    val source = project.scenes.firstOrNull { it.id == id } ?: return null
    checkpoint()
    val copy = source.deepCopy(true).also { it.name = source.name + " Copy" }
    project.scenes += copy
    selectedSceneId = copy.id
    selectedSourceId = null
    autosave()
    return copy
  }

  fun removeScene(id: String): Boolean {
    if (project.scenes.size <= 1) return false
    checkpoint()
    project.scenes.removeAll { it.id == id }
    if (project.programSceneId == id) project.programSceneId = project.scenes.first().id
    if (project.previewSceneId == id) project.previewSceneId = project.scenes.first().id
    normalize(); autosave(); return true
  }

  fun addSource(
    type: StudioSourceType,
    name: String,
    data: String = "",
    global: Boolean = false
  ): StudioSource {
    checkpoint()
    val source = StudioSource(name = name.ifBlank { type.name }, type = type, data = data, global = global)
    source.transform = when (type) {
      StudioSourceType.SCREEN -> StudioTransform(0f, 0f, 100f, 100f)
      StudioSourceType.TEXT, StudioSourceType.CLOCK, StudioSourceType.TIMER, StudioSourceType.CHAT -> StudioTransform(8f, 8f, 42f, 16f)
      StudioSourceType.CAMERA, StudioSourceType.EXTERNAL -> StudioTransform(68f, 5f, 28f, 28f)
      StudioSourceType.IMAGE -> StudioTransform(8f, 58f, 28f, 28f)
      else -> StudioTransform(15f, 15f, 70f, 55f)
    }
    if (global) project.globalSources += source else scene()?.sources?.add(source)
    selectedSourceId = source.id
    autosave()
    return source
  }

  fun duplicateSource(id: String): StudioSource? {
    val source = source(id) ?: return null
    checkpoint()
    val copy = source.deepCopy(true).also {
      it.name += " のコピー"
      it.transform.x = (it.transform.x + 3f).coerceAtMost(92f)
      it.transform.y = (it.transform.y + 3f).coerceAtMost(92f)
    }
    if (source.global) project.globalSources += copy
    else project.scenes.firstOrNull { scene -> scene.sources.any { it.id == id } }?.sources?.add(copy)
    selectedSourceId = copy.id
    autosave()
    return copy
  }

  fun removeSource(id: String): Boolean {
    val source = source(id) ?: return false
    if (source.type == StudioSourceType.SCREEN) return false
    checkpoint()
    project.globalSources.removeAll { it.id == id }
    project.scenes.forEach { scene -> scene.sources.removeAll { it.id == id } }
    if (selectedSourceId == id) selectedSourceId = null
    autosave()
    return true
  }

  fun moveSource(id: String, delta: Int): Boolean {
    val list = project.scenes.firstOrNull { scene -> scene.sources.any { it.id == id } }?.sources
      ?: project.globalSources.takeIf { global -> global.any { it.id == id } }
      ?: return false
    val current = list.indexOfFirst { it.id == id }
    if (current < 0) return false
    val next = (current + delta).coerceIn(0, list.lastIndex)
    if (current == next) return false
    checkpoint()
    val item = list.removeAt(current)
    list.add(next, item)
    autosave()
    return true
  }

  fun toggleGlobal(id: String): Boolean {
    val source = source(id) ?: return false
    if (source.type == StudioSourceType.SCREEN) return false
    checkpoint()
    if (source.global) {
      project.globalSources.removeAll { it.id == id }
      source.global = false
      scene()?.sources?.add(source)
    } else {
      project.scenes.forEach { scene -> scene.sources.removeAll { it.id == id } }
      source.global = true
      project.globalSources += source
    }
    autosave()
    return true
  }

  fun group(ids: Set<String>): String? {
    if (ids.size < 2) return null
    checkpoint()
    val group = UUID.randomUUID().toString()
    ids.forEach { source(it)?.groupId = group }
    autosave()
    return group
  }

  fun savePreset(name: String, source: StudioSource) {
    val root = JSONObject(prefs.getString("presets", "{}") ?: "{}")
    root.put(name, source.toJson())
    prefs.edit().putString("presets", root.toString()).apply()
  }

  fun presetNames(): List<String> =
    JSONObject(prefs.getString("presets", "{}") ?: "{}").keys().asSequence().toList().sorted()

  fun loadPreset(name: String): StudioSource? =
    JSONObject(prefs.getString("presets", "{}") ?: "{}")
      .optJSONObject(name)
      ?.let { StudioSource.fromJson(it).deepCopy(true) }

  fun exportJson(): String = project.toJson().toString(2)

  fun importJson(text: String) {
    checkpoint()
    project = StudioProject.fromJson(JSONObject(text))
    normalize(); autosave()
  }

  fun reset() {
    checkpoint()
    project = StudioProject.defaultProject()
    normalize(); autosave()
  }

  fun backupLabels(): List<String> {
    val format = SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault())
    return (0 until 10).mapNotNull { index ->
      val time = prefs.getLong("backup_time_$index", 0L)
      if (time <= 0L || prefs.getString("backup_$index", null) == null) null
      else "${index + 1}. ${format.format(Date(time))}"
    }
  }

  fun restoreBackup(index: Int): Boolean {
    val raw = prefs.getString("backup_$index", null) ?: return false
    return runCatching {
      checkpoint()
      project = StudioProject.fromJson(JSONObject(raw))
      normalize(); autosave()
      true
    }.getOrDefault(false)
  }

  fun forceBackup() = saveBackupSnapshot()

  private fun saveBackupSnapshot() {
    val now = System.currentTimeMillis()
    val editor = prefs.edit()
    for (index in 9 downTo 1) {
      val previous = prefs.getString("backup_${index - 1}", null)
      val previousTime = prefs.getLong("backup_time_${index - 1}", 0L)
      if (previous != null) editor.putString("backup_$index", previous)
      if (previousTime > 0L) editor.putLong("backup_time_$index", previousTime)
    }
    editor.putString("backup_0", project.toJson().toString())
    editor.putLong("backup_time_0", now)
    editor.putLong("lastBackupAt", now)
    editor.apply()
    lastBackupAt = now
  }

  private fun load(): StudioProject = runCatching {
    prefs.getString("autosave", null)?.let { StudioProject.fromJson(JSONObject(it)) }
  }.getOrNull() ?: StudioProject.defaultProject()

  private fun normalize() {
    if (project.scenes.none { it.id == selectedSceneId }) {
      selectedSceneId = project.programSceneId.takeIf { id -> project.scenes.any { it.id == id } }
        ?: project.scenes.first().id
    }
    if (selectedSourceId != null && source(selectedSourceId) == null) selectedSourceId = null
  }
}
