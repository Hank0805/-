package jp.minobs.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionJournal(private val context: Context) {
    private val prefs = context.getSharedPreferences("miniobs_session", Context.MODE_PRIVATE)
    private var startedAt = 0L
    private var markerFile: File? = null

    fun beginRecording(path: String) {
        startedAt = System.currentTimeMillis()
        val dir = File(context.filesDir, "markers").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedAt))
        markerFile = File(dir, "markers_$stamp.csv").apply { writeText("elapsed_ms,wall_time,label\n") }
        prefs.edit().putBoolean("active", true).putString("path", path).putLong("startedAt", startedAt).putString("markers", markerFile?.absolutePath).apply()
    }

    fun addMarker(label: String = "MARK"): Long {
        if (startedAt == 0L) startedAt = prefs.getLong("startedAt", System.currentTimeMillis())
        val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        val f = markerFile ?: prefs.getString("markers", null)?.let(::File)
        runCatching { f?.appendText("$elapsed,${System.currentTimeMillis()},\"${label.replace("\"", "'")}\"\n") }
        return elapsed
    }

    fun finishRecording() {
        prefs.edit().putBoolean("active", false).apply()
        startedAt = 0L; markerFile = null
    }

    fun recoveryMessage(): String? {
        if (!prefs.getBoolean("active", false)) return null
        val path = prefs.getString("path", null)
        return if (path.isNullOrBlank()) "前回の録画セッションが異常終了しました。" else "前回の録画が異常終了した可能性があります: ${File(path).name}"
    }

    fun markRecovered() { prefs.edit().putBoolean("active", false).apply() }

    fun recoverableReplayClips(): List<File> {
        val dir = File(context.cacheDir, "replay_buffer")
        return dir.listFiles()?.filter { it.extension.equals("mp4", true) && it.length() > 0 }?.sortedBy { it.lastModified() } ?: emptyList()
    }
}
