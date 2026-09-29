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
        if (!button.isEnabled) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            return Result.ALREADY_STOPPED
        }
        click(button)
        val ok = waitFor(3_000) { root ->
            root.findAccessibilityNodeInfosByViewId("android:id/button1").firstOrNull()
                ?: findByText(root, CONFIRM_TEXTS)?.takeIf { it.className?.contains("Button") == true }
        }
        val result = if (ok != null && click(ok)) Result.STOPPED else Result.FAILED
        delay(400)
        performGlobalAction(GLOBAL_ACTION_BACK)
        delay(300)
        return result
    }

    private fun findStopButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in STOP_IDS) root.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.let { return it }
        return findByText(root, STOP_TEXTS)
    }

    private fun findByText(root: AccessibilityNodeInfo, texts: List<String>): AccessibilityNodeInfo? {
        for (t in texts) {
            root.findAccessibilityNodeInfosByText(t)
                .firstOrNull { it.text?.toString()?.trim().equals(t, ignoreCase = true) }
                ?.let { return it }
        }
        return null
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        return n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
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

        private val STOP_IDS = listOf(
            "com.android.settings:id/force_stop_button",
            "com.android.settings:id/right_button",
            "com.miui.securitycenter:id/force_stop",
        )
        private val STOP_TEXTS = listOf("Force stop", "Force Stop", "FORCE STOP", "Force close", "Stop")
        private val CONFIRM_TEXTS = listOf("OK", "Force stop", "Stop")

        fun isEnabled(context: Context): Boolean {
            val cn = ComponentName(context, ForceStopService::class.java)
            val names = setOf(cn.flattenToString(), cn.flattenToShortString())
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return enabled.split(':').any { e -> names.any { it.equals(e, ignoreCase = true) } }
        }
    }
}
