package wang.robby.console.android;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.service.quicksettings.TileService;
import android.view.View;
import android.widget.RemoteViews;
import org.json.JSONObject;

/**
 * God 小组件 for the home screen and Pixel's lock screen (keyguard category). It shows the app mark and Tibo's public
 * reset announcement only — never balances, ledgers or anything behind the sign-in — because a lock screen is seen by
 * anyone holding the phone. A tap opens God; on the lock screen the system asks to unlock first. The announcement is
 * whatever the app last read (the reminder's five-minute check, 28 天挑战 or Token 额度); older than two hours it is
 * not shown, so a stale countdown never passes for a current one. The system ticks the countdown; nothing runs here.
 */
public final class GodWidget extends AppWidgetProvider {
    static final String ACTION_REFRESH = "wang.robby.console.android.WIDGET_REFRESH";
    static final long STALE_MS = 2 * 3600_000L;

    @Override public void onUpdate(Context c, AppWidgetManager manager, int[] ids) { manager.updateAppWidget(ids, views(c)); schedule(c); }
    @Override public void onReceive(Context c, Intent intent) { super.onReceive(c, intent); if (ACTION_REFRESH.equals(intent.getAction())) refresh(c); }

    /** What the widget says: a line, and the announced instant when a countdown applies (0 otherwise). */
    static final class Status {
        final String line; final long deadline; final boolean cards;
        Status(String line, long deadline, boolean cards) { this.line = line; this.deadline = deadline; this.cards = cards; }
    }
    /** Pure: the remembered announcement ({@code ""} for none known, null for never read) at {@code savedAt}, seen at {@code now}. */
    static Status status(String saved, long savedAt, long now) {
        if (saved == null || savedAt <= 0 || now - savedAt > STALE_MS || savedAt > now + 60_000L) return new Status("打开 God", 0, false);
        if (saved.isEmpty()) return new Status("暂无 Tibo 重置预告", 0, false);
        TiboChallenge.Post p;
        try { p = new TiboChallenge.Post(new JSONObject(saved)); } catch (org.json.JSONException e) { return new Status("打开 God", 0, false); }
        if (p.url == null || !TiboChallenge.INTENT.contains(p.kind)) return new Status("暂无 Tibo 重置预告", 0, false);
        if (p.deadline > now) return new Status(p.cards() ? "Tibo 发卡倒计时" : "Tibo 重置倒计时", p.deadline, p.cards());
        if (p.deadline > 0) return new Status("Tibo 预告时间已到 · 待确认", 0, p.cards());
        return new Status(p.cards() ? "Tibo 已预告发卡 · 时间未公布" : "Tibo 已预告重置 · 时间未公布", 0, p.cards());
    }
    /** The quick-settings subtitle: short, no seconds. */
    static String shortStatus(Context c) {
        long now = System.currentTimeMillis(); Status s = current(c, now);
        if (s.deadline <= 0) return s.line.startsWith("打开") ? "打开 App" : s.line.replace("Tibo ", "").replace(" · ", "，");
        long minutes = Math.max(1, (s.deadline - now) / 60_000L);
        return (s.cards ? "发卡 " : "重置 ") + (minutes >= 60 ? minutes / 60 + " 小时 " + minutes % 60 + " 分后" : minutes + " 分钟后");
    }
    private static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences("god-widget", Context.MODE_PRIVATE); }
    static Status current(Context c, long now) { SharedPreferences p = prefs(c); return status(p.getString("upcoming", null), p.getLong("savedAt", 0), now); }

    /** Keeps the announcement from a successful read: tokens/tibo and its alerts view when the monitor is current, Token 额度's tiboReset when set. */
    static void observe(Context c, String path, JSONObject data) {
        if (data == null) return;
        String route = path.split("\\?", 2)[0];
        if ((route.equals("tokens/tibo") || route.equals("tokens/tibo/alerts")) && "connected".equals(data.optString("status"))) remember(c, data.optJSONObject("upcoming"));
        else if (route.equals("tokens/quotas") && data.optJSONObject("tiboReset") != null) remember(c, data.optJSONObject("tiboReset"));
    }
    static void remember(Context c, JSONObject upcoming) {
        prefs(c).edit().putString("upcoming", upcoming == null ? "" : upcoming.toString()).putLong("savedAt", System.currentTimeMillis()).apply();
        refresh(c);
    }

    static RemoteViews views(Context c) {
        long now = System.currentTimeMillis(); Status s = current(c, now);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.god_widget);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent tap = PendingIntent.getActivity(c, 11, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.widget_frame, tap); v.setOnClickPendingIntent(R.id.widget_root, tap);
        v.setTextViewText(R.id.widget_status, s.line);
        if (s.deadline > now) {
            v.setViewVisibility(R.id.widget_countdown, View.VISIBLE);
            v.setChronometer(R.id.widget_countdown, SystemClock.elapsedRealtime() + (s.deadline - now), null, true);
            v.setChronometerCountDown(R.id.widget_countdown, true);
        } else { v.setChronometer(R.id.widget_countdown, SystemClock.elapsedRealtime(), null, false); v.setViewVisibility(R.id.widget_countdown, View.GONE); }
        v.setContentDescription(R.id.widget_root, "God，" + s.line + (s.deadline > now ? "，" + shortStatus(c) : "") + "，点按打开");
        return v;
    }
    /** Redraws every placed widget and the tile, then wakes again when the countdown ends or the reading goes stale. */
    static void refresh(Context context) {
        Context c = context.getApplicationContext(); AppWidgetManager manager = AppWidgetManager.getInstance(c);
        int[] ids = manager == null ? new int[0] : manager.getAppWidgetIds(new ComponentName(c, GodWidget.class));
        if (ids.length > 0) manager.updateAppWidget(ids, views(c));
        schedule(c);
        try { TileService.requestListeningState(c, new ComponentName(c, GodTileService.class)); } catch (RuntimeException ignored) { }
    }
    private static void schedule(Context c) {
        long now = System.currentTimeMillis(), savedAt = prefs(c).getLong("savedAt", 0); Status s = current(c, now);
        long next = savedAt > 0 && now - savedAt <= STALE_MS ? savedAt + STALE_MS + 1_000L : 0;
        if (s.deadline > now) next = next == 0 ? s.deadline + 1_000L : Math.min(next, s.deadline + 1_000L);
        AlarmManager alarms = c.getSystemService(AlarmManager.class);
        PendingIntent p = PendingIntent.getBroadcast(c, 12, new Intent(c, GodWidget.class).setAction(ACTION_REFRESH), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        if (next == 0) { alarms.cancel(p); return; }
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC, next, p); else alarms.set(AlarmManager.RTC, next, p);
    }
}
