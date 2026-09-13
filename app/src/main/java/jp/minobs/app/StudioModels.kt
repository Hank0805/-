package jp.minobs.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class StudioSourceType { SCREEN, TEXT, IMAGE, CAMERA, USB_CAPTURE, BROWSER, MEDIA, SLIDESHOW, CLOCK, TIMER, EXTERNAL, CHAT }
enum class StudioTransition { CUT, FADE, SLIDE, ZOOM, WIPE, STINGER }
enum class StudioPerformanceMode { QUALITY, BALANCED, BATTERY }

data class StudioTransform(
  var x: Float = 8f,
  var y: Float = 8f,
  var width: Float = 28f,
  var height: Float = 28f,
  var rotation: Int = 0,
  var alpha: Float = 1f,
  var cropLeft: Float = 0f,
  var cropTop: Float = 0f,
  var cropRight: Float = 0f,
  var cropBottom: Float = 0f
) {
  fun copyValue() = copy()
  fun toJson() = JSONObject().apply {
    put("x", x); put("y", y); put("width", width); put("height", height)
    put("rotation", rotation); put("alpha", alpha)
    put("cropLeft", cropLeft); put("cropTop", cropTop); put("cropRight", cropRight); put("cropBottom", cropBottom)
  }
  companion object {
    fun fromJson(o: JSONObject) = StudioTransform(
      x = o.optDouble("x", 8.0).toFloat(),
      y = o.optDouble("y", 8.0).toFloat(),
      width = o.optDouble("width", 28.0).toFloat(),
      height = o.optDouble("height", 28.0).toFloat(),
      rotation = o.optInt("rotation", 0),
      alpha = o.optDouble("alpha", 1.0).toFloat(),
      cropLeft = o.optDouble("cropLeft", 0.0).toFloat(),
      cropTop = o.optDouble("cropTop", 0.0).toFloat(),
      cropRight = o.optDouble("cropRight", 0.0).toFloat(),
      cropBottom = o.optDouble("cropBottom", 0.0).toFloat()
    )
  }
}

data class StudioFilter(
  var name: String,
  var enabled: Boolean = true,
  var amount: Float = 1f,
  var extra: String = ""
) {
  fun toJson() = JSONObject()
    .put("name", name).put("enabled", enabled).put("amount", amount).put("extra", extra)
  companion object {
    fun fromJson(o: JSONObject) = StudioFilter(
      o.optString("name"), o.optBoolean("enabled", true),
      o.optDouble("amount", 1.0).toFloat(), o.optString("extra")
    )
  }
}

data class StudioSource(
  val id: String = UUID.randomUUID().toString(),
  var name: String,
  var type: StudioSourceType,
  var visible: Boolean = true,
  var locked: Boolean = false,
  var global: Boolean = false,
  var transform: StudioTransform = StudioTransform(),
  var data: String = "",
  var filters: MutableList<StudioFilter> = mutableListOf(),
  var audioMonitor: Boolean = false,
  var streamAudio: Boolean = true,
  var recordAudio: Boolean = true,
  var audioDelayMs: Int = 0,
  var audioTrack: Int = 1,
  var groupId: String? = null,
  var chromaEnabled: Boolean = false,
  var chromaColor: Int = 0xFF00FF00.toInt(),
  var chromaSimilarity: Float = .28f,
  var chromaSmoothness: Float = .10f
) {
  fun deepCopy(newId: Boolean = false): StudioSource = StudioSource(
    id = if (newId) UUID.randomUUID().toString() else id,
    name = name,
    type = type,
    visible = visible,
    locked = locked,
    global = global,
    transform = transform.copyValue(),
    data = data,
    filters = filters.map { it.copy() }.toMutableList(),
    audioMonitor = audioMonitor,
    streamAudio = streamAudio,
    recordAudio = recordAudio,
    audioDelayMs = audioDelayMs,
    audioTrack = audioTrack,
    groupId = groupId,
    chromaEnabled = chromaEnabled,
    chromaColor = chromaColor,
    chromaSimilarity = chromaSimilarity,
    chromaSmoothness = chromaSmoothness
  )

  fun toJson() = JSONObject().apply {
    put("id", id); put("name", name); put("type", type.name)
    put("visible", visible); put("locked", locked); put("global", global)
    put("transform", transform.toJson()); put("data", data)
    put("audioMonitor", audioMonitor); put("streamAudio", streamAudio); put("recordAudio", recordAudio)
    put("audioDelayMs", audioDelayMs); put("audioTrack", audioTrack); put("groupId", groupId)
    put("chromaEnabled", chromaEnabled); put("chromaColor", chromaColor)
    put("chromaSimilarity", chromaSimilarity); put("chromaSmoothness", chromaSmoothness)
    put("filters", JSONArray().also { a -> filters.forEach { a.put(it.toJson()) } })
  }

  companion object {
    fun fromJson(o: JSONObject): StudioSource {
      val filters = mutableListOf<StudioFilter>()
      val a = o.optJSONArray("filters") ?: JSONArray()
      for (i in 0 until a.length()) filters += StudioFilter.fromJson(a.getJSONObject(i))
      return StudioSource(
        id = o.optString("id", UUID.randomUUID().toString()),
        name = o.optString("name", "ソース"),
        type = runCatching { StudioSourceType.valueOf(o.optString("type", "TEXT")) }.getOrDefault(StudioSourceType.TEXT),
        visible = o.optBoolean("visible", true),
        locked = o.optBoolean("locked", false),
        global = o.optBoolean("global", false),
        transform = StudioTransform.fromJson(o.optJSONObject("transform") ?: JSONObject()),
        data = o.optString("data"),
        filters = filters,
        audioMonitor = o.optBoolean("audioMonitor", false),
        streamAudio = o.optBoolean("streamAudio", true),
        recordAudio = o.optBoolean("recordAudio", true),
        audioDelayMs = o.optInt("audioDelayMs", 0),
        audioTrack = o.optInt("audioTrack", 1),
        groupId = o.optString("groupId").takeIf { it.isNotBlank() },
        chromaEnabled = o.optBoolean("chromaEnabled", false),
        chromaColor = o.optInt("chromaColor", 0xFF00FF00.toInt()),
        chromaSimilarity = o.optDouble("chromaSimilarity", .28).toFloat(),
        chromaSmoothness = o.optDouble("chromaSmoothness", .10).toFloat()
      )
    }
  }
}

