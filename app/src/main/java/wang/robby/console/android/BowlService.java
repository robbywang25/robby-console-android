package wang.robby.console.android;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.util.Log;

/**
 * Keeps 颂钵 going after God leaves the screen: a media-playback foreground service with one ongoing notification
 * (phase, a system-driven countdown, 暂停 / 继续 and 停止). It holds no timer of its own: the exact alarm at the end of
 * a phase starts it with {@link BowlSession#ACTION_TICK}, and {@link BowlSession#timer} does the rest. It stops as soon as
 * neither the timer runs nor a sound plays.
 */
public final class BowlService extends Service {
    static boolean running;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        try {
            startForeground(BowlSession.ID_PLAYING, BowlSession.notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            running = true;
        } catch (RuntimeException refused) { Log.w(BowlSession.TAG, "foreground_refused"); }
        if (BowlSession.ACTION_STOP.equals(action)) BowlSession.stopAll(this);
        else if (BowlSession.ACTION_TOGGLE.equals(action)) BowlSession.toggleTimer(this);
        else BowlSession.timer(this);
        // Restarted by the system after its process was stopped: the ambient sound did not survive, the timer did.
        if (!BowlSession.needsService(this)) { stopSelf(); return START_NOT_STICKY; }
        return START_STICKY;
    }
    @Override public void onDestroy() { running = false; stopForeground(STOP_FOREGROUND_REMOVE); getSystemService(NotificationManager.class).cancel(BowlSession.ID_PLAYING); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
