package com.zohaib.batterymonitor.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zohaib.batterymonitor.App
import com.zohaib.batterymonitor.core.BatteryReader
import com.zohaib.batterymonitor.core.LiveStatus
import com.zohaib.batterymonitor.core.UsageHelper
import com.zohaib.batterymonitor.data.PKG_SCREEN_OFF
import com.zohaib.batterymonitor.data.Session
import com.zohaib.batterymonitor.data.TYPE_CHARGE
import com.zohaib.batterymonitor.data.TYPE_DISCHARGE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(onOpenSessions: (String) -> Unit, onOpenApps: () -> Unit, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val app = App.instance
    val live by app.tracker.live.collectAsState()
    val charges by app.db.dao().sessionsFlow(TYPE_CHARGE).collectAsState(emptyList())
    val discharges by app.db.dao().sessionsFlow(TYPE_DISCHARGE).collectAsState(emptyList())
    val since = remember { UsageHelper.startOfToday() }
    val drain by app.db.dao().appDrainSince(since).collectAsState(emptyList())
    val resume = rememberResumeKey()
    val hasUsage = remember(resume) { UsageHelper.hasUsageAccess(ctx) }
    val screen by produceState(emptyList<com.zohaib.batterymonitor.core.AppTime>(), resume) {
        value = withContext(Dispatchers.IO) { UsageHelper.screenTime(ctx, since) }
    }
    val unused by produceState(emptyList<com.zohaib.batterymonitor.core.InstalledApp>(), resume) {
        value = withContext(Dispatchers.IO) { UsageHelper.unusedApps(ctx) }
    }
    val charging = live.session?.type == TYPE_CHARGE
    val now by produceState(live.battery, live.battery) {
        while (true) {
            value = BatteryReader.read(ctx) ?: live.battery
            kotlinx.coroutines.delay(5_000)
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Battery", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                now?.let { b ->
                    val extra = listOfNotNull(
                        b.plug.takeIf { it.isNotEmpty() },
                        b.currentMa?.let { "${if (it > 0) "+" else ""}$it mA" },
                        b.tempC?.let { "%.1f°C".format(it) },
                    ).joinToString(" · ")
                    Text(
                        "${b.level}%" + if (extra.isNotEmpty()) " · $extra" else "",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Settings") }
        }
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StateCard(
                modifier = Modifier.weight(1f),
                title = "Charging",
                active = charging,
                color = MaterialTheme.colorScheme.primary,
                live = live,
                last = charges.firstOrNull { it.end != null },
                onClick = { onOpenSessions(TYPE_CHARGE) },
            )
            StateCard(
                modifier = Modifier.weight(1f),
                title = "Discharging",
                active = live.session?.type == TYPE_DISCHARGE,
                color = MaterialTheme.colorScheme.secondary,
                live = live,
                last = discharges.firstOrNull { it.end != null },
                onClick = { onOpenSessions(TYPE_DISCHARGE) },
            )
        }

        if (!hasUsage) {
            Card(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Usage access needed", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Allow usage access to see which apps use your battery.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = { Perms.openUsageAccess(ctx) }) { Text("Allow") }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Top 5 apps using battery today", Modifier.weight(1f))
            TextButton(onClick = onOpenApps) { Text("All") }
        }
        val topDrain = drain.filter { it.pkg != PKG_SCREEN_OFF }.take(5)
        if (topDrain.isEmpty()) {
            Hint(if (hasUsage) "Nothing yet. Data appears after the battery drops by 1% while you use the phone." else "Needs usage access.")
        } else {
            val max = topDrain.first().pct.coerceAtLeast(0.01)
            topDrain.forEach {
                AppRow(
                    it.pkg, UsageHelper.label(ctx, it.pkg), "${fmtDuration(it.ms)} on screen",
                    "%.1f%%".format(it.pct), (it.pct / max).toFloat(), MaterialTheme.colorScheme.secondary,
                )
            }
        }

        SectionTitle("Most used today")
        if (screen.isEmpty()) Hint("No screen time recorded yet.")
        else {
            val max = screen.first().ms.coerceAtLeast(1)
            screen.take(5).forEach {
                AppRow(it.pkg, UsageHelper.label(ctx, it.pkg), "Last used ${fmtAgo(it.lastUsed)}", fmtDuration(it.ms), it.ms.toFloat() / max)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Unused for 7 days (${unused.size})", Modifier.weight(1f))
            TextButton(onClick = onOpenApps) { Text("All") }
        }
        if (unused.isEmpty()) Hint(if (hasUsage) "Every app was used this week." else "Needs usage access.")
        else unused.take(5).forEach {
            AppRow(it.pkg, it.label, "Not opened in 7 days. Uninstall or restrict it.", "", null)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun StateCard(
    modifier: Modifier,
    title: String,
    active: Boolean,
    color: Color,
    live: LiveStatus,
    last: Session?,
    onClick: () -> Unit,
) {
    val container = if (active) color else MaterialTheme.colorScheme.surfaceContainer
    val content = if (active) Color.White else MaterialTheme.colorScheme.onSurface
    val sub = if (active) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        onClick = onClick,
        modifier = modifier.height(210.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.padding(16.dp).fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (title == "Charging") Icons.Outlined.BatteryChargingFull else Icons.Outlined.BatteryAlert,
                    contentDescription = null, modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(title, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(if (active) "ON" else "OFF", style = MaterialTheme.typography.labelMedium, color = sub)
            }
            Spacer(Modifier.height(10.dp))
            if (active && live.battery != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${live.battery.level}%", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    if (title == "Charging") {
                        Spacer(Modifier.width(8.dp))
                        ChargingAnimation(live.battery.level, Modifier.size(28.dp, 44.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                if (live.remainingMs != null) {
                    Text(
                        (if (title == "Charging") "Full at " else "Empty at ") + fmtTime(live.etaAt!!),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("${fmtDuration(live.remainingMs)} left", color = sub, style = MaterialTheme.typography.bodySmall)
                    live.msPerPct?.let { Text(fmtRate(it), color = sub, style = MaterialTheme.typography.bodySmall) }
                } else {
                    Text("Measuring…", fontWeight = FontWeight.SemiBold)
                    Text("Estimate after 1 min", color = sub, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text("Last session", color = sub, style = MaterialTheme.typography.bodySmall)
                if (last != null) {
                    Text("${last.startPct}% → ${last.endPct}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(fmtDuration((last.end ?: 0) - last.start), color = sub)
                    Spacer(Modifier.weight(1f))
                    Text(
                        last.accuracy?.let { "Estimate ${it.toInt()}% accurate" } ?: "No estimate score",
                        style = MaterialTheme.typography.bodySmall, color = sub,
                    )
                } else {
                    Text("None yet", style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.weight(1f))
                Text("View sessions →", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Placeholder charging animation: a battery outline with a pulsing fill. */
@Composable
fun ChargingAnimation(level: Int, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "charge")
    val pulse by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "fill",
    )
    Canvas(modifier) {
        val stroke = 2.dp.toPx()
        val capH = size.height * 0.08f
        val body = Size(size.width, size.height - capH)
        drawRoundRect(Color.White, Offset(size.width * 0.3f, 0f), Size(size.width * 0.4f, capH), CornerRadius(2f))
        drawRoundRect(Color.White, Offset(0f, capH), body, CornerRadius(6f), style = Stroke(stroke))
        val inner = body.height - stroke * 4
        val base = level / 100f
        val fill = (base + (1 - base) * pulse).coerceAtMost(1f)
        val h = inner * fill
        drawRoundRect(
            Color.White.copy(alpha = 0.9f),
            Offset(stroke * 2, capH + stroke * 2 + inner - h),
            Size(body.width - stroke * 4, h), CornerRadius(3f),
        )
    }
}
