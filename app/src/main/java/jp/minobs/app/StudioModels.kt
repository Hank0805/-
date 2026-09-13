package jp.minobs.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class StudioSourceType { SCREEN, TEXT, IMAGE, CAMERA, BROWSER, MEDIA, SLIDESHOW, CLOCK, TIMER, EXTERNAL, CHAT }
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
            o.optDouble("x", 8.0).toFloat(), o.optDouble("y", 8.0).toFloat(),
            o.optDouble("width", 28.0).toFloat(), o.optDouble("height", 28.0).toFloat(),
            o.optInt("rotation", 0), o.optDouble("alpha", 1.0).toFloat(),
            o.optDouble("cropLeft", 0.0).toFloat(), o.optDouble("cropTop", 0.0).toFloat(),
            o.optDouble("cropRight", 0.0).toFloat(), o.optDouble("cropBottom", 0.0).toFloat()
        )
    }
}

data class StudioFilter(
    var name: String,
    var enabled: Boolean = true,
    var amount: Float = 1f,
    var extra: String = ""
) {
    fun toJson() = JSONObject().put("name", name).put("enabled", enabled).put("amount", amount).put("extra", extra)
    companion object { fun fromJson(o: JSONObject) = StudioFilter(o.optString("name"), o.optBoolean("enabled", true), o.optDouble("amount", 1.0).toFloat(), o.optString("extra")) }
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
    var groupId: String? = null
) {
    fun deepCopy(newId: Boolean = false): StudioSource = StudioSource(
        if (newId) UUID.randomUUID().toString() else id, name, type, visible, locked, global,
        transform.copyValue(), data, filters.map { it.copy() }.toMutableList(), audioMonitor,
        streamAudio, recordAudio, audioDelayMs, audioTrack, groupId
    )
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("type", type.name); put("visible", visible); put("locked", locked); put("global", global)
        put("transform", transform.toJson()); put("data", data); put("audioMonitor", audioMonitor); put("streamAudio", streamAudio)
        put("recordAudio", recordAudio); put("audioDelayMs", audioDelayMs); put("audioTrack", audioTrack); put("groupId", groupId)
        put("filters", JSONArray().also { a -> filters.forEach { a.put(it.toJson()) } })
    }
    companion object {
        fun fromJson(o: JSONObject): StudioSource {
            val filters = mutableListOf<StudioFilter>(); val a = o.optJSONArray("filters") ?: JSONArray()
            for (i in 0 until a.length()) filters += StudioFilter.fromJson(a.getJSONObject(i))
            return StudioSource(
                o.optString("id", UUID.randomUUID().toString()), o.optString("name", "ソース"),
                runCatching { StudioSourceType.valueOf(o.optString("type", "TEXT")) }.getOrDefault(StudioSourceType.TEXT),
                o.optBoolean("visible", true), o.optBoolean("locked", false), o.optBoolean("global", false),
                StudioTransform.fromJson(o.optJSONObject("transform") ?: JSONObject()), o.optString("data"), filters,
                o.optBoolean("audioMonitor", false), o.optBoolean("streamAudio", true), o.optBoolean("recordAudio", true),
                o.optInt("audioDelayMs", 0), o.optInt("audioTrack", 1), o.optString("groupId").takeIf { it.isNotBlank() }
            )
        }
    }
}

