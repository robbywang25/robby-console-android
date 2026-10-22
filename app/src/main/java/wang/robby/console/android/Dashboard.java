package wang.robby.console.android;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 看板 on 总览: the catalog of feature cards the person can combine, and the saved combination. The combination is
 * an ordered list of card ids kept in a preference; it holds layout only, never data. Pure Java for JVM tests.
 */
final class Dashboard {
    private Dashboard() {}
    static final class Card {
        /** {@code path} is the API read; {@code target} the feature a tap opens, or null when there is no detail. */
        final String id, title, subtitle, symbol, path, target; final boolean wide;
        Card(String id, String title, String subtitle, String symbol, String path, String target, boolean wide) {
            this.id = id; this.title = title; this.subtitle = subtitle; this.symbol = symbol; this.path = path; this.target = target; this.wide = wide;
        }
    }
    static final List<Card> ALL = List.of(
        new Card("services", "服务状态", "在线服务数与健康结论", "pulse", "overview", "status", true),
        new Card("reminders", "待办提醒", "到期与需要处理的提醒", "document", "overview", null, true),
        new Card("instances", "云实例", "基础设施实例数量", "server", "overview", "infrastructure", false),
        new Card("domains", "域名资产", "已登记的域名数量", "globe", "overview", "domains", false),
        new Card("openai", "OpenAI 用量", "请求、Token 与成本", "chart", "openai/usage", "openai", false),
        new Card("visits", "访问", "所选时段访问与事件", "chart", "analytics/overview", "observer", false),
        new Card("okx", "OKX 账户", "总权益与持仓", "trend", "okx/overview", "okx", false),
        new Card("clipboard", "云剪贴板", "剪贴内容条数", "clipboard", "clipboard", "clipboard", false),
        new Card("market", "关注行情", "关注列表的前几项报价", "trend", "market", "market", true),
        new Card("tokens", "Token 额度", "各账号最紧张的额度与重置倒计时", "gauge", "tokens/quotas", "tokens", true),
        new Card("calendar", "重置日历", "接下来的几次额度重置", "calendar", "tokens/quotas", "quota-calendar", true),
        new Card("wechat", "微信消息", "今天的消息、近 7 天日均与最活跃会话", "chat", "wechat/digest", "wechat-messages", false),
        // No read: the clocks are drawn on the phone from the zones chosen in 编辑看板.
        new Card("clocks", "时区时钟", "Tibo、北京等时区的当前时间", "history", null, null, true)
    );
    static final List<String> DEFAULT = List.of("services", "instances", "domains", "reminders", "tokens");

    /** Token 额度 accounts left off the 看板 card, by account id (codex-1, today-cn…); unknown text is dropped. */
    static Set<String> hiddenTokens(String saved) {
        Set<String> out = new LinkedHashSet<>(); if (saved == null) return out;
        for (String part : saved.split(",")) { String id = part.trim(); if (id.matches("[a-z0-9-]{1,32}")) out.add(id); }
        return out;
    }
    static String toggleHidden(String saved, String id) { Set<String> ids = hiddenTokens(saved); if (!ids.remove(id) && id.matches("[a-z0-9-]{1,32}")) ids.add(id); return String.join(",", ids); }
    static final String HIDDEN_TOKENS = "tokenBoardHidden", CLOCKS = "boardClocks", TOKEN_ROWS = "tokenBoardRows";
    /** The Token card lists every connected account, up to this many. Eight used to drop TodayAI 全球版. */
    static final int MAX_TOKEN_ROWS = 32;

