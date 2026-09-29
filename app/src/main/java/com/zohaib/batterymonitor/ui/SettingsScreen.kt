package com.zohaib.batterymonitor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zohaib.batterymonitor.App
import com.zohaib.batterymonitor.core.UsageHelper
import com.zohaib.batterymonitor.service.ForceStopService

@Composable
private fun PermissionList() {
    val ctx = LocalContext.current
    val resume = rememberResumeKey()
    data class P(val title: String, val why: String, val ok: Boolean, val open: () -> Unit)
    val items = remember(resume) {
        listOf(
            P("Usage access", "Needed to see which app is open while the battery drops. Required for the Apps screen.",
                UsageHelper.hasUsageAccess(ctx)) { Perms.openUsageAccess(ctx) },
            P("Notifications", "Shows the live % and ETA in the status bar.",
                Perms.notificationsGranted(ctx)) { Perms.openNotificationSettings(ctx) },
            P("Unrestricted battery", "Stops Android from killing the monitor in the background.",
                Perms.ignoringBatteryOpt(ctx)) { Perms.requestIgnoreBatteryOpt(ctx) },
            P("Force stop helper (optional)", "An Accessibility service used only when you press Kill on the Cleaner tab.",
                ForceStopService.isEnabled(ctx)) { Perms.openAccessibility(ctx) },
        )
    }
    items.forEach { p ->
        Card(
            Modifier.fillMaxWidth().padding(vertical = 5.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (p.ok) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
                    tint = if (p.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.title, fontWeight = FontWeight.SemiBold)
                    Text(p.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!p.ok) {
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = p.open) { Text("Allow") }
                }
            }
        }
    }
}

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("Welcome", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(
            "Battery Monitor times every 1% while you charge and use the phone, estimates when it will be full or empty, and checks how accurate those estimates were.\n\nAllow these so it can work in the background:",
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp),
        )
        PermissionList()
        Spacer(Modifier.height(16.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Continue") }
        Text(
            "You can change these later in Settings.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val prefs = App.instance.prefs
    var refresh by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        BackBar("Settings", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionTitle("Permissions")
            PermissionList()

            SectionTitle("What the app has learned")
            key(refresh) {
                Text("Charging below 80%: ${fmtRate(prefs.chargeLow)}")
                Text("Charging 80–100%: ${fmtRate(prefs.chargeHigh)}")
                Text("Discharging: ${fmtRate(prefs.discharge)}")
                Text(
                    "Learned from ${prefs.learnedCharges} charges and ${prefs.learnedDischarges} discharges.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { prefs.resetLearning(); refresh++ }) { Text("Reset learning") }

            SectionTitle("Data")
            Hint("History is kept for 90 days. Older sessions are deleted automatically.")
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun key(k: Int, content: @Composable () -> Unit) = androidx.compose.runtime.key(k) { content() }
