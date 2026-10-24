package wang.robby.console.android;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * God's own notifications, decided from what the five-minute background read sees (tokens/quotas, overview, market). Pure
 * rules: the caller keeps {@link State} between reads and posts what comes back. Each notice fires once per thing it is
 * about (a day, a quota period, a status change, a card, an outage), never on every read.
 *
 * Today's consumption is counted on the phone: the server only keeps the last hour, so each read adds how far an
 * account's longest quota (usually 每周) fell since the previous read. A rise means the quota was refilled; what was
 * used of the new one counts too. Reads the phone missed while off are still counted when the next read comes, as long
 * as the quota did not reset in between.
 */
final class Notices {
    private Notices() {}
    static final String DAILY = "daily", CREDITS = "credits", LOW = "low", RESET = "reset", ACCOUNT = "account", CARD = "card", SITES = "sites", MARKET = "market";
    static final List<String> KINDS = List.of(DAILY, CREDITS, LOW, RESET, ACCOUNT, CARD, SITES, MARKET);
    /** The kinds that read tokens/quotas. */
    static final Set<String> QUOTA_KINDS = Set.of(DAILY, CREDITS, LOW, RESET, ACCOUNT, CARD);
    static final int[] DAILY_CHOICES = {10, 20, 30, 40, 50, 70}, LOW_CHOICES = {5, 10, 20, 30}, CREDIT_CHOICES = {20, 50, 100, 200, 500};
    /** 行情波动: how many days back (the market read carries seven days of closes) and how far a move must go, in percent. */
    static final int[] MARKET_DAY_CHOICES = {1, 2, 3, 4, 5, 6, 7}, MARKET_LINE_CHOICES = {2, 3, 5, 8, 10, 15, 20};
    static final int CREDIT_DEFAULT = 50, MARKET_DAYS_DEFAULT = 3, MARKET_LINE_DEFAULT = 5;

