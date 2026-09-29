package com.zohaib.batterymonitor

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.zohaib.batterymonitor.core.Tracker
import com.zohaib.batterymonitor.data.AppDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    lateinit var db: AppDb
    lateinit var prefs: Prefs
    lateinit var tracker: Tracker
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = AppDb.build(this)
        prefs = Prefs(this)
        tracker = Tracker(this, db.dao(), prefs)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}

/** Learned rates (ms per 1%) and small UI settings. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var chargeLow: Double
        get() = sp.getFloat("chargeLow", 60_000f).toDouble()
        set(v) = sp.edit().putFloat("chargeLow", v.toFloat()).apply()
    var chargeHigh: Double
        get() = sp.getFloat("chargeHigh", 150_000f).toDouble()
        set(v) = sp.edit().putFloat("chargeHigh", v.toFloat()).apply()
    var discharge: Double
        get() = sp.getFloat("discharge", 240_000f).toDouble()
        set(v) = sp.edit().putFloat("discharge", v.toFloat()).apply()
    var learnedCharges: Int
        get() = sp.getInt("learnedCharges", 0)
        set(v) = sp.edit().putInt("learnedCharges", v).apply()
    var learnedDischarges: Int
        get() = sp.getInt("learnedDischarges", 0)
        set(v) = sp.edit().putInt("learnedDischarges", v).apply()

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()
    var lastPurge: Long
        get() = sp.getLong("lastPurge", 0)
        set(v) = sp.edit().putLong("lastPurge", v).apply()

    /** Apps the user keeps ticked on the Cleaner screen. */
    var killList: Set<String>
        get() = sp.getStringSet("killList", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("killList", v).apply()

    fun resetLearning() {
        sp.edit().remove("chargeLow").remove("chargeHigh").remove("discharge")
            .remove("learnedCharges").remove("learnedDischarges").apply()
    }
}
