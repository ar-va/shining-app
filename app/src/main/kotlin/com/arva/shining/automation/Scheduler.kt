package com.arva.shining.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import com.arva.shining.lightApp
import java.time.ZonedDateTime

/**
 * Turns saved automations into Android alarms (time and sun triggers) and home/away tracking
 * (arrival triggers, see [Presence]). Call [rescheduleAll] after any change, at app start and after boot.
 */
object Scheduler {
    const val ACTION_FIRE = "com.arva.shining.automation.FIRE"
    const val ACTION_WIFI = "com.arva.shining.automation.WIFI"
    const val EXTRA_ID = "id"
    const val EXTRA_AT = "at"

    fun rescheduleAll(context: Context, alarmsCleared: Boolean = false) {
        val app = context.lightApp
        val store = app.automations
        val alarms = context.getSystemService(AlarmManager::class.java)
        val now = ZonedDateTime.now()
        val nowMillis = now.toInstant().toEpochMilli()
        for (a in store.items.value) {
            val due = store.dueAt(a.id)
            when (CatchUp.dueAlarm(due, nowMillis, alarmsCleared)) {
                // Replacing an alarm Android still owes us would move this run to tomorrow.
                CatchUp.DueAlarm.STILL_COMING -> continue
                CatchUp.DueAlarm.LOST -> store.recordMissed(a, due!!, "phone was off or asleep")
                CatchUp.DueAlarm.NOT_DUE -> {}
            }
            val pi = firePendingIntent(context, a.id, null)
            alarms.cancel(pi)
            val at = a.nextRun(now, store.location)?.toInstant()?.toEpochMilli()
            store.setDueAt(a.id, at)
            if (at == null) continue
            val intent = firePendingIntent(context, a.id, at)
            if (canScheduleExact(context)) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            } else {
                // Without "Alarms & reminders" access Android may deliver up to a few minutes late.
                alarms.setWindow(AlarmManager.RTC_WAKEUP, at, 5 * 60_000L, intent)
            }
        }
        // Track home/away when any arrival automation OR any catch-up automation is enabled —
        // both need to know when the phone reconnects to the home Wi-Fi.
        Presence.update(
            context,
            store.items.value.any { it.enabled && (it.trigger is Trigger.Arrive || it.catchUp) },
        )
    }

    fun cancel(context: Context, id: String) {
        context.getSystemService(AlarmManager::class.java).cancel(firePendingIntent(context, id, null))
    }

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** One alarm per automation; the id is in the data URI so each gets its own PendingIntent. */
    private fun firePendingIntent(context: Context, id: String, at: Long?): PendingIntent {
        val intent = Intent(context, AutomationReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData("shining-automation://$id".toUri())
            .putExtra(EXTRA_ID, id)
        if (at != null) intent.putExtra(EXTRA_AT, at)
        return PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
