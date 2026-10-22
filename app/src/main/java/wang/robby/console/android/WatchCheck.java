package wang.robby.console.android;

/**
 * What the five-minute check does with its foreground service. The service is only in front while a read is running;
 * when the read finishes the status notification comes down and the service stops. The next exact alarm starts another
 * check. Pure Java so the decision and the wording can be tested without Android.
 */
final class WatchCheck {
    private WatchCheck() {}
    /** New channel id: an existing channel cannot lower its importance, and the old one said the notice stays up. */
    static final String CHANNEL_ID = "god-check-v1";
    /** {@link android.app.NotificationManager#IMPORTANCE_MIN}: present, but no sound, badge or heads-up. */
    static final int MIN_IMPORTANCE = 1;
    static final String TITLE = "God 正在检查提醒";
    static final String TEXT = "检查完成后这条通知会消失";
    static final boolean SILENT = true, ONLY_ALERT_ONCE = true;

    /** The check is over: take the foreground notification away and stop the service. */
    static final class End {
        final boolean removeForeground, stopService;
        End(boolean removeForeground, boolean stopService) { this.removeForeground = removeForeground; this.stopService = stopService; }
    }
    static End afterCheck() { return new End(true, true); }

    /** The only words the checking notification may show. */
    static final class Copy {
        final String channel, title, text; final boolean silent, onlyAlertOnce;
        Copy(String channel, String title, String text, boolean silent, boolean onlyAlertOnce) {
            this.channel = channel; this.title = title; this.text = text; this.silent = silent; this.onlyAlertOnce = onlyAlertOnce;
        }
        boolean mentionsLastCheck() { return (title + "\n" + text).contains("上次检查"); }
    }
    static Copy notification() { return new Copy(CHANNEL_ID, TITLE, TEXT, SILENT, ONLY_ALERT_ONCE); }
}
