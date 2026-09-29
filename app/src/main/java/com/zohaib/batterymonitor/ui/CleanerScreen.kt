package com.zohaib.batterymonitor.ui

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zohaib.batterymonitor.App
import com.zohaib.batterymonitor.core.InstalledApp
import com.zohaib.batterymonitor.core.UsageHelper
import com.zohaib.batterymonitor.service.ForceStopService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CleanerScreen() {
    val ctx = LocalContext.current
    val prefs = App.instance.prefs
    val resume = rememberResumeKey()
    var reload by remember { mutableIntStateOf(0) }
    val helperOn = remember(resume) { ForceStopService.isEnabled(ctx) }
    val apps by produceState<List<InstalledApp>?>(null, resume, reload) {
        value = withContext(Dispatchers.IO) { UsageHelper.stoppableApps(ctx) }
    }
    var selected by remember { mutableStateOf(prefs.killList) }
    var running by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun setSelected(v: Set<String>) {
        selected = v
        prefs.killList = v // remembered for next time
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Cleaner", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { reload++ }) { Icon(Icons.Outlined.Refresh, "Refresh") }
        }
        Text(
            "Apps that can still run in the background. Tick the ones you want to stop, then press Kill. Force-stopped apps stay stopped until you open them again.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        if (!helperOn) {
            Card(
                Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Turn on the Force stop helper", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Android doesn't let apps force-stop other apps directly. The helper is an Accessibility service that opens each app's info page and taps Force stop for you. Without it, only a soft background kill is possible, and apps may restart.\n\nSettings → Accessibility → Installed apps → Battery Monitor → On.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = { Perms.openAccessibility(ctx) }) { Text("Open Accessibility settings") }
                }
            }
        }

        val list = apps
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (list == null) "Loading…" else "${list.size} apps can run · ${selected.count { s -> list.any { it.pkg == s } }} selected",
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            TextButton(onClick = { setSelected(list?.map { it.pkg }?.toSet() ?: emptySet()) }) { Text("All") }
            TextButton(onClick = { setSelected(emptySet()) }) { Text("None") }
        }

        LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            items(list ?: emptyList(), key = { it.pkg }) { a ->
                val checked = a.pkg in selected
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { setSelected(if (checked) selected - a.pkg else selected + a.pkg) }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked, onCheckedChange = { setSelected(if (it) selected + a.pkg else selected - a.pkg) })
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.weight(1f)) {
                        AppRow(a.pkg, a.label, "Last used ${fmtAgo(a.lastUsed)}", "", null, openInfo = false)
                    }
                    IconButton(onClick = { Perms.openAppInfo(ctx, a.pkg) }) { Icon(Icons.Outlined.Info, "App info") }
                }
            }
        }

        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        val toKill = list?.map { it.pkg }?.filter { it in selected } ?: emptyList()
        Button(
            onClick = {
                message = null
                val svc = ForceStopService.instance
                if (helperOn && svc != null) {
                    running = "Starting…"
                    svc.forceStop(
                        toKill,
                        onProgress = { i, pkg -> running = "Stopping ${i + 1}/${toKill.size}: ${UsageHelper.label(ctx, pkg)}" },
                        onDone = { res ->
                            running = null
                            val ok = res.values.count { it != ForceStopService.Result.FAILED }
                            val failed = res.filterValues { it == ForceStopService.Result.FAILED }.keys
                            message = "Stopped $ok of ${res.size} apps." +
                                if (failed.isNotEmpty()) " Couldn't stop: " + failed.joinToString { UsageHelper.label(ctx, it) } else ""
                            reload++
                        },
                    )
                } else {
                    softKill(ctx, toKill)
                    message = "Soft-killed ${toKill.size} apps in the background. Turn on the Force stop helper for a full stop."
                    reload++
                }
            },
            enabled = toKill.isNotEmpty() && running == null,
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
        ) {
            if (running != null) {
                CircularProgressIndicator(Modifier.padding(end = 8.dp).height(20.dp).width(20.dp), strokeWidth = 2.dp)
                Text(running!!)
            } else {
                Text("Kill selected (${toKill.size})")
            }
        }
    }
}

private fun softKill(context: Context, pkgs: List<String>) {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    pkgs.forEach { am.killBackgroundProcesses(it) }
}
