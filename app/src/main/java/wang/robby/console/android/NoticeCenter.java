package wang.robby.console.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONObject;
import java.time.ZoneId;
import java.util.*;

/**
 * 我 → 通知: which of God's notifications are on, their thresholds, and posting them. Runs inside the same five-minute
 * background read as Tibo 重置提醒 (one foreground service while any notification is on). The first read after a
 * notification is switched on only learns the current state, so turning one on never floods the shade.
 * Notifications show their content only when the phone is unlocked; the lock screen says just "God · 额度提醒".
 */
final class NoticeCenter {
    private NoticeCenter() {}
    static final String CH_QUOTA = "god-quota-v1", CH_SITES = "god-sites-v1";
    static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences("god-notices", Context.MODE_PRIVATE); }

    static Notices.Settings settings(Context c) {
        SharedPreferences p = prefs(c);
        return new Notices.Settings(new HashSet<>(p.getStringSet("on", Collections.emptySet())), p.getInt("daily", 30), p.getInt("low", 10));
    }
    static boolean any(Context c) { return !prefs(c).getStringSet("on", Collections.emptySet()).isEmpty(); }
    static boolean on(Context c, String kind) { return prefs(c).getStringSet("on", Collections.emptySet()).contains(kind); }
    static int count(Context c) { return prefs(c).getStringSet("on", Collections.emptySet()).size(); }

    /** Switches one kind; the service starts with the first and stops with the last (unless Tibo 重置提醒 keeps it). */
    static void set(Context c, String kind, boolean value) { set(c, kind, value, true); }
    /** {@code service} false (the preview build) only records the choice. */
    static void set(Context c, String kind, boolean value, boolean service) {
        Set<String> on = new HashSet<>(prefs(c).getStringSet("on", Collections.emptySet()));
        if (value) on.add(kind); else on.remove(kind);
        // A newly switched-on kind learns the current state on its next read before it may notify.
        prefs(c).edit().putStringSet("on", on).putBoolean("primed", false).apply();
        if (!service) return;
        if (TiboWatch.active(c)) { TiboWatch.start(c, true); TiboWatch.refreshWatch(c); } else TiboWatch.stop(c);
    }
    static void threshold(Context c, String which, int value) { prefs(c).edit().putInt(which, value).apply(); }

    /** One read for every switched-on kind; runs on the watch's background thread. Failures are only recorded. */
    @android.annotation.SuppressLint("ApplySharedPref")
    static void check(Context c, MobileApi api, long now) {
        Notices.Settings s = settings(c); if (s.on.isEmpty()) return;
        SharedPreferences p = prefs(c); Notices.State state;
        try { state = new Notices.State(new JSONObject(p.getString("state", "{}"))); } catch (org.json.JSONException e) { state = new Notices.State(null); }
        List<Notices.Notice> due = new ArrayList<>(); String error = null;
        boolean quotas = false; for (String k : s.on) if (!k.equals(Notices.SITES)) quotas = true;
        try { if (quotas) due.addAll(Notices.quotas(api.get("tokens/quotas"), state, s, now, ZoneId.systemDefault())); }
        catch (Exception e) { error = reason(e); }
        try { if (s.has(Notices.SITES)) due.addAll(Notices.sites(api.get("overview"), state, s, now)); }
        catch (Exception e) { if (error == null) error = reason(e); }
        boolean primed = p.getBoolean("primed", false);
        SharedPreferences.Editor edit = p.edit().putString("state", state.json.toString()).putLong("lastCheckAt", now);
        if (error == null) edit.remove("lastError").putBoolean("primed", true); else edit.putString("lastError", error);
        edit.commit();
        Log.i("GodNotices", "check kinds=" + s.on.size() + " due=" + due.size() + " primed=" + primed + (error == null ? "" : " failed"));
        if (primed) for (Notices.Notice n : due) post(c, n);
    }
    private static String reason(Exception e) { return e instanceof MobileApi.ApiError ? (((MobileApi.ApiError) e).status == 401 ? "登录已失效" : e.getMessage()) : e instanceof java.io.IOException ? "网络暂不可用" : "暂时无法检查"; }

    static void channels(Context c) {
        NotificationChannel quota = new NotificationChannel(CH_QUOTA, "Token 额度提醒", NotificationManager.IMPORTANCE_DEFAULT);
        quota.setDescription("当天消耗、额度将用完、额度重置、账号与重置卡"); quota.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        NotificationChannel sites = new NotificationChannel(CH_SITES, "站点状态", NotificationManager.IMPORTANCE_HIGH);
        sites.setDescription("监测中的站点离线与恢复"); sites.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        c.getSystemService(NotificationManager.class).createNotificationChannels(List.of(quota, sites));
    }
    static void post(Context context, Notices.Notice n) {
        Context c = context.getApplicationContext(); channels(c);
        boolean site = n.kind.equals(Notices.SITES); String channel = site ? CH_SITES : CH_QUOTA;
        Intent open = new Intent(c, MainActivity.class).putExtra(TiboWatch.EXTRA_OPEN, n.open).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap = PendingIntent.getActivity(c, n.key.hashCode(), open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification hidden = new Notification.Builder(c, channel).setSmallIcon(R.drawable.ic_stat_tibo).setContentTitle("God").setContentText(site ? "站点状态有变化" : "额度提醒").build();
        Notification note = new Notification.Builder(c, channel).setSmallIcon(R.drawable.ic_stat_tibo).setContentTitle(n.title).setContentText(n.text)
            .setStyle(new Notification.BigTextStyle().bigText(n.text)).setContentIntent(tap).setAutoCancel(true).setShowWhen(true).setWhen(System.currentTimeMillis())
            .setCategory(site ? Notification.CATEGORY_ERROR : Notification.CATEGORY_REMINDER).setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(hidden)
            .setColor(site ? 0xffef4444 : 0xff0052d9).setGroup(channel).build();
        c.getSystemService(NotificationManager.class).notify(n.kind, n.key.hashCode(), note);
    }
    /** A sample of what a notification looks like; it changes no state. */
    static void test(Context c) {
        post(c, new Notices.Notice("test:" + System.currentTimeMillis(), Notices.DAILY, "试发 · Claude 今天已用掉 32%每周", "超过你设的每日 30% 提醒线，现在剩 41%。这是一条演示通知。", "tokens"));
    }
    /** 上次检查 HH:mm · 正常, or why the last read failed. */
    static String status(Context c) {
        SharedPreferences p = prefs(c); long at = p.getLong("lastCheckAt", 0); String error = p.getString("lastError", null);
        if (at == 0) return any(c) ? "等待第一次检查 · 每 5 分钟一次" : "未开启";
        String time = java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(java.time.Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()));
        return error == null ? "上次检查 " + time + " · 正常 · 每 5 分钟一次" : "上次检查 " + time + " · " + error;
    }
}
