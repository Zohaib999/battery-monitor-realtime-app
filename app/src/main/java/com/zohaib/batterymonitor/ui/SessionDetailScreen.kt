package com.zohaib.batterymonitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zohaib.batterymonitor.App
import com.zohaib.batterymonitor.core.CURVE_KNEE
import com.zohaib.batterymonitor.core.Estimator
import com.zohaib.batterymonitor.core.Rates
import com.zohaib.batterymonitor.core.UsageHelper
import com.zohaib.batterymonitor.data.Estimate
import com.zohaib.batterymonitor.data.Session
import com.zohaib.batterymonitor.data.Step
import com.zohaib.batterymonitor.data.TYPE_CHARGE

private val EstimateColors = listOf(Color(0xFF2563EB), Color(0xFF9333EA), Color(0xFFDB2777))

@Composable
fun SessionDetailScreen(id: Long, onBack: () -> Unit) {
    val dao = App.instance.db.dao()
    val session by dao.sessionFlow(id).collectAsState(null)
    val steps by dao.stepsFlow(id).collectAsState(emptyList())
    val estimates by dao.estimatesFlow(id).collectAsState(emptyList())
    val apps by dao.appDrainForSession(id).collectAsState(emptyList())
    val live by App.instance.tracker.live.collectAsState()
    val s = session
    if (s == null) {
        Column(Modifier.fillMaxSize()) { BackBar("Session", onBack) }
        return
    }
    val charge = s.type == TYPE_CHARGE
    val color = if (charge) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        BackBar("${fmtDay(s.start)} · ${if (charge) "Charging" else "Discharging"}", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            // Summary
            val end = s.end ?: System.currentTimeMillis()
            val lastPct = steps.lastOrNull()?.pct ?: s.startPct
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Range", "${s.startPct}→${s.endPct ?: lastPct}%", Modifier.weight(1f))
                Stat("Duration", fmtDuration(end - s.start), Modifier.weight(1f))
                Stat("Average", if (steps.isEmpty()) "–" else fmtDuration(steps.map { it.windowMs }.average().toLong()) + "/1%", Modifier.weight(1f))
            }
            Text(
                "${fmtTime(s.start)} – ${s.end?.let { fmtTime(it) } ?: "now"} · " +
                    when {
                        s.end == null -> "in progress"
                        s.complete -> if (charge) "charged to 100%" else "ran to empty"
                        else -> "partial (" + (if (charge) "unplugged early" else "charged before empty") + ")"
                    },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            SectionTitle("Chart")
            SessionChart(s, steps, estimates, color, Modifier.fillMaxWidth().height(220.dp))
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                LegendDot(color, "Actual")
                estimates.take(3).forEachIndexed { i, e -> LegendDot(EstimateColors[i], "Est. @${e.label}", dashed = true) }
            }

            SectionTitle("Estimate vs actual")
            EstimateTable(s, steps, estimates, if (s.end == null) live.etaAt else null)

            if (!charge && apps.isNotEmpty()) {
                SectionTitle("Apps in this session")
                val max = apps.first().pct.coerceAtLeast(0.01)
                apps.take(10).forEach {
                    AppRow(
                        it.pkg, UsageHelper.label(ctx, it.pkg), "${fmtDuration(it.ms)} on screen",
                        "%.1f%%".format(it.pct), (it.pct / max).toFloat(), color,
                    )
                }
            }

            StepTable(s, steps)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String, dashed: Boolean = false) {
    Canvas(Modifier.size(16.dp, 8.dp)) {
        drawLine(
            color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 3.dp.toPx(),
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) else null,
        )
    }
    Spacer(Modifier.width(4.dp))
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.width(10.dp))
}

private fun predictedPath(s: Session, e: Estimate): List<Pair<Long, Int>> {
    val r = Rates(e.rateLow, e.rateHigh)
    val target = if (s.type == TYPE_CHARGE) 100 else 0
    val pts = mutableListOf(e.madeAt to e.madeAtPct)
    if (s.type == TYPE_CHARGE && e.madeAtPct < CURVE_KNEE) {
        pts += (e.madeAt + Estimator.timeToReach(s.type, e.madeAtPct, CURVE_KNEE, r).toLong()) to CURVE_KNEE
    }
    pts += (e.madeAt + Estimator.timeToReach(s.type, e.madeAtPct, target, r).toLong()) to target
    return pts
}

