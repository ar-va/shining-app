package com.arva.shining.automation

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.util.Log
import com.arva.shining.LightApp
import com.arva.shining.lightApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** Runs due automations, hands Wi-Fi arrivals to [Presence], and reschedules after boot/time changes. */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.lightApp
        when (intent.action) {
            Scheduler.ACTION_FIRE -> runAsync(app) { fire(app, intent) }
            Scheduler.ACTION_WIFI -> networkFrom(intent)?.let { network -> runAsync(app) { Presence.onWifiAvailable(app, network) } }
            Intent.ACTION_BOOT_COMPLETED -> {
                Presence.reset(context)
                Scheduler.rescheduleAll(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> Scheduler.rescheduleAll(context)
        }
    }

    private fun runAsync(app: LightApp, block: suspend () -> Unit) {
        val pending = goAsync()
        app.appScope.launch {
            try {
                withTimeout(40_000) { block() }
            } catch (e: Exception) {
                Log.w("Shining", "automation work failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun fire(app: LightApp, intent: Intent) {
        val store = app.automations
        val id = intent.getStringExtra(Scheduler.EXTRA_ID) ?: return
        val automation = store.get(id)?.takeIf { it.enabled }
        val scheduled = intent.getLongExtra(Scheduler.EXTRA_AT, System.currentTimeMillis())
        try {
            if (automation == null) return
            // A long-overdue alarm (phone was off) would switch the light at an odd time: skip it.
            if (abs(System.currentTimeMillis() - scheduled) > STALE_MS) {
                store.setResult(id, "Skipped ${stamp(scheduled)}: phone was off or asleep")
                return
            }
            runCatching { app.controller.run(automation.command) }
                .onSuccess { store.setResult(id, "Ran ${stamp(scheduled)}") }
                .onFailure { store.setResult(id, "Skipped ${stamp(scheduled)}: ${reason(it)}") }
        } finally {
            Scheduler.rescheduleAll(app)
        }
    }

    @Suppress("DEPRECATION")
    private fun networkFrom(intent: Intent): Network? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK, Network::class.java)
        } else {
            intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK)
        }

    private fun stamp(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE HH:mm"))

    private fun reason(e: Throwable): String = when {
        e.message?.contains("reach", ignoreCase = true) == true ||
            e.message?.contains("answer", ignoreCase = true) == true -> "light unreachable (not home?)"
        else -> e.message ?: "error"
    }

    private companion object {
        const val STALE_MS = 15 * 60_000L
    }
}
