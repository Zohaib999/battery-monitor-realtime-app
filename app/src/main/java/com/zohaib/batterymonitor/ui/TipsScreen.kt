package com.zohaib.batterymonitor.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zohaib.batterymonitor.App
import com.zohaib.batterymonitor.core.UsageHelper
import com.zohaib.batterymonitor.data.AppDrain
import com.zohaib.batterymonitor.data.PKG_SCREEN_OFF

data class Tip(
    val title: String,
    val body: String,
    val action: String? = null,
    val intent: Intent? = null,
    val icon: ImageVector = Icons.Outlined.WarningAmber,
)

/** Checks the phone's current settings and today's data, and returns only the tips that apply. */
fun liveTips(context: Context, drain: List<AppDrain>, level: Int?): List<Tip> {
    val tips = mutableListOf<Tip>()
    val cr = context.contentResolver
    val display = Intent(Settings.ACTION_DISPLAY_SETTINGS)

    val autoBrightness = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 1) == 1
    if (!autoBrightness) tips += Tip(
        "Turn on adaptive brightness",
        "The screen uses the most battery. Adaptive brightness keeps it only as bright as needed.",
        "Display settings", display,
    )
    val timeout = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 30_000)
    if (timeout > 60_000) tips += Tip(
        "Screen timeout is ${fmtDuration(timeout.toLong())}",
        "Set it to 30 seconds so the screen doesn't stay on after you put the phone down.",
        "Display settings", display,
    )
    val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    if (!night) tips += Tip(
        "Use dark theme",
        "On AMOLED screens, black pixels are switched off, which saves a lot of battery.",
        "Display settings", display,
    )
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (!pm.isPowerSaveMode && level != null && level <= 30) tips += Tip(
        "Battery is at $level%: turn on Battery Saver",
        "It limits background activity and visual effects until you charge.",
        "Battery saver", Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
    )
    val total = drain.sumOf { it.pct }
    drain.firstOrNull { it.pkg != PKG_SCREEN_OFF && total > 0 && it.pct / total > 0.25 && it.pct >= 3 }?.let {
        val name = UsageHelper.label(context, it.pkg)
        tips += Tip(
            "$name used %.0f%% of battery today".format(it.pct),
            "Open its App info → Battery and choose Optimized or Restricted, or kill it from the Cleaner tab when you're done using it.",
            "App info", Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${it.pkg}")),
        )
    }
    drain.firstOrNull { it.pkg == PKG_SCREEN_OFF }?.let { off ->
        val hours = off.ms / 3_600_000.0
        if (hours >= 1 && off.pct / hours > 1.5) tips += Tip(
            "High standby drain: %.1f%%/hour with the screen off".format(off.pct / hours),
            "Something is running in the background. Check Battery usage, and kill or restrict apps you don't need.",
            "Battery usage", Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
        )
    }
    return tips
}

/** Tips that always apply, including Developer options. */
val generalTips = listOf(
    Tip(
        "Animation scales → 0.5x",
        "Developer options → Window animation scale, Transition animation scale and Animator duration scale → 0.5x (or Off). Less GPU work, and the phone feels faster.",
        "Developer options", Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), Icons.Outlined.DeveloperMode,
    ),
    Tip(
        "Mobile data always active → Off",
        "Developer options → turn off \"Mobile data always active\". Otherwise mobile data stays on even when you're on Wi-Fi.",
        "Developer options", Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), Icons.Outlined.DeveloperMode,
    ),
    Tip(
        "Background process limit → At most 3 processes",
        "Developer options → Background process limit. This saves a lot of battery, but messaging apps may deliver notifications late. Set it back to Standard if you notice that.",
        "Developer options", Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), Icons.Outlined.DeveloperMode,
    ),
    Tip(
        "Standby apps → set rarely used apps to RARE",
        "Developer options → Standby apps. Apps in the RARE or RESTRICTED bucket can rarely wake the phone.",
        "Developer options", Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), Icons.Outlined.DeveloperMode,
    ),
    Tip(
        "Keep these Developer options OFF",
        "\"Force 4x MSAA\", \"Force peak refresh rate\", \"Disable HW overlays\" and \"Don't keep activities\" all increase battery use.",
        "Developer options", Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), Icons.Outlined.DeveloperMode,
    ),
    Tip(
        "Use 60 Hz refresh rate",
        "Display → Refresh rate / Smooth display → Standard (60 Hz). 90 or 120 Hz uses noticeably more battery.",
        "Display settings", Intent(Settings.ACTION_DISPLAY_SETTINGS),
    ),
    Tip(
        "Turn off Wi-Fi and Bluetooth scanning",
        "Location → Location services → turn off Wi-Fi scanning and Bluetooth scanning. They keep searching even when Wi-Fi or Bluetooth is off.",
        "Location settings", Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
    ),
    Tip(
        "Turn off Always-on display",
        "Lock screen / Display → Always-on display → Off.",
        "Display settings", Intent(Settings.ACTION_DISPLAY_SETTINGS),
    ),
    Tip(
        "Prefer 4G when the signal is weak",
        "In weak 5G areas the modem keeps switching networks. Network → SIM → Preferred network type → 4G.",
        "Network settings", Intent(Settings.ACTION_WIRELESS_SETTINGS),
    ),
    Tip(
        "Turn off keyboard vibration and sounds",
        "Keyboard settings → Vibrate on keypress → Off.",
        "Keyboard settings", Intent(Settings.ACTION_INPUT_METHOD_SETTINGS),
    ),
    Tip(
        "Remove live wallpaper and unused widgets",
        "They redraw often and wake the CPU.",
    ),
    Tip(
        "Keep charge between 20% and 80%",
        "It slows battery wear. Many phones have a \"Protect battery\" or \"Adaptive charging\" option that stops at 80–85%.",
        "Battery settings", Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
    ),
)

@Composable
fun TipsScreen() {
    val ctx = LocalContext.current
    val drain by App.instance.db.dao().appDrainSince(remember { UsageHelper.startOfToday() }).collectAsState(emptyList())
    val live by App.instance.tracker.live.collectAsState()
    val resume = rememberResumeKey()
    val tips = remember(resume, drain, live.battery?.level) { liveTips(ctx, drain, live.battery?.level) }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Text("Tips", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        SectionTitle("For your phone right now")
        if (tips.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Your current settings look good.")
            }
        }
        tips.forEach { TipCard(it) }
        SectionTitle("Recommended settings")
        Hint("To see Developer options: Settings → About phone → tap Build number 7 times.")
        generalTips.forEach { TipCard(it) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TipCard(tip: Tip) {
    val ctx = LocalContext.current
    Card(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(16.dp)) {
            Icon(tip.icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tip.title, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(tip.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (tip.action != null && tip.intent != null) {
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = { Perms.open(ctx, Intent(tip.intent)) }) { Text(tip.action) }
                }
            }
        }
    }
}