    static final class Settings {
        final Set<String> on; final int daily, low, credits, marketDays, marketLine;
        Settings(Set<String> on, int daily, int low) { this(on, daily, low, CREDIT_DEFAULT); }
        Settings(Set<String> on, int daily, int low, int credits) { this(on, daily, low, credits, MARKET_DAYS_DEFAULT, MARKET_LINE_DEFAULT); }
        Settings(Set<String> on, int daily, int low, int credits, int marketDays, int marketLine) {
            this.on = on; this.daily = daily; this.low = low; this.credits = credits; this.marketDays = marketDays; this.marketLine = marketLine;
        }
        boolean has(String kind) { return on.contains(kind); }
    }
    /** {@code v} when it is one of {@code choices}, otherwise {@code fallback}. */
    static int choice(int v, int[] choices, int fallback) { for (int c : choices) if (c == v) return v; return fallback; }
    static final class Notice {
        final String key, kind, title, text, open;
        Notice(String key, String kind, String title, String text, String open) { this.key = key; this.kind = kind; this.title = title; this.text = text; this.open = open; }
        @Override public String toString() { return kind + ":" + title; }
    }
    /** What the rules remember between reads; stored by the caller as JSON. Holds percentages, ids and times only. */
    static final class State {
        final JSONObject json;
        State(JSONObject json) { this.json = json == null ? new JSONObject() : json; }
        JSONObject section(String name) { JSONObject s = json.optJSONObject(name); if (s == null) { s = new JSONObject(); put(json, name, s); } return s; }
        JSONObject entry(String section, String key) { JSONObject s = section(section), e = s.optJSONObject(key); if (e == null) { e = new JSONObject(); put(s, key, e); } return e; }
    }
    private static void put(JSONObject o, String k, Object v) { try { o.put(k, v); } catch (org.json.JSONException e) { throw new IllegalStateException(e); } }
    private static long at(Object v) { if (!(v instanceof String)) return -1; try { return Instant.parse((String) v).toEpochMilli(); } catch (RuntimeException e) { return -1; } }
    private static Double pct(JSONObject w) { Object v = w.opt("remainingPercent"); return v instanceof Number && !Boolean.TRUE.equals(w.opt("unlimited")) ? Math.max(0, Math.min(100, ((Number) v).doubleValue())) : null; }
    static String fmt(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v); }

    /** "Claude · name" — the platform and the account as the Tokens page names it. */
    static String accountName(JSONObject a) { return TokensModel.section(a) + " · " + TokensModel.displayName(a); }
    static String windowName(JSONObject w) { String l = w.optString("label", "").trim(); return l.isEmpty() ? "额度" : l; }
    static List<JSONObject> accounts(JSONObject quotas) {
        List<JSONObject> out = new ArrayList<>();
        for (String list : new String[]{"accounts", "additionalAccounts"}) { JSONArray a = quotas.optJSONArray(list); if (a != null) for (int i = 0; i < a.length() && i < 60; i++) { JSONObject x = a.optJSONObject(i); if (x != null && x.optString("id", "").matches("[A-Za-z0-9._:-]{1,80}") && TokensModel.shownOnPage(x)) out.add(x); } }
        return out;
    }
    static List<JSONObject> windows(JSONObject a) {
        List<JSONObject> out = new ArrayList<>(); JSONArray all = a.optJSONArray("windows");
        if (all != null) for (int i = 0; i < all.length() && i < 12; i++) { JSONObject w = all.optJSONObject(i); if (w != null && pct(w) != null && w.optString("id", "").matches("[A-Za-z0-9._:-]{1,60}")) out.add(w); }
        return out;
    }
    static long period(JSONObject w) { long s = at(w.opt("startsAt")), r = at(w.opt("resetsAt")); return s > 0 && r > s ? r - s : -1; }
    /** The account's longest quota (每周 over 5 小时); with no known periods, the first one. */
    static JSONObject longest(List<JSONObject> windows) {
        JSONObject best = null; long most = -2;
        for (JSONObject w : windows) { long p = period(w); if (p > most) { most = p; best = w; } }
        return best;
    }

    static List<Notice> quotas(JSONObject quotas, State state, Settings s, long now, ZoneId zone) {
        List<Notice> out = new ArrayList<>(); String today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString();
        for (JSONObject a : accounts(quotas)) {
            String id = a.optString("id"), name = accountName(a), status = TokensModel.effectiveStatus(a, now);
            // 账号需要处理: once each time the account turns to needing a sign-in or becomes unavailable.
            JSONObject acc = state.entry("accounts", id); String before = acc.optString("status", "");
            boolean bad = status.equals("needs_auth") || status.equals("unavailable");
            if (bad && !before.equals(status) && !before.isEmpty() && s.has(ACCOUNT))
                out.add(new Notice("account:" + id + ":" + status, ACCOUNT, name + (status.equals("needs_auth") ? " 需要重新授权" : " 暂时读不到"), status.equals("needs_auth") ? "打开 Token 额度，按提示重新连接这个账号。" : "采集器连续读取失败，额度数字可能不再更新。", "tokens"));
            put(acc, "status", status);
            if (!status.equals("connected")) continue;
            List<JSONObject> ws = windows(a); JSONObject main = longest(ws);
            for (JSONObject w : ws) {
                double r = pct(w); String key = id + "/" + w.optString("id"); long resets = at(w.opt("resetsAt"));
                JSONObject e = state.entry("windows", key); boolean known = e.has("last"); double last = e.optDouble("last", r); long lastResets = e.optLong("resetsAt", resets);
                boolean refilled = known && (r > last + 5 || resets > lastResets + 60_000L);
                // 额度即将用完: once per quota period, when it first reaches the line.
                if (s.has(LOW) && r <= s.low && !(e.optLong("lowFor", Long.MIN_VALUE) == resets) && (!known || last > s.low || refilled)) {
                    out.add(new Notice("low:" + key + ":" + resets, LOW, name + " " + windowName(w) + "只剩 " + fmt(r) + "%", resets > now ? "将在 " + TokensModel.countdown(resets, now) + "后重置。" : "重置时间未提供。", "tokens"));
                    put(e, "lowFor", resets);
                }
                // 额度已重置: only for day-or-longer quotas that were noticeably used, so 5 小时 refills stay quiet.
                if (s.has(RESET) && refilled && period(w) >= 86_400_000L && last <= 70)
                    out.add(new Notice("reset:" + key + ":" + resets, RESET, name + " " + windowName(w) + "已重置", "现在剩 " + fmt(r) + "%" + (resets > now ? "，下次重置在 " + TokensModel.countdown(resets, now) + "后" : "") + "。", "tokens"));
                if (w == main) daily(out, e, s, key, name, w, r, last, known, refilled, today);
                put(e, "last", r); put(e, "resetsAt", resets); put(e, "seenAt", now);
            }
            credits(out, state, a, name, s, today, now);
            if (s.has(CARD)) cards(out, state, a, name, now);
        }
        prune(state.section("windows"), now);
        prune(state.section("credits"), now);
        return out;
    }
    /**
     * Codex 积分消耗: the phone remembers the day's starting balance and adds each drop. A rise is a top-up, so the new
     * balance becomes the baseline and what was used before it does not carry. Unlimited and missing balances stay quiet.
     * One notice per account per local day, once the day's drops reach the point line.
     */
    private static void credits(List<Notice> out, State state, JSONObject a, String name, Settings s, String today, long now) {
        if (!"codex".equals(a.optString("product"))) return;
        JSONObject credits = a.optJSONObject("credits");
        if (credits == null || Boolean.TRUE.equals(credits.opt("unlimited")) || !(credits.opt("remaining") instanceof Number)) return;
        double left = ((Number) credits.opt("remaining")).doubleValue();
        if (Double.isNaN(left) || left < 0) return;
        JSONObject e = state.entry("credits", a.optString("id"));
        boolean sameDay = today.equals(e.optString("day")), known = sameDay && e.has("last");
        double used = sameDay ? e.optDouble("used", 0) : 0, last = known ? e.optDouble("last") : left;
        boolean fired = sameDay && e.optBoolean("fired", false);
        if (known) { if (left > last) used = 0; else used += Math.max(0, last - left); }
        double base = !known || left > last ? left : e.optDouble("base", last);
        put(e, "day", today); put(e, "last", left); put(e, "base", base); put(e, "used", used); put(e, "fired", fired); put(e, "seenAt", now);
        if (s.has(CREDITS) && !fired && used >= s.credits) {
            out.add(new Notice("credits:" + a.optString("id") + ":" + today, CREDITS, name + " 今天已用掉 " + fmt(Math.floor(used)) + " 点积分",
                "超过你设的每日 " + s.credits + " 点提醒线，现在剩 " + fmt(left) + " 点。", "tokens"));
            put(e, "fired", true);
        }
    }
    private static void daily(List<Notice> out, JSONObject e, Settings s, String key, String name, JSONObject w, double r, double last, boolean known, boolean refilled, String today) {
        double used = today.equals(e.optString("day")) ? e.optDouble("used", 0) : 0; boolean fired = today.equals(e.optString("day")) && e.optBoolean("fired", false);
        if (known && today.equals(e.optString("day"))) used += refilled ? Math.max(0, 100 - r) : Math.max(0, last - r);
        put(e, "day", today); put(e, "used", used); put(e, "fired", fired);
        if (s.has(DAILY) && !fired && used >= s.daily) {
            out.add(new Notice("daily:" + key + ":" + today, DAILY, name + " 今天已用掉 " + fmt(Math.floor(used)) + "%" + windowName(w), "超过你设的每日 " + s.daily + "% 提醒线，现在剩 " + fmt(r) + "%。", "tokens"));
            put(e, "fired", true);
        }
    }
    /** 重置卡即将过期: once per card, within its last 24 hours. */
    private static void cards(List<Notice> out, State state, JSONObject a, String name, long now) {
        JSONObject rc = a.optJSONObject("resetCredits"); JSONArray list = rc == null ? null : rc.optJSONArray("cards"); if (list == null) return;
        JSONObject done = state.section("cards");
        for (int i = 0; i < list.length() && i < 20; i++) {
            JSONObject c = list.optJSONObject(i); if (c == null || !"available".equals(c.optString("status"))) continue;
            String id = c.optString("id", ""); long exp = at(c.opt("expiresAt"));
            if (!id.matches("[A-Za-z0-9._:-]{1,80}") || exp <= now || exp - now > 86_400_000L || done.has(id)) continue;
            put(done, id, exp); out.add(new Notice("card:" + id, CARD, name + " 有一张重置卡快过期了", "还剩 " + TokensModel.countdown(exp, now) + "，过期前记得使用。", "tokens"));
        }
        for (Iterator<String> it = done.keys(); it.hasNext(); ) if (done.optLong(it.next()) < now - 7 * 86_400_000L) it.remove();
    }
    private static void prune(JSONObject windows, long now) {
        for (Iterator<String> it = windows.keys(); it.hasNext(); ) { JSONObject e = windows.optJSONObject(it.next()); if (e == null || e.optLong("seenAt", now) < now - 14 * 86_400_000L) it.remove(); }
    }

    /**
     * 站点离线 / 恢复 from overview's status: a site that answered before and fails two reads in a row is reported once;
     * its recovery is reported once. Excluded and unknown sites stay quiet.
     */
    static List<Notice> sites(JSONObject overview, State state, Settings s, long now) {
        List<Notice> out = new ArrayList<>(); if (!s.has(SITES)) return out;
        JSONObject status = overview.optJSONObject("status"); JSONArray list = status == null ? null : status.optJSONArray("sites"); if (list == null) return out;
        JSONObject seen = state.section("sites");
        for (int i = 0; i < list.length() && i < 80; i++) {
            JSONObject site = list.optJSONObject(i); if (site == null || "excluded".equals(site.optString("tracker")) || !(site.opt("online") instanceof Boolean)) continue;
            String host = site.optString("hostname", ""); if (!host.matches("[A-Za-z0-9.-]{1,253}")) continue;
            String label = site.optString("label", "").trim(); if (label.isEmpty() || label.length() > 60) label = host;
            boolean online = site.optBoolean("online"); JSONObject e = seen.optJSONObject(host); if (e == null) { e = new JSONObject(); put(seen, host, e); }
            int misses = online ? 0 : e.optInt("misses", 0) + 1; boolean reported = e.optBoolean("reported", false), everUp = e.optBoolean("up", false) || online;
            if (!online && misses == 2 && everUp && !reported) { out.add(new Notice("site:" + host + ":down:" + now, SITES, label + " 离线", host + " 连续两次检查都没有响应。", "status")); reported = true; }
            if (online && reported) { out.add(new Notice("site:" + host + ":up:" + now, SITES, label + " 已恢复", host + " 重新可以访问了。", "status")); reported = false; }
            put(e, "misses", misses); put(e, "reported", reported); put(e, "up", everUp); put(e, "seenAt", now);
        }
        for (Iterator<String> it = seen.keys(); it.hasNext(); ) { JSONObject e = seen.optJSONObject(it.next()); if (e == null || e.optLong("seenAt", now) < now - 14 * 86_400_000L) it.remove(); }
        return out;
    }

    /**
     * 行情波动 for each 关注行情 asset: the quote now against the highest and lowest price of the last {@code marketDays}
     * days (history's daily closes from that many local days back, plus the quote). A rise from the low or a fall from
     * the high that reaches the line notifies once. The same direction notifies again only after the move has eased
     * below half the line, or grown by another full line. When both directions reach it, the larger one is said.
     */
    static List<Notice> market(JSONObject market, State state, Settings s, long now, ZoneId zone) {
        List<Notice> out = new ArrayList<>(); if (!s.has(MARKET)) return out;
        JSONObject history = market.optJSONObject("history"); JSONArray series = history == null ? null : history.optJSONArray("series"); if (series == null) return out;
        Map<String, JSONObject> quotes = new HashMap<>(); JSONArray list = market.optJSONArray("watchlist");
        if (list != null) for (int i = 0; i < list.length() && i < 60; i++) { JSONObject q = list.optJSONObject(i); if (q != null) quotes.put(q.optString("symbol", ""), q); }
        Set<String> missing = new HashSet<>(); JSONArray gone = history.optJSONArray("unavailableSymbols"); if (gone != null) for (int i = 0; i < gone.length(); i++) missing.add(gone.optString(i));
        int days = s.marketDays, line = s.marketLine; LocalDate from = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(days);
        JSONObject seen = state.section("market");
        for (int i = 0; i < series.length() && i < 40; i++) {
            JSONObject x = series.optJSONObject(i); if (x == null) continue;
            String symbol = x.optString("symbol", ""); if (!symbol.matches("[A-Za-z0-9._:^=-]{1,40}") || missing.contains(symbol)) continue;
            JSONArray points = x.optJSONArray("points"); if (points == null) continue;
            double low = Double.MAX_VALUE, high = 0, last = 0; long lastAt = -1; int inWindow = 0;
            for (int j = 0; j < points.length() && j < 400; j++) {
                JSONObject p = points.optJSONObject(j); long t = p == null ? -1 : at(p.opt("timestamp")); double v = p == null ? 0 : price(p.opt("price"));
                if (t < 0 || v <= 0) continue;
                if (t > lastAt) { lastAt = t; last = v; }
                if (Instant.ofEpochMilli(t).atZone(zone).toLocalDate().isBefore(from)) continue;
                low = Math.min(low, v); high = Math.max(high, v); inWindow++;
            }
            // The quote is newer than the last close; without one the last close stands for now.
            JSONObject quote = quotes.get(symbol); double current = quote == null ? 0 : price(quote.opt("price")); if (current <= 0) current = last;
            if (inWindow == 0 || current <= 0) continue;
            low = Math.min(low, current); high = Math.max(high, current);
            double rise = (current - low) * 100 / low, fall = (high - current) * 100 / high;
            JSONObject e = state.entry("market", symbol); put(e, "seenAt", now);
            boolean up = due(e, "up", rise, line), down = due(e, "down", fall, line);
            if (!up && !down) continue;
            JSONObject named = quote != null ? quote : x;
            String label = named.optString("label", "").trim(); if (label.isEmpty() || label.length() > 40) label = symbol;
            String unit = named.optString("currency", "").trim(); unit = unit.matches("[A-Z]{3,5}") ? " " + unit : "";
            String span = days + " 天内", tail = "，超过你设的 " + line + "% 提醒线。";
            if (up && (!down || rise >= fall)) out.add(new Notice("market:" + symbol + ":up", MARKET, label + " " + span + "上涨 " + pct(rise) + "%", "从 " + span + "低点 " + money(low) + " 涨到 " + money(current) + unit + tail, "market"));
            else out.add(new Notice("market:" + symbol + ":down", MARKET, label + " " + span + "下跌 " + pct(fall) + "%", "从 " + span + "高点 " + money(high) + " 跌到 " + money(current) + unit + tail, "market"));
            if (up) put(e, "up", rise);
            if (down) put(e, "down", fall);
        }
        for (Iterator<String> it = seen.keys(); it.hasNext(); ) { JSONObject e = seen.optJSONObject(it.next()); if (e == null || e.optLong("seenAt", now) < now - 14 * 86_400_000L) it.remove(); }
        return out;
    }
    /** Whether a move in one direction notifies now; one that eased below half the line is re-armed. */
    private static boolean due(JSONObject e, String direction, double move, int line) {
        double fired = e.optDouble(direction, Double.NaN);
        if (!Double.isNaN(fired) && move < line / 2.0) { e.remove(direction); return false; }
        return move >= line && (Double.isNaN(fired) || move >= fired + line);
    }
    private static double price(Object v) { double d = v instanceof Number ? ((Number) v).doubleValue() : 0; return Double.isFinite(d) && d > 0 ? d : 0; }
    static String pct(double v) { return String.format(Locale.ROOT, "%.1f", v); }
    /** 84,830.5 · 128.4 · 0.0123: grouped, at most two decimals, four below one. */
    static String money(double v) { java.text.NumberFormat f = java.text.NumberFormat.getNumberInstance(Locale.US); f.setMaximumFractionDigits(v < 1 ? 4 : 2); return f.format(v); }

    static String label(String kind) {
        switch (kind) {
            case DAILY: return "当天消耗超过提醒线"; case CREDITS: return "Codex 积分消耗"; case LOW: return "额度即将用完";
            case RESET: return "每周额度已重置"; case ACCOUNT: return "账号需要处理"; case CARD: return "重置卡即将过期";
            case MARKET: return "行情波动提醒";
            default: return "站点离线与恢复";
        }
    }
    static String detail(String kind, Settings s) {
        switch (kind) {
            case DAILY: return "任一账号当天用掉主要额度的 " + s.daily + "% 时提醒";
            case CREDITS: return "任一 Codex 账号当天积分余额累计下降 " + s.credits + " 点时提醒";
            case LOW: return "任一额度剩余低于 " + s.low + "% 时提醒";
            case RESET: return "每周等长周期额度被重置时提醒";
            case ACCOUNT: return "账号需要重新授权或读不到时提醒";
            case CARD: return "可用的重置卡 24 小时内过期时提醒";
            case MARKET: return "任一关注资产 " + s.marketDays + " 天内涨跌超过 " + s.marketLine + "% 时提醒";
            default: return "监测中的站点连续两次无响应、以及恢复时提醒";
        }
    }
}
