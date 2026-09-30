package com.zohaib.batterymonitor.core

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.zohaib.batterymonitor.data.PKG_SCREEN_OFF
import com.zohaib.batterymonitor.data.PKG_SCREEN_ON_OTHER
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

data class AppTime(val pkg: String, val ms: Long, val lastUsed: Long)

data class InstalledApp(val pkg: String, val label: String, val lastUsed: Long, val system: Boolean = false)

object UsageHelper {

    private const val SCREEN_INTERACTIVE = 15
    private const val SCREEN_NON_INTERACTIVE = 16

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Foreground time per app inside [start, end]. Looks back one hour so the app already
     * open at [start] is counted.
     */
    fun foregroundTimes(context: Context, start: Long, end: Long): Map<String, Long> {
        if (!hasUsageAccess(context) || end <= start) return emptyMap()
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(start - 3_600_000, end)
        val out = HashMap<String, Long>()
        var cur: String? = null
        var curStart = 0L
        fun add(pkg: String, from: Long, to: Long) {
            val a = maxOf(from, start)
            val b = minOf(to, end)
            if (b > a) out[pkg] = (out[pkg] ?: 0L) + (b - a)
        }
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val t = e.timeStamp
            when (e.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    cur?.let { add(it, curStart, t) }
                    cur = e.packageName
                    curStart = t
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> if (e.packageName == cur) {
                    add(cur!!, curStart, t)
                    cur = null
                }
                SCREEN_NON_INTERACTIVE -> {
                    cur?.let { add(it, curStart, t) }
                    cur = null
                }
                SCREEN_INTERACTIVE -> Unit
            }
        }
        cur?.let { add(it, curStart, end) }
        return out
    }

    /**
     * Milliseconds the screen was on inside [start, end], from Android's screen on/off events.
     * Null when the phone reports no such events (older Android), so callers can fall back.
     */
    fun screenOnTime(context: Context, start: Long, end: Long): Long? {
        if (!hasUsageAccess(context) || end <= start) return null
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(start - 6 * 3_600_000L, end)
        val e = UsageEvents.Event()
        var on: Boolean? = null
        var since = start
        var total = 0L
        var seen = false
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val type = e.eventType
            if (type != SCREEN_INTERACTIVE && type != SCREEN_NON_INTERACTIVE) continue
            seen = true
            val t = e.timeStamp.coerceIn(start, end)
            // The first event tells us the state before it: turning off means it was on.
            if (on == null) on = type == SCREEN_NON_INTERACTIVE
            if (on == true) total += t - since
            on = type == SCREEN_INTERACTIVE
            since = t
        }
        if (!seen) return null
        if (on == true) total += end - since
        return total.coerceIn(0, end - start)
    }

    /** Foreground time per app since [since], most used first. */
    fun screenTime(context: Context, since: Long): List<AppTime> {
        if (!hasUsageAccess(context)) return emptyList()
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        return usm.queryAndAggregateUsageStats(since, System.currentTimeMillis()).values
            .filter { it.totalTimeInForeground > 0 && it.packageName != context.packageName }
            .map { AppTime(it.packageName, it.totalTimeInForeground, it.lastTimeUsed) }
            .sortedByDescending { it.ms }
    }

    private fun launchablePackages(context: Context): Set<String> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(i, 0).map { it.activityInfo.packageName }.toSet()
    }

    /** Launchable apps not opened in the last [days] days. */
    fun unusedApps(context: Context, days: Int = 7): List<InstalledApp> {
        if (!hasUsageAccess(context)) return emptyList()
        val since = System.currentTimeMillis() - days * 86_400_000L
        val used = screenTime(context, since).map { it.pkg }.toSet()
        return launchablePackages(context)
            .filter { it !in used && it != context.packageName }
            .map { InstalledApp(it, label(context, it), 0) }
            .sortedBy { it.label.lowercase() }
    }

    /** Core packages that must never be stopped, or the phone stops working properly. */
    private val PROTECTED = setOf(
        "android", "com.android.systemui", "com.android.phone", "com.android.settings",
        "com.android.providers.telephony", "com.android.bluetooth", "com.android.nfc",
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
        "com.google.android.inputmethod.latin", "com.android.shell",
    )

    private fun homeAndKeyboard(context: Context): Set<String> {
        val pm = context.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val out = pm.queryIntentActivities(home, 0).map { it.activityInfo.packageName }.toMutableSet()
        android.provider.Settings.Secure.getString(context.contentResolver, "default_input_method")
            ?.substringBefore('/')?.let { out += it }
        return out
    }

    /**
     * Apps that are not in the force-stopped state, i.e. apps that are running or can wake up in
     * the background. With [includeSystem], safe-to-stop system apps are listed too.
     * Most recently used first.
     */
    fun stoppableApps(context: Context, includeSystem: Boolean = false): List<InstalledApp> {
        val pm = context.packageManager
        val lastUsed = if (hasUsageAccess(context)) {
            screenTime(context, System.currentTimeMillis() - 7 * 86_400_000L).associate { it.pkg to it.lastUsed }
        } else emptyMap()
        val skip = PROTECTED + homeAndKeyboard(context) + context.packageName
        @Suppress("DEPRECATION")
        return pm.getInstalledApplications(0)
            .filter { it.flags and ApplicationInfo.FLAG_STOPPED == 0 }
            .filter { it.flags and ApplicationInfo.FLAG_PERSISTENT == 0 }
            .filter { it.packageName !in skip }
            .filter { includeSystem || it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .map {
                InstalledApp(
                    it.packageName, pm.getApplicationLabel(it).toString(), lastUsed[it.packageName] ?: 0,
                    it.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                )
            }
            .sortedWith(compareBy<InstalledApp> { it.system }.thenByDescending { it.lastUsed }.thenBy { it.label.lowercase() })
    }

    /** True when the app is force-stopped (or not installed), so it can't run until opened. */
    fun isStopped(context: Context, pkg: String): Boolean = try {
        context.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_STOPPED != 0
    } catch (_: PackageManager.NameNotFoundException) {
        true
    }

    private val labels = ConcurrentHashMap<String, String>()
    private val icons = ConcurrentHashMap<String, ImageBitmap>()

    fun label(context: Context, pkg: String): String {
        if (pkg == PKG_SCREEN_OFF) return "Screen off"
        if (pkg == PKG_SCREEN_ON_OTHER) return "Screen on · home / lock / shade"
        return labels.getOrPut(pkg) {
            try {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
                pkg
            }
        }
    }

    fun icon(context: Context, pkg: String): ImageBitmap? {
        if (pkg == PKG_SCREEN_OFF || pkg == PKG_SCREEN_ON_OTHER) return null
        icons[pkg]?.let { return it }
        return try {
            val bmp = context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
            icons[pkg] = bmp
            bmp
        } catch (_: Exception) {
            null
        }
    }

    fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
