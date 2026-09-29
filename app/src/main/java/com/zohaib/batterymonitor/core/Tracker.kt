package com.zohaib.batterymonitor.core

import android.content.Context
import com.zohaib.batterymonitor.Prefs
import com.zohaib.batterymonitor.data.BatteryDao
import com.zohaib.batterymonitor.data.Estimate
import com.zohaib.batterymonitor.data.PKG_SCREEN_OFF
import com.zohaib.batterymonitor.data.PKG_SCREEN_ON_OTHER
import com.zohaib.batterymonitor.data.Session
import com.zohaib.batterymonitor.data.Step
import com.zohaib.batterymonitor.data.StepApp
import com.zohaib.batterymonitor.data.TYPE_CHARGE
import com.zohaib.batterymonitor.data.TYPE_DISCHARGE
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LiveStatus(
    val battery: BatterySnapshot? = null,
    val session: Session? = null,
    /** Null until the session has run for a minute. */
    val remainingMs: Long? = null,
    val etaAt: Long? = null,
    val msPerPct: Double? = null,
)

/**
 * Turns battery broadcasts into charge / discharge sessions, 1% steps and estimates.
 * Plugging in closes the discharge session and opens a charge session, and unplugging
 * does the reverse.
 */
class Tracker(
    private val context: Context,
    private val dao: BatteryDao,
    private val prefs: Prefs,
) {
    private val mutex = Mutex()
    private val _live = MutableStateFlow(LiveStatus())
    val live: StateFlow<LiveStatus> = _live

    private var session: Session? = null
    private var steps: MutableList<Step> = mutableListOf()
    private var labels: MutableSet<String> = mutableSetOf()
    private var lastLevel = -1
    private var lastStepTs = 0L
    private var lastBattery: BatterySnapshot? = null

    suspend fun onBattery(b: BatterySnapshot) = mutex.withLock {
        val now = System.currentTimeMillis()
        lastBattery = b
        loadIfNeeded()

        val wantType = if (b.charging) TYPE_CHARGE else TYPE_DISCHARGE
        session?.let { if (it.type != wantType) close(it, b.level, now) }
        if (session == null) open(wantType, b.level, now)

        val s = session!!
        if (b.level != lastLevel) {
            val forward = if (s.type == TYPE_CHARGE) b.level > lastLevel else b.level < lastLevel
            if (forward) addSteps(s, lastLevel, b.level, now)
            else lastStepTs = now // level moved the "wrong" way: restart timing from here
            lastLevel = b.level
        }
        checkSnapshots(s, b.level, now)
        publish(now)
    }

    /** Called every few seconds by the service: takes the 1-minute snapshot and refreshes the ETA. */
    suspend fun tick(publish: Boolean = true) = mutex.withLock {
        val s = session ?: return@withLock
        val now = System.currentTimeMillis()
        lastBattery?.let { checkSnapshots(s, it.level, now) }
        if (publish) publish(now)
        if (now - prefs.lastPurge > 86_400_000L) {
            val before = now - 90L * 86_400_000L
            dao.purgeStepApps(before)
            dao.purgeSteps(before)
            dao.purgeEstimates(before)
            dao.purgeSessions(before)
            prefs.lastPurge = now
        }
    }

    private suspend fun loadIfNeeded() {
        if (session != null) return
        val s = dao.openSession() ?: return
        session = s
        steps = dao.steps(s.id).toMutableList()
        labels = dao.estimates(s.id).map { it.label }.toMutableSet()
        lastLevel = steps.lastOrNull()?.pct ?: s.startPct
        lastStepTs = steps.lastOrNull()?.ts ?: s.start
    }

    private suspend fun open(type: String, level: Int, now: Long) {
        val s = Session(type = type, start = now, startPct = level)
        session = s.copy(id = dao.insert(s))
        steps = mutableListOf()
        labels = mutableSetOf()
        lastLevel = level
        lastStepTs = now
    }

    private suspend fun close(s: Session, level: Int, now: Long) {
        val endPct = steps.lastOrNull()?.pct ?: level
        val complete = if (s.type == TYPE_CHARGE) endPct >= 100 else endPct <= 1
        val scores = Estimator.score(s, steps, dao.estimates(s.id))
        val acc = if (scores.isEmpty()) null else scores.map { it.accuracy }.average()
        dao.update(s.copy(end = now, endPct = endPct, complete = complete, accuracy = acc))
        learn(s)
        session = null
    }

    private suspend fun addSteps(s: Session, from: Int, to: Int, now: Long) {
        val count = kotlin.math.abs(to - from)
        val window = (now - lastStepTs).coerceAtLeast(1)
        val each = window / count
        val apps: Map<String, Long> =
            if (s.type == TYPE_DISCHARGE) UsageHelper.foregroundTimes(context, lastStepTs, now) else emptyMap()
        val fg = apps.values.sum().coerceAtMost(window)
        val screenOn = if (s.type == TYPE_DISCHARGE) {
            (UsageHelper.screenOnTime(context, lastStepTs, now) ?: fg).coerceIn(fg, window)
        } else 0L
        val onOther = screenOn - fg
        val screenOff = window - screenOn
        val all = apps + mapOf(PKG_SCREEN_ON_OTHER to onOther, PKG_SCREEN_OFF to screenOff).filterValues { it > 0 }
        val dir = if (to > from) 1 else -1
        for (k in 1..count) {
            val pct = from + dir * k
            val ts = lastStepTs + each * k
            val top = all.maxByOrNull { it.value }?.key
            val step = Step(sessionId = s.id, pct = pct, ts = ts, windowMs = each, topApp = top)
            val id = dao.insert(step)
            steps.add(step.copy(id = id))
            if (s.type == TYPE_DISCHARGE) {
                val rows = all.map { StepApp(stepId = id, pkg = it.key, ms = it.value / count) }
                if (rows.isNotEmpty()) dao.insertApps(rows)
            }
        }
        lastStepTs = now
    }

    private fun rates(s: Session): Rates =
        if (s.type == TYPE_CHARGE) Estimator.chargeRates(steps, prefs.chargeLow, prefs.chargeHigh)
        else Estimator.dischargeRate(steps, prefs.discharge).let { Rates(it, it) }

    private suspend fun checkSnapshots(s: Session, level: Int, now: Long) {
        val wanted = buildList {
            if (now - s.start >= 60_000) add("1 min")
            if (s.type == TYPE_CHARGE) {
                if (s.startPct < 50 && level >= 50) add("50%")
                if (s.startPct < 80 && level >= 80) add("80%")
            } else {
                if (s.startPct > 50 && level <= 50) add("50%")
                if (s.startPct > 20 && level <= 20) add("20%")
            }
        }
        for (label in wanted) {
            if (label in labels) continue
            val r = rates(s)
            dao.insert(Estimate(sessionId = s.id, label = label, madeAt = now, madeAtPct = level, rateLow = r.low, rateHigh = r.high))
            labels.add(label)
        }
    }

    private fun publish(now: Long) {
        val s = session
        val b = lastBattery
        if (s == null || b == null) {
            _live.value = LiveStatus(b, s)
            return
        }
        val r = rates(s)
        val target = if (s.type == TYPE_CHARGE) 100 else 0
        val current = if (s.type == TYPE_CHARGE) (if (b.level < CURVE_KNEE) r.low else r.high) else r.low
        val sinceStep = (now - lastStepTs).toDouble().coerceAtMost(current * 0.95)
        val total = Estimator.timeToReach(s.type, b.level, target, r)
        val remaining = if (total <= 0) 0L else (total - sinceStep).toLong().coerceAtLeast(0)
        val ready = now - s.start >= 60_000
        _live.value = LiveStatus(
            battery = b,
            session = s,
            remainingMs = if (ready) remaining else null,
            etaAt = if (ready) now + remaining else null,
            msPerPct = if (ready) current else null,
        )
    }

    /** Blend this session's speed into the learned rates (moving average, 30% weight). */
    private fun learn(s: Session) {
        if (steps.size < 3) return
        fun blend(old: Double, new: Double, n: Int) = if (n == 0) new else old * 0.7 + new * 0.3
        if (s.type == TYPE_CHARGE) {
            val low = steps.filter { it.pct <= CURVE_KNEE }.map { it.windowMs.toDouble() }
            val high = steps.filter { it.pct > CURVE_KNEE }.map { it.windowMs.toDouble() }
            val n = prefs.learnedCharges
            if (low.size >= 3) prefs.chargeLow = blend(prefs.chargeLow, low.average(), n)
            if (high.size >= 3) prefs.chargeHigh = blend(prefs.chargeHigh, high.average(), n)
            prefs.learnedCharges = n + 1
        } else {
            val n = prefs.learnedDischarges
            prefs.discharge = blend(prefs.discharge, steps.map { it.windowMs.toDouble() }.average(), n)
            prefs.learnedDischarges = n + 1
        }
    }
}
