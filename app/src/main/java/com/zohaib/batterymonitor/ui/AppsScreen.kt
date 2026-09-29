package com.zohaib.batterymonitor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zohaib.batterymonitor.App
import com.zohaib.batterymonitor.core.AppTime
import com.zohaib.batterymonitor.core.InstalledApp
import com.zohaib.batterymonitor.core.UsageHelper
import com.zohaib.batterymonitor.data.AppDrain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen() {
    val ctx = LocalContext.current
    val dao = App.instance.db.dao()
    val periods = listOf("Session", "Today", "7 days")
    var period by rememberSaveable { mutableIntStateOf(1) }
    val live by App.instance.tracker.live.collectAsState()
    val resume = rememberResumeKey()
    val hasUsage = remember(resume) { UsageHelper.hasUsageAccess(ctx) }

    val since = when (period) {
        1 -> UsageHelper.startOfToday()
        2 -> System.currentTimeMillis() - 7 * 86_400_000L
        else -> live.session?.start ?: System.currentTimeMillis()
    }
    val sessionId = live.session?.id
    val drainFlow = remember(period, sessionId) {
        if (period == 0) sessionId?.let { dao.appDrainForSession(it) } ?: flowOf(emptyList())
        else dao.appDrainSince(since)
    }
    val drain: List<AppDrain> by drainFlow.collectAsState(emptyList())
    val screen by produceState(emptyList<AppTime>(), period, resume) {
        value = withContext(Dispatchers.IO) { UsageHelper.screenTime(ctx, since) }
    }
    val unused by produceState(emptyList<InstalledApp>(), resume) {
        value = withContext(Dispatchers.IO) { UsageHelper.unusedApps(ctx) }
    }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
        item {
            Text("Apps", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(12.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                periods.forEachIndexed { i, label ->
                    SegmentedButton(period == i, { period = i }, SegmentedButtonDefaults.itemShape(i, periods.size)) { Text(label) }
                }
            }
            if (!hasUsage) {
                Spacer(Modifier.height(12.dp))
                Hint("Usage access is off. Tap here to allow it.")
                Text(
                    "Open usage access settings",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { Perms.openUsageAccess(ctx) }.padding(vertical = 4.dp),
                )
            }
            SectionTitle("Battery used (estimated)")
            if (period == 0 && live.session?.type == com.zohaib.batterymonitor.data.TYPE_CHARGE) {
                Hint("The phone is charging, so no battery is being used by apps in this session.")
            } else if (drain.isEmpty()) Hint("No data yet. It appears after each 1% drop.")
        }
        val maxDrain = drain.firstOrNull()?.pct?.coerceAtLeast(0.01) ?: 1.0
        items(drain, key = { "d" + it.pkg }) {
            val perPct = if (it.pct > 0) fmtRate(it.ms / it.pct) else ""
            AppRow(
                it.pkg, UsageHelper.label(ctx, it.pkg), "${fmtDuration(it.ms)} · $perPct",
                "%.1f%%".format(it.pct), (it.pct / maxDrain).toFloat(), MaterialTheme.colorScheme.secondary,
            )
        }
        item {
            SectionTitle("Screen time")
            if (screen.isEmpty()) Hint("No screen time recorded for this period.")
        }
        val maxScreen = screen.firstOrNull()?.ms?.coerceAtLeast(1) ?: 1
        items(screen.take(15), key = { "s" + it.pkg }) {
            AppRow(it.pkg, UsageHelper.label(ctx, it.pkg), "Last used ${fmtAgo(it.lastUsed)}", fmtDuration(it.ms), it.ms.toFloat() / maxScreen)
        }
        item {
            SectionTitle("Unused for 7 days (${unused.size})")
            Hint("Tap an app to open its info page, where you can uninstall it or set its battery use to Restricted.")
        }
        items(unused, key = { "u" + it.pkg }) {
            Column(Modifier.clickable { Perms.openAppInfo(ctx, it.pkg) }) {
                AppRow(it.pkg, it.label, "Not opened in 7 days", "", null)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
