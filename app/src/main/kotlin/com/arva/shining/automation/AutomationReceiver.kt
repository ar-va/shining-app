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
                Scheduler.rescheduleAll(context, alarmsCleared = true)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> Scheduler.rescheduleAll(context, alarmsCleared = true)
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
        store.setDueAt(id, null)
        try {
            if (automation == null) return
            // A long-overdue alarm (phone was off) would switch the light at an odd time: skip it.
            if (abs(System.currentTimeMillis() - scheduled) > CatchUp.LATE_MS) {
                missed(app, automation, scheduled, "phone was off or asleep")
                return
            }
            runCatching { app.controller.run(automation.command, automated = true) }
                .onSuccess {
                    store.setResult(id, "Ran ${CatchUp.stamp(scheduled)}")
                    store.clearPendingUpTo(scheduled)
                }
                .onFailure { missed(app, automation, scheduled, reason(it)) }
        } finally {
            Scheduler.rescheduleAll(app)
        }
    }

    /**
     * Queues a missed run. The check right after it matters: it marks the phone away and arms the
     * Wi-Fi listener now, rather than whenever the next periodic check happens to run.
     */
    private suspend fun missed(app: LightApp, automation: Automation, scheduled: Long, why: String) {
        app.automations.recordMissed(automation, scheduled, why)
        if (automation.catchUp) Presence.check(app)
    }

    @Suppress("DEPRECATION")
    private fun networkFrom(intent: Intent): Network? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK, Network::class.java)
        } else {
            intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK)
        }

    private fun reason(e: Throwable): String = when {
        e.message?.contains("reach", ignoreCase = true) == true ||
            e.message?.contains("answer", ignoreCase = true) == true -> "light unreachable (not home?)"
        else -> e.message ?: "error"
    }
}
