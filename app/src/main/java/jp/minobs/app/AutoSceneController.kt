package jp.minobs.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process

class AutoSceneController(
    private val context: Context,
    private val onScene: (String) -> Unit,
    private val onSensitive: (Boolean, String?) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var lastPackage: String? = null
    private var sensitivePackages: Set<String> = emptySet()
    private val homePackage: String? by lazy {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.resolveActivity(i, 0)?.activityInfo?.packageName
    }

    fun setSensitivePackages(packages: Set<String>) { sensitivePackages = packages }

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        else @Suppress("DEPRECATION") appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun start() {
        if (running) return
        running = true
        handler.post(tick)
    }

    fun stop() { running = false; handler.removeCallbacks(tick); lastPackage = null }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val pkg = foregroundPackage()
            if (pkg != null && pkg != lastPackage) {
                lastPackage = pkg
                val sensitive = pkg in sensitivePackages
                onSensitive(sensitive, pkg)
                if (!sensitive) {
                    val scene = when (pkg) {
                        context.packageName -> "talk"
                        homePackage -> "wait"
                        else -> "game"
                    }
                    onScene(scene)
                }
            }
            handler.postDelayed(this, 1800)
        }
    }

    private fun foregroundPackage(): String? {
        if (!hasUsageAccess()) return null
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis(); val start = end - 7000
        val events = usm.queryEvents(start, end)
        val event = UsageEvents.Event(); var latest: String? = null; var latestTime = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val foreground = if (Build.VERSION.SDK_INT >= 29) event.eventType == UsageEvents.Event.ACTIVITY_RESUMED else event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            if (foreground && event.timeStamp >= latestTime) { latestTime = event.timeStamp; latest = event.packageName }
        }
        return latest
    }
}