data class StudioScene(
  val id: String = UUID.randomUUID().toString(),
  var name: String,
  var sources: MutableList<StudioSource> = mutableListOf(),
  var transitionOverride: StudioTransition? = null,
  var transitionMsOverride: Int? = null
) {
  fun deepCopy(newId: Boolean = false) = StudioScene(
    id = if (newId) UUID.randomUUID().toString() else id,
    name = name,
    sources = sources.map { it.deepCopy(newId) }.toMutableList(),
    transitionOverride = transitionOverride,
    transitionMsOverride = transitionMsOverride
  )
  fun toJson() = JSONObject().apply {
    put("id", id); put("name", name)
    put("sources", JSONArray().also { a -> sources.forEach { a.put(it.toJson()) } })
    transitionOverride?.let { put("transitionOverride", it.name) }
    transitionMsOverride?.let { put("transitionMsOverride", it) }
  }
  companion object {
    fun fromJson(o: JSONObject): StudioScene {
      val list = mutableListOf<StudioSource>()
      val a = o.optJSONArray("sources") ?: JSONArray()
      for (i in 0 until a.length()) list += StudioSource.fromJson(a.getJSONObject(i))
      val override = o.optString("transitionOverride").takeIf { it.isNotBlank() }
        ?.let { runCatching { StudioTransition.valueOf(it) }.getOrNull() }
      val duration = if (o.has("transitionMsOverride")) o.optInt("transitionMsOverride") else null
      return StudioScene(
        id = o.optString("id", UUID.randomUUID().toString()),
        name = o.optString("name", "シーン"),
        sources = list,
        transitionOverride = override,
        transitionMsOverride = duration
      )
    }
  }
}

data class StudioMacro(
  val id: String = UUID.randomUUID().toString(),
  var name: String,
  var trigger: String,
  var action: String,
  var enabled: Boolean = true,
  var delayMs: Long = 0L
) {
  fun toJson() = JSONObject().put("id", id).put("name", name).put("trigger", trigger)
    .put("action", action).put("enabled", enabled).put("delayMs", delayMs)
  companion object {
    fun fromJson(o: JSONObject) = StudioMacro(
      o.optString("id", UUID.randomUUID().toString()), o.optString("name"),
      o.optString("trigger"), o.optString("action"), o.optBoolean("enabled", true), o.optLong("delayMs", 0)
    )
  }
}

