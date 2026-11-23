package com.arva.shining.automation

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.arva.shining.LightApp
import com.arva.shining.LightController
import com.arva.shining.lightApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Home/away tracking for "arriving home" automations.
 *
 * Android's PendingIntent network listener is one-shot: it fires once when a Wi-Fi network becomes
 * available, then the system releases it (ConnectivityService.sendPendingIntentForRequest). So:
 *  - a check every ~15 min decides HOME (on Wi-Fi and the light answers) or AWAY;
 *  - only while AWAY is the one-shot Wi-Fi listener armed;
 *  - when it fires and the light answers on that Wi-Fi, that is an arrival.
 * While HOME nothing is armed, so Wi-Fi blips at home never count as arriving. Whichever of these
 * first reaches the light (listener, periodic check, a failed alarm, opening the app) runs the queue.
 */
object Presence {
    enum class State { UNKNOWN, HOME, AWAY }

    private const val JOB_PERIODIC = 4101
    private const val JOB_NOW = 4102
    private const val ARRIVAL_COOLDOWN_MS = 30 * 60_000L
    private const val TAG = "Shining"

    /** Starts or stops tracking, depending on whether any arrival automation is enabled. */
    fun update(context: Context, enabled: Boolean) {
        val jobs = context.getSystemService(JobScheduler::class.java)
        val service = ComponentName(context, PresenceJobService::class.java)
        if (!enabled) {
            jobs.cancel(JOB_PERIODIC)
            jobs.cancel(JOB_NOW)
            runCatching { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(wifiIntent(context)) }
            return
        }
        if (jobs.getPendingJob(JOB_PERIODIC) == null) {
            jobs.schedule(JobInfo.Builder(JOB_PERIODIC, service).setPeriodic(15 * 60_000L).setPersisted(true).build())
        }
        jobs.schedule(JobInfo.Builder(JOB_NOW, service).setOverrideDeadline(1_000).build())
    }

    /** After a reboot network ids start over, so forget which connection was "home". */
    fun reset(context: Context) {
        val store = context.lightApp.automations
        store.presence = State.UNKNOWN
        store.homeNetwork = null
    }

    /** Every way of noticing home goes through here, one at a time, so nothing runs twice. */
    private val lock = Mutex()

    /** The app came to the front: if it can reach the light, anything queued runs now. */
    suspend fun onAppOpened(app: LightApp) {
        val tracking = app.automations.items.value.any { it.enabled && (it.trigger is Trigger.Arrive || it.catchUp) }
        if (tracking) check(app)
    }

    /** HOME if on Wi-Fi and the light answers, otherwise AWAY (and arm the listener). */
    suspend fun check(app: LightApp): Unit = lock.withLock {
        val store = app.automations
        val wifi = currentWifi(app)
        Log.i(TAG, "check: wifi=$wifi presence=${store.presence} home=${store.homeNetwork}")
        if (wifi != null) {
            // Same connection we already know is home, nothing queued: no traffic to the light.
            val settled = store.presence == State.HOME && store.homeNetwork == wifi.toString()
            if (settled && store.pendingCatchUp().isEmpty()) return@withLock
            val snapshot = reach(app, wifi, waits = listOf(0L))
            if (snapshot != null) {
                onHome(app, wifi, snapshot)
                return@withLock
            }
        }
        store.presence = State.AWAY
        armWifiListener(app)
        Log.i(TAG, "check: away, Wi-Fi listener armed")
    }

    /** The one-shot listener fired: a Wi-Fi network became available. */
    suspend fun onWifiAvailable(app: LightApp, network: Network): Unit = lock.withLock {
        val store = app.automations
        Log.i(TAG, "wifi available: $network presence=${store.presence} home=${store.homeNetwork}")
        val settled = store.presence == State.HOME && store.homeNetwork == network.toString()
        if (settled && store.pendingCatchUp().isEmpty()) return@withLock
        val snapshot = reach(app, network, waits = listOf(2_000L, 5_000L, 8_000L))
        if (snapshot == null) {
            Log.i(TAG, "wifi available: light not reachable on $network, not home")
            return@withLock // another Wi-Fi (or the light is unplugged); the periodic check re-arms later
        }
        onHome(app, network, snapshot)
    }

