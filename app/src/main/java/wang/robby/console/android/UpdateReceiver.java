package wang.robby.console.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

/**
 * Result of an in-app update session. Declared non-exported, so only the platform installer firing this app's own
 * PendingIntent can reach it. The confirmation screen it opens is the one the installer supplied for that session.
 */
public final class UpdateReceiver extends BroadcastReceiver {
    static final String ACTION = "wang.robby.console.android.UPDATE_STATUS";
    interface Listener { void onUpdateStatus(int status); }
    /** The visible activity, set while it exists; results for a gone activity are dropped. */
    static volatile Listener listener;

    @Override public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class) : legacyConfirm(intent);
            if (confirm != null) { confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(confirm); }
        }
        Listener l = listener; if (l != null) l.onUpdateStatus(status);
    }
    @SuppressWarnings("deprecation") private static Intent legacyConfirm(Intent intent) { return intent.getParcelableExtra(Intent.EXTRA_INTENT); }

    /** Person-facing text for a final installer status; null for statuses that need no message. */
    static String message(int status) {
        switch (status) {
            case PackageInstaller.STATUS_SUCCESS: case PackageInstaller.STATUS_PENDING_USER_ACTION: return null;
            case PackageInstaller.STATUS_FAILURE_ABORTED: return "已取消安装";
            case PackageInstaller.STATUS_FAILURE_BLOCKED: return "系统阻止了这次安装";
            case PackageInstaller.STATUS_FAILURE_CONFLICT: case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE: return "安装包与当前版本不兼容";
            case PackageInstaller.STATUS_FAILURE_STORAGE: return "存储空间不足";
            case PackageInstaller.STATUS_FAILURE_INVALID: return "安装包无效";
            default: return "安装未完成";
        }
    }
}
