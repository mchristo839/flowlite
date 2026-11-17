package com.utomat.flowlite

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/** Quick Settings tile that starts or stops the dictation bubble. */
class BubbleTile : TileService() {
    override fun onStartListening() = paint(DictationService.running)

    override fun onClick() {
        if (DictationService.running) {
            stopService(Intent(this, DictationService::class.java)); paint(false); return
        }
        try {
            DictationService.start(this); paint(true)
        } catch (e: Exception) {
            // Android refused a background start: open the app, which starts the service from the foreground.
            Log.w("FlowLite", "Tile start failed", e)
            val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
            else @Suppress("DEPRECATION") startActivityAndCollapse(open)
        }
    }

    private fun paint(on: Boolean) {
        val t = qsTile ?: return
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }
}
