package com.arva.shining

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.controls.Control
import android.service.controls.ControlsProviderService
import android.service.controls.DeviceTypes
import android.service.controls.actions.BooleanAction
import android.service.controls.actions.ControlAction
import android.service.controls.actions.FloatAction
import android.service.controls.templates.ControlButton
import android.service.controls.templates.RangeTemplate
import android.service.controls.templates.ToggleRangeTemplate
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Flow
import java.util.function.Consumer

/**
 * Android "Device controls" (Android 11+): the strip appears next to other smart-home tiles,
 * with on/off and a brightness slider. Uses the app-wide [LightController], so it shares the
 * single local connection with the app and the Quick Settings tile.
 */
@RequiresApi(Build.VERSION_CODES.R)
class LightControlsService : ControlsProviderService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val subscribers = CopyOnWriteArrayList<Flow.Subscriber<in Control>>()

    @Volatile private var last: LightController.Snapshot? = null
    @Volatile private var error: String? = null

    /** Shown when the user adds controls: one light, if the app is set up. */
    override fun createPublisherForAllAvailable(): Flow.Publisher<Control> {
        val cfg = lightApp.store.load()?.takeIf { it.isComplete }
        val controls = if (cfg == null) emptyList() else listOf(
            Control.StatelessBuilder(CONTROL_ID, openAppIntent())
                .setTitle(cfg.name)
                .setSubtitle(getString(R.string.app_name))
                .setDeviceType(DeviceTypes.TYPE_LIGHT)
                .build()
        )
        return Flow.Publisher { subscriber ->
            subscriber.onSubscribe(NoopSubscription)
            controls.forEach(subscriber::onNext)
            subscriber.onComplete()
        }
    }

    /** Live state for the controls the user added. Stays open while the panel is visible. */
    override fun createPublisherFor(controlIds: List<String>): Flow.Publisher<Control> =
        Flow.Publisher { subscriber ->
            subscriber.onSubscribe(object : Flow.Subscription {
                override fun request(n: Long) {}
                override fun cancel() { subscribers.remove(subscriber) }
            })
            if (CONTROL_ID !in controlIds) {
                subscriber.onComplete()
                return@Publisher
            }
            subscribers += subscriber
            subscriber.onNext(stateful(loading = last == null))
            refresh()
        }

    override fun performControlAction(controlId: String, action: ControlAction, consumer: Consumer<Int>) {
        val command = when (action) {
            is BooleanAction -> LightCommand(power = if (action.newState) LightCommand.Power.ON else LightCommand.Power.OFF)
            // Brightness only: the driver keeps the current colour/white mode.
            is FloatAction -> LightCommand(brightness = action.newValue.toInt().coerceIn(1, 100))
            else -> {
                consumer.accept(ControlAction.RESPONSE_FAIL)
                return
            }
        }
        consumer.accept(ControlAction.RESPONSE_OK)
        scope.launch {
            runCatching { lightApp.controller.run(command) }
                .onFailure { error = it.message }
            refresh()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        subscribers.clear()
        super.onDestroy()
    }

    private fun refresh() {
        scope.launch {
            runCatching { lightApp.controller.status() }
                .onSuccess { last = it; error = null }
                .onFailure { error = it.message ?: "Unreachable" }
            val control = stateful(loading = false)
            subscribers.forEach { it.onNext(control) }
        }
    }

    private fun stateful(loading: Boolean): Control {
        val name = lightApp.store.load()?.name ?: getString(R.string.app_name)
        val state = last?.state
        val on = state?.on == true
        val percent = ((state?.brightness ?: 1f) * 100f).coerceIn(1f, 100f)
        val template = ToggleRangeTemplate(
            "power_brightness",
            ControlButton(on, if (on) "Turn off" else "Turn on"),
            RangeTemplate("brightness", 1f, 100f, percent, 1f, "%.0f%%"),
        )
        val builder = Control.StatefulBuilder(CONTROL_ID, openAppIntent())
            .setTitle(name)
            .setSubtitle(getString(R.string.app_name))
            .setDeviceType(DeviceTypes.TYPE_LIGHT)
            .setControlTemplate(template)
            .setStatus(
                when {
                    loading -> Control.STATUS_UNKNOWN
                    error != null -> Control.STATUS_ERROR
                    else -> Control.STATUS_OK
                }
            )
            .setStatusText(
                when {
                    loading -> ""
                    error != null -> "Unreachable"
                    on -> "On"
                    else -> "Off"
                }
            )
        // Lets the user toggle from the lock screen (if "Control from locked device" is on).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) builder.setAuthRequired(false)
        return builder.build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private object NoopSubscription : Flow.Subscription {
        override fun request(n: Long) {}
        override fun cancel() {}
    }

    companion object {
        const val CONTROL_ID = "lightstrip"
    }
}
