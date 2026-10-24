package wang.robby.console.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.util.Log;
import org.json.JSONObject;
import java.time.ZoneId;
import java.util.*;

/**
 * 我 → 通知: which of God's notifications are on, their thresholds, and posting them. The five-minute read is the same
 * one Tibo 重置提醒 uses; that read is a foreground service only while it runs. The first read after a notification is
 * switched on only learns the current state, so turning one on never floods the shade. A switch or a line that is
 * already where the person left it does not clear that latch. Notifications show their content only when the phone is
 * unlocked; the lock screen says just "God · 额度提醒".
 */
final class NoticeCenter {
    private NoticeCenter() {}
    static final String CH_QUOTA = "god-quota-v1", CH_SITES = "god-sites-v1", CH_ALERT = "god-alert-v1";
    static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences("god-notices", Context.MODE_PRIVATE); }

    static Notices.Settings settings(Context c) {
        SharedPreferences p = prefs(c);
        return new Notices.Settings(new HashSet<>(p.getStringSet("on", Collections.emptySet())), p.getInt("daily", 30), p.getInt("low", 10), creditsLine(p),
            Notices.choice(p.getInt("marketDays", Notices.MARKET_DAYS_DEFAULT), Notices.MARKET_DAY_CHOICES, Notices.MARKET_DAYS_DEFAULT),
            Notices.choice(p.getInt("marketLine", Notices.MARKET_LINE_DEFAULT), Notices.MARKET_LINE_CHOICES, Notices.MARKET_LINE_DEFAULT));
    }
    static final String CH_MARKET = "god-market-v1";
    /** Daily closes and a quote that moves slowly next to quotas: the market is read at most every ten minutes. */
    static final long MARKET_EVERY = 10 * 60_000L;
    /** One of 20/50/100/200/500; anything else, including a missing key, is 50. */
    static int creditsLine(SharedPreferences p) {
        int v = p.getInt("creditsLine", Notices.CREDIT_DEFAULT);
        for (int choice : Notices.CREDIT_CHOICES) if (choice == v) return v;
        return Notices.CREDIT_DEFAULT;
    }
    static boolean any(Context c) { return !prefs(c).getStringSet("on", Collections.emptySet()).isEmpty(); }
    static boolean on(Context c, String kind) { return prefs(c).getStringSet("on", Collections.emptySet()).contains(kind); }
    static int count(Context c) { return prefs(c).getStringSet("on", Collections.emptySet()).size(); }

    /** Switches one kind; the service starts with the first and stops with the last (unless Tibo 重置提醒 keeps it). */
    static void set(Context c, String kind, boolean value) { set(c, kind, value, true); }
    /** {@code service} false (the preview build) only records the choice. The same position again changes nothing. */
    static void set(Context c, String kind, boolean value, boolean service) {
        Set<String> on = new HashSet<>(prefs(c).getStringSet("on", Collections.emptySet()));
        if (on.contains(kind) == value) return;
        if (value) on.add(kind); else on.remove(kind);
        // A newly switched-on kind learns the current state on its next read before it may notify.
        prefs(c).edit().putStringSet("on", on).putBoolean("primed", false).apply();
        PreferenceSyncHub.note(c, "noticeKinds");
        if (!service) return;
        if (TiboWatch.active(c)) TiboWatch.start(c, true); else TiboWatch.stop(c);
    }
    /**
     * daily, low, creditsLine, marketDays or marketLine. Picking the line already stored does nothing, so opening the
     * menu cannot clear the first-read latch. A real change does not clear it either: the next read would otherwise
     * mark the day fired and drop the notice this line was set to catch.
     */
    static void threshold(Context c, String which, int value) {
        String sync = switch (which) { case "daily" -> "dailyLine"; case "low" -> "lowLine"; case "creditsLine", "marketDays", "marketLine" -> which; default -> null; };
        if (sync == null) return;
        int fallback = switch (which) { case "daily" -> 30; case "low" -> 10; case "marketDays" -> Notices.MARKET_DAYS_DEFAULT; case "marketLine" -> Notices.MARKET_LINE_DEFAULT; default -> Notices.CREDIT_DEFAULT; };
        if (prefs(c).getInt(which, fallback) == value) return;
        prefs(c).edit().putInt(which, value).apply();
        PreferenceSyncHub.note(c, sync);
    }

    /** One read for every switched-on kind; runs on the watch's background thread. Failures are only recorded. */
    @android.annotation.SuppressLint("ApplySharedPref")
    static void check(Context c, MobileApi api, long now) {
        Notices.Settings s = settings(c); if (s.on.isEmpty()) return;
        SharedPreferences p = prefs(c); Notices.State state;
        try { state = new Notices.State(new JSONObject(p.getString("state", "{}"))); } catch (org.json.JSONException e) { state = new Notices.State(null); }
        List<Notices.Notice> due = new ArrayList<>(); String error = null;
        boolean quotas = false; for (String k : s.on) if (Notices.QUOTA_KINDS.contains(k)) quotas = true;
        try { if (quotas) { JSONObject q = api.get("tokens/quotas"); GodWidgets.observe(c, "tokens/quotas", q); due.addAll(Notices.quotas(q, state, s, now, ZoneId.systemDefault())); } }
        catch (Exception e) { error = reason(e); }
        try { if (s.has(Notices.SITES)) { JSONObject o = api.get("overview"); GodWidgets.observe(c, "overview", o); due.addAll(Notices.sites(o, state, s, now)); } }
        catch (Exception e) { if (error == null) error = reason(e); }
        boolean primed = p.getBoolean("primed", false);
        // Before the latch is set the market is read at once, so the first read learns the moves already under way.
        boolean market = s.has(Notices.MARKET) && (!primed || now - p.getLong("marketReadAt", 0) >= MARKET_EVERY);
        try { if (market) { JSONObject m = api.get("market"); due.addAll(Notices.market(m, state, s, now, ZoneId.systemDefault())); p.edit().putLong("marketReadAt", now).apply(); } }
        catch (Exception e) { if (error == null) error = reason(e); }
        SharedPreferences.Editor edit = p.edit().putString("state", state.json.toString()).putLong("lastCheckAt", now);
        if (error == null) edit.remove("lastError").putBoolean("primed", true); else edit.putString("lastError", error);
        edit.commit();
        Log.i("GodNotices", "check kinds=" + s.on.size() + " due=" + due.size() + " primed=" + primed + (error == null ? "" : " failed"));
        if (primed) for (Notices.Notice n : due) post(c, n);
    }
    private static String reason(Exception e) { return e instanceof MobileApi.ApiError ? (((MobileApi.ApiError) e).status == 401 ? "登录已失效" : e.getMessage()) : e instanceof java.io.IOException ? "网络暂不可用" : "暂时无法检查"; }

    static void channels(Context c) {
        NotificationChannel quota = new NotificationChannel(CH_QUOTA, "Token 额度提醒", NotificationManager.IMPORTANCE_DEFAULT);
        quota.setDescription("当天消耗、Codex 积分、额度将用完、额度重置、账号与重置卡"); quota.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        NotificationChannel sites = new NotificationChannel(CH_SITES, "站点状态", NotificationManager.IMPORTANCE_HIGH);
        sites.setDescription("监测中的站点离线与恢复"); sites.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        NotificationChannel market = new NotificationChannel(CH_MARKET, "行情波动提醒", NotificationManager.IMPORTANCE_DEFAULT);
        market.setDescription("关注资产在你设的天数内涨跌超过提醒线"); market.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        // A channel's importance cannot be raised after it exists, so 增强提醒 has a channel of its own.
        NotificationChannel alert = new NotificationChannel(CH_ALERT, "God 增强提醒", NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("开启增强提醒后，额度和站点提醒以横幅、声音和震动送达；持续提醒会一直响到你点「知道了」");
        alert.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        alert.enableVibration(true); alert.setVibrationPattern(new long[]{0, 400, 250, 400}); alert.enableLights(true); alert.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        c.getSystemService(NotificationManager.class).createNotificationChannels(List.of(quota, sites, market, alert));
    }
    static boolean enhanced(Context c) { return prefs(c).getBoolean("enhanced", false); }
    static boolean persistent(Context c) { return enhanced(c) && prefs(c).getBoolean("persistent", false); }
    /** 增强提醒 off also ends 持续提醒 and silences anything still ringing. The same position again changes nothing. */
    static void setMode(Context c, String which, boolean value) {
        if (!"enhanced".equals(which) && !"persistent".equals(which)) return;
        if (prefs(c).getBoolean(which, false) == value) return;
        prefs(c).edit().putBoolean(which, value).apply();
        PreferenceSyncHub.note(c, "enhanced".equals(which) ? "enhancedNotice" : "persistentNotice");
        if (!persistent(c)) ackAll(c);
    }
    static void post(Context context, Notices.Notice n) { post(context, n, 1); }
    /**
     * Posts {@code n}. With 增强提醒 on it uses the high-priority channel (banner, sound, vibration); with 持续提醒 also on it
     * cannot be swiped away, its sound repeats until 知道了 (or opening it), and it rings again after ten minutes, at most
     * {@link #ROUNDS} rounds, staying silent in the shade after the last.
     */
    static void post(Context context, Notices.Notice n, int round) {
        Context c = context.getApplicationContext(); channels(c);
        boolean site = n.kind.equals(Notices.SITES), market = n.kind.equals(Notices.MARKET), loud = enhanced(c), keep = persistent(c);
        String channel = loud ? CH_ALERT : site ? CH_SITES : market ? CH_MARKET : CH_QUOTA;
        Intent open = new Intent(c, MainActivity.class).putExtra(TiboWatch.EXTRA_OPEN, n.open).putExtra(EXTRA_KEY, n.key).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap = PendingIntent.getActivity(c, n.key.hashCode(), open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification hidden = new Notification.Builder(c, channel).setSmallIcon(R.drawable.ic_stat_tibo).setContentTitle("God").setContentText(site ? "站点状态有变化" : market ? "行情波动提醒" : "额度提醒").build();
        Notification.Builder b = new Notification.Builder(c, channel).setSmallIcon(R.drawable.ic_stat_tibo).setContentTitle((round > 1 ? "再次提醒 · " : "") + n.title).setContentText(n.text)
            .setStyle(new Notification.BigTextStyle().bigText(n.text)).setContentIntent(tap).setAutoCancel(!keep).setShowWhen(true).setWhen(System.currentTimeMillis())
            .setCategory(site ? Notification.CATEGORY_ERROR : Notification.CATEGORY_REMINDER).setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(hidden)
            .setColor(site ? 0xffef4444 : 0xff0052d9).setGroup(channel);
        if (keep) {
            PendingIntent ack = PendingIntent.getBroadcast(c, ("ack:" + n.key).hashCode(), new Intent(c, TiboWatchReceiver.class).setAction(ACTION_ACK).putExtra(EXTRA_KEY, n.key), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.setOngoing(true).setDeleteIntent(ack).setOnlyAlertOnce(round > ROUNDS).addAction(new Notification.Action.Builder(null, "知道了", ack).build());
        }
        Notification note = b.build(); if (keep && round <= ROUNDS) note.flags |= Notification.FLAG_INSISTENT;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (round > 1 && round <= ROUNDS) nm.cancel(n.kind, n.key.hashCode());
        nm.notify(n.kind, n.key.hashCode(), note);
        if (keep) remember(c, n, round);
    }
    static final String ACTION_ACK = "wang.robby.console.android.NOTICE_ACK", ACTION_RERING = "wang.robby.console.android.NOTICE_RERING", EXTRA_KEY = "wang.robby.console.android.NOTICE_KEY";
    static final int ROUNDS = 3; static final long RERING = 10 * 60_000L;
    private static JSONObject pending(Context c) { try { return new JSONObject(prefs(c).getString("pending", "{}")); } catch (org.json.JSONException e) { return new JSONObject(); } }
    private static void remember(Context c, Notices.Notice n, int round) {
        JSONObject all = pending(c);
        try { all.put(n.key, new JSONObject().put("kind", n.kind).put("title", n.title).put("text", n.text).put("open", n.open).put("round", round)); } catch (org.json.JSONException e) { return; }
        prefs(c).edit().putString("pending", all.toString()).apply(); scheduleRering(c, all);
    }
    private static void scheduleRering(Context c, JSONObject all) {
        android.app.AlarmManager alarms = c.getSystemService(android.app.AlarmManager.class);
        PendingIntent p = PendingIntent.getBroadcast(c, ACTION_RERING.hashCode(), new Intent(c, TiboWatchReceiver.class).setAction(ACTION_RERING), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        boolean due = false; for (Iterator<String> it = all.keys(); it.hasNext(); ) { JSONObject e = all.optJSONObject(it.next()); if (e != null && e.optInt("round") < ROUNDS) due = true; }
        if (!due) { alarms.cancel(p); return; }
        long at = System.currentTimeMillis() + RERING;
        if (android.os.Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, p);
        else alarms.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, p);
    }
    /** After a reboot or an update the system has dropped the re-ring alarm; bring it back for anything still unacknowledged. */
    static void resume(Context c) { if (persistent(c)) scheduleRering(c, pending(c)); else ackAll(c); }
    /** Ten minutes on: every reminder not yet acknowledged rings its next round. */
    static void rering(Context c) {
        if (!persistent(c)) { ackAll(c); return; }
        JSONObject all = pending(c);
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String key = it.next(); JSONObject e = all.optJSONObject(key); if (e == null || e.optInt("round") >= ROUNDS) continue;
            post(c, new Notices.Notice(key, e.optString("kind"), e.optString("title"), e.optString("text"), e.optString("open")), e.optInt("round") + 1);
        }
    }
    /** 知道了, swiping it away, or opening God from it: that reminder stops. */
    static void ack(Context c, String key) {
        JSONObject all = pending(c); JSONObject e = key == null ? null : all.optJSONObject(key); if (e == null) return;
        all.remove(key); prefs(c).edit().putString("pending", all.toString()).apply();
        c.getSystemService(NotificationManager.class).cancel(e.optString("kind"), key.hashCode()); scheduleRering(c, all);
    }
    static void ackAll(Context c) {
        JSONObject all = pending(c); NotificationManager nm = c.getSystemService(NotificationManager.class);
        for (Iterator<String> it = all.keys(); it.hasNext(); ) { String key = it.next(); JSONObject e = all.optJSONObject(key); if (e != null) nm.cancel(e.optString("kind"), key.hashCode()); }
        prefs(c).edit().remove("pending").apply(); scheduleRering(c, new JSONObject());
    }
    /** A sample of what a notification looks like; it changes no state. */
    static void test(Context c) {
        // One fixed key, so tapping 发送测试通知 again replaces the sample instead of stacking another.
        post(c, new Notices.Notice("test:sample", Notices.DAILY, "试发 · Claude 今天已用掉 32%每周", "超过你设的每日 30% 提醒线，现在剩 41%。这是一条演示通知。", "tokens"));
    }
    /** 上次检查 HH:mm · 正常, or why the last read failed. */
    static String status(Context c) {
        SharedPreferences p = prefs(c); long at = p.getLong("lastCheckAt", 0); String error = p.getString("lastError", null);
        if (at == 0) return any(c) ? "等待第一次检查 · " + TiboWatch.EVERY + "一次" : "未开启";
        String time = java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(java.time.Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()));
        return error == null ? "上次检查 " + time + " · 正常 · " + TiboWatch.EVERY + "一次" : "上次检查 " + time + " · " + error;
    }
}
