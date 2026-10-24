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
import android.util.Log;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The home screen widgets besides God 小组件: Token 额度, 重置日历, 时区时钟, 运行状态 and 微信消息. Drawing never reads the
 * network. A read the app made (a page, the board, or the background check, which reads for placed widgets itself
 * through {@link #read}) is cut down by {@link WidgetSnapshot} and kept in "god-widgets"; signing out deletes it. Only 时区时钟 may sit on the lock screen.
 */
final class GodWidgets {
    private GodWidgets() {}
    static final String CLOCK = "clock";
    static final String ACTION_REFRESH = "wang.robby.console.android.WIDGETS_REFRESH";
    /** Token 额度's six lines of two cells: {cell, dot, name, reset, percent, bar}; a line's left cell is 2r−1, its right one 2r. */
    private static final int[] TOKEN_LINES = {R.id.wt_row1, R.id.wt_row2, R.id.wt_row3, R.id.wt_row4, R.id.wt_row5, R.id.wt_row6};
    private static final int[][] TOKEN_IDS = {
        {R.id.wt_c1, R.id.wt_dot1, R.id.wt_name1, R.id.wt_reset1, R.id.wt_pct1, R.id.wt_bar1}, {R.id.wt_c2, R.id.wt_dot2, R.id.wt_name2, R.id.wt_reset2, R.id.wt_pct2, R.id.wt_bar2},
        {R.id.wt_c3, R.id.wt_dot3, R.id.wt_name3, R.id.wt_reset3, R.id.wt_pct3, R.id.wt_bar3}, {R.id.wt_c4, R.id.wt_dot4, R.id.wt_name4, R.id.wt_reset4, R.id.wt_pct4, R.id.wt_bar4},
        {R.id.wt_c5, R.id.wt_dot5, R.id.wt_name5, R.id.wt_reset5, R.id.wt_pct5, R.id.wt_bar5}, {R.id.wt_c6, R.id.wt_dot6, R.id.wt_name6, R.id.wt_reset6, R.id.wt_pct6, R.id.wt_bar6},
        {R.id.wt_c7, R.id.wt_dot7, R.id.wt_name7, R.id.wt_reset7, R.id.wt_pct7, R.id.wt_bar7}, {R.id.wt_c8, R.id.wt_dot8, R.id.wt_name8, R.id.wt_reset8, R.id.wt_pct8, R.id.wt_bar8},
        {R.id.wt_c9, R.id.wt_dot9, R.id.wt_name9, R.id.wt_reset9, R.id.wt_pct9, R.id.wt_bar9}, {R.id.wt_c10, R.id.wt_dot10, R.id.wt_name10, R.id.wt_reset10, R.id.wt_pct10, R.id.wt_bar10},
        {R.id.wt_c11, R.id.wt_dot11, R.id.wt_name11, R.id.wt_reset11, R.id.wt_pct11, R.id.wt_bar11}, {R.id.wt_c12, R.id.wt_dot12, R.id.wt_name12, R.id.wt_reset12, R.id.wt_pct12, R.id.wt_bar12}};
    private static final int[][] CALENDAR_IDS = {{R.id.wk_row1, R.id.wk_day1, R.id.wk_time1, R.id.wk_text1, R.id.wk_dot1}, {R.id.wk_row2, R.id.wk_day2, R.id.wk_time2, R.id.wk_text2, R.id.wk_dot2},
        {R.id.wk_row3, R.id.wk_day3, R.id.wk_time3, R.id.wk_text3, R.id.wk_dot3}, {R.id.wk_row4, R.id.wk_day4, R.id.wk_time4, R.id.wk_text4, R.id.wk_dot4}, {R.id.wk_row5, R.id.wk_day5, R.id.wk_time5, R.id.wk_text5, R.id.wk_dot5}};
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
    static void update(Context c, AppWidgetManager manager, int[] ids, String kind) { if (ids.length > 0) manager.updateAppWidget(ids, views(c, kind)); schedule(c); watch(c); }

    /** What the placed widgets show, as the routes the background check reads for them; empty when none needs a read. */
    static Set<String> routes(Context context) {
        Context c = context.getApplicationContext(); AppWidgetManager manager = AppWidgetManager.getInstance(c); Set<String> kinds = new HashSet<>();
        if (manager == null) return kinds;
        for (String kind : new String[]{WidgetSnapshot.TOKENS, WidgetSnapshot.CALENDAR, WidgetSnapshot.STATUS, WidgetSnapshot.WECHAT})
            if (manager.getAppWidgetIds(new ComponentName(c, provider(kind))).length > 0) kinds.add(kind);
        if (manager.getAppWidgetIds(new ComponentName(c, GodWidget.class)).length > 0) kinds.add(WidgetSnapshot.GOD);
        return WidgetSnapshot.routes(kinds);
    }
    static boolean placed(Context c) { return !routes(c).isEmpty(); }
    /** A widget was placed or the phone restarted: keep the background check running, reading at once when one is due. */
    static void watch(Context c) { if (!PreviewMode.isBuild() && placed(c)) TiboWatch.start(c, TiboWatch.checkDue(c)); }
    /**
     * The background check's reads for the placed widgets, on its thread. A route 我 → 通知 has just read in the same check
     * is skipped. Each read goes through {@link GodWidget#observe}, so God 小组件 and these widgets redraw from it. When a
     * route was skipped or failed they are redrawn anyway: after God is updated ColorOS shows a placed widget's initialLayout until the next
     * update, so every check puts the drawn widget back within a couple of minutes.
     */
    static void read(Context c, MobileApi api, long now) {
        SharedPreferences p = prefs(c); boolean drawn = true;
        for (String route : routes(c)) {
            if (WidgetSnapshot.justRead(p.getLong(WidgetSnapshot.kind(route) + "At", 0), now)) { drawn = false; continue; }
            try { GodWidget.observe(c, route, api.get(route)); Log.i("GodWidgets", "read " + route); }
            catch (Exception e) { drawn = false; Log.w("GodWidgets", "read " + route + " failed " + (e instanceof MobileApi.ApiError ? "http=" + ((MobileApi.ApiError) e).status : e.getClass().getSimpleName())); }
        }
        if (!drawn) { refreshAll(c); GodWidget.refresh(c); }
    }
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
    // Up to 560dp tall so a 4 × 4 or 4 × 5 placement gets a layout with rows to match; RemoteViews takes at most 16 sizes.
    private static final int[][] SIZES = {{110, 110}, {110, 150}, {110, 200}, {110, 260}, {110, 330}, {110, 400}, {110, 480}, {110, 560},
        {200, 110}, {200, 150}, {200, 200}, {200, 260}, {200, 330}, {200, 400}, {200, 480}, {200, 560}};
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
            case WidgetSnapshot.TOKENS: return tokens(c, data, stale, at, now, zone, body - 2, f, w >= 200 * f, roomy); // two columns only while names stay legible
            case WidgetSnapshot.CALENDAR: return calendar(c, data, stale, at, now, zone, Math.max(1, Math.min(5, (int) ((body - 3) / (6 + 19 * f)))), roomy);
            case WidgetSnapshot.STATUS: {
                int issues = data == null ? 0 : data.optInt("issues");
                String value = data == null ? "—" : String.valueOf(data.optInt("online")), unit = data == null ? "" : "/ " + data.optInt("total") + " 在线";
                String line = data == null ? "打开 God 后显示" : issues > 0 ? issues + " 项异常" : "全部正常";
                int dot = data == null ? 0 : stale ? R.color.widget_faint : issues > 0 ? R.color.widget_alert : R.color.widget_ok;
                RemoteViews v = figure(c, "运行状态", "pulse", 0xff22c55e, "status", 23, value, unit, line, dot, stale || data == null, at, now, zone, roomy);
                int total = data == null ? 0 : data.optInt("total");
                if (total > 0) { v.setViewVisibility(R.id.wf_bar, View.VISIBLE); v.setProgressBar(R.id.wf_bar, total, Math.max(0, Math.min(total, data.optInt("online"))), false);
                    if (Build.VERSION.SDK_INT >= 31) v.setColorStateList(R.id.wf_bar, "setProgressTintList", stale ? R.color.widget_faint : issues > 0 ? R.color.widget_warn : R.color.widget_ok); }
                return v;
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
    /**
     * Token 额度 with {@code lines} rows of room: one column with reset times while every account fits, else two columns
     * (when {@code twoUp}) so all of them show; any still left over are counted as 「+N」 in the corner.
     */
    private static RemoteViews tokens(Context c, JSONObject data, boolean stale, long at, long now, ZoneId zone, float room, float f, boolean wide, boolean roomy) {
        // A list line is its spacing, a 13sp name and the bar; Android 12+ packs the two-column lines tighter (thinner bar, less space).
        boolean tight = Build.VERSION.SDK_INT >= 31; float listRow = 16 + 19 * f, gridRow = tight ? 10 + 18 * f : listRow;
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_tokens); tap(c, v, android.R.id.background, "tokens", 21);
        JSONArray list = data == null ? null : data.optJSONArray("rows"); int n = list == null ? 0 : list.length(), total = data == null ? 0 : Math.max(n, data.optInt("total", n));
        int lines = Math.max(1, Math.min(TOKEN_LINES.length, (int) (room / listRow))); boolean grid = wide && total > lines;
        if (grid) lines = Math.max(1, Math.min(TOKEN_LINES.length, (int) (room / gridRow)));
        int per = grid ? 2 : 1, shown = Math.min(n, lines * per), more = total - shown;
        String meta = data == null ? null : (more > 0 ? "+" + more + (roomy ? " · " : "") : "") + (roomy ? WidgetSnapshot.meta(at, now, zone) : "");
        header(c, v, "Token 额度", "gauge", 0xff6366f1, meta == null || meta.isEmpty() ? null : meta, stale);
        StringBuilder said = new StringBuilder("Token 额度");
        // The lines share the height left below them by weight (widget_tokens.xml), so a short list or a cell taller than its size step leaves no gap.
        float density = c.getResources().getDisplayMetrics().density;
        for (int r = 0; r < TOKEN_LINES.length; r++) { v.setViewVisibility(TOKEN_LINES[r], r == 0 || r * per < shown ? View.VISIBLE : View.GONE); v.setViewPadding(TOKEN_LINES[r], 0, Math.round((grid && tight ? r == 0 ? 6 : 4 : r == 0 ? 9 : 7) * density), 0, 0); }
        for (int k = 0; k < TOKEN_IDS.length; k++) {
            int[] id = TOKEN_IDS[k]; int slot = grid ? k : k % 2 == 0 ? k / 2 : -1; JSONObject row = slot < 0 || slot >= shown ? null : list.optJSONObject(slot);
            if (row == null) {
                boolean note = k == 0 && n == 0; v.setViewVisibility(id[0], note ? View.VISIBLE : k % 2 == 1 && grid && k / 2 * 2 < shown ? View.INVISIBLE : View.GONE);
                if (note) { v.setTextViewText(id[2], data == null ? "打开 God 后显示" : "没有已接入的账号"); tint(v, id[2], R.color.widget_muted); v.setTextViewText(id[3], ""); v.setTextViewText(id[4], ""); v.setViewVisibility(id[5], View.GONE); v.setViewVisibility(id[1], View.GONE); }
                continue;
            }
            boolean fresh = WidgetSnapshot.fresh(row) && !stale; int level = WidgetSnapshot.level(row);
            v.setViewVisibility(id[0], View.VISIBLE); v.setTextViewText(id[2], WidgetSnapshot.label(row, grid)); v.setTextViewText(id[3], wide && !grid ? WidgetSnapshot.resetNote(row, zone, now) : ""); v.setTextViewText(id[4], WidgetSnapshot.value(row));
            v.setViewVisibility(id[1], View.VISIBLE); tint(v, id[1], kindColor(row.optString("kind")));
            if (grid) { v.setTextViewTextSize(id[2], android.util.TypedValue.COMPLEX_UNIT_SP, 12); v.setTextViewTextSize(id[4], android.util.TypedValue.COMPLEX_UNIT_SP, 13); }
            if (grid && Build.VERSION.SDK_INT >= 31) { v.setViewLayoutHeight(id[5], 3, android.util.TypedValue.COMPLEX_UNIT_DIP); v.setViewLayoutMargin(id[5], RemoteViews.MARGIN_TOP, 3, android.util.TypedValue.COMPLEX_UNIT_DIP); }
            int color = !fresh || level < 0 ? R.color.widget_faint : level == 2 ? R.color.widget_alert : level == 1 ? R.color.widget_warn : R.color.widget_ok;
            tint(v, id[2], stale ? R.color.widget_muted : R.color.widget_ink); tint(v, id[4], !fresh || level < 0 ? R.color.widget_faint : level == 2 ? R.color.widget_alert : R.color.widget_ink);
            v.setViewVisibility(id[5], level < 0 ? View.GONE : View.VISIBLE);
            if (level >= 0) { v.setProgressBar(id[5], 100, (int) Math.max(2, Math.min(100, row.optLong("percent"))), false); if (Build.VERSION.SDK_INT >= 31) v.setColorStateList(id[5], "setProgressTintList", color); }
            said.append("，").append(WidgetSnapshot.tokenLine(row, zone, now));
        }
        if (more > 0) said.append("，另有 ").append(more).append(" 个账号放不下");
        v.setContentDescription(android.R.id.background, said + "，" + WidgetSnapshot.footer(at, now, zone));
        return v;
    }
    /** The dot colour of an account kind or product, the same families 编辑看板 uses. */
    private static int kindColor(String kind) {
        switch (WidgetSnapshot.family(kind)) {
            case "openai": return R.color.widget_openai;
            case "claude": return R.color.widget_claude;
            case "grok": return R.color.widget_grok;
            case "green": return R.color.widget_green;
            default: return R.color.widget_faint;
        }
    }
    private static RemoteViews calendar(Context c, JSONObject data, boolean stale, long at, long now, ZoneId zone, int fit, boolean roomy) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_calendar); tap(c, v, android.R.id.background, "quota-calendar", 22);
        String footer = WidgetSnapshot.footer(at, now, zone); header(c, v, "重置日历", "calendar", 0xff8b5cf6, data == null || !roomy ? null : WidgetSnapshot.meta(at, now, zone), stale);
        JSONArray list = data == null ? null : data.optJSONArray("rows"); StringBuilder said = new StringBuilder("重置日历"); String lastDay = null;
        for (int i = 0; i < CALENDAR_IDS.length; i++) {
            int[] id = CALENDAR_IDS[i]; JSONObject row = list == null || i >= fit ? null : list.optJSONObject(i);
            if (row == null) {
                v.setViewVisibility(id[0], i == 0 ? View.VISIBLE : View.GONE);
                v.setViewVisibility(id[4], View.GONE);
                if (i == 0) { v.setTextViewText(id[1], ""); v.setTextViewText(id[2], ""); v.setTextViewText(id[3], data == null ? "打开 God 后显示" : "近期没有已知的重置"); }
                continue;
            }
            long when = row.optLong("at"); String day = WidgetSnapshot.day(when, zone, now);
            v.setViewVisibility(id[4], row.has("product") ? View.VISIBLE : View.GONE); tint(v, id[4], stale ? R.color.widget_faint : kindColor(row.optString("product")));
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
