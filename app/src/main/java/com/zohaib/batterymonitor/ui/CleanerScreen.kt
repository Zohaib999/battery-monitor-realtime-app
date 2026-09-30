package com.zohaib.batterymonitor.ui

import android.app.ActivityManager
import android.content.Context
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanerScreen() {
    val ctx = LocalContext.current
    val prefs = App.instance.prefs
    val resume = rememberResumeKey()
    var reload by remember { mutableIntStateOf(0) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    val helperOn = remember(resume) { ForceStopService.isEnabled(ctx) }
    val apps by produceState<List<InstalledApp>?>(null, resume, reload, showSystem) {
        value = withContext(Dispatchers.IO) { UsageHelper.stoppableApps(ctx, showSystem) }
    }
    var myList by remember { mutableStateOf(prefs.killList) }
    // Which apps in my list are running again (not force-stopped).
    val listRunning by produceState(emptySet<String>(), myList, resume, reload) {
        value = withContext(Dispatchers.IO) { myList.filterNot { UsageHelper.isStopped(ctx, it) }.toSet() }
    }
    var running by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun setList(v: Set<String>) {
        myList = v
        prefs.killList = v
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Cleaner", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { reload++ }) { Icon(Icons.Outlined.Refresh, "Refresh") }
        }

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
        val tabs = listOf("Running (${list?.size ?: "…"})", "My list (${myList.size})")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            tabs.forEachIndexed { i, label ->
                SegmentedButton(tab == i, { tab = i }, SegmentedButtonDefaults.itemShape(i, tabs.size)) { Text(label) }
            }
        }

        if (tab == 0) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Apps running or able to wake up. Tap + to add one to your list.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text("System", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 6.dp))
                Switch(showSystem, { showSystem = it })
            }
            if (showSystem) Text(
                "System apps are shown after your apps. Core parts (phone, SystemUI, Play services, launcher, keyboard) are hidden. Some system apps start again by themselves, and some don't allow Force stop.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                if (list == null) item { Text("Loading…", Modifier.padding(16.dp)) }
                items(list ?: emptyList(), key = { it.pkg }) { a ->
                    val inList = a.pkg in myList
                    AppLine(a.pkg, a.label, runningNote(a)) {
                        IconButton(onClick = { setList(if (inList) myList - a.pkg else myList + a.pkg) }) {
                            if (inList) Icon(Icons.Outlined.Check, "In my list", tint = MaterialTheme.colorScheme.primary)
                            else Icon(Icons.Outlined.Add, "Add to my list")
                        }
                    }
                }
            }
        } else {
            Text(
                if (myList.isEmpty()) "Your list is empty. Add apps with + on the Running tab."
                else "${listRunning.size} of ${myList.size} are running again. Kill stops them all in one tap.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            val sorted = myList.sortedWith(compareBy<String> { it !in listRunning }.thenBy { UsageHelper.label(ctx, it).lowercase() })
            LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                items(sorted, key = { it }) { pkg ->
                    val on = pkg in listRunning
                    AppLine(pkg, UsageHelper.label(ctx, pkg), if (on) "● Running again" else "Stopped") {
                        IconButton(onClick = { setList(myList - pkg) }) { Icon(Icons.Outlined.RemoveCircleOutline, "Remove from list") }
                    }
                }
            }
        }

        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        val toKill = myList.filter { it in listRunning }
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
                Text(if (toKill.isEmpty()) "My list: nothing running" else "Kill my list (${toKill.size})")
            }
        }
    }
}

/** Marks apps that are running although you haven't opened them recently, i.e. they started by themselves. */
private fun runningNote(a: InstalledApp): String {
    val tag = if (a.system) "System · " else ""
    return when {
        a.lastUsed == 0L -> tag + "Started by itself · not opened in 7 days"
        System.currentTimeMillis() - a.lastUsed > 3_600_000 -> tag + "Started by itself? Last opened ${fmtAgo(a.lastUsed)}"
        else -> tag + "Opened ${fmtAgo(a.lastUsed)}"
    }
}

@Composable
private fun AppLine(pkg: String, label: String, note: String, action: @Composable () -> Unit) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { AppRow(pkg, label, note, "", null, openInfo = false) }
        IconButton(onClick = { Perms.openAppInfo(ctx, pkg) }) { Icon(Icons.Outlined.Info, "App info") }
        action()
    }
}

private fun softKill(context: Context, pkgs: List<String>) {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    pkgs.forEach { am.killBackgroundProcesses(it) }
}