data class StudioScene(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var sources: MutableList<StudioSource> = mutableListOf()
) {
    fun deepCopy(newId: Boolean = false) = StudioScene(if (newId) UUID.randomUUID().toString() else id, name, sources.map { it.deepCopy(newId) }.toMutableList())
    fun toJson() = JSONObject().put("id", id).put("name", name).put("sources", JSONArray().also { a -> sources.forEach { a.put(it.toJson()) } })
    companion object {
        fun fromJson(o: JSONObject): StudioScene {
            val list = mutableListOf<StudioSource>(); val a = o.optJSONArray("sources") ?: JSONArray()
            for (i in 0 until a.length()) list += StudioSource.fromJson(a.getJSONObject(i))
            return StudioScene(o.optString("id", UUID.randomUUID().toString()), o.optString("name", "シーン"), list)
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
    fun toJson() = JSONObject().put("id", id).put("name", name).put("trigger", trigger).put("action", action).put("enabled", enabled).put("delayMs", delayMs)
    companion object { fun fromJson(o: JSONObject) = StudioMacro(o.optString("id", UUID.randomUUID().toString()), o.optString("name"), o.optString("trigger"), o.optString("action"), o.optBoolean("enabled", true), o.optLong("delayMs", 0)) }
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
    var autoSplitMinutes: Int = 60,
    var backupRecord: Boolean = false,
    var scenes: MutableList<StudioScene> = mutableListOf(),
    var globalSources: MutableList<StudioSource> = mutableListOf(),
    var macros: MutableList<StudioMacro> = mutableListOf(),
    var programSceneId: String = "",
    var previewSceneId: String = ""
) {
    fun deepCopy(): StudioProject = fromJson(toJson())
    fun toJson() = JSONObject().apply {
        put("name", name); put("canvasWidth", canvasWidth); put("canvasHeight", canvasHeight); put("outputWidth", outputWidth); put("outputHeight", outputHeight)
        put("fps", fps); put("bitrate", bitrate); put("transition", transition.name); put("transitionMs", transitionMs); put("studioMode", studioMode)
        put("safeArea", safeArea); put("performanceMode", performanceMode.name); put("autoSplitMinutes", autoSplitMinutes); put("backupRecord", backupRecord)
        put("programSceneId", programSceneId); put("previewSceneId", previewSceneId)
        put("scenes", JSONArray().also { a -> scenes.forEach { a.put(it.toJson()) } })
        put("globalSources", JSONArray().also { a -> globalSources.forEach { a.put(it.toJson()) } })
        put("macros", JSONArray().also { a -> macros.forEach { a.put(it.toJson()) } })
    }
    companion object {
        fun defaultProject(): StudioProject {
            val game = StudioScene(name = "ゲーム", sources = mutableListOf(StudioSource(name = "画面キャプチャ", type = StudioSourceType.SCREEN, locked = true, transform = StudioTransform(0f, 0f, 100f, 100f))))
            val talk = StudioScene(name = "雑談", sources = mutableListOf(StudioSource(name = "画面キャプチャ", type = StudioSourceType.SCREEN, locked = true, transform = StudioTransform(0f, 0f, 100f, 100f))))
            val wait = StudioScene(name = "待機", sources = mutableListOf(StudioSource(name = "まもなく開始", type = StudioSourceType.TEXT, data = "まもなく開始", transform = StudioTransform(25f, 42f, 50f, 16f))))
            return StudioProject(scenes = mutableListOf(game, talk, wait), programSceneId = game.id, previewSceneId = talk.id)
        }
        fun fromJson(o: JSONObject): StudioProject {
            val scenes = mutableListOf<StudioScene>(); val sa = o.optJSONArray("scenes") ?: JSONArray(); for (i in 0 until sa.length()) scenes += StudioScene.fromJson(sa.getJSONObject(i))
            val globals = mutableListOf<StudioSource>(); val ga = o.optJSONArray("globalSources") ?: JSONArray(); for (i in 0 until ga.length()) globals += StudioSource.fromJson(ga.getJSONObject(i))
            val macros = mutableListOf<StudioMacro>(); val ma = o.optJSONArray("macros") ?: JSONArray(); for (i in 0 until ma.length()) macros += StudioMacro.fromJson(ma.getJSONObject(i))
            return StudioProject(
                o.optString("name", "Mini OBS プロジェクト"), o.optInt("canvasWidth", 1920), o.optInt("canvasHeight", 1080), o.optInt("outputWidth", 1920), o.optInt("outputHeight", 1080),
                o.optInt("fps", 60), o.optInt("bitrate", 8_000_000), runCatching { StudioTransition.valueOf(o.optString("transition", "FADE")) }.getOrDefault(StudioTransition.FADE),
                o.optInt("transitionMs", 300), o.optBoolean("studioMode", false), o.optBoolean("safeArea", true), runCatching { StudioPerformanceMode.valueOf(o.optString("performanceMode", "BALANCED")) }.getOrDefault(StudioPerformanceMode.BALANCED),
                o.optInt("autoSplitMinutes", 60), o.optBoolean("backupRecord", false), scenes.ifEmpty { defaultProject().scenes }, globals, macros,
                o.optString("programSceneId"), o.optString("previewSceneId")
            ).also { p -> if (p.programSceneId.isBlank()) p.programSceneId = p.scenes.first().id; if (p.previewSceneId.isBlank()) p.previewSceneId = p.scenes.getOrElse(1) { p.scenes.first() }.id }
        }
    }
}
