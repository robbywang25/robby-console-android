package wang.robby.console.android;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * God in 快捷设置: one tap opens the app; when the phone is locked the system asks to unlock first. The subtitle shows
 * the same public Tibo line as the widget, never account data.
 */
public final class GodTileService extends TileService {
    @Override public void onStartListening() {
        Tile tile = getQsTile(); if (tile == null) return;
        String status = GodWidget.shortStatus(this);
        tile.setLabel("God"); tile.setState(Tile.STATE_INACTIVE); tile.setContentDescription("打开 God，" + status);
        tile.setSubtitle(status);
        tile.updateTile();
    }
    @Override public void onClick() { if (isLocked()) unlockAndRun(this::open); else open(); }
    // Android 14+ takes the PendingIntent form; 11–13 have only the Intent form, so that call stays behind the version check.
    @SuppressWarnings("deprecation") @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private void open() {
        Intent intent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 13, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        else startActivityAndCollapse(intent);
    }
}
