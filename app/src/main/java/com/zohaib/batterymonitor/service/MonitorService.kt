package com.zohaib.batterymonitor.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.zohaib.batterymonitor.App
import com.zohaib.batterymonitor.R
import com.zohaib.batterymonitor.core.BatteryReader
import com.zohaib.batterymonitor.core.LiveStatus
import com.zohaib.batterymonitor.data.TYPE_CHARGE
import com.zohaib.batterymonitor.ui.MainActivity
import com.zohaib.batterymonitor.ui.fmtMinutes
import com.zohaib.batterymonitor.ui.fmtTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keeps tracking alive in the background, with a notification showing % and ETA. */
class MonitorService : LifecycleService() {

    private val app get() = application as App
    private var lastText = ""
    private var lastLevel = -1
    private var lastPlugged = -1

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // This broadcast fires on every small voltage/temperature change. Only a level or
            // plug change matters for tracking, so everything else is dropped here cheaply.
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            if (level == lastLevel && plugged == lastPlugged) return
            lastLevel = level
            lastPlugged = plugged
            val snap = BatteryReader.read(context, intent) ?: return
            lifecycleScope.launch { app.tracker.onBattery(snap) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIF_ID, build(LiveStatus()),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        // Sticky broadcast: delivers the current state immediately, then every change.
        registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        lifecycleScope.launch {
            val pm = getSystemService(PowerManager::class.java)
            while (true) {
                app.tracker.tick(publish = pm.isInteractive)
                delay(if (pm.isInteractive) 30_000 else 120_000)
            }
        }
        lifecycleScope.launch {
            app.tracker.live.collect { update(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Battery monitor", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
    }

    private fun text(s: LiveStatus): String {
        val b = s.battery ?: return "Starting…"
        val charging = s.session?.type == TYPE_CHARGE
        val eta = s.remainingMs?.let {
            if (charging) "full in ${fmtMinutes(it)} (${fmtTime(s.etaAt!!)})"
            else "~${fmtMinutes(it)} left (${fmtTime(s.etaAt!!)})"
        } ?: "measuring…"
        return "${b.level}% · ${if (charging) "Charging" else "On battery"} · $eta"
    }

    private fun build(s: LiveStatus) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_battery)
        .setContentTitle("Battery Monitor")
        .setContentText(text(s))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        .build()

    private fun update(s: LiveStatus) {
        if (!getSystemService(PowerManager::class.java).isInteractive) return
        val t = text(s)
        if (t == lastText) return
        lastText = t
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(s))
    }

    companion object {
        private const val CHANNEL = "monitor"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        }
    }
}
