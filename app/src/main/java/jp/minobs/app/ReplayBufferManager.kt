package jp.minobs.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rolling MP4 replay buffer using short finalized segments. A saved replay is a group of sequential
 * MP4 clips so even a crash normally loses at most the current segment.
 */
class ReplayBufferManager(
    private val context: Context,
    private val startRecord: (File) -> Boolean,
    private val stopRecord: () -> Boolean,
    private val canRecord: () -> Boolean
) {
    private val handler = Handler(Looper.getMainLooper())
    private val finalized = ArrayDeque<File>()
    private var current: File? = null
    private var segmentActive = false
    var enabled = false; private set
    var windowSeconds = 30
    var segmentSeconds = 10

    fun start(windowSeconds: Int = this.windowSeconds): Boolean {
        this.windowSeconds = windowSeconds.coerceIn(10, 120)
        if (enabled) return true
        enabled = true
        return beginSegment().also { if (it) scheduleRotate() else enabled = false }
    }

    fun stop(deleteBuffer: Boolean = true) {
        enabled = false
        handler.removeCallbacks(rotateRunnable)
        if (segmentActive) { runCatching { stopRecord() }; segmentActive = false; current?.let(::finalizeSegment) }
        current = null
        if (deleteBuffer) { finalized.forEach { runCatching { it.delete() } }; finalized.clear() }
    }

    /** Temporarily yield the muxer to normal recording. */
    fun pauseForNormalRecord(): Boolean {
        val wasEnabled = enabled
        if (wasEnabled) stop(deleteBuffer = false)
        return wasEnabled
    }

    fun resumeAfterNormalRecord() { if (!enabled) { enabled = true; if (beginSegment()) scheduleRotate() else enabled = false } }

    /** Finalize the current segment and return the clips covering the requested replay window. */
    fun snapshot(): List<File> {
        if (enabled && segmentActive) {
            handler.removeCallbacks(rotateRunnable)
            runCatching { stopRecord() }
            segmentActive = false
            current?.let(::finalizeSegment)
            current = null
            if (beginSegment()) scheduleRotate()
        }
        val count = ((windowSeconds + segmentSeconds - 1) / segmentSeconds).coerceAtLeast(1)
        return finalized.takeLast(count).filter { it.exists() && it.length() > 0 }
    }

    fun status(): String = if (enabled) "ON ${windowSeconds}s (${finalized.size} clips)" else "OFF"

    private fun beginSegment(): Boolean {
        if (!enabled || !canRecord()) return false
        val dir = File(context.cacheDir, "replay_buffer").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val file = File(dir, "replay_$stamp.mp4")
        val ok = runCatching { startRecord(file) }.getOrDefault(false)
        if (ok) { current = file; segmentActive = true }
        return ok
    }

    private fun scheduleRotate() { handler.postDelayed(rotateRunnable, segmentSeconds * 1000L) }

    private val rotateRunnable = object : Runnable {
        override fun run() {
            if (!enabled) return
            if (segmentActive) {
                runCatching { stopRecord() }; segmentActive = false; current?.let(::finalizeSegment); current = null
            }
            if (beginSegment()) scheduleRotate() else enabled = false
        }
    }

    private fun finalizeSegment(file: File) {
        if (file.exists() && file.length() > 0) finalized.addLast(file) else runCatching { file.delete() }
        val max = ((windowSeconds + segmentSeconds - 1) / segmentSeconds + 2).coerceAtLeast(3)
        while (finalized.size > max) runCatching { finalized.removeFirst().delete() }
    }
}