    /** 重置日历 on 总览 lists the resets in a day range, chosen in 编辑看板: 今天, or the next 3, 7, 14 or 30 days. */
    static final String CALENDAR_DAYS = "boardCalendarDays";
    static final List<Integer> CALENDAR_RANGES = List.of(1, 3, 7, 14, 30);
    static final int CALENDAR_DEFAULT = 7, CALENDAR_MAX_ROWS = 12;
    static int calendarDays(String saved) {
        try { int d = Integer.parseInt(saved == null ? "" : saved.trim()); if (CALENDAR_RANGES.contains(d)) return d; } catch (NumberFormatException ignored) { }
        return CALENDAR_DEFAULT;
    }
    static int calendarDays(android.content.Context c) { return calendarDays(c.getSharedPreferences("god-ui", android.content.Context.MODE_PRIVATE).getString(CALENDAR_DAYS, null)); }
    static String calendarRange(int days) { return days == 1 ? "今天" : "未来 " + days + " 天"; }
    /** Resets still ahead of {@code now} whose local day is within {@code days} days counting today, soonest first. */
    static List<QuotaCalendar.Event> calendarWithin(List<QuotaCalendar.Event> events, long now, java.time.LocalDate today, int days) {
        java.time.LocalDate last = today.plusDays(Math.max(1, days) - 1L); List<QuotaCalendar.Event> out = new java.util.ArrayList<>();
        for (QuotaCalendar.Event e : events) if (e.at > now && e.day != null && !e.day.isAfter(last)) out.add(e);
        out.sort(java.util.Comparator.comparingLong(e -> e.at)); return out;
    }
    /**
     * Accounts the Token card shows, in the order given: on the page, not hidden, at most {@link #MAX_TOKEN_ROWS}.
     * Two accounts of one product both stay, and names are left as they arrived.
     */
    static List<JSONObject> tokenBoardAccounts(List<JSONObject> accounts, Set<String> hidden) {
        List<JSONObject> out = new ArrayList<>(); if (accounts == null) return out;
        Set<String> hide = hidden == null ? Set.of() : hidden;
        for (JSONObject a : accounts) {
            if (a == null || !TokensModel.shownOnPage(a) || hide.contains(a.optString("id"))) continue;
            if (out.size() >= MAX_TOKEN_ROWS) break;
            out.add(a);
        }
        return out;
    }
    /** The Token card's last rows as id and product label (ChatGPT Codex, Claude…): layout only, never figures or emails. */
    static List<String[]> tokenRows(String saved) {
        List<String[]> out = new ArrayList<>(); if (saved == null) return out;
        for (String part : saved.split("\n")) { int bar = part.indexOf('|'); if (bar <= 0 || out.size() >= MAX_TOKEN_ROWS) continue;
            String id = part.substring(0, bar), label = label(part.substring(bar + 1), 32); if (id.matches("[a-z0-9-]{1,32}") && label != null) out.add(new String[]{id, label}); }
        return out;
    }
    static String serializeTokenRows(List<String[]> rows) {
        List<String> parts = new ArrayList<>(); for (String[] r : rows) { String label = label(r[1], 32); if (r[0].matches("[a-z0-9-]{1,32}") && label != null && parts.size() < MAX_TOKEN_ROWS) parts.add(r[0] + "|" + label); }
        return String.join("\n", parts);
    }
    static List<String[]> tokenRows(android.content.Context c) { return tokenRows(c.getSharedPreferences("god-ui", android.content.Context.MODE_PRIVATE).getString(TOKEN_ROWS, null)); }
    static void saveTokenRows(android.content.Context c, List<String[]> rows) { c.getSharedPreferences("god-ui", android.content.Context.MODE_PRIVATE).edit().putString(TOKEN_ROWS, serializeTokenRows(rows)).apply(); }
    /** 时区时钟: a name and an IANA zone each; Tibo, Beijing and fixed PST until the person changes them. */
    static final class Clock { final String name, zone; Clock(String name, String zone) { this.name = name; this.zone = zone; } }
    static final String PACIFIC = "America/Los_Angeles";
    /** Pacific Standard Time all year (UTC-8). POSIX Etc zones invert the sign, so GMT+8 is eight hours behind UTC. */
    static final String PST = "Etc/GMT+8";
    static final List<Clock> DEFAULT_CLOCKS = List.of(new Clock("Tibo", PACIFIC), new Clock("北京时间", "Asia/Shanghai"), new Clock("PST", PST));
    static final int MAX_CLOCKS = 6;
    /** Zones offered by 添加时区, with the name a new clock starts with. */
    static final List<Clock> SUGGESTED = List.of(new Clock("北京时间", "Asia/Shanghai"), new Clock("PST", PST), new Clock("Tibo", PACIFIC), new Clock("新西兰", "Pacific/Auckland"),
        new Clock("香港", "Asia/Hong_Kong"), new Clock("东京", "Asia/Tokyo"), new Clock("首尔", "Asia/Seoul"), new Clock("新加坡", "Asia/Singapore"), new Clock("迪拜", "Asia/Dubai"),
        new Clock("伦敦", "Europe/London"), new Clock("巴黎", "Europe/Paris"), new Clock("柏林", "Europe/Berlin"), new Clock("纽约", "America/New_York"),
        new Clock("芝加哥", "America/Chicago"), new Clock("悉尼", "Australia/Sydney"), new Clock("UTC", "Etc/UTC"));
    /** The line under a fixed PST clock, the same all year; null for every other zone. */
    static String zoneTitle(String zone) { return PST.equals(zone) ? "PST · 太平洋标准时间 · Pacific Standard Time" : null; }
    /** 同本机, 晚 15 小时 or 早 5.5 小时: the zone against the phone's own at {@code at}. */
    static String offsetLabel(String zone, java.time.ZoneId phone, long at) {
        java.time.Instant i = java.time.Instant.ofEpochMilli(at);
        int diff = java.time.ZoneId.of(zone).getRules().getOffset(i).getTotalSeconds() - phone.getRules().getOffset(i).getTotalSeconds();
        if (diff == 0) return "同本机";
        String hours = diff % 3600 == 0 ? String.valueOf(Math.abs(diff) / 3600) : new java.text.DecimalFormat("0.##").format(Math.abs(diff) / 3600.0);
        return (diff < 0 ? "晚 " : "早 ") + hours + " 小时";
    }
    static List<Clock> clocks(String saved) {
        if (saved == null) return new ArrayList<>(DEFAULT_CLOCKS);
        List<Clock> out = new ArrayList<>();
        for (String part : saved.split("\\n")) { int bar = part.lastIndexOf('|'); if (bar <= 0) continue;
            String name = clockName(part.substring(0, bar)), zone = part.substring(bar + 1).trim();
            if (name != null && validZone(zone) && out.size() < MAX_CLOCKS) out.add(new Clock(name, zone)); }
        return out;
    }
    static String serializeClocks(List<Clock> clocks) {
        List<String> parts = new ArrayList<>(); for (Clock c : clocks) { String name = clockName(c.name); if (name != null && validZone(c.zone) && parts.size() < MAX_CLOCKS) parts.add(name + "|" + c.zone); }
        return String.join("\n", parts);
    }
    /** A display name of 1–12 characters without line breaks or the separator; null when nothing is left. */
    static String clockName(String raw) { return label(raw, 12); }
    /** Up to {@code max} characters without line breaks or the separator; null when nothing is left. */
    static String label(String raw, int max) {
        if (raw == null) return null; String s = raw.replaceAll("[\\u0000-\\u001f\\u007f|]", " ").trim();
        int[] points = s.codePoints().limit(max).toArray(); s = new String(points, 0, points.length).trim(); return s.isEmpty() ? null : s;
    }
    static boolean validZone(String zone) { try { return zone != null && zone.matches("[A-Za-z_]+(/[A-Za-z0-9_+-]+){0,2}") && java.time.ZoneId.of(zone) != null; } catch (RuntimeException e) { return false; } }
    static Set<String> hiddenTokens(android.content.Context c) { return hiddenTokens(c.getSharedPreferences("god-ui", android.content.Context.MODE_PRIVATE).getString(HIDDEN_TOKENS, null)); }

