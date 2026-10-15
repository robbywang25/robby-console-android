package wang.robby.console.android;

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
        new Card("market", "关注行情", "关注列表的前几项报价", "trend", "market", "market", true)
    );
    static final List<String> DEFAULT = List.of("services", "instances", "domains", "reminders");

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
        Set<String> out = new LinkedHashSet<>(); for (String id : ids) { Card c = find(id); if (c != null) out.add(c.path); }
        return new ArrayList<>(out);
    }
}