data class StudioProject(
  var name: String = "Mini OBS プロジェクト",
  var canvasWidth: Int = 1920,
  var canvasHeight: Int = 1080,
  var outputWidth: Int = 1920,
  var outputHeight: Int = 1080,
  var fps: Int = 60,
  var bitrate: Int = 8_000_000,
  var transition: StudioTransition = StudioTransition.FADE,
  var transitionMs: Int = 300,
  var studioMode: Boolean = false,
  var safeArea: Boolean = true,
  var performanceMode: StudioPerformanceMode = StudioPerformanceMode.BALANCED,
  var autoSplitMinutes: Int = 30,
  var backupRecord: Boolean = false,
  var scenes: MutableList<StudioScene> = mutableListOf(),
  var globalSources: MutableList<StudioSource> = mutableListOf(),
  var macros: MutableList<StudioMacro> = mutableListOf(),
  var programSceneId: String = "",
  var previewSceneId: String = ""
) {
  fun deepCopy(): StudioProject = fromJson(toJson())
  fun toJson() = JSONObject().apply {
    put("name", name); put("canvasWidth", canvasWidth); put("canvasHeight", canvasHeight)
    put("outputWidth", outputWidth); put("outputHeight", outputHeight)
    put("fps", fps); put("bitrate", bitrate); put("transition", transition.name); put("transitionMs", transitionMs)
    put("studioMode", studioMode); put("safeArea", safeArea); put("performanceMode", performanceMode.name)
    put("autoSplitMinutes", autoSplitMinutes); put("backupRecord", backupRecord)
    put("programSceneId", programSceneId); put("previewSceneId", previewSceneId)
    put("scenes", JSONArray().also { a -> scenes.forEach { a.put(it.toJson()) } })
    put("globalSources", JSONArray().also { a -> globalSources.forEach { a.put(it.toJson()) } })
    put("macros", JSONArray().also { a -> macros.forEach { a.put(it.toJson()) } })
  }

  companion object {
    fun defaultProject(): StudioProject {
      val game = StudioScene(
        name = "ゲーム",
        sources = mutableListOf(
          StudioSource(
            name = "画面キャプチャ", type = StudioSourceType.SCREEN, locked = true,
            transform = StudioTransform(0f, 0f, 100f, 100f)
          )
        )
      )
      val talk = StudioScene(
        name = "雑談",
        sources = mutableListOf(
          StudioSource(
            name = "画面キャプチャ", type = StudioSourceType.SCREEN, locked = true,
            transform = StudioTransform(0f, 0f, 100f, 100f)
          )
        )
      )
      val wait = StudioScene(
        name = "待機",
        sources = mutableListOf(
          StudioSource(
            name = "まもなく開始", type = StudioSourceType.TEXT, data = "まもなく開始",
            transform = StudioTransform(25f, 42f, 50f, 16f)
          )
        )
      )
      return StudioProject(
        scenes = mutableListOf(game, talk, wait),
        programSceneId = game.id,
        previewSceneId = talk.id
      )
    }

    fun fromJson(o: JSONObject): StudioProject {
      val scenes = mutableListOf<StudioScene>()
      val sa = o.optJSONArray("scenes") ?: JSONArray()
      for (i in 0 until sa.length()) scenes += StudioScene.fromJson(sa.getJSONObject(i))
      val globals = mutableListOf<StudioSource>()
      val ga = o.optJSONArray("globalSources") ?: JSONArray()
      for (i in 0 until ga.length()) globals += StudioSource.fromJson(ga.getJSONObject(i))
      val macros = mutableListOf<StudioMacro>()
      val ma = o.optJSONArray("macros") ?: JSONArray()
      for (i in 0 until ma.length()) macros += StudioMacro.fromJson(ma.getJSONObject(i))
      return StudioProject(
        name = o.optString("name", "Mini OBS プロジェクト"),
        canvasWidth = o.optInt("canvasWidth", 1920),
        canvasHeight = o.optInt("canvasHeight", 1080),
        outputWidth = o.optInt("outputWidth", 1920),
        outputHeight = o.optInt("outputHeight", 1080),
        fps = o.optInt("fps", 60),
        bitrate = o.optInt("bitrate", 8_000_000),
        transition = runCatching { StudioTransition.valueOf(o.optString("transition", "FADE")) }.getOrDefault(StudioTransition.FADE),
        transitionMs = o.optInt("transitionMs", 300),
        studioMode = o.optBoolean("studioMode", false),
        safeArea = o.optBoolean("safeArea", true),
        performanceMode = runCatching { StudioPerformanceMode.valueOf(o.optString("performanceMode", "BALANCED")) }.getOrDefault(StudioPerformanceMode.BALANCED),
        autoSplitMinutes = o.optInt("autoSplitMinutes", 30),
        backupRecord = o.optBoolean("backupRecord", false),
        scenes = scenes.ifEmpty { defaultProject().scenes },
        globalSources = globals,
        macros = macros,
        programSceneId = o.optString("programSceneId"),
        previewSceneId = o.optString("previewSceneId")
      ).also { p ->
        if (p.programSceneId.isBlank()) p.programSceneId = p.scenes.first().id
        if (p.previewSceneId.isBlank()) p.previewSceneId = p.scenes.getOrElse(1) { p.scenes.first() }.id
      }
    }
  }
}
