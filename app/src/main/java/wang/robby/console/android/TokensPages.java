package wang.robby.console.android;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.ref.WeakReference;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.LongFunction;

/** 重置日历 and 28 天挑战, the two tabs of tokens.robby.wang beside 账号额度, plus the Tibo 重置提醒 switch. */
final class TokensPages {
    private TokensPages() {}
    /** Implemented by the host Activity: switching needs permissions and settings pages. */
    interface Host { void tiboSwitch(boolean on); void tiboTest(); void tiboFix(String what); }
    // Selection is process memory only, like the web's page state.
    private static YearMonth month; private static LocalDate day; private static int challengeDay, eventIndex;
    private static WeakReference<Runnable> reminder = new WeakReference<>(null);
    private static final String[] WEEKDAYS = {"一", "二", "三", "四", "五", "六", "日"};

    // ---------------------------------------------------------------- 重置日历

    static void calendar(Context ctx, LinearLayout root, JSONObject p) {
        List<JSONObject> accounts = new ArrayList<>(); for (String key : new String[]{"accounts", "additionalAccounts"}) { JSONArray a = p.optJSONArray(key); if (a != null) for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null) accounts.add(o); } }
        QuotaCalendar.Model m = QuotaCalendar.build(accounts, ZoneId.systemDefault(), System.currentTimeMillis());
        if (day == null || month == null) { day = m.today; month = YearMonth.from(m.today); }
        LinearLayout box = new LinearLayout(ctx); box.setOrientation(LinearLayout.VERTICAL); root.addView(box);
        drawCalendar(ctx, new ConsoleTheme(ctx), box, m);
    }
    private static void drawCalendar(Context ctx, ConsoleTheme t, LinearLayout box, QuotaCalendar.Model m) {
        box.removeAllViews();
        LinearLayout head = t.card(box); LinearLayout top = new LinearLayout(ctx); top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = t.text("额度日历", 17, true); title.setAccessibilityHeading(true); top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView zone = t.text(TimeZone.getTimeZone(m.zone).getDisplayName(TimeZone.getTimeZone(m.zone).inDaylightTime(new Date()), TimeZone.LONG, Locale.SIMPLIFIED_CHINESE), 12, false); zone.setTextColor(t.faint); top.addView(zone); head.addView(top);
        TextView summary = t.text(QuotaCalendar.monthSummary(m, month), 13, false); summary.setPadding(0, t.dp(4), 0, 0); head.addView(summary);

        LinearLayout c = t.card(box); c.setPadding(t.dp(4), t.dp(12), t.dp(4), t.dp(12));
        LinearLayout bar = new LinearLayout(ctx); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(t.dp(6), 0, 0, t.dp(8));
        TextView label = t.text(month.getYear() + " 年 " + month.getMonthValue() + " 月", 16, true); bar.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(link(t, "‹", "上一个月", () -> { month = month.minusMonths(1); day = clamp(day, month); drawCalendar(ctx, t, box, m); }));
        bar.addView(link(t, "今天", "回到今天", () -> { day = m.today; month = YearMonth.from(m.today); drawCalendar(ctx, t, box, m); }));
        bar.addView(link(t, "›", "下一个月", () -> { month = month.plusMonths(1); day = clamp(day, month); drawCalendar(ctx, t, box, m); }));
        c.addView(bar);
        LinearLayout names = new LinearLayout(ctx); names.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        for (String w : WEEKDAYS) { TextView n = t.text(w, 11, false); n.setTextColor(t.faint); n.setGravity(Gravity.CENTER); names.addView(n, new LinearLayout.LayoutParams(0, -2, 1)); }
        c.addView(names);
        List<LocalDate> grid = QuotaCalendar.grid(month);
        for (int week = 0; week < 6; week++) {
            LinearLayout row = new LinearLayout(ctx);
            for (int i = 0; i < 7; i++) {
                LocalDate d = grid.get(week * 7 + i); List<QuotaCalendar.Event> events = m.on(d);
                boolean inMonth = YearMonth.from(d).equals(month), today = d.equals(m.today), selected = d.equals(day);
                // As the web's phone layout: the day number, then every reset of that day as a small block in the cell.
                LinearLayout cell = new LinearLayout(ctx); cell.setOrientation(LinearLayout.VERTICAL); cell.setMinimumHeight(t.dp(64)); cell.setPadding(t.dp(1), t.dp(3), t.dp(1), t.dp(4));
                GradientDrawable bg = t.shape(selected ? t.soft : Color.TRANSPARENT, 0); bg.setStroke(Math.max(1, t.dp(.5f)), selected ? t.accent : t.line);
                cell.setBackground(t.ripple(bg, 0)); cell.setFocusable(true); cell.setClickable(true);
                TextView number = t.text(String.valueOf(d.getDayOfMonth()), 12, today || selected); number.setGravity(Gravity.CENTER); number.setIncludeFontPadding(false);
                number.setTextColor(today ? Color.WHITE : !inMonth ? t.faint : t.ink); if (today) number.setBackground(t.shape(t.accent, 99)); if (!inMonth) number.setAlpha(.55f);
                LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(t.dp(22), t.dp(22)); np.leftMargin = t.dp(2); np.bottomMargin = t.dp(2); cell.addView(number, np);
                for (QuotaCalendar.Event e : events) cell.addView(chip(ctx, t, e, m.zone), chipParams(t));
                if (!inMonth && !events.isEmpty()) cell.setAlpha(.6f);
                StringBuilder say = new StringBuilder(d.getMonthValue() + "月" + d.getDayOfMonth() + "日" + (today ? "，今天" : "") + "，" + events.size() + " 项额度重置");
                for (QuotaCalendar.Event e : events) say.append("，").append(time(e.at, m.zone)).append(" ").append(e.platform).append(" ").append(e.account).append(" ").append(e.shortLabel()).append(QuotaCalendar.statusLabel(e) == null ? "" : " " + QuotaCalendar.statusLabel(e));
                cell.setContentDescription(say); cell.setSelected(selected);
                cell.setOnClickListener(v -> { day = d; month = YearMonth.from(d); drawCalendar(ctx, t, box, m); });
                row.addView(cell, new LinearLayout.LayoutParams(0, -1, 1));
            }
            c.addView(row);
        }

        List<QuotaCalendar.Event> chosen = m.on(day);
        LinearLayout d = t.card(box); LinearLayout dh = new LinearLayout(ctx); dh.setGravity(Gravity.CENTER_VERTICAL);
        TextView dl = t.text(day.getMonthValue() + " 月 " + day.getDayOfMonth() + " 日 周" + WEEKDAYS[day.getDayOfWeek().getValue() - 1], 16, true); dl.setAccessibilityHeading(true); dh.addView(dl, new LinearLayout.LayoutParams(0, -2, 1));
        TextView count = t.text(chosen.size() + " 项", 13, false); dh.addView(count); d.addView(dh);
        if (chosen.isEmpty()) { TextView none = t.text("这一天暂无已知重置", 14, false); none.setPadding(0, t.dp(10), 0, t.dp(4)); d.addView(none); }
        for (QuotaCalendar.Event e : chosen) {
            View line = new View(ctx); line.setBackgroundColor(t.line); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, t.dp(.5f))); lp.topMargin = t.dp(10); d.addView(line, lp);
            LinearLayout r = new LinearLayout(ctx); r.setPadding(0, t.dp(10), 0, 0); boolean live = QuotaCalendar.SCHEDULED.equals(e.status);
            TextView at = t.text(time(e.at, m.zone), 15, true); at.setTextColor(live ? t.ink : t.muted); r.addView(at, new LinearLayout.LayoutParams(t.dp(56), -2));
            LinearLayout col = new LinearLayout(ctx); col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout who = new LinearLayout(ctx); who.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = t.text(e.account, 14, true); name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END); who.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            View dot = t.dot(live ? tint(t, e.product) : t.faint, 6); ((LinearLayout.LayoutParams) dot.getLayoutParams()).setMargins(t.dp(8), 0, t.dp(4), 0); who.addView(dot);
            TextView platform = t.text(e.platform, 12, false); platform.setTextColor(live ? tint(t, e.product) : t.muted); who.addView(platform); col.addView(who);
            LinearLayout bottom = new LinearLayout(ctx); TextView l = t.text(e.label, 12, false); bottom.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
            String state = QuotaCalendar.statusLabel(e); if (state != null) { TextView s = t.text(state, 12, false); s.setTextColor(QuotaCalendar.STALE.equals(e.status) ? t.warning : t.faint); bottom.addView(s); }
            col.addView(bottom); r.addView(col, new LinearLayout.LayoutParams(0, -2, 1)); d.addView(r);
            r.setContentDescription(time(e.at, m.zone) + "，" + e.account + "，" + e.platform + "，" + e.label + (state == null ? "" : "，" + state));
        }
        String missing = QuotaCalendar.missing(m);
        if (missing != null) { TextView foot = t.text(missing, 12, false); foot.setTextColor(t.faint); foot.setPadding(t.dp(6), t.dp(2), t.dp(6), 0); box.addView(foot); }
        box.addView(t.info(List.of("只展示平台已提供的重置日期，不按周期推算。", "时间按本机时区显示；灰色圆点表示已过期或读数待核实。")));
    }
    /** One reset inside a day cell: provider-coloured left edge on a light wash; time, platform, account and window. */
    private static View chip(Context ctx, ConsoleTheme t, QuotaCalendar.Event e, ZoneId zone) {
        boolean live = QuotaCalendar.SCHEDULED.equals(e.status); int tint = live ? tint(t, e.product) : t.faint;
        LinearLayout chip = new LinearLayout(ctx); chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setBackground(t.shape((tint & 0x00ffffff) | (t.dark ? 0x33000000 : 0x1f000000), 3));
        View edge = new View(ctx); edge.setBackgroundColor(tint); chip.addView(edge, new LinearLayout.LayoutParams(t.dp(2), -1));
        LinearLayout lines = new LinearLayout(ctx); lines.setOrientation(LinearLayout.VERTICAL); lines.setPadding(t.dp(2), t.dp(2), t.dp(1), t.dp(2));
        lines.addView(tiny(t, time(e.at, zone), live ? t.ink : t.muted, true, 1));
        lines.addView(tiny(t, e.platform, live ? t.ink : t.muted, true, 2));
        lines.addView(tiny(t, QuotaCalendar.shortAccount(e.account), t.muted, false, 2));
        lines.addView(tiny(t, e.shortLabel(), t.muted, false, 2));
        String state = QuotaCalendar.statusLabel(e); if (state != null) lines.addView(tiny(t, state, t.faint, false, 1));
        chip.addView(lines, new LinearLayout.LayoutParams(0, -2, 1));
        chip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        return chip;
    }
    private static TextView tiny(ConsoleTheme t, String value, int color, boolean bold, int lines) {
        TextView v = t.text(value, 9, bold); v.setTextColor(color); v.setMaxLines(lines); v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.setIncludeFontPadding(false); v.setLineSpacing(0, 1f); v.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE); return v;
    }
    private static LinearLayout.LayoutParams chipParams(ConsoleTheme t) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = t.dp(3); return p; }
    private static LocalDate clamp(LocalDate d, YearMonth m) { return m.atDay(Math.min(d.getDayOfMonth(), m.lengthOfMonth())); }
    private static String time(long at, ZoneId zone) { return DateTimeFormatter.ofPattern("HH:mm").format(java.time.Instant.ofEpochMilli(at).atZone(zone)); }
    /** The same provider colours as the Token 额度 tiles. */
    static int tint(ConsoleTheme t, String product) {
        switch (product) {
            case "codex": case "chatgpt-dots": case "chatgpt-chat": return t.accent;
            case "claude": return t.dark ? 0xffe39a7e : 0xffb8532f;
            case "grok": case "grok-bot": return t.dark ? 0xffb19cf5 : 0xff6a4cc0;
            case "cursor": case "today-cn": case "today-global": return t.dark ? 0xff6fcfab : 0xff1d7f5f;
            default: return t.muted;
        }
    }

    // ---------------------------------------------------------------- 28 天挑战

    static void challenge(Context ctx, LinearLayout root, JSONObject p) {
        TiboChallenge.Model m = new TiboChallenge.Model(p);
        if (challengeDay < 1 || challengeDay > 28) { challengeDay = m.defaultDay(); eventIndex = 0; }
        LinearLayout box = new LinearLayout(ctx); box.setOrientation(LinearLayout.VERTICAL); root.addView(box);
        drawChallenge(ctx, new ConsoleTheme(ctx), box, m);
    }
    private static void drawChallenge(Context ctx, ConsoleTheme t, LinearLayout box, TiboChallenge.Model m) {
        box.removeAllViews(); long now = System.currentTimeMillis(); ZoneId zone = ZoneId.systemDefault();
        LinearLayout head = t.card(box);
        LinearLayout top = new LinearLayout(ctx); top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = t.text("Tibo 28 天挑战", 19, true); title.setAccessibilityHeading(true); top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView progress = t.text("第 " + m.currentDay + " 天 · " + m.recorded + "/28 天有记录", 12, false); top.addView(progress); head.addView(top);
        TextView clock = t.text("", 12, false); clock.setTextColor(t.faint); clock.setPadding(0, t.dp(4), 0, 0); head.addView(clock);
        tick(clock, at -> "Tibo 当前时间 · 美西 " + TiboChallenge.tiboClock(at));

        LinearLayout cd = t.card(box);
        if (m.upcoming != null) {
            TiboChallenge.Post next = m.upcoming;
            TextView kind = t.text((next.conditional ? "有条件的预告 · " : "") + (next.cards() ? "官方发卡预告" : "官方重置预告") + (m.current(now) ? "" : " · 数据待更新"), 13, false); cd.addView(kind);
            TextView big = t.text("", 30, true); big.setTextColor(t.danger); big.setPadding(0, t.dp(4), 0, t.dp(2)); cd.addView(big);
            if (next.deadline > 0) tick(big, at -> TiboChallenge.countdown(next.deadline, at)); else big.setText("已预告，时间未公布");
            LinearLayout line = new LinearLayout(ctx); line.setGravity(Gravity.CENTER_VERTICAL);
            TextView when = t.text(next.deadline > 0 ? TiboChallenge.format(next.deadline, zone) : "时间未公布", 13, false); line.addView(when, new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(link(t, "原帖 ↗", "查看 Tibo 原帖", () -> open(ctx, next.url))); cd.addView(line);
        } else { TextView none = t.text("尚无明确的下一次重置时间", 15, false); cd.addView(none); }
        if (m.latest != null) { TextView latest = t.text("最近确认：" + m.latest.title + " · " + TiboChallenge.format(m.latest.published, zone), 12, false); latest.setPadding(0, t.dp(8), 0, 0); cd.addView(latest); }

        LinearLayout remind = new LinearLayout(ctx); remind.setOrientation(LinearLayout.VERTICAL); box.addView(remind);
        Runnable redraw = () -> { remind.removeAllViews(); reminderCard(ctx, t, remind); }; redraw.run(); reminder = new WeakReference<>(redraw);

        LinearLayout grid = t.card(box); grid.setPadding(t.dp(10), t.dp(12), t.dp(10), t.dp(12));
        List<TiboChallenge.Cell> cells = m.cells();
        for (int week = 0; week < 4; week++) {
            LinearLayout row = new LinearLayout(ctx);
            for (int i = 0; i < 7; i++) {
                TiboChallenge.Cell cell = cells.get(week * 7 + i); boolean selected = cell.day == challengeDay;
                LinearLayout v = new LinearLayout(ctx); v.setOrientation(LinearLayout.VERTICAL); v.setGravity(Gravity.CENTER_HORIZONTAL); v.setPadding(0, t.dp(6), 0, t.dp(6)); v.setMinimumHeight(t.dp(58));
                GradientDrawable bg = t.shape(selected ? t.soft : Color.TRANSPARENT, 12); if (selected) bg.setStroke(t.dp(1.2f), t.accent); else if (cell.today) bg.setStroke(t.dp(1), t.line);
                v.setBackground(t.ripple(bg, 12)); v.setFocusable(true); v.setClickable(true);
                TextView n = t.text(String.valueOf(cell.day), 15, true); n.setGravity(Gravity.CENTER); n.setTextColor(cell.day > m.currentDay ? t.faint : t.ink); v.addView(n);
                TextView date = t.text(cell.date.getMonthValue() + "/" + cell.date.getDayOfMonth(), 9, false); date.setTextColor(t.faint); date.setGravity(Gravity.CENTER); v.addView(date);
                TextView mark = t.text(cell.marker, 9, false); mark.setGravity(Gravity.CENTER); mark.setTextColor(markerColor(t, cell.marker)); mark.setPadding(0, t.dp(2), 0, 0); v.addView(mark);
                v.setContentDescription("第" + cell.day + "天 " + cell.date.getMonthValue() + "月" + cell.date.getDayOfMonth() + "日 " + cell.marker + (cell.brief.isEmpty() ? "" : "，" + cell.brief)); v.setSelected(selected);
                v.setOnClickListener(x -> { challengeDay = cell.day; eventIndex = 0; drawChallenge(ctx, t, box, m); });
                row.addView(v, new LinearLayout.LayoutParams(0, -2, 1));
            }
            grid.addView(row);
        }

        LinearLayout d = t.card(box); TiboChallenge.Cell chosen = cells.get(challengeDay - 1);
        LinearLayout dh = new LinearLayout(ctx); dh.setGravity(Gravity.CENTER_VERTICAL);
        TextView dl = t.text("Day " + challengeDay + " · " + chosen.date.getMonthValue() + "/" + chosen.date.getDayOfMonth(), 16, true); dl.setAccessibilityHeading(true); dh.addView(dl, new LinearLayout.LayoutParams(0, -2, 1));
        List<TiboChallenge.Post> posts = m.on(challengeDay); TiboChallenge.Summary auto = m.summary(challengeDay);
        if (auto == null && !posts.isEmpty()) {
            int index = Math.max(0, Math.min(eventIndex, posts.size() - 1));
            if (posts.size() > 1) { dh.addView(link(t, "‹", "上一条当天进展", () -> { eventIndex = Math.max(0, index - 1); drawChallenge(ctx, t, box, m); }));
                TextView pos = t.text((index + 1) + "/" + posts.size(), 12, false); pos.setPadding(t.dp(4), 0, t.dp(4), 0); dh.addView(pos);
                dh.addView(link(t, "›", "下一条当天进展", () -> { eventIndex = Math.min(posts.size() - 1, index + 1); drawChallenge(ctx, t, box, m); })); }
            d.addView(dh); TiboChallenge.Post e = posts.get(index);
            LinearLayout chips = new LinearLayout(ctx); chips.setPadding(0, t.dp(8), 0, 0); pill(t, chips, e.kindLabel(), e.kind.endsWith("completed") ? t.success : TiboChallenge.INTENT.contains(e.kind) ? t.danger : t.accent); d.addView(chips);
            TextView h = t.text(e.title, 15, true); h.setPadding(0, t.dp(8), 0, 0); d.addView(h);
            if (!e.excerpt.isEmpty()) { TextView x = t.text(e.excerpt, 14, false); x.setPadding(0, t.dp(4), 0, 0); d.addView(x); }
            LinearLayout foot = new LinearLayout(ctx); foot.setGravity(Gravity.CENTER_VERTICAL); foot.setPadding(0, t.dp(6), 0, 0);
            TextView at = t.text(TiboChallenge.format(e.published, zone), 12, false); foot.addView(at, new LinearLayout.LayoutParams(0, -2, 1)); foot.addView(link(t, "查看 Tibo 原帖 ↗", "查看 Tibo 原帖", () -> open(ctx, e.url))); d.addView(foot);
        } else if (auto != null) {
            d.addView(dh); TextView sub = t.text("当天发布与重置进展", 13, true); sub.setPadding(0, t.dp(8), 0, 0); d.addView(sub);
            for (TiboChallenge.Item item : auto.items) {
                View line = new View(ctx); line.setBackgroundColor(t.line); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, t.dp(.5f))); lp.topMargin = t.dp(10); d.addView(line, lp);
                TextView h = t.text(item.title, 15, true); h.setPadding(0, t.dp(10), 0, 0); d.addView(h);
                TextView x = t.text(item.summary, 14, false); x.setPadding(0, t.dp(4), 0, 0); d.addView(x);
                String note = TiboChallenge.verificationNote(item, challengeDay, m); if (note != null) { TextView w = t.text(note, 12, false); w.setTextColor(t.warning); w.setPadding(0, t.dp(4), 0, 0); d.addView(w); }
                LinearLayout foot = new LinearLayout(ctx); foot.setGravity(Gravity.END); foot.addView(link(t, "对应原帖 ↗", "查看对应原帖", () -> open(ctx, item.url))); d.addView(foot);
            }
            TextView n = t.text(("connected".equals(m.summaryStatus) || m.summaryStatus == null ? "自动同步" : "保留上次同步内容") + " · 内容更新于 " + (auto.updated > 0 ? DateTimeFormatter.ISO_LOCAL_DATE.format(java.time.Instant.ofEpochMilli(auto.updated).atZone(ZoneId.of("UTC"))) : "未知"), 12, false);
            n.setTextColor(t.faint); n.setPadding(0, t.dp(10), 0, 0); d.addView(n);
            d.addView(t.info(List.of("摘要来自 Codex Radar，已核对原帖作者；摘要的说法不代表你的账号额度已经到账。")));
        } else {
            d.addView(dh); TextView h = t.text("等待官方公布", 15, true); h.setPadding(0, t.dp(8), 0, 0); d.addView(h);
            TextView x = t.text("未收到已核实的当日记录，不代表已经重置额度。", 14, false); x.setPadding(0, t.dp(4), 0, 0); d.addView(x);
        }
        String sync = "自动同步" + (m.syncing() && m.current(now) ? "正常" : "待更新") + " · 最近同步 " + TiboChallenge.format(m.summaryStatus != null ? m.summaryLastSuccess : m.lastSuccess, zone) + " · 每 " + Math.max(1, Math.round(m.interval / 60f)) + " 分钟检查";
        TextView foot = t.text(sync, 12, false); foot.setTextColor(t.faint); foot.setPadding(t.dp(6), t.dp(2), t.dp(6), 0); box.addView(foot);
        List<String> notes = new ArrayList<>(); if (!m.sourceNote.isEmpty()) notes.add(m.sourceNote); notes.add("帖子先由 Codex Radar 发现、再核对 Tibo 本人账号，可能比 X 上晚几分钟；预告时间到了也不会自动算作已重置。");
        box.addView(t.info(notes));
    }
    private static int markerColor(ConsoleTheme t, String marker) {
        switch (marker) { case "已重置": return t.success; case "已发卡": return t.accent; case "有更新": return t.ink; case "今天": return t.warning; default: return t.faint; }
    }

    /** The Tibo 重置提醒 switch, its last check and anything that would keep the phone from ringing. */
    private static void reminderCard(Context ctx, ConsoleTheme t, LinearLayout parent) {
        boolean preview = PreviewMode.isBuild(), on = !preview && TiboWatch.enabled(ctx); Host host = ctx instanceof Host ? (Host) ctx : null;
        LinearLayout c = t.card(parent);
        LinearLayout top = new LinearLayout(ctx); top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(t.tile("bell", 36), new LinearLayout.LayoutParams(t.dp(36), t.dp(36)));
        LinearLayout texts = new LinearLayout(ctx); texts.setOrientation(LinearLayout.VERTICAL); texts.setPadding(t.dp(12), 0, t.dp(8), 0);
        TextView title = t.text("重置提醒", 16, true); texts.addView(title);
        TextView state = t.text(preview ? "预览版不连接服务" : on ? "已开启" : "未开启", 12, false); state.setTextColor(on ? t.success : t.muted); texts.addView(state);
        top.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(ctx); toggle.setChecked(on); toggle.setEnabled(!preview && host != null); toggle.setContentDescription("Tibo 重置提醒");
        toggle.setOnCheckedChangeListener((v, checked) -> { if (host != null) host.tiboSwitch(checked); v.post(TokensPages::refreshReminder); });
        top.addView(toggle); c.addView(top);
        if (on) {
            TextView last = t.text(TiboWatch.status(ctx), 12, false); last.setTextColor(t.faint); last.setPadding(0, t.dp(8), 0, 0); c.addView(last);
            for (String[] problem : TiboWatch.problems(ctx)) {
                LinearLayout r = new LinearLayout(ctx); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(0, t.dp(8), 0, 0);
                View dot = t.dot(t.warning, 7); ((LinearLayout.LayoutParams) dot.getLayoutParams()).rightMargin = t.dp(8); r.addView(dot);
                TextView w = t.text(problem[0], 13, false); w.setTextColor(t.ink); r.addView(w, new LinearLayout.LayoutParams(0, -2, 1));
                if (host != null) r.addView(link(t, "去设置", "去设置：" + problem[0], () -> host.tiboFix(problem[1])));
                c.addView(r);
            }
            if (host != null) { LinearLayout r = new LinearLayout(ctx); r.setGravity(Gravity.END); r.addView(link(t, "打开应用设置", "打开 God 的系统设置", () -> host.tiboFix("app"))); c.addView(r); }
        }
        List<String> notes = new ArrayList<>(List.of("Tibo 发出重置或发卡预告时，用闹钟铃声持续响铃并全屏提醒，直到你点「知道了」；没处理会每 10 分钟再响，最多三轮。", "开启后每 5 分钟检查一次，通知栏常驻一条「已开启」。"));
        if (on) notes.add("OPPO / ColorOS 还需在 设置 → 应用 → God → 耗电管理 中允许后台行为，否则锁屏后检查可能被暂停。");
        c.addView(t.info(notes));
        if (host != null) { android.widget.Button test = t.button("试响一次", host::tiboTest); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = t.dp(12); c.addView(test, lp); }
    }
    /** Redraws the 重置提醒 card after a settings page or permission dialog, if the page is showing. */
    static void refreshReminder() { Runnable r = reminder.get(); if (r != null) r.run(); }

    // ---------------------------------------------------------------- shared

    /** A compact text button, at least 44dp tall for touch. */
    private static TextView link(ConsoleTheme t, String label, String description, Runnable action) {
        TextView v = t.text(label, 13, true); v.setTextColor(t.accent); v.setGravity(Gravity.CENTER); v.setMinHeight(t.dp(44)); v.setMinWidth(t.dp(44)); v.setPadding(t.dp(10), 0, t.dp(10), 0);
        v.setBackground(t.ripple(t.shape(Color.TRANSPARENT, 12), 12)); v.setFocusable(true); v.setClickable(true); v.setContentDescription(description); v.setOnClickListener(x -> action.run());
        return v;
    }
    private static void pill(ConsoleTheme t, LinearLayout parent, String label, int color) {
        LinearLayout chip = new LinearLayout(t.context); chip.setGravity(Gravity.CENTER_VERTICAL); chip.setPadding(t.dp(9), t.dp(4), t.dp(10), t.dp(4)); chip.setBackground(t.shape(t.soft, 99));
        chip.addView(t.dot(color, 6)); TextView v = t.text(label, 12, false); v.setTextColor(t.ink); v.setPadding(t.dp(5), 0, 0, 0); chip.addView(v); parent.addView(chip);
    }
    /** Sets the text now and every second while the view is on screen; the clock never runs off screen. */
    static void tick(TextView v, LongFunction<String> text) {
        v.setText(text.apply(System.currentTimeMillis())); v.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        Runnable[] step = new Runnable[1];
        step[0] = () -> { if (!v.isAttachedToWindow()) return; v.setText(text.apply(System.currentTimeMillis())); v.postDelayed(step[0], 1000 - System.currentTimeMillis() % 1000); };
        v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View x) { v.removeCallbacks(step[0]); v.post(step[0]); }
            @Override public void onViewDetachedFromWindow(View x) { v.removeCallbacks(step[0]); }
        });
        if (v.isAttachedToWindow()) v.post(step[0]);
    }
    /** Opens one of Tibo's verified post URLs; anything else is ignored. */
    static void open(Context ctx, String url) {
        String safe = TiboChallenge.official(url); if (safe == null) return;
        try { ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(safe))); } catch (RuntimeException ignored) { }
    }
}
