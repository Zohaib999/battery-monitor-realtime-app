package com.zohaib.batterymonitor.core

import com.zohaib.batterymonitor.data.Estimate
import com.zohaib.batterymonitor.data.Session
import com.zohaib.batterymonitor.data.Step
import com.zohaib.batterymonitor.data.TYPE_CHARGE
import kotlin.math.abs
import kotlin.math.max

/** Charging slows down above this level, so it gets its own learned rate. */
const val CURVE_KNEE = 80

data class Rates(val low: Double, val high: Double)

data class Score(
    val estimate: Estimate,
    val targetPct: Int,
    val predictedAt: Long,
    val actualAt: Long,
    val errorMs: Long,
    val accuracy: Double,
)

object Estimator {

    /** Weighted average of the last [n] step durations; newer steps weigh more. */
    private fun weighted(steps: List<Step>, n: Int = 5): Double? {
        val recent = steps.takeLast(n)
        if (recent.isEmpty()) return null
        var sum = 0.0
        var w = 0.0
        recent.forEachIndexed { i, s ->
            sum += s.windowMs * (i + 1)
            w += (i + 1)
        }
        return sum / w
    }

    fun chargeRates(steps: List<Step>, learnedLow: Double, learnedHigh: Double): Rates {
        val low = weighted(steps.filter { it.pct <= CURVE_KNEE }) ?: learnedLow
        // Scale the learned slow part by how fast this charger is compared with usual.
        val high = weighted(steps.filter { it.pct > CURVE_KNEE })
            ?: (learnedHigh * (low / learnedLow)).coerceAtLeast(low)
        return Rates(low, high)
    }

    fun dischargeRate(steps: List<Step>, learned: Double): Double {
        if (steps.isEmpty()) return learned
        val recent = weighted(steps)!!
        val avg = steps.map { it.windowMs }.average()
        // Blend recent speed with the session average, plus some of the learned value
        // while the session is still short.
        val sessionPart = 0.5 * recent + 0.5 * avg
        val trust = (steps.size / 10.0).coerceAtMost(1.0)
        return trust * sessionPart + (1 - trust) * learned
    }

    /** Milliseconds to go from [from]% to [target]%. */
    fun timeToReach(type: String, from: Int, target: Int, rates: Rates): Double =
        if (type == TYPE_CHARGE) {
            var t = 0.0
            for (p in from until target) t += if (p < CURVE_KNEE) rates.low else rates.high
            t
        } else {
            max(0, from - target) * rates.low
        }

    /** Score each saved estimate against when the session's final level was actually reached. */
    fun score(session: Session, steps: List<Step>, estimates: List<Estimate>): List<Score> {
        val lastStep = steps.lastOrNull() ?: return emptyList()
        val target = lastStep.pct
        val actualAt = steps.first { it.pct == target }.ts
        return estimates.mapNotNull { e ->
            if (e.madeAt >= actualAt) return@mapNotNull null
            val moving = if (session.type == TYPE_CHARGE) target > e.madeAtPct else target < e.madeAtPct
            if (!moving) return@mapNotNull null
            val predictedAt = e.madeAt + timeToReach(session.type, e.madeAtPct, target, Rates(e.rateLow, e.rateHigh)).toLong()
            val err = predictedAt - actualAt
            val span = (actualAt - e.madeAt).coerceAtLeast(1)
            val acc = (100.0 - abs(err).toDouble() / span * 100.0).coerceIn(0.0, 100.0)
            Score(e, target, predictedAt, actualAt, err, acc)
        }
    }
}
