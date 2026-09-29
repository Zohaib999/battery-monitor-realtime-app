package com.zohaib.batterymonitor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zohaib.batterymonitor.App
import com.zohaib.batterymonitor.data.Session
import com.zohaib.batterymonitor.data.TYPE_CHARGE

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
fun SessionsScreen(type: String, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val sessions by App.instance.db.dao().sessionsFlow(type).collectAsState(emptyList())
    val charge = type == TYPE_CHARGE
    // Newest day first; inside a day, Session 1 is the earliest.
    val byDay = sessions.groupBy { fmtDay(it.start) }

    Column(Modifier.fillMaxSize()) {
        BackBar(if (charge) "Charging sessions" else "Discharging sessions", onBack)
        if (sessions.isEmpty()) {
            Column(Modifier.padding(16.dp)) {
                Hint(if (charge) "Plug in the charger to start a charging session." else "Unplug the charger to start a discharging session.")
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            byDay.forEach { (day, list) ->
                item(key = "h$day") { SectionTitle(day) }
                val ordered = list.sortedBy { it.start }
                items(ordered.reversed(), key = { it.id }) { s ->
                    SessionRow(ordered.indexOf(s) + 1, s, charge) { onOpen(s.id) }
                }
            }
            item { Spacer(Modifier.padding(12.dp)) }
        }
    }
}

@Composable
private fun SessionRow(number: Int, s: Session, charge: Boolean, onClick: () -> Unit) {
    val color = if (charge) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Session $number", fontWeight = FontWeight.SemiBold)
                val endTxt = s.end?.let { fmtTime(it) } ?: "now"
                Text(
                    "${fmtTime(s.start)} – $endTxt · ${fmtDuration((s.end ?: System.currentTimeMillis()) - s.start)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val status = when {
                    s.end == null -> "In progress"
                    s.accuracy != null -> "Estimate ${s.accuracy.toInt()}% accurate" + if (!s.complete) " · partial" else ""
                    !s.complete -> "Partial"
                    else -> "Complete"
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = color)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "${s.startPct}→${s.endPct ?: "…"}%",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            )
        }
    }
}
