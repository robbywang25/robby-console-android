package wang.robby.console.android;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

/**
 * God's own notifications, decided from what the five-minute background read sees (tokens/quotas, overview). Pure
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
    static final String DAILY = "daily", LOW = "low", RESET = "reset", ACCOUNT = "account", CARD = "card", SITES = "sites";
    static final List<String> KINDS = List.of(DAILY, LOW, RESET, ACCOUNT, CARD, SITES);
    static final int[] DAILY_CHOICES = {10, 20, 30, 40, 50, 70}, LOW_CHOICES = {5, 10, 20, 30};

    static final class Settings {
        final Set<String> on; final int daily, low;
        Settings(Set<String> on, int daily, int low) { this.on = on; this.daily = daily; this.low = low; }
        boolean has(String kind) { return on.contains(kind); }
    }
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
            if (s.has(CARD)) cards(out, state, a, name, now);
        }
        prune(state.section("windows"), now);
        return out;
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

    static String label(String kind) {
        switch (kind) {
            case DAILY: return "当天消耗超过提醒线"; case LOW: return "额度即将用完"; case RESET: return "每周额度已重置";
            case ACCOUNT: return "账号需要处理"; case CARD: return "重置卡即将过期"; default: return "站点离线与恢复";
        }
    }
    static String detail(String kind, Settings s) {
        switch (kind) {
            case DAILY: return "任一账号当天用掉主要额度的 " + s.daily + "% 时提醒";
            case LOW: return "任一额度剩余低于 " + s.low + "% 时提醒";
            case RESET: return "每周等长周期额度被重置时提醒";
            case ACCOUNT: return "账号需要重新授权或读不到时提醒";
            case CARD: return "可用的重置卡 24 小时内过期时提醒";
            default: return "监测中的站点连续两次无响应、以及恢复时提醒";
        }
    }
}
