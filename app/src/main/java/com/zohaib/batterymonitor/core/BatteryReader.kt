package com.zohaib.batterymonitor.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlin.math.abs

data class BatterySnapshot(
    val level: Int,
    val charging: Boolean,
    val plug: String,
    val tempC: Float?,
    val voltageMv: Int?,
    val currentMa: Int?,
)

object BatteryReader {

    fun read(context: Context, intent: Intent? = null): BatterySnapshot? {
        val i = intent ?: context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return null
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val plug = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            0 -> ""
            else -> "Dock"
        }
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val volt = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        val charging = plugged != 0
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        return BatterySnapshot(
            level = level * 100 / scale,
            charging = charging,
            plug = plug,
            tempC = if (temp == Int.MIN_VALUE) null else temp / 10f,
            voltageMv = if (volt > 0) volt else null,
            currentMa = normalizeCurrent(raw, charging),
        )
    }

    /**
     * Phones report current in µA or mA, with either sign. Large values are treated as µA,
     * and the sign follows the charging state. Returns null when the phone reports nothing.
     */
    fun normalizeCurrent(raw: Int, charging: Boolean): Int? {
        if (raw == 0 || raw == Int.MIN_VALUE || raw == Int.MAX_VALUE) return null
        val ma = if (abs(raw) > 10_000) raw / 1000 else raw
        if (ma == 0) return null
        return if (charging) abs(ma) else -abs(ma)
    }
}
