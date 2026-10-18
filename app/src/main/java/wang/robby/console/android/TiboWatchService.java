package wang.robby.console.android;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Keeps Tibo 重置提醒 alive while it is switched on: ColorOS stops background processes that hold no foreground
 * service, and a foreground service keeps network access in Doze. Each start may ask for one read at once.
 */
public final class TiboWatchService extends Service {
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!TiboWatch.enabled(this)) { stopSelf(); return START_NOT_STICKY; }
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(TiboWatch.ID_WATCH, TiboWatch.watchNotification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(TiboWatch.ID_WATCH, TiboWatch.watchNotification(this));
        } catch (RuntimeException refused) {
            // Started from the background without an exemption: the exact alarm keeps checking instead.
            Log.w("GodTibo", "foreground_refused"); TiboWatch.schedule(this, System.currentTimeMillis() + 1_000L); stopSelf(); return START_NOT_STICKY;
        }
        if (intent == null || intent.getBooleanExtra("check", false)) TiboWatch.checkAsync(this, null); else TiboWatch.ensureScheduled(this);
        return START_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
