package wang.robby.console.android;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.View;
import android.widget.RemoteViews;
import java.time.ZoneId;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The home screen widgets besides God 小组件: Token 额度, 重置日历, 时区时钟, 运行状态 and 微信消息. Nothing here reads the
 * network. A read the app already made (a page, the board, or the five-minute reminder check) is cut down by
 * {@link WidgetSnapshot} and kept in "god-widgets"; signing out deletes it. Only 时区时钟 may sit on the lock screen.
 */
final class GodWidgets {
    private GodWidgets() {}
    static final String CLOCK = "clock";
    static final String ACTION_REFRESH = "wang.robby.console.android.WIDGETS_REFRESH";
    private static final int[] ROWS = {R.id.wr_row1, R.id.wr_row2, R.id.wr_row3, R.id.wr_row4, R.id.wr_row5, R.id.wr_row6};
    private static final int[][] CLOCK_IDS = {{R.id.wc_row1, R.id.wc_name1, R.id.wc_clock1}, {R.id.wc_row2, R.id.wc_name2, R.id.wc_clock2}, {R.id.wc_row3, R.id.wc_name3, R.id.wc_clock3}};

    private static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences("god-widgets", Context.MODE_PRIVATE); }

    /** Called with every successful read; keeps the cut-down reading for the widgets that use that path. */
    static void observe(Context context, String path, JSONObject data) {
        if (data == null || path == null) return;
        Context c = context.getApplicationContext(); String route = path.split("\\?", 2)[0]; long now = System.currentTimeMillis();
        SharedPreferences.Editor edit = prefs(c).edit(); boolean changed = false;
        if (route.equals("tokens/quotas")) {
            changed |= keep(edit, WidgetSnapshot.TOKENS, WidgetSnapshot.tokens(data, Dashboard.hiddenTokens(c)), now);
            changed |= keep(edit, WidgetSnapshot.CALENDAR, WidgetSnapshot.calendar(data, ZoneId.systemDefault(), now), now);
        } else if (route.equals("overview")) changed = keep(edit, WidgetSnapshot.STATUS, WidgetSnapshot.status(data), now);
        else if (route.equals("wechat/digest")) changed = keep(edit, WidgetSnapshot.WECHAT, WidgetSnapshot.wechat(data), now);
        if (!changed) return;
        edit.apply(); refreshAll(c);
    }
    private static boolean keep(SharedPreferences.Editor edit, String kind, JSONObject snapshot, long now) {
        if (snapshot == null) return false;
        edit.putString(kind, snapshot.toString()).putLong(kind + "At", now); return true;
    }
    /** Signing out: forget every reading at once and redraw, so no figure of the old account stays on the home screen. */
    static void clear(Context context) { Context c = context.getApplicationContext(); prefs(c).edit().clear().apply(); refreshAll(c); }

    private static Class<?> provider(String kind) {
        switch (kind) {
            case WidgetSnapshot.TOKENS: return TokenWidget.class;
            case WidgetSnapshot.CALENDAR: return CalendarWidget.class;
            case WidgetSnapshot.STATUS: return StatusWidget.class;
            case WidgetSnapshot.WECHAT: return WechatWidget.class;
            default: return ClockWidget.class;
        }
    }
    static void update(Context c, AppWidgetManager manager, int[] ids, String kind) { if (ids.length > 0) manager.updateAppWidget(ids, views(c, kind)); schedule(c); }
    /** Redraws every placed widget of these five kinds and books the redraw for when a reading turns stale. */
    static void refreshAll(Context context) {
        Context c = context.getApplicationContext(); AppWidgetManager manager = AppWidgetManager.getInstance(c); if (manager == null) return;
        for (String kind : new String[]{WidgetSnapshot.TOKENS, WidgetSnapshot.CALENDAR, WidgetSnapshot.STATUS, WidgetSnapshot.WECHAT, CLOCK}) {
            int[] ids = manager.getAppWidgetIds(new ComponentName(c, provider(kind)));
            if (ids.length > 0) manager.updateAppWidget(ids, views(c, kind));
        }
        schedule(c);
    }

    static RemoteViews views(Context c, String kind) {
        long now = System.currentTimeMillis(); ZoneId zone = ZoneId.systemDefault(); SharedPreferences p = prefs(c);
        long at = p.getLong(kind + "At", 0); boolean stale = WidgetSnapshot.stale(at, now);
        JSONObject data = null; try { String s = p.getString(kind, null); data = s == null ? null : new JSONObject(s); } catch (JSONException ignored) { }
        switch (kind) {
            case WidgetSnapshot.TOKENS: return rows(c, "Token 额度", "tokens", 21, data, stale, at, now, zone, true);
            case WidgetSnapshot.CALENDAR: return rows(c, "重置日历", "quota-calendar", 22, data, stale, at, now, zone, false);
            case WidgetSnapshot.STATUS: {
                String value = data == null ? "—" : "在线 " + data.optInt("online") + "/" + data.optInt("total");
                int issues = data == null ? 0 : data.optInt("issues");
                return big(c, "运行状态", "status", 23, value, data == null ? "" : issues > 0 ? issues + " 项异常" : "全部正常", issues > 0 && !stale, stale || data == null, at, now, zone);
            }
            case WidgetSnapshot.WECHAT: {
                String value = data == null ? "—" : data.optLong("today") + " 条";
                String line = data == null ? "" : "今日 · 近 7 天日均 " + data.optLong("average7d");
                return big(c, "微信消息", "wechat-messages", 24, value, line, false, stale || data == null, at, now, zone);
            }
            default: return clock(c);
        }
    }
    private static RemoteViews rows(Context c, String title, String open, int code, JSONObject data, boolean stale, long at, long now, ZoneId zone, boolean tokens) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_rows); tap(c, v, R.id.wr_root, open, code);
        v.setTextViewText(R.id.wr_title, title); v.setTextViewText(R.id.wr_footer, WidgetSnapshot.footer(at, now, zone));
        JSONArray list = data == null ? null : data.optJSONArray("rows"); StringBuilder said = new StringBuilder(title);
        for (int i = 0; i < ROWS.length; i++) {
            JSONObject row = list == null ? null : list.optJSONObject(i);
            if (row == null) { v.setViewVisibility(ROWS[i], i == 0 ? View.VISIBLE : View.GONE); if (i == 0) v.setTextViewText(ROWS[i], data == null ? "打开 God 后显示" : tokens ? "没有已接入的账号" : "近期没有已知的重置"); tint(v, ROWS[i], R.color.widget_muted); continue; }
            String text = tokens ? WidgetSnapshot.tokenLine(row, zone, now) : WidgetSnapshot.calendarLine(row, zone, now);
            v.setViewVisibility(ROWS[i], View.VISIBLE); v.setTextViewText(ROWS[i], text); said.append("，").append(text);
            tint(v, ROWS[i], stale ? R.color.widget_muted : tokens && WidgetSnapshot.low(row) ? R.color.widget_alert : R.color.widget_ink);
        }
        v.setContentDescription(R.id.wr_root, said + "，" + WidgetSnapshot.footer(at, now, zone));
        return v;
    }
    private static RemoteViews big(Context c, String title, String open, int code, String value, String line, boolean alert, boolean muted, long at, long now, ZoneId zone) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_big); tap(c, v, R.id.wb_root, open, code);
        String footer = WidgetSnapshot.footer(at, now, zone);
        v.setTextViewText(R.id.wb_title, title); v.setTextViewText(R.id.wb_value, value); v.setTextViewText(R.id.wb_line, line); v.setTextViewText(R.id.wb_footer, footer);
        tint(v, R.id.wb_value, muted ? R.color.widget_muted : R.color.widget_ink); tint(v, R.id.wb_line, alert ? R.color.widget_alert : R.color.widget_muted);
        v.setContentDescription(R.id.wb_root, title + "，" + value + "，" + line + "，" + footer);
        return v;
    }
    private static RemoteViews clock(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_clock); tap(c, v, R.id.wc_root, null, 25);
        List<Dashboard.Clock> clocks = WidgetSnapshot.clocks(c.getSharedPreferences("god-ui", Context.MODE_PRIVATE).getString(Dashboard.CLOCKS, null));
        for (int i = 0; i < CLOCK_IDS.length; i++) {
            if (i >= clocks.size()) { v.setViewVisibility(CLOCK_IDS[i][0], View.GONE); continue; }
            v.setViewVisibility(CLOCK_IDS[i][0], View.VISIBLE); v.setTextViewText(CLOCK_IDS[i][1], clocks.get(i).name);
            v.setString(CLOCK_IDS[i][2], "setTimeZone", clocks.get(i).zone);
        }
        return v;
    }
    /** Opens God on the matching page; the system asks to unlock first, and a signed-out God stays on its first page. */
    private static void tap(Context c, RemoteViews v, int root, String feature, int code) {
        Intent open = new Intent(Intent.ACTION_VIEW, null, c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (feature != null) open.putExtra(TiboWatch.EXTRA_OPEN, feature);
        v.setOnClickPendingIntent(root, PendingIntent.getActivity(c, code, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
    }
    /** Theme colours by resource so the widget follows light and dark mode; Android 11 keeps the layout's colours. */
    private static void tint(RemoteViews v, int id, int color) { if (Build.VERSION.SDK_INT >= 31) v.setColorStateList(id, "setTextColor", color); }
    /** Wakes once when the soonest current reading turns stale, so the widgets grey out without the app running. */
    private static void schedule(Context c) {
        long now = System.currentTimeMillis(), next = 0; SharedPreferences p = prefs(c);
        for (String kind : new String[]{WidgetSnapshot.TOKENS, WidgetSnapshot.CALENDAR, WidgetSnapshot.STATUS, WidgetSnapshot.WECHAT}) {
            long at = p.getLong(kind + "At", 0); if (WidgetSnapshot.stale(at, now)) continue;
            long due = at + WidgetSnapshot.STALE_MS + 1_000L; next = next == 0 ? due : Math.min(next, due);
        }
        AlarmManager alarms = c.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(c, 26, new Intent(c, TokenWidget.class).setAction(ACTION_REFRESH), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        if (next == 0) alarms.cancel(pi); else alarms.set(AlarmManager.RTC, next, pi);
    }
}
