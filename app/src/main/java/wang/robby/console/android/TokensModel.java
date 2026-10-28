package wang.robby.console.android;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Wording and judgements for the Token 额度 page (tokens.robby.wang via the mobile API). Mirrors the web dashboard
 * and the iOS app so the same quota reads the same everywhere; pure Java for JVM tests.
 */
final class TokensModel {
    private TokensModel() {}
    /** A quota window shown on a 看板 row, with the short period name it is shown under (null for a single value). */
    static final class Period {
        final String label; final JSONObject window;
        Period(String label, JSONObject window) { this.label = label; this.window = window; }
    }
    /**
     * The windows one 看板 row shows. Claude shows its 5-hour and weekly balances side by side, as tokens.robby.wang
     * does (the collector's ids, then its labels); every other account shows the window with the least remaining.
     * Reserve windows are never shown here, as on iOS.
     */
    static List<Period> boardWindows(JSONObject account) {
        List<JSONObject> windows = new ArrayList<>(); JSONArray all = account.optJSONArray("windows");
        if (all != null) for (int i = 0; i < all.length(); i++) { JSONObject w = all.optJSONObject(i); if (w != null && !"reserve".equals(w.opt("quotaGroup"))) windows.add(w); }
        if ("claude".equals(account.opt("product"))) {
            List<Period> out = new ArrayList<>();
            JSONObject five = find(windows, Set.of("five_hour", "five-hour", "five", "5h"), Set.of("5小时", "5小时额度"));
            JSONObject week = find(windows, Set.of("seven_day", "seven-day", "weekly", "week", "7d"), Set.of("每周", "每周额度", "7天"));
            if (five != null) out.add(new Period("5 小时", five));
            if (week != null) out.add(new Period("每周", week));
            if (!out.isEmpty()) return out;
        }
        JSONObject tightest = null; Double low = null;
        for (JSONObject w : windows) { Double p = remaining(w); if (p != null && (low == null || p < low)) { low = p; tightest = w; } }
        return tightest == null ? List.of() : List.of(new Period(null, tightest));
    }
    /**
     * The small line under a Codex row on the 看板, as on iOS: Points (「正在用 Points」 once the default quota is spent or
     * not allowed), then the reset cards with the earliest expiry among the available ones. Claude's line has its resets,
     * then its reset cards. Null when there is nothing to say.
     */
    static String boardExtras(JSONObject account, boolean fresh, long now, java.time.ZoneId zone) {
        // Claude: both resets on the small line, the 5-hour one first. They come from resetsAt, so a stale reading still
        // shows when its window starts over; a 5-hour window already past says so instead of vanishing.
        if ("claude".equals(account.opt("product"))) {
            List<String> parts = new ArrayList<>();
            for (Period p : boardWindows(account)) { long resets = at(p.window.opt("resetsAt"));
                if ("5 小时".equals(p.label) && resets > 0) parts.add(resets <= now ? "5 小时额度已重置" : "5 小时额度 " + countdown(resets, now));
                else if ("每周".equals(p.label)) { String left = days(resets, now); if (left != null) parts.add("每周额度 " + left); } }
            String cards = cardsPart(account, now, zone); if (cards != null) parts.add(cards);
            return parts.isEmpty() ? null : String.join(" · ", parts);
        }
        if (!"codex".equals(account.opt("product"))) return null;
        List<String> parts = new ArrayList<>();
        JSONObject credits = account.optJSONObject("credits");
        if (credits != null) {
            Object left = credits.opt("remaining");
            if (Boolean.TRUE.equals(credits.opt("unlimited"))) parts.add("Points 不限量");
            else if (left instanceof Number) parts.add((usesPoints(account) ? "正在用 Points " : "Points ") + new java.text.DecimalFormat("#,##0.#").format(((Number) left).doubleValue()));
        }
        String cards = cardsPart(account, now, zone); if (cards != null) parts.add(cards);
        if (parts.isEmpty()) return null;
        return (fresh ? "" : "上次 ") + String.join(" · ", parts);
    }
    /**
     * The board's reset card part: the count, then the earliest expiry among the available cards. A Claude count from a
     * dated observation says so and when, as tokens.robby.wang does, since it is not live provider data.
     */
    static String cardsPart(JSONObject account, long now, ZoneId zone) {
        JSONObject cards = cardData(account);
        if (cards == null || !(cards.opt("available") instanceof Number)) return null;
        int available = ((Number) cards.opt("available")).intValue(); String part = "重置卡 " + available + " 张";
        String source = observation(account); long seen = at(cards.opt("detailsAt"));
        if (source != null) part += "（" + ("official-web".equals(source) ? "官网" : "你") + (seen >= 0 ? " " + monthDay(seen, zone) : "") + ("official-web".equals(source) ? "核验" : "确认") + "）";
        long next = Long.MAX_VALUE; JSONArray list = cards.optJSONArray("cards");
        if (available > 0 && list != null) for (int i = 0; i < list.length(); i++) {
            JSONObject c = list.optJSONObject(i); if (c == null || !"available".equals(c.opt("status"))) continue;
            long expires = at(c.opt("expiresAt")); if (expires >= 0) next = Math.min(next, expires);
        }
        if (next != Long.MAX_VALUE) part += next <= now ? " · 最近一张已到期" : " · 最近 " + monthDay(next, zone) + "到期";
        return part;
    }
    /**
     * The account's reset cards: the provider's own, else for Claude the dated observation tokens.robby.wang shows
     * (confirmed by the owner, or read on the official site). Null when there is neither.
     */
    static JSONObject cardData(JSONObject a) {
        JSONObject live = a.optJSONObject("resetCredits"); if (live != null) return live;
        return "claude".equals(a.opt("product")) ? a.optJSONObject("reportedResetCredits") : null;
    }
    /** Where Claude's cards come from when not from the provider: "official-web" or "owner-confirmed"; null otherwise. */
    static String observation(JSONObject a) {
        if (a.optJSONObject("resetCredits") != null) return null; JSONObject r = cardData(a);
        Object p = r == null ? null : r.opt("provenance"); return "official-web".equals(p) || "owner-confirmed".equals(p) ? (String) p : null;
    }
    private static String monthDay(long ms, ZoneId zone) { java.time.LocalDate d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate(); return d.getMonthValue() + "月" + d.getDayOfMonth() + "日"; }
    /** iOS's rule: Codex is on Points when its default quota is spent or not allowed and a Points balance is known. */
    static boolean usesPoints(JSONObject account) {
        JSONObject credits = account.optJSONObject("credits"), route = account.optJSONObject("usageRoute");
        if (!"codex".equals(account.opt("product")) || credits == null || !(credits.opt("remaining") instanceof Number)) return false;
        JSONObject primary = null; JSONArray all = account.optJSONArray("windows");
        if (all != null) for (int i = 0; i < all.length() && primary == null; i++) { JSONObject w = all.optJSONObject(i); if (w != null && "default".equals(w.opt("quotaGroup"))) primary = w; }
        if (all != null) for (int i = 0; i < all.length() && primary == null; i++) { JSONObject w = all.optJSONObject(i); if (w != null && !"reserve".equals(w.opt("quotaGroup"))) primary = w; }
        Double left = primary == null ? null : remaining(primary);
        return (left != null && left == 0) || (route != null && Boolean.FALSE.equals(route.opt("defaultAllowed")));
    }
    /** How long the Token cards and page wait before reading again: the server's refreshAfterSeconds within 15 s – 5 min, else 30 s. */
    static long refreshMs(JSONObject data) { int s = data == null ? 30 : data.optInt("refreshAfterSeconds", 30); return Math.max(15, Math.min(300, s <= 0 ? 30 : s)) * 1000L; }
    static Double remaining(JSONObject window) { Object v = window.opt("remainingPercent"); return v instanceof Number ? ((Number) v).doubleValue() : null; }
    private static JSONObject find(List<JSONObject> windows, Set<String> ids, Set<String> labels) {
        for (JSONObject w : windows) if (ids.contains(String.valueOf(w.opt("id")))) return w;
        for (JSONObject w : windows) if (labels.contains(String.valueOf(w.opt("label")).replaceAll("\\s", ""))) return w;
        return null;
    }
    static String status(String s) {
        switch (s == null ? "" : s) {
            case "connected": return "已更新";
            case "stale": return "待更新";
            case "needs_auth": return "待授权";
            case "unavailable": return "暂不可用";
            case "not_configured": return "待接入";
            default: return "状态未知";
        }
    }
    /** Severity of a remaining share: 1 healthy, 2 low, 3 nearly exhausted; 0 when unknown. */
    static int level(Double remainingPercent) {
        if (remainingPercent == null || remainingPercent.isNaN()) return 0;
        return remainingPercent >= 50 ? 1 : remainingPercent >= 20 ? 2 : 3;
    }
    /** Lit cells of the 看板's ten-cell meter, one per 10%: any balance left lights at least one, only a full one lights all ten. */
    static int segments(Double remainingPercent) {
        if (remainingPercent == null || remainingPercent.isNaN() || remainingPercent <= 0) return 0;
        if (remainingPercent >= 100) return 10;
        return Math.max(1, Math.min(9, (int) Math.round(remainingPercent / 10)));
    }
    /**
     * "即将重置": a fresh reading, the window resets within 24 h, at least 20% is left, and more is left than the
     * remaining share of the period, so quota is about to go unused. Stale readings never raise it.
     */
    /** "3 小时 20 分后重置" style countdown; null when past or unknown. */
    /** A weekly reset in days: "3 天后重置", "1 天 5 小时后重置"; under a day, the usual countdown. */
    static String days(long resetsAtMs, long nowMs) {
        if (resetsAtMs <= nowMs) return null; long hours = (resetsAtMs - nowMs) / 3_600_000L;
        if (hours < 24) return countdown(resetsAtMs, nowMs);
        return hours / 24 + " 天" + (hours % 24 == 0 ? "" : " " + hours % 24 + " 小时") + "后重置";
    }
    static String countdown(long resetsAtMs, long nowMs) {
        if (resetsAtMs <= 0 || resetsAtMs <= nowMs) return null; long minutes = (resetsAtMs - nowMs) / 60_000L;
        if (minutes < 60) return Math.max(1, minutes) + " 分钟后重置";
        long hours = minutes / 60; if (hours < 48) return hours + " 小时" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + " 分") + "后重置";
        return (hours / 24) + " 天后重置";
    }

    // ---- The tokens.robby.wang 账号额度 tile (renderOverview), field by field ----

    /** The page's sections in the web's order; accounts never move between them when their readings change. */
    static final List<String> SECTIONS = List.of("OpenAI", "Claude", "Grok", "Cursor", "TodayAI", "其他");
    private static final Map<String, String> PRODUCT_NAMES = Map.of("codex", "Codex", "claude", "Claude", "grok", "Grok Build", "grok-bot", "Grok Bot",
            "cursor", "Cursor", "today-cn", "TodayAI 中国版", "today-global", "TodayAI 全球版", "chatgpt-dots", "ChatGPT Dots", "chatgpt-chat", "ChatGPT 聊天");
    private static final Map<String, String> IDENTITY_PRODUCTS = Map.of("codex", "ChatGPT", "claude", "Claude", "grok", "Grok Build", "grok-bot", "Grok Bot",
            "cursor", "Cursor", "today-cn", "TodayAI 中国版", "today-global", "TodayAI 全球版");
    private static final Map<String, String> CLAUDE_CARDS = Map.of("full", "完整重置卡", "five_hour", "5 小时重置卡", "weekly", "每周重置卡");
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("MM/dd HH:mm"), LONG = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

    /** Only connected-or-once-connected accounts, never the two ChatGPT entries the web keeps out of the quota grid. */
    static boolean shownOnPage(JSONObject a) {
        String product = String.valueOf(a.opt("product"));
        return !"not_configured".equals(a.opt("status")) && !"chatgpt-dots".equals(product) && !"chatgpt-chat".equals(product);
    }
    static String section(JSONObject a) {
        String product = String.valueOf(a.opt("product")).toLowerCase(java.util.Locale.ROOT), provider = String.valueOf(a.opt("provider")).toLowerCase(java.util.Locale.ROOT);
        if (product.startsWith("today-") || provider.equals("todayai")) return "TodayAI";
        if (product.equals("cursor") || provider.equals("cursor")) return "Cursor";
        if (product.equals("claude") || provider.equals("anthropic")) return "Claude";
        if (product.startsWith("grok") || provider.equals("xai")) return "Grok";
        if (product.equals("codex") || product.startsWith("chatgpt") || provider.equals("openai")) return "OpenAI";
        return "其他";
    }
    /** Stable slot order inside a section: ids compared with their numbers as numbers (codex-2 before codex-10). */
    static final Comparator<JSONObject> SLOT_ORDER = (x, y) -> {
        String a = String.valueOf(x.opt("id")), b = String.valueOf(y.opt("id"));
        String pa = a.replaceAll("\\d+$", ""), pb = b.replaceAll("\\d+$", "");
        if (!pa.equals(pb)) return pa.compareTo(pb);
        String na = a.substring(pa.length()), nb = b.substring(pb.length());
        if (na.isEmpty() || nb.isEmpty()) return na.compareTo(nb);
        return Long.compare(Long.parseLong(na.length() > 18 ? na.substring(0, 18) : na), Long.parseLong(nb.length() > 18 ? nb.substring(0, 18) : nb));
    };
    static String productName(JSONObject a) {
        String name = PRODUCT_NAMES.get(String.valueOf(a.opt("product")));
        return name != null ? name : a.opt("label") instanceof String ? (String) a.opt("label") : "Agent";
    }
    private static String clean(Object v) { return v instanceof String ? ((String) v).replaceAll("[\\u0000-\\u001f\\u007f-\\u009f]", " ").trim() : ""; }
    private static String meaningful(Object v) { String s = clean(v); return !s.isEmpty() && !s.matches("(?iu).*((账号|账户)\\s*[一二三四五六七八九十百零〇\\d]+|\\baccount\\s*[-#]?\\s*\\d+\\b).*") ? s : ""; }
    /** The account's email for the 总览 board's small line, or null when the collector reports none. */
    static String boardEmail(JSONObject a) {
        JSONObject profile = a.optJSONObject("profile");
        String email = profile == null ? "" : clean(profile.opt("email"));
        return email.isEmpty() ? null : email;
    }
    /** The board's small second line: the email first, then Points or the weekly reset; null when both are missing. */
    static String boardSubline(String email, String extras) {
        if (email == null) return extras;
        return extras == null ? email : email + " · " + extras;
    }
    /** The tile's title: the email, else a meaningful name such as 手机尾号 5320, else the product with 身份待核实. */
    static String displayName(JSONObject a) {
        JSONObject profile = a.optJSONObject("profile");
        String email = profile == null ? "" : clean(profile.opt("email"));
        if (!email.isEmpty()) return email;
        String name = profile == null ? "" : meaningful(profile.opt("name"));
        if (!name.isEmpty()) return name;
        String label = meaningful(a.opt("label"));
        if (!label.isEmpty() && !IDENTITY_PRODUCTS.containsValue(label) && !label.equals("ChatGPT Codex") && !label.equals("Codex")) return label;
        String product = IDENTITY_PRODUCTS.get(String.valueOf(a.opt("product")));
        return (product != null ? product : clean(a.opt("provider")).isEmpty() ? "账号" : clean(a.opt("provider"))) + ("not_configured".equals(a.opt("status")) ? " 待连接" : " 身份待核实");
    }
    private static long at(Object v) { if (!(v instanceof String)) return -1; try { return Instant.parse((String) v).toEpochMilli(); } catch (RuntimeException e) { return -1; } }
    private static List<JSONObject> windows(JSONObject a) {
        List<JSONObject> out = new ArrayList<>(); JSONArray all = a.optJSONArray("windows");
        if (all != null) for (int i = 0; i < all.length(); i++) { JSONObject w = all.optJSONObject(i); if (w != null) out.add(w); }
        return out;
    }
    /** The web's statusOf: a connected reading older than two minutes, or past a reset, is 待更新. */
    static String effectiveStatus(JSONObject a, long now) {
        String s = String.valueOf(a.opt("status"));
        if (!List.of("connected", "stale", "needs_auth", "unavailable", "not_configured").contains(s)) return "unavailable";
        if (!s.equals("connected")) return s;
        long seen = at(a.opt("lastSuccessAt"));
        if (seen < 0 || now - seen > 120_000L || seen > now + 60_000L) return "stale";
        for (JSONObject w : windows(a)) { long r = at(w.opt("resetsAt")); if (r >= 0 && r <= now) return "stale"; }
        return s;
    }
    static String number(double v) { return new java.text.DecimalFormat("#,##0.##").format(v); }
    private static JSONObject mainWindow(JSONObject a) {
        for (JSONObject w : windows(a)) if ("default".equals(w.opt("quotaGroup"))) return w;
        List<JSONObject> all = windows(a); return all.isEmpty() ? null : all.get(0);
    }
    /** The big value in the tile's corner. Claude says 已更新 there, its balances follow below. */
    static String headline(JSONObject a, long now) {
        String status = effectiveStatus(a, now);
        if (!status.equals("connected")) return "unsupported".equals(a.opt("reason")) ? "暂无额度接口" : "duplicate".equals(a.opt("reason")) ? "重复账号" : status(status);
        if ("claude".equals(a.opt("product"))) return "已更新";
        JSONObject w = mainWindow(a);
        if (w == null) return "未知";
        if (Boolean.TRUE.equals(w.opt("unlimited"))) return "不限量";
        Double pc = remaining(w); if (pc != null && pc >= 0 && pc <= 100) return number(pc) + "%";
        Object left = w.opt("remaining"); if (left instanceof Number) return number(((Number) left).doubleValue()) + (w.opt("unit") instanceof String && !"percent".equals(w.opt("unit")) ? " " + w.opt("unit") : "");
        return "未知";
    }
    /** 参考 / 续费 / 月付 US$… from the monthly cost, 已含订阅 when included, 月付 — when unknown. */
    static String monthly(JSONObject a) {
        JSONObject cost = a.optJSONObject("monthlyCost");
        if (cost != null && "included".equals(cost.opt("basis"))) return "已含订阅";
        Object minor = cost == null ? null : cost.opt("amountMinor");
        if (cost == null || !"USD".equals(cost.opt("currency")) || !(minor instanceof Number) || ((Number) minor).longValue() < 0) return "月付 —";
        long m = ((Number) minor).longValue();
        String amount = "US$" + new java.text.DecimalFormat(m % 100 == 0 ? "#,##0" : "#,##0.00").format(m / 100.0);
        Object basis = cost.opt("basis");
        return ("official-price".equals(basis) ? "参考 " : "confirmation".equals(basis) ? "续费 " : "月付 ") + amount;
    }
    /** The plan, without its brand prefix; a ChatGPT Pro tier shows its usage multiplier (Pro 500 is 25x). */
    static String plan(JSONObject a) {
        JSONObject sub = a.optJSONObject("subscription");
        String plan = sub == null ? "" : clean(sub.opt("planName"));
        if (plan.isEmpty()) return "—";
        if ("codex".equals(a.opt("product"))) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^ChatGPT Pro (100|200|500)$").matcher(plan);
            if (m.matches()) {
                String tier = m.group(1); Object observed = sub.opt("planMultiplier");
                List<Integer> supported = tier.equals("200") ? List.of(10, 20) : tier.equals("500") ? List.of(25) : List.of(5, 10);
                Integer multiplier = observed instanceof Number && supported.contains(((Number) observed).intValue()) ? ((Number) observed).intValue() : tier.equals("500") ? 25 : null;
                if (multiplier != null) plan = "ChatGPT Pro " + multiplier + "x";
            }
        }
        return plan.replaceFirst("^(ChatGPT |SuperGrok |Claude |TodayAI )", "");
    }
    /** The tile's two small balances: Codex Points and reset cards, Claude its 5-hour and weekly balances. */
    static List<String[]> balances(JSONObject a, long now) {
        boolean historical = !effectiveStatus(a, now).equals("connected");
        if ("codex".equals(a.opt("product"))) {
            JSONObject credits = a.optJSONObject("credits"), cards = a.optJSONObject("resetCredits");
            String points = credits == null ? "未提供" : Boolean.TRUE.equals(credits.opt("unlimited")) ? "不限量" : credits.opt("remaining") instanceof Number ? number(((Number) credits.opt("remaining")).doubleValue()) : "未提供";
            return List.of(new String[] {historical && credits != null ? "上次 Points" : "Points 点数", points},
                    new String[] {"重置卡", cards != null && cards.opt("available") instanceof Number ? ((Number) cards.opt("available")).intValue() + " 张" : "未提供"});
        }
        if ("claude".equals(a.opt("product"))) {
            List<String[]> out = new ArrayList<>(); JSONObject five = null, week = null;
            for (Period p : boardWindows(a)) { if ("5 小时".equals(p.label)) five = p.window; if ("每周".equals(p.label)) week = p.window; }
            for (Object[] row : new Object[][] {{"5 小时余额", five}, {"每周总量余额", week}}) {
                JSONObject w = (JSONObject) row[1]; Double pc = w == null ? null : remaining(w);
                out.add(new String[] {(String) row[0], pc == null ? "未提供" : (historical ? "上次 " : "") + number(pc) + "%"});
            }
            return out;
        }
        return List.of();
    }
    /** The web's usagePace: on a day-or-longer window, used at least 10 points less than time elapsed. */
    static String pace(JSONObject a, long now) {
        if (!effectiveStatus(a, now).equals("connected")) return null;
        for (JSONObject w : windows(a)) {
            long start = at(w.opt("startsAt")), end = at(w.opt("resetsAt")); Double pc = remaining(w);
            if (start < 0 || end < 0 || pc == null || pc < 0 || pc > 100 || end <= now || start > now || end <= start) continue;
            long duration = end - start, elapsed = now - start;
            if (duration < 86_400_000L || elapsed < Math.min(86_400_000L, duration / 4)) continue;
            double expected = elapsed * 100.0 / duration, used = 100 - pc;
            if (expected - used >= 10) return end - now <= 86_400_000L && pc >= 20 ? "即将重置" : "使用偏慢";
        }
        return null;
    }
    /** Reset deadlines: Claude's 5-hour and weekly resets, otherwise the next reset of any window. */
    static List<String> resetLines(JSONObject a, ZoneId zone) { return resetLines(a, zone, System.currentTimeMillis()); }
    static List<String> resetLines(JSONObject a, ZoneId zone, long now) {
        if ("claude".equals(a.opt("product"))) {
            JSONObject five = null, week = null;
            for (Period p : boardWindows(a)) { if ("5 小时".equals(p.label)) five = p.window; if ("每周".equals(p.label)) week = p.window; }
            return List.of("5 小时重置：" + shortTime(five == null ? -1 : at(five.opt("resetsAt")), zone), "每周重置：" + shortTime(week == null ? -1 : at(week.opt("resetsAt")), zone));
        }
        List<Long> resets = new ArrayList<>(); for (JSONObject w : windows(a)) { long r = at(w.opt("resetsAt")); if (r >= 0) resets.add(r); }
        if (resets.isEmpty()) return List.of("额度重置：未提供");
        java.util.Collections.sort(resets); long next = resets.get(resets.size() - 1);
        for (long r : resets) if (r > now) { next = r; break; }
        return List.of("额度重置： " + shortTime(next, zone));
    }
    private static String shortTime(long ms, ZoneId zone) { return ms < 0 ? "未提供" : SHORT.format(Instant.ofEpochMilli(ms).atZone(zone)); }
    private static String longTime(long ms, ZoneId zone) { return LONG.format(Instant.ofEpochMilli(ms).atZone(zone)); }

    /** 重置卡明细 for Codex, Claude and TodayAI: heading, notes, and one entry per card. */
    static final class Cards {
        /** {@code items}: name, state, expiry, granted. {@code pending} follows the list, as on the web; null when none. */
        final String heading, pending; final List<String> notes; final List<String[]> items;
        Cards(String heading, List<String> notes, List<String[]> items, String pending) { this.heading = heading; this.notes = notes; this.items = items; this.pending = pending; }
    }
    static Cards cards(JSONObject a, ZoneId zone, long now) {
        String product = String.valueOf(a.opt("product"));
        if (!List.of("codex", "claude", "today-cn", "today-global").contains(product)) return null;
        JSONObject data = cardData(a); String source = observation(a); boolean web = "official-web".equals(source), reported = "owner-confirmed".equals(source);
        // An official-site reading is a snapshot: older than five minutes it reads as the last record, as on the web.
        boolean historical = !effectiveStatus(a, now).equals("connected") || web && now - at(data.opt("detailsAt")) > 5 * 60_000L;
        List<String[]> items = new ArrayList<>(); int unused = 0;
        JSONArray list = data == null ? null : data.optJSONArray("cards");
        if (list != null) for (int i = 0; i < list.length(); i++) {
            JSONObject c = list.optJSONObject(i); if (c == null) continue;
            long expires = at(c.opt("expiresAt")), granted = at(c.opt("grantedAt")); String status = String.valueOf(c.opt("status"));
            String state = status.equals("available") && expires >= 0 && expires <= now ? "已过期"
                    : Map.of("available", "未使用", "redeemed", "已使用", "expired", "已过期", "redeeming", "使用中").getOrDefault(status, "待确认");
            if (state.equals("未使用")) unused++;
            long starts = at(c.opt("startsAt")); String kind = "claude".equals(product) ? CLAUDE_CARDS.get(String.valueOf(c.opt("resetType"))) : null;
            items.add(new String[] {(kind != null ? kind : "重置卡") + " " + (items.size() + 1), state, expires >= 0 ? longTime(expires, zone) + " 到期" : "到期时间未提供",
                    granted >= 0 ? longTime(granted, zone) + " 领取" : starts >= 0 ? longTime(starts, zone) + " 起可用" : "领取时间未提供"});
        }
        List<String> notes = new ArrayList<>();
        int available = data != null && data.opt("available") instanceof Number ? ((Number) data.opt("available")).intValue() : 0;
        long seen = data == null ? -1 : at(data.opt("detailsAt"));
        if (reported) notes.add("你确认有 " + available + " 张，待自动核验" + (seen >= 0 ? "（" + DateTimeFormatter.ofPattern("MM/dd").format(Instant.ofEpochMilli(seen).atZone(zone)) + "）" : ""));
        else if (web && seen >= 0) notes.add("官网核验：" + longTime(seen, zone));
        if (data != null && data.opt("applicable") instanceof Number) notes.add((web ? "核验时" : "当前") + "可使用 " + ((Number) data.opt("applicable")).intValue() + " 张");
        if (data == null) notes.add("重置卡明细暂未读取");
        else if (items.isEmpty()) notes.add(available > 0 ? "共有 " + available + " 张，逐张明细暂未读取" : "暂无可用卡片");
        String pending = !reported && data != null && !items.isEmpty() && available > unused ? "部分可用卡明细待更新" : null;
        return new Cards("重置卡明细" + (historical && data != null && !reported ? "（上次记录）" : ""), notes, items, pending);
    }
}
