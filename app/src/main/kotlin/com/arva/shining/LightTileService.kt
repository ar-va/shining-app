package com.arva.shining

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.arva.tuya.LightDps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Quick Settings tile: one tap toggles the lightstrip without opening the app. */
class LightTileService : TileService() {
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch {
            val snap = withTimeoutOrNull(4_000) { runCatching { lightApp.controller.status() }.getOrNull() }
            render(snap?.state?.on, reachable = snap != null)
        }
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return
        val target = tile.state != Tile.STATE_ACTIVE
        render(target, reachable = true) // optimistic
        scope.launch {
            val ok = runCatching { lightApp.controller.send { LightDps.power(it, target) } }.isSuccess
            if (!ok) render(!target, reachable = false)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(on: Boolean?, reachable: Boolean) {
        val tile = qsTile ?: return
        tile.label = lightApp.store.load()?.name ?: getString(R.string.tile_label)
        tile.state = when {
            on == null || !reachable -> Tile.STATE_INACTIVE
            on -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            tile.subtitle = if (!reachable) "Unreachable" else null
        }
        tile.updateTile()
    }
}