    static Card find(String id) { for (Card c : ALL) if (c.id.equals(id)) return c; return null; }
    /** Saved value to an ordered combination: unknown and repeated ids are dropped; null (never saved) is the default set. */
    static List<String> parse(String saved) {
        if (saved == null) return new ArrayList<>(DEFAULT);
        Set<String> ids = new LinkedHashSet<>();
        for (String part : saved.split(",")) { String id = part.trim(); if (find(id) != null) ids.add(id); }
        return new ArrayList<>(ids);
    }
    static String serialize(List<String> ids) { return String.join(",", parse(String.join(",", ids))); }
    /** Adds a card at the end, or removes it. */
    static List<String> toggle(List<String> ids, String id) {
        List<String> next = parse(String.join(",", ids));
        if (!next.remove(id) && find(id) != null) next.add(id);
        return next;
    }
    /** Moves a card one place up ({@code delta} -1) or down (+1); out-of-range moves change nothing. */
    static List<String> move(List<String> ids, String id, int delta) {
        List<String> next = parse(String.join(",", ids)); int i = next.indexOf(id), j = i + delta;
        if (i < 0 || j < 0 || j >= next.size()) return next;
        next.remove(i); next.add(j, id); return next;
    }
    /** Distinct API paths the combination needs, in first-use order, so shared reads happen once. */
    static List<String> paths(List<String> ids) {
        Set<String> out = new LinkedHashSet<>(); for (String id : ids) { Card c = find(id); if (c != null && c.path != null) out.add(c.path); }
        return new ArrayList<>(out);
    }
}