    /**
     * The light answered on [network]. Queued runs always go now. Arrival automations need a real
     * return: away, on a new connection (network ids change on reconnect), so a blip at home or a
     * light that was briefly unplugged doesn't count as coming home.
     */
    private suspend fun onHome(app: LightApp, network: Network, snapshot: LightController.Snapshot) {
        val store = app.automations
        val arrived = store.presence == State.AWAY && store.homeNetwork != network.toString()
        store.presence = State.HOME
        store.homeNetwork = network.toString()
        Log.i(TAG, "home on $network, arrived=$arrived")
        runCatchUp(app, network)
        if (arrived) runArrivals(app, snapshot, network)
    }

    /** Applies runs missed while away. One that fails stays queued for the next attempt. */
    private suspend fun runCatchUp(app: LightApp, network: Network) {
        val store = app.automations
        val now = System.currentTimeMillis()
        val pending = store.pendingCatchUp()
        if (pending.isEmpty()) return
        val plan = CatchUp.plan(pending, store.items.value, now)
        Log.i(TAG, "catch-up: run=${plan.run.size} replaced=${plan.replaced.size} expired=${plan.expired.size}")
        plan.gone.forEach(store::clearPending)
        plan.expired.forEach {
            store.clearPending(it.automation.id)
            store.setResult(it.automation.id, "Missed ${CatchUp.stamp(it.scheduled)}. Too long ago to catch up")
        }
        plan.replaced.forEach {
            store.clearPending(it.automation.id)
            store.setResult(it.automation.id, "Missed ${CatchUp.stamp(it.scheduled)}. A later automation took over")
        }
        for (m in plan.run) {
            val id = m.automation.id
            val ok = runCatching { app.controller.run(m.automation.command, network, automated = true) }.isSuccess
            if (!ok) {
                Log.i(TAG, "catch-up: $id failed, kept for the next attempt")
                return
            }
            store.clearPending(id)
            store.setResult(id, "Ran ${CatchUp.stamp(now)}, caught up from ${CatchUp.stamp(m.scheduled)}")
        }
    }

    private suspend fun runArrivals(app: LightApp, snapshot: LightController.Snapshot, network: Network) {
        val store = app.automations
        val now = System.currentTimeMillis()
        if (now - store.lastArrivalMillis < ARRIVAL_COOLDOWN_MS) {
            Log.i(TAG, "arrival: within cooldown, skipped")
            return
        }
        val candidates = store.items.value.filter { it.arrivalAllowed(ZonedDateTime.now(), store.location) }
        Log.i(TAG, "arrival: ${candidates.size} automation(s) allowed now")
        if (candidates.isEmpty()) return
        store.lastArrivalMillis = now
        val stamp = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE HH:mm"))
        for (a in candidates) {
            if (snapshot.state?.on == true) {
                store.setResult(a.id, "Skipped $stamp: light was already on")
                continue
            }
            runCatching { app.controller.run(a.command, network, automated = true) }
                .onSuccess { store.setResult(a.id, "Ran $stamp (arrived home)") }
                .onFailure { store.setResult(a.id, "Skipped $stamp: ${it.message ?: "error"}") }
        }
    }

    /** Reads the light over [network], retrying while a fresh connection settles. */
    private suspend fun reach(app: LightApp, network: Network, waits: List<Long>): LightController.Snapshot? {
        for (wait in waits) {
            if (wait > 0) delay(wait)
            runCatching { app.controller.status(network) }
                .onFailure { Log.i(TAG, "light check on $network failed: ${it.message}") }
                .getOrNull()?.let { return it }
        }
        return null
    }

    private fun currentWifi(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val active = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(active) ?: return null
        return if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) active else null
    }

    private fun armWifiListener(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val pi = wifiIntent(context)
        runCatching { cm.unregisterNetworkCallback(pi) }
        cm.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            pi,
        )
    }

    /** Mutable: Android adds the Network to the intent. */
    private fun wifiIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 1,
        Intent(context, AutomationReceiver::class.java).setAction(Scheduler.ACTION_WIFI),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Runs [Presence.check] for the periodic and the immediate job. */
class PresenceJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = lightApp
        app.appScope.launch {
            runCatching { Presence.check(app) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
