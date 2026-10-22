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
    private static final int[][] TOKEN_IDS = {{R.id.wt_row1, R.id.wt_name1, R.id.wt_reset1, R.id.wt_pct1, R.id.wt_bar1}, {R.id.wt_row2, R.id.wt_name2, R.id.wt_reset2, R.id.wt_pct2, R.id.wt_bar2},
        {R.id.wt_row3, R.id.wt_name3, R.id.wt_reset3, R.id.wt_pct3, R.id.wt_bar3}, {R.id.wt_row4, R.id.wt_name4, R.id.wt_reset4, R.id.wt_pct4, R.id.wt_bar4},
        {R.id.wt_row5, R.id.wt_name5, R.id.wt_reset5, R.id.wt_pct5, R.id.wt_bar5}, {R.id.wt_row6, R.id.wt_name6, R.id.wt_reset6, R.id.wt_pct6, R.id.wt_bar6}};
    private static final int[][] CALENDAR_IDS = {{R.id.wk_row1, R.id.wk_day1, R.id.wk_time1, R.id.wk_text1}, {R.id.wk_row2, R.id.wk_day2, R.id.wk_time2, R.id.wk_text2},
        {R.id.wk_row3, R.id.wk_day3, R.id.wk_time3, R.id.wk_text3}, {R.id.wk_row4, R.id.wk_day4, R.id.wk_time4, R.id.wk_text4}, {R.id.wk_row5, R.id.wk_day5, R.id.wk_time5, R.id.wk_text5}};
    private static final int[][] CLOCK_IDS = {{R.id.wc_row1, R.id.wc_name1, R.id.wc_clock1, R.id.wc_day1}, {R.id.wc_row2, R.id.wc_name2, R.id.wc_clock2, R.id.wc_day2}, {R.id.wc_row3, R.id.wc_name3, R.id.wc_clock3, R.id.wc_day3}};

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

    /** Every widget at the size it sits in: Android 12+ picks the closest of these per placement, Android 11 gets a mid-size one. */
    static RemoteViews views(Context c, String kind) {
        if (Build.VERSION.SDK_INT < 31) return views(c, kind, 250, 180);
        java.util.Map<android.util.SizeF, RemoteViews> sized = new java.util.LinkedHashMap<>();
        for (int[] s : SIZES) sized.put(new android.util.SizeF(s[0], s[1]), views(c, kind, s[0], s[1]));
        return new RemoteViews(sized);
    }
    private static final int[][] SIZES = {{110, 110}, {110, 150}, {110, 200}, {110, 260}, {200, 110}, {200, 150}, {200, 200}, {200, 260}};
    /**
     * The widget laid out for a cell of {@code w} × {@code h} dp: how many rows fit, and whether the reset note and reading
     * time have room. Text is in sp and the cell in dp, so a larger font shows fewer rows instead of smaller text.
     */
    static RemoteViews views(Context c, String kind, int w, int h) {
        float f = Math.max(1f, Math.min(2f, c.getResources().getConfiguration().fontScale)); boolean roomy = f < 1.3f || w >= 200;
        float body = h - 24 - Math.max(20, 18 * f); // padding and header; rows below are their dp spacing plus an sp line
        long now = System.currentTimeMillis(); ZoneId zone = ZoneId.systemDefault(); SharedPreferences p = prefs(c);
        long at = p.getLong(kind + "At", 0); boolean stale = WidgetSnapshot.stale(at, now);
        JSONObject data = null; try { String s = p.getString(kind, null); data = s == null ? null : new JSONObject(s); } catch (JSONException ignored) { }
        switch (kind) {
            case WidgetSnapshot.TOKENS: return tokens(c, data, stale, at, now, zone, Math.max(1, Math.min(6, (int) ((body - 2) / (16 + 19 * f)))), w >= 200 * f, roomy);
            case WidgetSnapshot.CALENDAR: return calendar(c, data, stale, at, now, zone, Math.max(1, Math.min(5, (int) ((body - 3) / (6 + 19 * f)))), roomy);
            case WidgetSnapshot.STATUS: {
                int issues = data == null ? 0 : data.optInt("issues");
                String value = data == null ? "—" : String.valueOf(data.optInt("online")), unit = data == null ? "" : "/ " + data.optInt("total") + " 在线";
                String line = data == null ? "打开 God 后显示" : issues > 0 ? issues + " 项异常" : "全部正常";
                int dot = data == null ? 0 : stale ? R.color.widget_faint : issues > 0 ? R.color.widget_alert : R.color.widget_ok;
                return figure(c, "运行状态", "pulse", 0xff22c55e, "status", 23, value, unit, line, dot, stale || data == null, at, now, zone, roomy);
            }
            case WidgetSnapshot.WECHAT: {
                String value = data == null ? "—" : String.valueOf(data.optLong("today")), unit = data == null ? "" : "条";
                String versus = data == null ? "" : WidgetSnapshot.versusAverage(data.optLong("today"), data.optLong("average7d"));
                String line = data == null ? "打开 God 后显示" : (w < 200 ? "今日 · 日均 " : "今日 · 近 7 天日均 ") + data.optLong("average7d") + (versus.isEmpty() || w < 200 ? "" : " · " + versus);
                return figure(c, "微信消息", "chat", 0xff07c160, "wechat-messages", 24, value, unit, line, 0, stale || data == null, at, now, zone, roomy);
            }
            default: return clock(c, h >= 150 * f, w >= 200 * f, Math.max(1, Math.min(3, (int) ((h - 22) / (5 + 24 * f)))));
        }
    }
    /** The shared header: a tinted icon tile, the title, and the reading time (amber 「待更新」 once stale), short enough for a 2 × 2 cell. */
    private static void header(Context c, RemoteViews v, String title, String symbol, int hue, String meta, boolean stale) {
        v.setImageViewBitmap(R.id.w_icon, ConsoleTheme.widgetIcon(c, symbol, hue, 20)); v.setTextViewText(R.id.w_title, title);
        v.setTextViewText(R.id.w_meta, meta == null ? "" : meta); if (meta != null) tint(v, R.id.w_meta, stale ? R.color.widget_warn : R.color.widget_faint);
    }
    private static RemoteViews tokens(Context c, JSONObject data, boolean stale, long at, long now, ZoneId zone, int fit, boolean wide, boolean roomy) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_tokens); tap(c, v, android.R.id.background, "tokens", 21);
        String footer = WidgetSnapshot.footer(at, now, zone); header(c, v, "Token 额度", "gauge", 0xff6366f1, data == null || !roomy ? null : WidgetSnapshot.meta(at, now, zone), stale);
        JSONArray list = data == null ? null : data.optJSONArray("rows"); StringBuilder said = new StringBuilder("Token 额度");
        for (int i = 0; i < TOKEN_IDS.length; i++) {
            int[] id = TOKEN_IDS[i]; JSONObject row = list == null || i >= fit ? null : list.optJSONObject(i);
            if (row == null) {
                boolean note = i == 0; v.setViewVisibility(id[0], note ? View.VISIBLE : View.GONE); v.setViewVisibility(id[4], View.GONE);
                if (note) { v.setTextViewText(id[1], data == null ? "打开 God 后显示" : "没有已接入的账号"); tint(v, id[1], R.color.widget_muted); v.setTextViewText(id[2], ""); v.setTextViewText(id[3], ""); }
                continue;
            }
            int level = WidgetSnapshot.level(row); String pct = level < 0 ? "—" : row.optLong("percent") + "%", reset = WidgetSnapshot.resetNote(row, zone, now);
            v.setViewVisibility(id[0], View.VISIBLE); v.setTextViewText(id[1], row.optString("name")); v.setTextViewText(id[2], wide ? reset : ""); v.setTextViewText(id[3], pct);
            int color = stale || level < 0 ? R.color.widget_faint : level == 2 ? R.color.widget_alert : level == 1 ? R.color.widget_warn : R.color.widget_ok;
            tint(v, id[1], stale ? R.color.widget_muted : R.color.widget_ink); tint(v, id[3], stale || level < 0 ? R.color.widget_faint : level == 2 ? R.color.widget_alert : R.color.widget_ink);
            v.setViewVisibility(id[4], level < 0 ? View.GONE : View.VISIBLE);
            if (level >= 0) { v.setProgressBar(id[4], 100, (int) Math.max(2, Math.min(100, row.optLong("percent"))), false); if (Build.VERSION.SDK_INT >= 31) v.setColorStateList(id[4], "setProgressTintList", color); }
            said.append("，").append(WidgetSnapshot.tokenLine(row, zone, now));
        }
        v.setContentDescription(android.R.id.background, said + "，" + footer);
        return v;
    }
    private static RemoteViews calendar(Context c, JSONObject data, boolean stale, long at, long now, ZoneId zone, int fit, boolean roomy) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_calendar); tap(c, v, android.R.id.background, "quota-calendar", 22);
        String footer = WidgetSnapshot.footer(at, now, zone); header(c, v, "重置日历", "calendar", 0xff8b5cf6, data == null || !roomy ? null : WidgetSnapshot.meta(at, now, zone), stale);
        JSONArray list = data == null ? null : data.optJSONArray("rows"); StringBuilder said = new StringBuilder("重置日历"); String lastDay = null;
        for (int i = 0; i < CALENDAR_IDS.length; i++) {
            int[] id = CALENDAR_IDS[i]; JSONObject row = list == null || i >= fit ? null : list.optJSONObject(i);
            if (row == null) {
                v.setViewVisibility(id[0], i == 0 ? View.VISIBLE : View.GONE);
                if (i == 0) { v.setTextViewText(id[1], ""); v.setTextViewText(id[2], ""); v.setTextViewText(id[3], data == null ? "打开 God 后显示" : "近期没有已知的重置"); }
                continue;
            }
            long when = row.optLong("at"); String day = WidgetSnapshot.day(when, zone, now);
            v.setViewVisibility(id[0], View.VISIBLE); v.setTextViewText(id[1], day.equals(lastDay) ? "" : day); v.setTextViewText(id[2], WidgetSnapshot.clockTime(when, zone)); v.setTextViewText(id[3], row.optString("text"));
            tint(v, id[2], stale ? R.color.widget_faint : i == 0 ? R.color.widget_accent : R.color.widget_ink); tint(v, id[3], stale ? R.color.widget_faint : i == 0 ? R.color.widget_ink : R.color.widget_muted);
            lastDay = day; said.append("，").append(WidgetSnapshot.calendarLine(row, zone, now));
        }
        v.setContentDescription(android.R.id.background, said + "，" + footer);
        return v;
    }
    private static RemoteViews figure(Context c, String title, String symbol, int hue, String open, int code, String value, String unit, String line, int dot, boolean muted, long at, long now, ZoneId zone, boolean roomy) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_figure); tap(c, v, android.R.id.background, open, code);
        String footer = WidgetSnapshot.footer(at, now, zone); boolean known = !"—".equals(value);
        header(c, v, title, symbol, hue, known && roomy ? WidgetSnapshot.meta(at, now, zone) : null, WidgetSnapshot.stale(at, now));
        v.setTextViewText(R.id.wf_value, value); v.setTextViewText(R.id.wf_unit, unit); v.setTextViewText(R.id.wf_line, line);
        tint(v, R.id.wf_value, muted ? R.color.widget_faint : R.color.widget_ink);
        v.setViewVisibility(R.id.wf_dot, dot == 0 ? View.GONE : View.VISIBLE); if (dot != 0) tint(v, R.id.wf_dot, dot);
        tint(v, R.id.wf_line, dot == R.color.widget_alert ? R.color.widget_alert : R.color.widget_muted);
        v.setContentDescription(android.R.id.background, title + "，" + value + " " + unit + "，" + line + "，" + footer);
        return v;
    }
    private static RemoteViews clock(Context c, boolean tall, boolean wide, int fit) {
        List<Dashboard.Clock> clocks = WidgetSnapshot.clocks(c.getSharedPreferences("god-ui", Context.MODE_PRIVATE).getString(Dashboard.CLOCKS, null));
        boolean hero = tall && clocks.size() >= 2;
        RemoteViews v = new RemoteViews(c.getPackageName(), hero ? R.layout.widget_clock_hero : R.layout.widget_clock); tap(c, v, android.R.id.background, null, 25);
        header(c, v, "时区时钟", "globe", 0xff0ea5e9, null, false); v.setViewVisibility(R.id.w_head, hero || tall ? View.VISIBLE : View.GONE);
        StringBuilder said = new StringBuilder("时区时钟");
        for (int i = 0; i < CLOCK_IDS.length; i++) {
            if (i >= clocks.size() || !hero && i >= fit) { v.setViewVisibility(CLOCK_IDS[i][0], hero ? View.INVISIBLE : View.GONE); continue; }
            v.setViewVisibility(CLOCK_IDS[i][0], View.VISIBLE); v.setTextViewText(CLOCK_IDS[i][1], clocks.get(i).name); said.append("，").append(clocks.get(i).name);
            v.setString(CLOCK_IDS[i][2], "setTimeZone", clocks.get(i).zone); v.setString(CLOCK_IDS[i][3], "setTimeZone", clocks.get(i).zone); if (!hero || i > 0) v.setViewVisibility(CLOCK_IDS[i][3], wide ? View.VISIBLE : View.GONE);
        }
        if (clocks.isEmpty()) { v.setViewVisibility(CLOCK_IDS[0][0], View.VISIBLE); v.setTextViewText(CLOCK_IDS[0][1], "在编辑看板里添加时区"); }
        v.setContentDescription(android.R.id.background, said.toString());
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
