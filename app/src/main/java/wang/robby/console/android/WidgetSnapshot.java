package wang.robby.console.android;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * What the home screen widgets may show, cut down from a successful read: product names, remaining percentages,
 * reset times and counts. Never an email, account id, site or conversation name, or message text, because a widget is
 * drawn outside God's FLAG_SECURE window. Each kind keeps its own reading time and reads as stale after two hours.
 */
final class WidgetSnapshot {
    private WidgetSnapshot() {}
    static final String TOKENS = "tokens", CALENDAR = "calendar", STATUS = "status", WECHAT = "wechat";
    static final long STALE_MS = 2 * 3600_000L;
    static final int TOKEN_ROWS = 6, CALENDAR_ROWS = 5, CLOCKS = 3;
    static final double LOW = 20;
    static final String STALE_TEXT = "打开 God 更新";
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm"), MD_HM = DateTimeFormatter.ofPattern("M/d HH:mm");

    /** Token 额度: up to six accounts as product, the tightest remaining percent and the first window's reset, least left first. */
    static JSONObject tokens(JSONObject quotas, Set<String> hidden) {
        if (quotas == null) return null;
        List<JSONObject> rows = new ArrayList<>();
        for (JSONObject a : FeatureRenderer.tokenAccounts(quotas)) {
            if (a == null || !TokensModel.shownOnPage(a) || hidden.contains(a.optString("id"))) continue;
            List<TokensModel.Period> periods = TokensModel.boardWindows(a);
            Double min = null; for (TokensModel.Period p : periods) { Double pc = TokensModel.remaining(p.window); if (pc != null && (min == null || pc < min)) min = pc; }
            JSONObject row = new JSONObject();
            put(row, "name", TokensModel.productName(a));
            put(row, "percent", min == null ? JSONObject.NULL : (Object) Math.round(min));
            put(row, "resetAt", periods.isEmpty() ? 0L : instant(periods.get(0).window.opt("resetsAt")));
            rows.add(row);
        }
        rows.sort(Comparator.comparingLong(r -> r.isNull("percent") ? Long.MAX_VALUE : r.optLong("percent")));
        JSONArray out = new JSONArray(); for (int i = 0; i < Math.min(TOKEN_ROWS, rows.size()); i++) out.put(rows.get(i));
        return wrap(out);
    }
    /** 重置日历: the next five scheduled resets of the connected accounts, as the platform and window only. */
    static JSONObject calendar(JSONObject quotas, ZoneId zone, long now) {
        if (quotas == null) return null;
        QuotaCalendar.Model m = QuotaCalendar.build(FeatureRenderer.tokenAccounts(quotas), zone, now);
        List<QuotaCalendar.Event> next = new ArrayList<>();
        for (QuotaCalendar.Event e : m.events) if (e.at > now && QuotaCalendar.SCHEDULED.equals(e.status)) next.add(e);
        next.sort(Comparator.comparingLong(e -> e.at));
        JSONArray out = new JSONArray();
        for (int i = 0; i < Math.min(CALENDAR_ROWS, next.size()); i++) {
            JSONObject row = new JSONObject(); put(row, "at", next.get(i).at); put(row, "text", next.get(i).platform + " " + next.get(i).shortLabel()); out.put(row);
        }
        return wrap(out);
    }
    /** 运行状态: sites online of those known, and how many sites or upstreams are down; null when the summary is missing. */
    static JSONObject status(JSONObject overview) {
        JSONObject s = overview == null ? null : overview.optJSONObject("status");
        JSONObject sum = s == null ? null : s.optJSONObject("summary");
        if (sum == null) return null;
        int online = 0, total = 0, issues = 0;
        for (String k : new String[]{"public", "private"}) {
            int on = sum.optInt(k + "Online", -1), known = sum.optInt(k + "Known", -1), all = sum.optInt(k + "Total", -1);
            if (on < 0 || all < 0) return null;
            online += on; total += all; if (known >= on) issues += known - on;
        }
        int upOk = sum.optInt("upstreamOperational", -1), upAll = sum.optInt("upstreamTotal", -1);
        if (upOk >= 0 && upAll >= upOk) issues += upAll - upOk;
        JSONObject out = new JSONObject(); put(out, "online", online); put(out, "total", total); put(out, "issues", issues); return out;
    }
    /** 微信消息: today's count and the last seven days' daily average from a valid digest; no conversation names. */
    static JSONObject wechat(JSONObject envelope) {
        WechatDigest.Doc d = envelope == null ? null : WechatDigest.parseResponse(envelope);
        if (d == null) return null;
        JSONObject out = new JSONObject(); put(out, "today", d.today.messages); put(out, "average7d", Math.round(d.last7d.messages / 7.0));
        put(out, "archivedAt", d.archiveTime.toEpochMilli()); return out;
    }

    /** A reading is shown as current only when taken within two hours and not in the future. */
    static boolean stale(long savedAt, long now) { return savedAt <= 0 || now - savedAt > STALE_MS || savedAt > now + 60_000L; }
    static String footer(long savedAt, long now, ZoneId zone) { return stale(savedAt, now) ? STALE_TEXT : HM.format(Instant.ofEpochMilli(savedAt).atZone(zone)) + " 更新"; }
    /** 「Claude  72% · 14:30 重置」, the reset written as a clock time so the widget never needs to tick. */
    static String tokenLine(JSONObject row, ZoneId zone, long now) {
        String name = row.optString("name"), pct = row.isNull("percent") ? "暂无读数" : row.optLong("percent") + "%";
        long reset = row.optLong("resetAt");
        return name + "  " + pct + (reset > now ? " · " + when(reset, zone, now) + " 重置" : "");
    }
    static boolean low(JSONObject row) { return !row.isNull("percent") && row.optLong("percent") < LOW; }
    static String calendarLine(JSONObject row, ZoneId zone, long now) { return when(row.optLong("at"), zone, now) + " · " + row.optString("text"); }
    /** Today as HH:mm, tomorrow as 明天 HH:mm, later as M/d HH:mm, in the phone's zone. */
    static String when(long at, ZoneId zone, long now) {
        ZonedDateTime t = Instant.ofEpochMilli(at).atZone(zone); LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        if (t.toLocalDate().equals(today)) return HM.format(t);
        if (t.toLocalDate().equals(today.plusDays(1))) return "明天 " + HM.format(t);
        return MD_HM.format(t);
    }
    /** The first three 时区时钟 of 编辑看板, or the defaults (Tibo, 北京时间, PST) when never edited. */
    static List<Dashboard.Clock> clocks(String saved) {
        List<Dashboard.Clock> all = Dashboard.clocks(saved); return new ArrayList<>(all.subList(0, Math.min(CLOCKS, all.size())));
    }

    private static JSONObject wrap(JSONArray rows) { JSONObject o = new JSONObject(); put(o, "rows", rows); return o; }
    private static long instant(Object v) { if (!(v instanceof String)) return 0; try { return Instant.parse((String) v).toEpochMilli(); } catch (RuntimeException e) { return 0; } }
    private static void put(JSONObject o, String k, Object v) { try { o.put(k, v); } catch (JSONException e) { throw new IllegalStateException(e); } }
}
