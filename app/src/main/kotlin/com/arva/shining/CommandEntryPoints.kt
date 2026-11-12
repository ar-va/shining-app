package com.arva.shining

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles `shining://light?power=off`, `shining://light?preset=Warm&brightness=40`, etc. without
 * showing UI. Used by launcher shortcuts, NFC tags, and any app that can open a link.
 */
class CommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val command = LightCommand.parse(intent?.data?.encodedQuery)
        runCommand(applicationContext, command)
        finish()
    }
}

/**
 * Broadcast for automation apps (Tasker, MacroDroid, …):
 * action `com.arva.shining.action.LIGHT`, package `com.arva.shining`, with either an extra
 * `command` = "preset=Warm&brightness=40" or separate extras power/preset/brightness/hue/saturation/warmth.
 */
class CommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val extras = intent.extras ?: return
        val command = extras.getString("command")?.let { LightCommand.parse(it) }
            ?: LightCommand.fromMap(KEYS.associateWith { @Suppress("DEPRECATION") extras.get(it)?.toString() })
        val pending = goAsync()
        runCommand(context.applicationContext, command) { pending.finish() }
    }

    companion object {
        const val ACTION = "com.arva.shining.action.LIGHT"
        private val KEYS = listOf("power", "preset", "brightness", "hue", "saturation", "warmth")
    }
}

private fun runCommand(context: Context, command: LightCommand, done: () -> Unit = {}) {
    val app = context.lightApp
    if (command.isEmpty) {
        Toast.makeText(context, "Shining: nothing to do in that command", Toast.LENGTH_SHORT).show()
        done()
        return
    }
    app.appScope.launch {
        val result = runCatching { app.controller.run(command) }
        withContext(Dispatchers.Main) {
            result.exceptionOrNull()?.let {
                Toast.makeText(context, "Shining: ${it.message ?: "could not reach the light"}", Toast.LENGTH_LONG).show()
            }
        }
        done()
    }
}
