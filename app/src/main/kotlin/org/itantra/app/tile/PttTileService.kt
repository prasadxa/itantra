package org.itantra.app.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import org.itantra.app.MainActivity
import org.itantra.app.R

/**
 * Quick Settings tile: "iTantra PTT/SOS" — a one-tap shortcut from the shade straight to the talk
 * screen, so getting to PTT/SOS never depends on unlocking to the home screen and finding the app
 * icon. [MainActivity] always opens on the chat/talk screen (AppRoot's default `Screen.CHAT`)
 * once onboarding is done, so no extra intent payload is needed here — just launch it.
 *
 * This is an action tile (launches the app), not a toggle, so it always reports
 * [Tile.STATE_INACTIVE] rather than tracking an on/off state.
 */
class PttTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = getString(R.string.tile_ptt_label)
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ requires the PendingIntent overload — the raw-Intent overload throws
            // UnsupportedOperationException on this SDK.
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
