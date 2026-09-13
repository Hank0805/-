package jp.minobs.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local-only profiles. Stream keys/URLs never leave the device through this class. */
class ProfileStore(context: Context) {
    data class Profile(
        val name: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrate: Int,
        val audioMode: String,
        val endpoints: List<String>,
        val vertical: Boolean = false,
        val replaySeconds: Int = 30,
        val audioPreset: String = "game"
    )

    private val prefs = context.getSharedPreferences("miniobs_profiles", Context.MODE_PRIVATE)

    fun builtIns(): List<Profile> = listOf(
        Profile("ゲーム配信 1080p60", 1920, 1080, 60, 8_000_000, "MIX", emptyList(), false, 30, "game"),
        Profile("安定配信 720p30", 1280, 720, 30, 4_000_000, "MIX", emptyList(), false, 30, "broadcast"),
        Profile("高画質録画", 1920, 1080, 60, 12_000_000, "MIX", emptyList(), false, 60, "broadcast"),
        Profile("縦配信", 1080, 1920, 60, 7_000_000, "MIX", emptyList(), true, 30, "voice")
    )

    fun all(): List<Profile> = builtIns() + loadCustom()

    fun save(profile: Profile) {
        val list = loadCustom().filterNot { it.name == profile.name }.toMutableList()
        list += profile
        val array = JSONArray()
        list.forEach { array.put(toJson(it)) }
        prefs.edit().putString("profiles", array.toString()).apply()
    }

    fun delete(name: String) {
        val list = loadCustom().filterNot { it.name == name }
        val array = JSONArray(); list.forEach { array.put(toJson(it)) }
        prefs.edit().putString("profiles", array.toString()).apply()
    }

    private fun loadCustom(): List<Profile> = runCatching {
        val array = JSONArray(prefs.getString("profiles", "[]"))
        buildList {
            for (i in 0 until array.length()) add(fromJson(array.getJSONObject(i)))
        }
    }.getOrDefault(emptyList())

    private fun toJson(p: Profile) = JSONObject().apply {
        put("name", p.name); put("width", p.width); put("height", p.height); put("fps", p.fps); put("bitrate", p.bitrate)
        put("audioMode", p.audioMode); put("vertical", p.vertical); put("replaySeconds", p.replaySeconds); put("audioPreset", p.audioPreset)
        put("endpoints", JSONArray(p.endpoints))
    }

    private fun fromJson(o: JSONObject): Profile {
        val endpoints = buildList {
            val a = o.optJSONArray("endpoints") ?: JSONArray()
            for (i in 0 until a.length()) add(a.optString(i))
        }
        return Profile(
            o.optString("name", "Custom"), o.optInt("width", 1280), o.optInt("height", 720), o.optInt("fps", 30),
            o.optInt("bitrate", 5_000_000), o.optString("audioMode", "MIX"), endpoints,
            o.optBoolean("vertical", false), o.optInt("replaySeconds", 30), o.optString("audioPreset", "game")
        )
    }
}
