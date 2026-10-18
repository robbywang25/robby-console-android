package wang.robby.console.android;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;
import org.json.JSONObject;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import wang.robby.console.android.auth.AuthSession;

/**
 * Tibo 重置提醒. Without Google push on the phone, a foreground service and exact alarms read tokens/tibo/alerts every
 * five minutes (the server's own monitor interval). A new reset or card-grant announcement rings like an alarm: alarm
 * sound repeating, full screen on the lock screen, until 知道了 — re-ringing every ten minutes for at most three rounds.
 * Stored on the phone: the switch, check times and the ids of public posts already announced; never account data.
 */
final class TiboWatch {
    private TiboWatch() {}
    static final String CH_ALARM = "tibo-reset-alarm-v1", CH_WATCH = "tibo-watch-v1";
    static final String ACTION_CHECK = "wang.robby.console.android.TIBO_CHECK", ACTION_RERING = "wang.robby.console.android.TIBO_RERING", ACTION_ACK = "wang.robby.console.android.TIBO_ACK";
    static final String EXTRA_OPEN = "wang.robby.console.android.OPEN_FEATURE";
    static final int ID_WATCH = 7101, ID_ALARM = 7102, ID_NOTICE = 7103;
    static final long INTERVAL = 5 * 60_000L, RERING = 10 * 60_000L; static final int ROUNDS = 3;
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences("tibo-watch", Context.MODE_PRIVATE); }
    static boolean enabled(Context c) { return prefs(c).getBoolean("enabled", false); }

    static void enable(Context c) {
        prefs(c).edit().putBoolean("enabled", true).putLong("enabledAt", System.currentTimeMillis()).putBoolean("primed", false).remove("seen").remove("lastCheckAt").remove("lastError").apply();
        start(c, true);
    }
    static void disable(Context c) {
        prefs(c).edit().putBoolean("enabled", false).remove("pending").remove("round").apply();
        AlarmManager alarms = c.getSystemService(AlarmManager.class); alarms.cancel(pending(c, ACTION_CHECK)); alarms.cancel(pending(c, ACTION_RERING));
        c.stopService(new Intent(c, TiboWatchService.class));
        NotificationManager nm = c.getSystemService(NotificationManager.class); nm.cancel(ID_ALARM); nm.cancel(ID_WATCH); nm.cancel(ID_NOTICE);
    }
    /** Starts (or keeps) the monitoring service; {@code check} asks it to read now. Refused starts fall back to the alarm. */
    static void start(Context c, boolean check) {
        if (!enabled(c)) return;
        try { c.startForegroundService(new Intent(c, TiboWatchService.class).putExtra("check", check)); }
        catch (RuntimeException refused) { Log.w("GodTibo", "service_start_refused"); schedule(c, System.currentTimeMillis() + (check ? 1_000L : INTERVAL)); }
    }

    /** The last read is older than one interval, or there has been none. */
    static boolean checkDue(Context c) { return System.currentTimeMillis() - prefs(c).getLong("lastCheckAt", 0) >= INTERVAL; }
    /** Keeps one pending check alarm without pushing an existing one later. */
    static void ensureScheduled(Context c) {
        PendingIntent existing = PendingIntent.getBroadcast(c, ACTION_CHECK.hashCode(), new Intent(c, TiboWatchReceiver.class).setAction(ACTION_CHECK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE);
        if (existing == null) schedule(c, System.currentTimeMillis() + INTERVAL);
    }
    static PendingIntent pending(Context c, String action) {
        return PendingIntent.getBroadcast(c, action.hashCode(), new Intent(c, TiboWatchReceiver.class).setAction(action), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
    static void schedule(Context c, long at) { alarm(c, at, ACTION_CHECK); }
    private static void alarm(Context c, long at, String action) {
        AlarmManager alarms = c.getSystemService(AlarmManager.class); PendingIntent p = pending(c, action);
        // Exact while-idle alarms keep firing in Doze; without the exact permission the system may batch them.
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
    }

    /** One read on a background thread under a short wake lock; {@code done} runs when it has finished. */
    static void checkAsync(Context context, Runnable done) {
        Context c = context.getApplicationContext();
        if (!RUNNING.compareAndSet(false, true)) { if (done != null) done.run(); return; }
        PowerManager.WakeLock lock = c.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "God:TiboWatch"); lock.acquire(60_000L);
        new Thread(() -> {
            try { check(c); }
            finally { RUNNING.set(false); if (lock.isHeld()) lock.release(); if (done != null) done.run(); }
        }, "tibo-watch").start();
    }
    // Runs on its own thread; commit() so what was announced is on disk before the process may be stopped.
    @android.annotation.SuppressLint("ApplySharedPref")
    private static void check(Context c) {
        SharedPreferences p = prefs(c); if (!p.getBoolean("enabled", false)) return;
        long now = System.currentTimeMillis();
        try {
            AuthSession auth = new AuthSession(c);
            if (!auth.hasSession()) { record(p, now, "登录后才能检查"); return; }
            List<TiboAlert> alerts = TiboAlert.parse(new MobileApi(auth).get("tokens/tibo/alerts"));
            Set<String> seen = new HashSet<>(p.getStringSet("seen", Collections.emptySet()));
            List<TiboAlert> due = TiboAlert.due(alerts, seen, p.getBoolean("primed", false), p.getLong("enabledAt", now), now);
            if (seen.size() > 400) seen.clear(); for (TiboAlert a : alerts) seen.add(a.id);
            p.edit().putStringSet("seen", seen).putBoolean("primed", true).putLong("lastCheckAt", now).remove("lastError").commit();
            Log.i("GodTibo", "check ok alerts=" + alerts.size() + " due=" + due.size());
            if (!due.isEmpty()) ring(c, due.get(0), 1, false, due.size() - 1);
        } catch (Exception e) {
            String reason = e instanceof MobileApi.ApiError ? (((MobileApi.ApiError) e).status == 401 ? "登录已失效，请重新登录" : e.getMessage()) : e instanceof java.io.IOException ? "网络暂不可用" : "暂时无法检查";
            Log.w("GodTibo", "check failed " + (e instanceof MobileApi.ApiError ? "http=" + ((MobileApi.ApiError) e).status : e.getClass().getSimpleName()));
            record(p, now, reason);
        } finally {
            if (p.getBoolean("enabled", false)) { schedule(c, System.currentTimeMillis() + INTERVAL); refreshWatch(c); }
        }
    }
    @android.annotation.SuppressLint("ApplySharedPref")
    private static void record(SharedPreferences p, long now, String error) { p.edit().putLong("lastCheckAt", now).putString("lastError", error).commit(); }

    static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel alarm = new NotificationChannel(CH_ALARM, "Tibo 重置提醒", NotificationManager.IMPORTANCE_HIGH);
        alarm.setDescription("Tibo 发出重置或发卡预告时，像闹钟一样持续响铃");
        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM); if (sound == null) sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        alarm.setSound(sound, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        alarm.enableVibration(true); alarm.setVibrationPattern(new long[]{0, 900, 500, 900, 500, 900}); alarm.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC); alarm.setBypassDnd(true);
        NotificationChannel watch = new NotificationChannel(CH_WATCH, "Tibo 监测状态", NotificationManager.IMPORTANCE_MIN);
        watch.setDescription("开启重置提醒时常驻，显示上次检查时间"); watch.setShowBadge(false);
        nm.createNotificationChannels(List.of(alarm, watch));
    }

    /** Rings for {@code alert}: round 1..3 repeat the alarm sound until acknowledged; later rounds stay silent. */
    static void ring(Context context, TiboAlert alert, int round, boolean test, int more) {
        Context c = context.getApplicationContext(); channels(c); long now = System.currentTimeMillis();
        Intent screen = new Intent(c, TiboAlarmActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION).putExtra("alert", alert.json().toString()).putExtra("test", test);
        PendingIntent full = PendingIntent.getActivity(c, 1, screen, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent post = PendingIntent.getActivity(c, 2, new Intent(screen).putExtra("open", alert.url), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent ack = pending(c, ACTION_ACK);
        String title = (test ? "试响 · " : "") + alert.heading() + (more > 0 ? " 等 " + (more + 1) + " 条" : "");
        Notification.Builder b = new Notification.Builder(c, CH_ALARM).setSmallIcon(R.drawable.ic_stat_tibo).setContentTitle(title)
            .setContentText(alert.when(ZoneId.systemDefault(), now)).setStyle(new Notification.BigTextStyle().bigText(alert.body(ZoneId.systemDefault(), now)))
            .setCategory(Notification.CATEGORY_ALARM).setVisibility(Notification.VISIBILITY_PUBLIC).setColor(0xffef4444).setWhen(alert.published).setShowWhen(true)
            .setOngoing(true).setAutoCancel(false).setContentIntent(full).setDeleteIntent(ack).setOnlyAlertOnce(round > ROUNDS)
            .addAction(new Notification.Action.Builder(null, "知道了", ack).build()).addAction(new Notification.Action.Builder(null, "查看原帖", post).build());
        if (round <= ROUNDS) b.setFullScreenIntent(full, true);
        Notification n = b.build(); if (round <= ROUNDS) n.flags |= Notification.FLAG_INSISTENT;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (round > 1 && round <= ROUNDS) nm.cancel(ID_ALARM);
        nm.notify(ID_ALARM, n);
        prefs(c).edit().putString("pending", alert.json().toString()).putInt("round", round).putBoolean("pendingTest", test).putInt("more", more).apply();
        if (round <= ROUNDS) alarm(c, now + RERING, ACTION_RERING);
    }
    /** Ten minutes on and still not acknowledged: ring the next round, or leave it silent after the last. */
    static void rering(Context c) {
        SharedPreferences p = prefs(c); String raw = p.getString("pending", null); if (raw == null) return;
        try { TiboAlert a = TiboAlert.from(new JSONObject(raw)); if (a != null) ring(c, a, p.getInt("round", 1) + 1, p.getBoolean("pendingTest", false), p.getInt("more", 0)); }
        catch (org.json.JSONException e) { ack(c); }
    }
    static void ack(Context c) {
        prefs(c).edit().remove("pending").remove("round").remove("pendingTest").remove("more").apply();
        c.getSystemService(AlarmManager.class).cancel(pending(c, ACTION_RERING));
        c.getSystemService(NotificationManager.class).cancel(ID_ALARM);
    }
    /** A sample alarm so the sound, vibration and lock-screen page can be checked; it changes nothing else. */
    static void test(Context c) {
        long now = System.currentTimeMillis();
        ring(c, new TiboAlert("0000000000000000000", "reset-scheduled", "试响：这是一次提醒演示", "Tibo 发出重置预告时会这样提醒你，直到你点「知道了」。", "https://x.com/thsottiaux/status/0000000000000000000", now, now + 3 * 3600_000L, false), 1, true, 0);
    }

    static Notification watchNotification(Context context) {
        Context c = context.getApplicationContext(); channels(c);
        Intent open = new Intent(c, MainActivity.class).putExtra(EXTRA_OPEN, "tibo").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return new Notification.Builder(c, CH_WATCH).setSmallIcon(R.drawable.ic_stat_tibo).setContentTitle("Tibo 重置提醒已开启").setContentText(status(c))
            .setContentIntent(PendingIntent.getActivity(c, 3, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
            .setOngoing(true).setShowWhen(false).setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PUBLIC).build();
    }
    private static void refreshWatch(Context c) {
        if (!enabled(c)) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        for (android.service.notification.StatusBarNotification s : nm.getActiveNotifications()) if (s.getId() == ID_WATCH) { nm.notify(ID_WATCH, watchNotification(c)); return; }
    }
    /** 上次检查 HH:mm · 正常, or the reason the last check failed. */
    static String status(Context c) {
        SharedPreferences p = prefs(c); long at = p.getLong("lastCheckAt", 0); String error = p.getString("lastError", null);
        if (at == 0) return "等待第一次检查 · 每 5 分钟一次";
        String time = java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(java.time.Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()));
        return error == null ? "上次检查 " + time + " · 正常 · 每 5 分钟一次" : "上次检查 " + time + " · " + error;
    }
    /** What still stands between an announcement and a ringing phone, each with the settings page that fixes it. */
    static List<String[]> problems(Context c) {
        List<String[]> out = new ArrayList<>(); NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED || !nm.areNotificationsEnabled())
            out.add(new String[]{"通知未开启，无法响铃", "notifications"});
        else { NotificationChannel ch = nm.getNotificationChannel(CH_ALARM); if (ch != null && ch.getImportance() < NotificationManager.IMPORTANCE_HIGH) out.add(new String[]{"「Tibo 重置提醒」通知类别被调低，不会响铃", "channel"}); }
        if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) out.add(new String[]{"未允许全屏提醒，锁屏时只显示横幅", "fullscreen"});
        if (!c.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(c.getPackageName())) out.add(new String[]{"仍受电池优化限制，后台检查可能被推迟", "battery"});
        if (Build.VERSION.SDK_INT >= 31 && !c.getSystemService(AlarmManager.class).canScheduleExactAlarms()) out.add(new String[]{"未允许精确闹钟，检查可能被系统推迟", "exact"});
        return out;
    }
}
