package wang.robby.console.android;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 我 → 通知 as a short list of groups, the way iOS Settings nests a page. Pure Java: the activity only draws what this
 * describes and writes a preference when a switch or a line actually changes. Opening a group changes nothing, so the
 * first-read latch ({@code primed}) stays as it was.
 */
final class NoticePages {
    private NoticePages() {}
    static final String ROOT = "notices";
    static final String QUOTA = "notices-quota", SITES = "notices-sites", MODE = "notices-mode", CHALLENGE = "notices-challenge", CHECK = "notices-check";

    static final class Group {
        final String id, title, symbol; final List<String> keys; final boolean readOnly;
        Group(String id, String title, String symbol, List<String> keys, boolean readOnly) {
            this.id = id; this.title = title; this.symbol = symbol; this.keys = keys; this.readOnly = readOnly;
        }
    }
    static final List<Group> GROUPS = List.of(
        new Group(QUOTA, "Token 额度", "gauge", List.of(Notices.DAILY, Notices.CREDITS, Notices.LOW, Notices.RESET, Notices.ACCOUNT, Notices.CARD), false),
        new Group(SITES, "站点", "pulse", List.of(Notices.SITES), false),
        new Group(MODE, "提醒方式", "bell", List.of("enhanced", "persistent"), false),
        new Group(CHALLENGE, "28 天挑战", "calendar", List.of("tibo"), false),
        new Group(CHECK, "后台检查", "history", List.of(), true));

    /** What the phone has stored. Old installs only have on, daily, low, enhanced and persistent; the new line defaults. */
    static final class Snapshot {
        final Set<String> on; final int daily, low, creditsLine; final boolean enhanced, persistent, tibo, primed;
        Snapshot(Set<String> on, int daily, int low, int creditsLine, boolean enhanced, boolean persistent, boolean tibo, boolean primed) {
            this.on = on; this.daily = daily; this.low = low; this.creditsLine = creditsLine;
            this.enhanced = enhanced; this.persistent = persistent; this.tibo = tibo; this.primed = primed;
        }
        /** Persistent counts only while 增强提醒 is on, because it cannot ring without it. */
        boolean enabled(String key) {
            return switch (key) {
                case "enhanced" -> enhanced;
                case "persistent" -> enhanced && persistent;
                case "tibo" -> tibo;
                default -> on.contains(key);
            };
        }
        boolean stored(String key) {
            return switch (key) {
                case "enhanced" -> enhanced;
                case "persistent" -> persistent;
                case "tibo" -> tibo;
                default -> on.contains(key);
            };
        }
    }

    static boolean page(String id) {
        if (ROOT.equals(id)) return true;
        for (Group g : GROUPS) if (g.id.equals(id)) return true;
        return false;
    }
    /** Group pages go back to 通知; everything else leaves the section. */
    static String parent(String id) { return find(id) == null ? null : ROOT; }
    static Group find(String id) { for (Group g : GROUPS) if (g.id.equals(id)) return g; return null; }

    /** 「关闭」 when none of the group's switches are on, otherwise 「已开 N 项」. The read-only group names its rhythm. */
    static String summary(Group g, Snapshot s) {
        if (g.readOnly) return TiboWatch.EVERY;
        int n = 0; for (String key : g.keys) if (s.enabled(key)) n++;
        return n == 0 ? "关闭" : "已开 " + n + " 项";
    }

    static Snapshot read(Map<String, ?> notices, Map<String, ?> tibo) {
        Set<String> on = new LinkedHashSet<>();
        Object raw = notices == null ? null : notices.get("on");
        if (raw instanceof Collection<?> items) for (Object item : items) { String kind = String.valueOf(item); if (Notices.KINDS.contains(kind)) on.add(kind); }
        return new Snapshot(on,
            number(notices, "daily", 30, Notices.DAILY_CHOICES),
            number(notices, "low", 10, Notices.LOW_CHOICES),
            number(notices, "creditsLine", Notices.CREDIT_DEFAULT, Notices.CREDIT_CHOICES),
            flag(notices, "enhanced"), flag(notices, "persistent"), flag(tibo, "enabled"), flag(notices, "primed"));
    }
    /** Looking at a group. The snapshot comes back unchanged, primed included. */
    static Snapshot browse(Snapshot s, String pageId) { find(pageId); return s; }

    /** A switch that is already in this position does not clear primed. A real change does. */
    static Snapshot toggle(Snapshot s, String key, boolean value) {
        if (s.stored(key) == value) return s;
        Set<String> on = new LinkedHashSet<>(s.on);
        boolean enhanced = s.enhanced, persistent = s.persistent, tibo = s.tibo;
        switch (key) {
            case "enhanced" -> enhanced = value;
            case "persistent" -> persistent = value;
            case "tibo" -> tibo = value;
            default -> { if (value) on.add(key); else on.remove(key); }
        }
        return new Snapshot(on, s.daily, s.low, s.creditsLine, enhanced, persistent, tibo, false);
    }
    /** A threshold that is already selected does not clear primed. A real change does. */
    static Snapshot line(Snapshot s, String which, int value) {
        int current = "daily".equals(which) ? s.daily : "low".equals(which) ? s.low : s.creditsLine;
        if (current == value) return s;
        return new Snapshot(s.on, "daily".equals(which) ? value : s.daily, "low".equals(which) ? value : s.low,
            "daily".equals(which) || "low".equals(which) ? s.creditsLine : value, s.enhanced, s.persistent, s.tibo, false);
    }

    private static boolean flag(Map<String, ?> map, String key) { return map != null && Boolean.TRUE.equals(map.get(key)); }
    private static int number(Map<String, ?> map, String key, int fallback, int[] allowed) {
        if (map == null || !(map.get(key) instanceof Number n) || n.doubleValue() != n.intValue()) return fallback;
        int v = n.intValue();
        for (int choice : allowed) if (choice == v) return v;
        return fallback;
    }
}
