package wang.robby.console.android;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * What the home screen widgets may show, cut down from a successful read: product names, an account's email prefix
 * (the part before @, so two accounts of one product tell apart), remaining percentages, reset times and counts. Never a
 * whole email, account id, site or conversation name, or message text, because a widget is drawn outside God's
 * FLAG_SECURE window. Each kind keeps its own reading time and reads as stale after two hours.
 */
final class WidgetSnapshot {
    private WidgetSnapshot() {}
    static final String TOKENS = "tokens", CALENDAR = "calendar", STATUS = "status", WECHAT = "wechat";
    /** God 小组件, which keeps its own reading (see {@link GodWidget}) but is fed by the same background read. */
    static final String GOD = "god";
    /** A route read by 我 → 通知 this recently is not read again for the widgets in the same check. */
    static final long JUST_READ_MS = 60_000L;
    static final long STALE_MS = 2 * 3600_000L;
    static final int TOKEN_ROWS = 12, CALENDAR_ROWS = 5, CLOCKS = 3;
    static final double LOW = 20;
    static final String STALE_TEXT = "打开 God 更新";
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm"), MD_HM = DateTimeFormatter.ofPattern("M/d HH:mm");

    /**
     * Token 额度: every account the 看板 card shows, in its order with the same kind kept together (OpenAI, Claude,
     * Grok, Cursor, TodayAI), as product, email prefix, kind, the tightest remaining percent and the first window's reset.
     * Accounts that share both product and prefix, or the product without an email, are numbered (Codex 1, Codex 2).
     */
    static JSONObject tokens(JSONObject quotas, Set<String> hidden) {
        if (quotas == null) return null;
        List<JSONObject> board = new ArrayList<>(Dashboard.tokenBoardAccounts(FeatureRenderer.tokenAccounts(quotas), hidden));
        board.sort(Comparator.comparingInt(a -> TokensModel.SECTIONS.indexOf(TokensModel.section(a))));
        java.util.Map<String, Integer> count = new java.util.HashMap<>(), seen = new java.util.HashMap<>();
        for (JSONObject a : board) count.merge(TokensModel.productName(a) + "\u0000" + emailPrefix(TokensModel.boardEmail(a)), 1, Integer::sum);
        JSONArray out = new JSONArray();
        for (JSONObject a : board) {
            List<TokensModel.Period> periods = TokensModel.boardWindows(a);
            Double min = null; for (TokensModel.Period p : periods) { Double pc = TokensModel.remaining(p.window); if (pc != null && (min == null || pc < min)) min = pc; }
            String name = TokensModel.productName(a), who = emailPrefix(TokensModel.boardEmail(a)), key = name + "\u0000" + who; int n = seen.merge(key, 1, Integer::sum);
            JSONObject row = new JSONObject(); boolean fresh = "connected".equals(a.optString("status"));
            put(row, "name", count.get(key) > 1 ? name + " " + n : name);
            if (!who.isEmpty()) put(row, "account", who);
            put(row, "kind", TokensModel.section(a));
            put(row, "percent", min == null ? JSONObject.NULL : (Object) Math.round(min));
            put(row, "fresh", fresh);
            if (!fresh) put(row, "state", TokensModel.status(a.optString("status")));
            put(row, "resetAt", periods.isEmpty() ? 0L : instant(periods.get(0).window.opt("resetsAt")));
            if (out.length() < TOKEN_ROWS) out.put(row);
        }
        JSONObject o = wrap(out); put(o, "total", board.size()); return o;
    }
    /** 重置日历: the next five scheduled resets of the connected accounts, as the platform, email prefix and window. */
    static JSONObject calendar(JSONObject quotas, ZoneId zone, long now) {
        if (quotas == null) return null;
        QuotaCalendar.Model m = QuotaCalendar.build(FeatureRenderer.tokenAccounts(quotas), zone, now);
        List<QuotaCalendar.Event> next = new ArrayList<>();
        for (QuotaCalendar.Event e : m.events) if (e.at > now && QuotaCalendar.SCHEDULED.equals(e.status)) next.add(e);
        next.sort(Comparator.comparingLong(e -> e.at));
        JSONArray out = new JSONArray();
        for (int i = 0; i < Math.min(CALENDAR_ROWS, next.size()); i++) {
            QuotaCalendar.Event e = next.get(i); String who = emailPrefix(e.email);
            JSONObject row = new JSONObject(); put(row, "at", e.at); put(row, "text", e.platform + (who.isEmpty() ? "" : " · " + who) + " " + e.shortLabel()); put(row, "product", e.product);
            if (!who.isEmpty()) put(row, "account", who);
            out.put(row);
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
    /** The reads that keep these placed widget kinds current; Token 额度, 重置日历 and God 小组件 share tokens/quotas. */
    static Set<String> routes(Set<String> kinds) {
        Set<String> out = new LinkedHashSet<>();
        if (kinds.contains(TOKENS) || kinds.contains(CALENDAR) || kinds.contains(GOD)) out.add("tokens/quotas");
        if (kinds.contains(STATUS)) out.add("overview");
        if (kinds.contains(WECHAT)) out.add("wechat/digest");
        return out;
    }
    /** The kind whose reading time tells when a route was last read; null for routes no widget reads. */
    static String kind(String route) {
        switch (route) { case "tokens/quotas": return TOKENS; case "overview": return STATUS; case "wechat/digest": return WECHAT; default: return null; }
    }
    static boolean justRead(long savedAt, long now) { return savedAt > 0 && savedAt <= now && now - savedAt < JUST_READ_MS; }
    static boolean stale(long savedAt, long now) { return savedAt <= 0 || now - savedAt > STALE_MS || savedAt > now + 60_000L; }
    /** The widget header's corner: the reading's clock time, or 「待更新」 once stale (the full 「打开 God 更新」 goes to the spoken label). */
    static String meta(long savedAt, long now, ZoneId zone) { return stale(savedAt, now) ? "待更新" : HM.format(Instant.ofEpochMilli(savedAt).atZone(zone)); }
    static String footer(long savedAt, long now, ZoneId zone) { return stale(savedAt, now) ? STALE_TEXT : HM.format(Instant.ofEpochMilli(savedAt).atZone(zone)) + " 更新"; }
    /** An email's name part (robby for robby@example.com), or "" when there is no email. */
    static String emailPrefix(String email) { int at = email == null ? -1 : email.indexOf('@'); return at > 0 ? email.substring(0, at).trim() : ""; }
    /** The account's name on a widget: 「Codex · robby」 with an email prefix, else the product; numbered ones read 「Codex 1 · robby」. */
    static String label(JSONObject row, boolean narrow) {
        String name = narrow ? shortName(row.optString("name")) : row.optString("name"), who = row.optString("account");
        return who.isEmpty() ? name : name + " · " + who;
    }
    /** 「Claude · robby  72% · 14:30 重置」, the reset written as a clock time so the widget never needs to tick. */
    static String tokenLine(JSONObject row, ZoneId zone, long now) {
        String name = label(row, false), pct = row.isNull("percent") ? row.optString("state", "暂无读数") : (fresh(row) ? "" : "上次 ") + row.optLong("percent") + "%";
        long reset = row.optLong("resetAt");
        return name + "  " + pct + (reset > now ? " · " + when(reset, zone, now) + " 重置" : "");
    }
    /** The kind's colour family for the dot before an account: the same families as 编辑看板's account dots. */
    static String family(String kindOrProduct) {
        String k = kindOrProduct == null ? "" : kindOrProduct.toLowerCase(java.util.Locale.ROOT);
        if (k.equals("openai") || k.equals("codex") || k.startsWith("chatgpt")) return "openai";
        if (k.equals("claude")) return "claude";
        if (k.startsWith("grok")) return "grok";
        if (k.equals("cursor") || k.startsWith("today")) return "green";
        return "";
    }
    /** The name in a half-width cell: 「TodayAI 中国」 and 「TodayAI 全球」 so the two stay apart when space is short. */
    static String shortName(String name) { return name.replace("TodayAI 中国版", "TodayAI 中国").replace("TodayAI 全球版", "TodayAI 全球"); }
    /** Whether the account's own reading is current; snapshots from 0.35 have no flag and count as current. */
    static boolean fresh(JSONObject row) { return row.optBoolean("fresh", true); }
    /** The short value for an account: 「64%」, or its status (待授权, 待更新…) when there is no percent. */
    static String value(JSONObject row) { return row.isNull("percent") ? row.optString("state", "—") : row.optLong("percent") + "%"; }
    static boolean low(JSONObject row) { return !row.isNull("percent") && row.optLong("percent") < LOW; }
    /** The bar colour band: 2 for under 20% left, 1 for under 50%, 0 otherwise, -1 when there is no reading. */
    static int level(JSONObject row) { if (row.isNull("percent")) return -1; long p = row.optLong("percent"); return p < LOW ? 2 : p < 50 ? 1 : 0; }
    /** The token row's small reset note: 「14:30 重置」, 「明天 09:00 重置」, or empty when no reset is ahead. */
    static String resetNote(JSONObject row, ZoneId zone, long now) { long reset = row.optLong("resetAt"); return reset > now ? when(reset, zone, now) + " 重置" : ""; }
    /** 今天, 明天, 周五 within the week, else M/d: the day column of 重置日历. */
    static String day(long at, ZoneId zone, long now) {
        LocalDate d = Instant.ofEpochMilli(at).atZone(zone).toLocalDate(), today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        if (d.equals(today)) return "今天";
        if (d.equals(today.plusDays(1))) return "明天";
        if (d.isBefore(today.plusDays(7)) && d.isAfter(today)) return "周" + "一二三四五六日".charAt(d.getDayOfWeek().getValue() - 1);
        return d.getMonthValue() + "/" + d.getDayOfMonth();
    }
    static String clockTime(long at, ZoneId zone) { return HM.format(Instant.ofEpochMilli(at).atZone(zone)); }
    /** 微信消息's comparison: 「比日均多 33%」, 「比日均少 12%」, 「与日均持平」, or "" without an average. */
    static String versusAverage(long today, long average) {
        if (average <= 0) return "";
        long pct = Math.round((today - average) * 100.0 / average);
        return pct == 0 ? "与日均持平" : (pct > 0 ? "比日均多 " : "比日均少 ") + Math.abs(pct) + "%";
    }
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
