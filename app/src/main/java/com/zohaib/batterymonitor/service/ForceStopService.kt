package com.zohaib.batterymonitor.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.zohaib.batterymonitor.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Force-stops apps the user picked on the Cleaner screen. Android has no API for this, so
 * for each app it opens the App info page and taps "Force stop", then "OK".
 * It only acts while a user-started kill run is in progress.
 */
class ForceStopService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    enum class Result { STOPPED, ALREADY_STOPPED, FAILED }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun forceStop(
        pkgs: List<String>,
        onProgress: (done: Int, pkg: String) -> Unit,
        onDone: (Map<String, Result>) -> Unit,
    ) {
        scope.launch {
            val results = LinkedHashMap<String, Result>()
            pkgs.forEachIndexed { i, pkg ->
                onProgress(i, pkg)
                results[pkg] = stopOne(pkg)
            }
            startActivity(
                Intent(this@ForceStopService, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
            onDone(results)
        }
    }

    private suspend fun stopOne(pkg: String): Result {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
        )
        val button = waitFor(5_000) { root -> findStopButton(root) }
        if (button == null) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            return Result.FAILED
        }
        val target = clickable(button)
        if (target == null || !target.isEnabled || !button.isEnabled) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            return Result.ALREADY_STOPPED
        }
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        // Only confirm a dialog that is about stopping. Anything mentioning uninstall/disable is cancelled.
        var unsafe = false
        val ok = waitFor(3_000) { root ->
            val text = allText(root)
            when {
                UNSAFE_WORDS.any { text.contains(it) } -> { unsafe = true; root }
                text.contains("stop") -> confirmButton(root)
                else -> null
            }
        }
        val result = when {
            unsafe -> {
                rootInActiveWindow?.findAccessibilityNodeInfosByViewId("android:id/button2")?.firstOrNull()
                    ?.let { clickable(it)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                    ?: performGlobalAction(GLOBAL_ACTION_BACK)
                delay(300)
                Result.FAILED
            }
            ok != null && clickable(ok)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true -> Result.STOPPED
            else -> Result.FAILED
        }
        delay(400)
        performGlobalAction(GLOBAL_ACTION_BACK)
        delay(300)
        return result
    }

    /** The "Force stop" button, found only by its text so we can never hit Uninstall or Disable. */
    private fun findStopButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        STOP_TEXTS.firstNotNullOfOrNull { t ->
            root.findAccessibilityNodeInfosByText(t).firstOrNull { n ->
                val label = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase() ?: return@firstOrNull false
                label == t.lowercase() && UNSAFE_WORDS.none { label.contains(it) }
            }
        }

    private fun confirmButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        root.findAccessibilityNodeInfosByViewId("android:id/button1").firstOrNull()?.let { b ->
            val label = b.text?.toString()?.lowercase() ?: ""
            if (UNSAFE_WORDS.none { label.contains(it) }) return b
        }
        return CONFIRM_TEXTS.firstNotNullOfOrNull { t ->
            root.findAccessibilityNodeInfosByText(t).firstOrNull { it.text?.toString()?.trim().equals(t, ignoreCase = true) }
        }
    }

    /** Lower-cased text of every node in the window. */
    private fun allText(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 30) return
            n.text?.let { sb.append(it).append(' ') }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return sb.toString().lowercase()
    }

    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        return n
    }

    private suspend fun waitFor(timeoutMs: Long, find: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            rootInActiveWindow?.let { root -> find(root)?.let { return it } }
            delay(200)
        }
        return null
    }

    companion object {
        @Volatile
        var instance: ForceStopService? = null
            private set

        private val STOP_TEXTS = listOf("Force stop", "Force close")
        private val CONFIRM_TEXTS = listOf("OK", "Force stop", "Force close")
        private val UNSAFE_WORDS = listOf("uninstall", "disable", "delete", "remove")

        fun isEnabled(context: Context): Boolean {
            val cn = ComponentName(context, ForceStopService::class.java)
            val names = setOf(cn.flattenToString(), cn.flattenToShortString())
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return enabled.split(':').any { e -> names.any { it.equals(e, ignoreCase = true) } }
        }
    }
}
