package jp.minobs.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import org.json.JSONObject
import java.util.Locale

class HealthMonitor(private val context: Context) {
    private var lastCpuMs = Process.getElapsedCpuTime()
    private var lastWallMs = android.os.SystemClock.elapsedRealtime()

    fun snapshot(extra: Map<String, Any?> = emptyMap()): JSONObject {
        val nowCpu = Process.getElapsedCpuTime()
        val nowWall = android.os.SystemClock.elapsedRealtime()
        val dtWall = (nowWall - lastWallMs).coerceAtLeast(1)
        val cpuPercent = ((nowCpu - lastCpuMs).toDouble() / dtWall * 100.0).coerceIn(0.0, 400.0)
        lastCpuMs = nowCpu; lastWallMs = nowWall

        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPct = if (level >= 0) level * 100 / scale.coerceAtLeast(1) else -1
        val tempTenth = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val temp = tempTenth / 10f

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        val stat = StatFs(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.absolutePath ?: context.filesDir.absolutePath)
        val freeStorage = stat.availableBytes

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val transport = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Other"
        }
        val downKbps = caps?.linkDownstreamBandwidthKbps ?: 0
        val upKbps = caps?.linkUpstreamBandwidthKbps ?: 0
        val thermal = if (Build.VERSION.SDK_INT >= 29) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.currentThermalStatus
        } else -1

        return JSONObject().apply {
            put("cpu", String.format(Locale.US, "%.1f", cpuPercent).toDouble())
            put("battery", batteryPct); put("temperature", temp); put("thermal", thermal)
            put("memoryFreeMb", mem.availMem / 1024 / 1024); put("storageFreeMb", freeStorage / 1024 / 1024)
            put("network", transport); put("downKbps", downKbps); put("upKbps", upKbps)
            extra.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) }
        }
    }
}
