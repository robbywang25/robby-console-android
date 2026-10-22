package wang.robby.console.android;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * In front only while one five-minute check runs. The exact alarm starts it; when the read finishes the status
 * notification comes down and the service stops. A second start during that read is joined, not dropped: stopping
 * the service under a newer start would remove the foreground notification that start still needs.
 */
public final class TiboWatchService extends Service {
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean busy, again;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!TiboWatch.active(this)) { stopSelf(); return START_NOT_STICKY; }
        try {
            Notification n = TiboWatch.checkingNotification(this);
            if (Build.VERSION.SDK_INT >= 34) startForeground(TiboWatch.ID_WATCH, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(TiboWatch.ID_WATCH, n);
        } catch (RuntimeException refused) {
            // Started from the background without an exemption: the exact alarm keeps checking instead.
            Log.w("GodTibo", "foreground_refused"); TiboWatch.schedule(this, System.currentTimeMillis() + 1_000L); stopSelf(); return START_NOT_STICKY;
        }
        boolean check = intent == null || intent.getBooleanExtra("check", true);
        if (!check) { TiboWatch.ensureScheduled(this); finishCheck(); return START_NOT_STICKY; }
        // Both this and the completion callback run on the main thread, so a newer start cannot be stopped under itself.
        if (busy) { again = true; return START_NOT_STICKY; }
        busy = true;
        if (!TiboWatch.checkAsync(this, () -> main.post(this::afterCheck))) { busy = false; finishCheck(); }
        return START_NOT_STICKY;
    }
    private void afterCheck() {
        if (again && TiboWatch.active(this)) { again = false; if (TiboWatch.checkAsync(this, () -> main.post(this::afterCheck))) return; }
        busy = false; again = false; finishCheck();
    }
    private void finishCheck() {
        WatchCheck.End end = WatchCheck.afterCheck();
        if (end.removeForeground) stopForeground(STOP_FOREGROUND_REMOVE);
        if (end.stopService) stopSelf();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