/** % over time: the actual line, plus a dashed line for each saved estimate. */
@Composable
fun SessionChart(s: Session, steps: List<Step>, estimates: List<Estimate>, color: Color, modifier: Modifier) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val actual = listOf(s.start to s.startPct) + steps.map { it.ts to it.pct } +
        listOfNotNull(if (s.end == null) System.currentTimeMillis() to (steps.lastOrNull()?.pct ?: s.startPct) else null)
    val paths = estimates.take(3).map { predictedPath(s, it) }
    val tMin = s.start
    val actualEnd = s.end ?: System.currentTimeMillis()
    // Show predictions past the actual end, but not more than 2x the real duration.
    val predictedEnd = paths.maxOfOrNull { p -> p.last().first } ?: actualEnd
    val tMax = maxOf(actualEnd, minOf(predictedEnd, tMin + 2 * (actualEnd - tMin).coerceAtLeast(3_600_000)))
        .coerceAtLeast(tMin + 60_000)

    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Canvas(Modifier.fillMaxSize()) {
                val left = 32.dp.toPx()
                val w = size.width - left
                val h = size.height
                fun x(t: Long) = left + w * ((t - tMin).toFloat() / (tMax - tMin))
                fun y(p: Int) = h - h * p / 100f
                for (p in listOf(0, 25, 50, 75, 100)) {
                    drawLine(grid, Offset(left, y(p)), Offset(size.width, y(p)), 1f)
                    drawContext.canvas.nativeCanvas.drawText(
                        "$p", 0f, y(p) + 4.dp.toPx(),
                        android.graphics.Paint().apply {
                            this.color = labelColor.toArgbCompat()
                            textSize = 10.dp.toPx()
                            isAntiAlias = true
                        },
                    )
                }
                paths.forEachIndexed { i, pts ->
                    val path = Path()
                    pts.forEachIndexed { k, (t, p) ->
                        val tx = x(t.coerceAtMost(tMax))
                        if (k == 0) path.moveTo(tx, y(p)) else path.lineTo(tx, y(p))
                    }
                    drawPath(path, EstimateColors[i], style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
                }
                val path = Path()
                actual.forEachIndexed { k, (t, p) -> if (k == 0) path.moveTo(x(t), y(p)) else path.lineTo(x(t), y(p)) }
                drawPath(path, color, style = Stroke(3.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 32.dp, top = 4.dp)) {
            Text(fmtTime(tMin), style = MaterialTheme.typography.labelSmall, color = labelColor)
            Spacer(Modifier.weight(1f))
            Text(fmtTime(tMax), style = MaterialTheme.typography.labelSmall, color = labelColor)
        }
    }
}

private fun Color.toArgbCompat(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
)

@Composable
private fun EstimateTable(s: Session, steps: List<Step>, estimates: List<Estimate>, liveEta: Long?) {
    val charge = s.type == TYPE_CHARGE
    val scores = remember(steps, estimates, s.end) {
        if (s.end != null) Estimator.score(s, steps, estimates).associateBy { it.estimate.id } else emptyMap()
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp)) {
            Row {
                Cell("Made at", 1.2f, header = true)
                Cell(if (charge) "Said full at" else "Said empty at", 1.2f, header = true)
                Cell("Actual", 1f, header = true)
                Cell("Off by", 1f, header = true)
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            if (estimates.isEmpty()) {
                Text("No estimate yet: the first one is saved 1 minute into the session.", style = MaterialTheme.typography.bodySmall)
            }
            estimates.forEach { e ->
                val target = if (charge) 100 else 0
                val predictedFull = e.madeAt + Estimator.timeToReach(s.type, e.madeAtPct, target, Rates(e.rateLow, e.rateHigh)).toLong()
                val sc = scores[e.id]
                Row {
                    Cell("${e.label} (${e.madeAtPct}%)", 1.2f)
                    Cell(fmtTime(predictedFull), 1.2f)
                    Cell(sc?.let { "${fmtTime(it.actualAt)}\n@${it.targetPct}%" } ?: if (s.end == null) "…" else "–", 1f)
                    Cell(
                        sc?.let { (if (it.errorMs >= 0) "+" else "-") + fmtDuration(it.errorMs) + "\n${it.accuracy.toInt()}%" } ?: "–",
                        1f,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
            if (s.end != null && scores.isNotEmpty() && !s.complete) {
                Text(
                    "Partial session: each estimate is checked at ${steps.last().pct}%, the level actually reached.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (liveEta != null) {
                Text(
                    "Current estimate: ${if (charge) "full" else "empty"} at ${fmtTime(liveEta)}",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                )
            }
            s.accuracy?.let {
                Text("Session accuracy: ${it.toInt()}%", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(text: String, weight: Float, header: Boolean = false) {
    Text(
        text,
        modifier = Modifier.weight(weight),
        style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
        color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
    )
}

/** Time per 1% step, or grouped per 5% with a running total ("Combined"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StepTable(s: Session, steps: List<Step>) {
    val ctx = LocalContext.current
    var combined by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("Steps", Modifier.weight(1f))
        SingleChoiceSegmentedButtonRow {
            SegmentedButton(!combined, { combined = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("1%") }
            SegmentedButton(combined, { combined = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Combined") }
        }
    }
    if (steps.isEmpty()) {
        Hint("No 1% change yet.")
        return
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp)) {
            Row {
                Cell("Level", 1f, header = true)
                Cell("Took", 1f, header = true)
                Cell(if (combined) "Total" else if (s.type == TYPE_CHARGE) "At" else "Main app", 1.3f, header = true)
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            if (!combined) {
                var prev = s.startPct
                steps.forEach { st ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Cell("$prev→${st.pct}%", 1f)
                        Cell(fmtDuration(st.windowMs), 1f)
                        Cell(if (s.type == TYPE_CHARGE) fmtTime(st.ts) else st.topApp?.let { UsageHelper.label(ctx, it) } ?: "–", 1.3f)
                    }
                    prev = st.pct
                }
            } else {
                var total = 0L
                steps.chunked(5).forEachIndexed { i, chunk ->
                    val from = if (i == 0) s.startPct else steps[i * 5 - 1].pct
                    val took = chunk.sumOf { it.windowMs }
                    total += took
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Cell("$from→${chunk.last().pct}%", 1f)
                        Cell(fmtDuration(took), 1f)
                        Cell(fmtDuration(total), 1.3f)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text(
                    "${s.startPct}% → ${steps.last().pct}% took ${fmtDuration(total)} in total",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
