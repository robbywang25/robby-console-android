package wang.robby.console.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Alarm, acknowledgement and restart entry points of God's reminders and 心力球; every action is fixed and unexported. */
public final class TiboWatchReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction(); if (action == null) return;
        switch (action) {
            case TiboWatch.ACTION_ACK: TiboWatch.ack(context); break;
            case TiboWatch.ACTION_RERING: TiboWatch.rering(context); break;
            case NoticeCenter.ACTION_ACK: NoticeCenter.ack(context, intent.getStringExtra(NoticeCenter.EXTRA_KEY)); break;
            case NoticeCenter.ACTION_RERING: NoticeCenter.rering(context); break;
            case MindBall.ACTION_DISMISSED: MindBall.noticeDismissed = true; break;
            case TiboWatch.ACTION_CHECK: {
                if (!TiboWatch.active(context)) return;
                // The exact alarm lets the service start from the background; if it is still refused, read here.
                try { context.startForegroundService(new Intent(context, TiboWatchService.class).putExtra("check", true)); }
                catch (RuntimeException refused) {
                    PendingResult result = goAsync();
                    // A read already running does not call the callback, so the broadcast result still has to finish.
                    if (!TiboWatch.checkAsync(context, result::finish)) result.finish();
                }
                break; }
            case Intent.ACTION_BOOT_COMPLETED: case Intent.ACTION_MY_PACKAGE_REPLACED: NoticeCenter.resume(context); TiboWatch.start(context, true); MindBall.afterRestart(context); BowlSession.alarm(context); break;
            default: break;
        }
    }
}
