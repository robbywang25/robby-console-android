package wang.robby.console.android;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Explicit presentation allowlists. Never traverses or prints arbitrary JSON, credentials or clipboard content. */
public final class FeatureRenderer {
    private FeatureRenderer() {}
    /** Implemented by the host so overview figures can open their detail screen. */
    public interface Opener { void openFeature(String id); }
    private static final String UNKNOWN = "未知";
    private static final int MAX_ROWS = 100;
    /** Status rows the reader expanded (keys are hostnames or upstream ids); process memory only. */
    private static final Set<String> OPEN = new HashSet<>();

    public static void render(Context context, LinearLayout container, FeatureCatalog.Feature feature, JSONObject payload) {
        container.removeAllViews();
        UI ui = new UI(context, container);
        if (!isCompatible(feature, payload)) {
            ui.message("数据暂不可用", "响应版本或结构不兼容，请刷新重试。");
            return;
        }
        ui.freshness(payload, feature.id.equals("travel-ledger") ? 30 : 600);
        switch (feature.id) {
            case "overview": overview(ui, payload); break;
            case "infrastructure": infrastructure(ui, obj(payload, "infrastructure")); break;
            case "status": health(ui, obj(payload, "status")); break;
            case "observer": observer(ui, payload); break;
            case "umami": umami(ui, payload); break;
            case "posthog": posthog(ui, payload); break;
            case "openai": openai(ui, payload); break;
            case "domains": domains(ui, payload); break;
            case "site-maintenance": maintenance(ui, payload); break;
            case "market": market(ui, payload); break;
            case "okx": okx(ui, payload); break;
            case "engineering": engineering(ui, payload); break;
            case "clipboard": clipboard(ui, payload); break;
            case "travel-ledger": ledger(ui, payload); break;
            case "wechat-cards": cards(ui, payload); break;
            case "wechat-publishing": publishing(ui, payload); break;
            case "miniprogram-catalog": catalog(ui, payload); break;
            case "wechat-login-audit": audit(ui, payload); break;
            case "prompts": prompts(ui, payload); break;
            case "image-workshop": images(ui, payload); break;
            case "visit-insight-reports": reports(ui, payload); break;
            case "secrets": secrets(ui, payload); break;
            default: ui.message("暂未接入", "此功能尚无原生数据视图。");
        }
    }

    /** Version and minimum container checks; deeper server contract validation is a separate concern. */
    public static boolean isCompatible(FeatureCatalog.Feature f, JSONObject p) {
        if (f == null || p == null) return false;
        switch (f.id) {
            case "overview": case "infrastructure": case "status":
                return "2026-08-01".equals(p.opt("apiVersion")) && p.optJSONObject("summary") != null
                    && p.optJSONObject("status") != null && version(obj(p, "status"), 2);
            case "openai": return "2026-08-01".equals(p.opt("apiVersion")) && version(p, 4) && p.optJSONObject("totals") != null;
            case "posthog": return "2026-07-01".equals(p.opt("apiVersion")) && version(p, 1) && p.optJSONObject("summary") != null;
            case "observer": return api(p) && p.optJSONObject("metrics") != null && p.optJSONArray("sites") != null;
            case "umami": return api(p) && p.optJSONArray("sites") != null;
            case "market": return api(p) && p.optJSONArray("watchlist") != null;
            case "engineering": return api(p) && p.optJSONArray("repositoryIndex") != null && p.optJSONArray("items") != null;
            case "clipboard": return api(p) && p.optJSONArray("items") != null && p.optJSONObject("sourceStatus") != null;
            case "secrets": return api(p) && p.optJSONArray("services") != null;
            case "travel-ledger": return version(p, 1) && (p.optJSONArray("projects") != null ||
                (p.optJSONObject("project") != null && p.optJSONObject("summary") != null && p.optJSONArray("entries") != null));
            case "domains": return version(p, 1) && p.optJSONArray("domains") != null;
            case "okx": return version(p, 1) && p.optJSONArray("assets") != null;
            case "site-maintenance": return version(p, 1) && p.optJSONArray("sites") != null;
            case "wechat-cards": return version(p, 1) && p.optJSONArray("cards") != null;
            case "wechat-publishing": return (version(p, 1) || version(p, 2)) && p.optJSONArray("articles") != null;
            case "miniprogram-catalog": return (version(p, 1) || version(p, 2)) && p.optJSONArray("tools") != null;
            case "wechat-login-audit": return version(p, 1) && p.optJSONObject("binding") != null && p.optJSONArray("events") != null;
            case "prompts": return (version(p, 1) || version(p, 2)) && p.opt("version") instanceof String && p.opt("model") instanceof String;
            case "image-workshop": return version(p, 1) && p.optJSONObject("inventory") != null;
            case "visit-insight-reports": return version(p, 1) && p.optJSONArray("reports") != null;
            default: return false;
        }
    }
    private static boolean api(JSONObject p) { return "2026-07-01".equals(p.opt("apiVersion")); }
    private static boolean version(JSONObject p, int n) {
        Object value = p.opt("schemaVersion"); return value instanceof Number && ((Number) value).doubleValue() == n;
    }
    /**
     * Answer first: one hero sentence about service health, two figures that open their detail screen,
     * then reminders. Counts are only derived when all three numbers are present; otherwise it says 未知.
     */
    private static void overview(UI u, JSONObject p) {
        JSONObject s = obj(p, "summary");
        boolean wide=u.context.getResources().getConfiguration().screenWidthDp>=360&&u.context.getResources().getConfiguration().fontScale<1.3f;
        LinearLayout hero = u.tile("在线服务", "pulse", "status");
        SpannableStringBuilder figure = new SpannableStringBuilder(number(s, "servicesOnline"));
        if (!number(s, "servicesTotal").equals(UNKNOWN)) { int from = figure.length(); figure.append(" / ").append(number(s, "servicesTotal"));
            figure.setSpan(new AbsoluteSizeSpan(22, true), from, figure.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            figure.setSpan(new ForegroundColorSpan(u.theme.muted), from, figure.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); }
        TextView value = u.text("", 44, true); value.setText(figure); value.setPadding(0, u.dp(4), 0, u.dp(2)); hero.addView(value);
        hero.addView(u.text("已知 " + number(s, "servicesKnown") + " / 总计 " + number(s, "servicesTotal"), 13, false));
        LinearLayout chips = new LinearLayout(u.context); chips.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL); chips.setPadding(0, u.dp(12), 0, 0);
        Object online = s.opt("servicesOnline"), known = s.opt("servicesKnown"), total = s.opt("servicesTotal");
        if (online instanceof Number && known instanceof Number && total instanceof Number) {
            long down = ((Number) known).longValue() - ((Number) online).longValue(), unknown = ((Number) total).longValue() - ((Number) known).longValue();
            if (down <= 0 && unknown <= 0) u.chip(chips, "全部在线", u.theme.success);
            if (down > 0) u.chip(chips, down + " 项未在线", u.theme.danger);
            if (unknown > 0) u.chip(chips, unknown + " 项状态未知", u.theme.warning);
        } else u.chip(chips, "状态未知", u.theme.faint);
        hero.addView(chips);
        LinearLayout band = new LinearLayout(u.context); band.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL); u.root.addView(band, new LinearLayout.LayoutParams(-1, -2));
        String[][] figures = {{"云实例", "infrastructureInstances", "基础设施", "server", "infrastructure"}, {"域名资产", "domainAssets", "已登记域名", "globe", "domains"}};
        for (int i = 0; i < figures.length; i++) {
            LinearLayout tile = u.tile(figures[i][0], figures[i][3], figures[i][4], band);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tile.getLayoutParams(); if (wide) { lp.width = 0; lp.weight = 1; if (i == 0) lp.rightMargin = u.dp(12); }
            TextView v = u.text(number(s, figures[i][1]), 30, true); v.setPadding(0, u.dp(4), 0, u.dp(2)); tile.addView(v); tile.addView(u.text(figures[i][2], 12, false));
        }
        JSONArray notices = arr(p, "notices");
        LinearLayout c = u.card("待办提醒");
        if (notices.length() == 0) u.paragraph(c, "暂无提醒");
        for (int i = 0; i < limit(u, notices); i++) { JSONObject n = notices.optJSONObject(i); if (n == null) continue;
            String level = state(n, "severity"); u.notice(c, s(n, "title"), s(n, "detail"), "级别 " + level + (n.has("expiresAt") ? " · 到期 " + date(n, "expiresAt") : ""), level); }
    }
    private static void infrastructure(UI u, JSONObject p) {
        if (p.length() == 0) { u.message("基础设施", "当前快照未提供实例数据"); return; }
        u.freshness(p, 600); JSONObject cost = obj(p, "costSummary");
        LinearLayout c = u.card("费用参考"); u.row(c, "账单口径", flag(cost, "actualBill", "实际账单", "价格估算"));
        for (JSONObject m : rows(cost, "totals")) u.row(c, s(m, "currency"), number(m, "amount"));
        for (JSONObject n : rows(p, "instances")) {
            c = u.card(s(n, "name")); u.row(c, "状态", state(n, "state")); u.fields(c, n, "region", "区域", "cpuCores", "CPU 核数", "memoryGb", "内存 GB", "diskGb", "磁盘 GB");
            JSONObject m = obj(n, "metrics"); u.row(c, "CPU", unit(m, "cpuUsage", "%"));
            u.row(c, "内存", unit(m, "memoryUsage", "%")); u.row(c, "磁盘", unit(m, "diskUsage", "%"));
            JSONObject price = obj(n, "monthlyPrice"); u.row(c, "月价", money(price, "amount", "currency"));
            u.row(c, "到期", date(n, "expiresAt"));
        }
        empty(u, p, "instances", "暂无实例");
    }
    /**
     * 运行状态, modelled on status.robby.wang: an answer-first summary with the public / private / upstream counts,
     * then each group as one list in the web page's fixed order. Rows expand in place for the probe details, and the
     * problems named in the summary jump to their row.
     */
    private static void health(UI u, JSONObject p) {
        List<JSONObject> pub = new ArrayList<>(), priv = new ArrayList<>(), ups = new ArrayList<>();
        for (JSONObject n : rows(p, "sites")) ("public_page".equals(n.optString("surface")) ? pub : priv).add(n);
        for (JSONObject n : rows(p, "upstreams")) ups.add(n);
        List<String[]> issues = new ArrayList<>(); int bad = 0, unchecked = 0;
        for (JSONObject n : concat(pub, priv)) { int tone = StatusPage.siteTone(bool(n, "online"));
            if (tone == StatusPage.BAD) { bad++; issues.add(new String[]{"site:" + s(n, "hostname"), s(n, "label"), siteState(n), String.valueOf(tone)}); }
            else if (tone == StatusPage.NONE) unchecked++; }
        for (JSONObject n : ups) { int tone = upTone(n);
            if (tone == StatusPage.BAD || tone == StatusPage.WARN) { if (tone == StatusPage.BAD) bad++; issues.add(new String[]{"up:" + s(n, "id"), s(n, "name"), upState(n), String.valueOf(tone)}); }
            else if (tone == StatusPage.NONE) unchecked++; }
        LinearLayout hero = u.statusHero(p, issues.size(), bad, unchecked);
        JSONObject sum = obj(p, "summary");
        u.figures(hero, new String[]{"公开页面", "私有入口", "官方上游"}, new int[][]{
            {sum.optInt("publicOnline", -1), sum.optInt("publicKnown", -1), sum.optInt("publicTotal", -1)},
            {sum.optInt("privateOnline", -1), sum.optInt("privateKnown", -1), sum.optInt("privateTotal", -1)},
            {sum.optInt("upstreamOperational", -1), sum.optInt("upstreamTotal", -1), sum.optInt("upstreamTotal", -1)}});
        Map<String, Runnable> reveal = new HashMap<>();
        u.heading("公开页面与资产", "DNS 与 HTTPS 响应头耗时");
        LinearLayout group = u.group(); for (JSONObject n : pub) reveal.put("site:" + s(n, "hostname"), siteRow(u, group, n));
        if (pub.isEmpty()) u.paragraph(group, "站点清单暂不可读。");
        u.heading("私有入口与服务", "门禁可达不等于登录后功能正常");
        group = u.group(); for (JSONObject n : priv) reveal.put("site:" + s(n, "hostname"), siteRow(u, group, n));
        if (priv.isEmpty()) u.paragraph(group, "私有入口清单暂不可读。");
        u.heading("上游官方状态", "只读取官方整体状态和白名单组件");
        group = u.group(); for (JSONObject n : ups) reveal.put("up:" + s(n, "id"), upRow(u, group, n));
        if (ups.isEmpty()) u.paragraph(group, "上游状态暂不可读。");
        for (int i = 0; i < Math.min(6, issues.size()); i++) { String[] it = issues.get(i); u.jump(hero, it[1], it[2], Integer.parseInt(it[3]), reveal.get(it[0])); }
        if (issues.size() > 6) u.paragraph(hero, "另有 " + (issues.size() - 6) + " 项，见下方列表");
        LinearLayout c = u.card("这页能证明什么");
        u.paragraph(c, "站点状态来自固定清单的 DNS 与 HTTPS 响应；耗时是得到响应头的时间，不是完整页面加载时间。");
        u.paragraph(c, "认证门可达只证明门禁入口可达，不能代替登录后的功能验收。");
        u.paragraph(c, "OpenAI 与 GitHub 只读取官方整体状态和白名单组件，不读取账号或仓库。");
    }
    private static List<JSONObject> concat(List<JSONObject> a, List<JSONObject> b) { List<JSONObject> all = new ArrayList<>(a); all.addAll(b); return all; }
    /** JSON boolean, or null when the field is null or missing; status rows must tell "unchecked" from "false". */
    private static Boolean bool(JSONObject p, String key) { Object v = p.opt(key); return v instanceof Boolean ? (Boolean) v : null; }
    private static Integer integer(JSONObject p, String key) { Object v = p.opt(key); return v instanceof Number ? ((Number) v).intValue() : null; }
    private static String siteState(JSONObject n) { return StatusPage.site(bool(n, "online"), bool(n, "reachable"), n.optString("expectation"), bool(obj(n, "dns"), "resolved")); }
    private static int upTone(JSONObject n) { return StatusPage.upstreamTone(Boolean.TRUE.equals(n.opt("available")), Boolean.TRUE.equals(n.opt("stale")), n.optString("state")); }
    private static String upState(JSONObject n) { return StatusPage.upstream(Boolean.TRUE.equals(n.opt("available")), Boolean.TRUE.equals(n.opt("stale")), n.optString("state")); }
    private static Runnable siteRow(UI u, LinearLayout group, JSONObject n) {
        Boolean online = bool(n, "online"); Integer code = integer(n, "status"), latency = integer(n, "latencyMs"); Boolean resolved = bool(obj(n, "dns"), "resolved");
        String host = s(n, "hostname"), meta = online == null ? host + " · 未检测" : code == null ? host + " · 无响应" : host + " · HTTP " + code + " · " + (latency == null ? "—" : latency + " ms");
        LinearLayout d = u.details();
        u.detail(d, "检测方式", StatusPage.expectation(n.optString("expectation")), StatusPage.NONE);
        u.detail(d, "DNS", resolved == null ? "未检测" : resolved ? "已解析（" + ("cname".equals(obj(n, "dns").optString("recordType")) ? "CNAME" : "地址记录") + "）" : "未解析",
            resolved == null ? StatusPage.NONE : resolved ? StatusPage.OK : StatusPage.BAD);
        u.detail(d, "HTTP 状态", code == null ? (online == null ? "未检测" : "无响应") : String.valueOf(code), StatusPage.NONE);
        u.detail(d, "响应头耗时", latency == null ? "—" : latency + " ms", StatusPage.NONE);
        if ("auth_gateway".equals(n.optString("expectation"))) u.note(d, "认证门可达只说明门禁入口可达，不代表登录后功能正常。");
        return u.statusRow(group, "site:" + host, StatusPage.siteTone(online), s(n, "label"), siteState(n), meta, d);
    }
    private static Runnable upRow(UI u, LinearLayout group, JSONObject n) {
        List<String> off = new ArrayList<>(); int count = 0; LinearLayout d = u.details();
        for (JSONObject part : rows(n, "components")) { count++; String st = part.optString("status");
            if (!"operational".equals(st)) off.add(s(part, "name") + " " + StatusPage.component(st));
            u.detail(d, s(part, "name"), StatusPage.component(st), StatusPage.componentTone(st)); }
        if (n.has("updatedAt") && !n.isNull("updatedAt")) u.detail(d, "官方更新", date(n, "updatedAt"), StatusPage.NONE);
        String meta = Boolean.TRUE.equals(n.opt("stale")) ? "最近一次成功读取的状态已过期" : count == 0 ? "组件明细暂不可读"
            : off.isEmpty() ? count + " 个关注组件正常" : String.join("、", off.subList(0, Math.min(2, off.size()))) + (off.size() > 2 ? " 等 " + off.size() + " 项" : "");
        return u.statusRow(group, "up:" + s(n, "id"), upTone(n), s(n, "name"), upState(n), meta, d);
    }
    private static void observer(UI u, JSONObject p) {
        u.sources(obj(p, "sourceStatus"), "overview", "访问数据", "siteStatus", "站点探测", "analytics", "趋势数据");
        LinearLayout c = u.card("所选时段"); JSONObject metrics = obj(p, "metrics");
        u.fields(c, obj(metrics, "selectedRange"), "visitCount", "访问", "eventCount", "事件");
        u.fields(c, obj(metrics, "sites"), "total", "站点", "checked", "已检查", "online", "在线");
        for (JSONObject n : rows(p, "sites")) { c = u.card(s(n, "label")); u.paragraph(c, s(n, "hostname"));
            u.fields(c, n, "visitCount", "访问", "eventCount", "事件"); u.row(c, "健康", flag(n, "online", "在线", "离线")); }
    }
    private static void umami(UI u, JSONObject p) {
        for (JSONObject n : rows(p, "sites")) { LinearLayout c = u.card(s(n, "name")); u.paragraph(c, s(n, "hostname"));
            JSONObject status = obj(n, "sourceStatus"); u.row(c, "汇总来源", flag(status, "summary", "可用", "不可用"));
            u.fields(c, obj(obj(n, "summary"), "current"), "pageviews", "浏览量", "visitors", "访客", "visits", "访问", "bounces", "跳出");
            for (JSONObject item : rows(n, "paths")) u.row(c, s(item, "path"), unit(item, "visitorCount", " 位访客"));
            for (JSONObject item : rows(n, "sources")) u.row(c, s(item, "hostname"), unit(item, "visitorCount", " 位访客"));
        }
        empty(u, p, "sites", "暂无统计站点");
    }
    private static void posthog(UI u, JSONObject p) {
        u.sources(obj(p, "sourceStatus"), "eventTrend", "事件趋势", "funnel", "转化漏斗", "webVitals", "Web Vitals");
        LinearLayout c = u.card(s(obj(p, "project"), "label"));
        u.fields(c, obj(p, "summary"), "visibleEvents", "可见事件", "availableSections", "可用板块", "poorVitalCount", "较差体验指标");
        u.row(c, "漏斗转化", unit(obj(p, "summary"), "funnelConversionPercent", "%"));
        for (JSONObject n : rows(obj(p, "eventTrend"), "series")) u.row(c, s(n, "label"), number(n, "visibleTotal"));
        JSONObject funnel = obj(p, "funnel");
        if (funnel.length() > 0) { c = u.card(s(funnel, "label")); for (JSONObject n : rows(funnel, "steps"))
            u.row(c, s(n, "label"), Boolean.TRUE.equals(n.opt("suppressed")) ? "小样本隐藏" : number(n, "count")); }
        JSONObject vitals = obj(p, "webVitals");
        if (vitals.length() > 0) { c = u.card("Web Vitals"); for (JSONObject n : rows(vitals, "metrics"))
            u.row(c, s(n, "key"), unit(obj(n, "latest"), "value", " " + s(n, "unit")) + " · " + state(obj(n, "latest"), "rating")); }
    }
    private static void openai(UI u, JSONObject p) {
        LinearLayout c = u.card("组织用量"); JSONObject totals = obj(p, "totals");
        u.fields(c, totals, "requests", "请求", "inputTokens", "输入 Token", "outputTokens", "输出 Token", "cachedInputTokens", "缓存输入 Token");
        JSONObject cost = obj(obj(p, "costStatus"), "total");
        u.row(c, "成本 USD", Boolean.TRUE.equals(cost.opt("available")) ? number(totals, "costsUsd") : "暂不可用");
        u.row(c, "用量 / 费用口径", flag(obj(p, "scope"), "directlyComparable", "可直接比较", "口径不同"));
        u.row(c, "当前周期", flag(obj(p, "scope"), "currentBucketMayBePartial", "可能尚未完整", "完整"));
        u.row(c, "缓存状态", state(obj(p, "cacheState"), "state"));
        c = u.card("模型用量"); for (JSONObject n : rows(p, "models")) { u.row(c, s(n, "label"), unit(n, "requests", " 次请求"));
            u.row(c, "输入 / 输出", number(n, "inputTokens") + " / " + number(n, "outputTokens")); }
        // apiKeys and raw identifiers are deliberately never rendered.
    }
    private static void domains(UI u, JSONObject p) {
        u.sources(obj(p, "sources"), "dns", "DNS", "registration", "注册", "pricing", "价格", "icp", "备案");
        LinearLayout c = u.card("域名概览"); u.fields(c, obj(p, "summary"), "domains", "域名", "registered", "账号内注册", "dnsManaged", "DNS 管理", "expiringWithin60Days", "60 天内到期");
        for (JSONObject n : rows(p, "domains")) { c = u.card(s(n, "name")); JSONObject r = obj(n, "registration");
            u.row(c, "注册", state(r, "status")); u.row(c, "到期", s(r, "expiresAt")); u.row(c, "剩余", unit(r, "expiryDays", " 天"));
            u.row(c, "自动续费", flag(r, "autoRenew", "已开启", "未开启"));
            u.row(c, "年续费参考", money(obj(r, "annualRenewalCost"), "amount", "currency"));
            u.row(c, "DNS", flag(obj(n, "dns"), "managed", "已管理", "未管理"));
            u.row(c, "备案匹配", flag(obj(n, "icp"), "matched", "已匹配", "未匹配，需官方核验")); }
    }
    private static void maintenance(UI u, JSONObject p) {
        LinearLayout c = u.card("维护标记"); u.row(c, "一致性", flag(p, "stateHealthy", "正常", "需核对"));
        for (JSONObject n : rows(p, "sites")) { c = u.card(s(n, "label")); u.paragraph(c, s(n, "hostname"));
            u.row(c, "状态", state(n, "state")); u.paragraph(c, s(n, "impact")); u.row(c, "更新", date(n, "updatedAt")); }
    }
    private static void market(UI u, JSONObject p) {
        u.sources(obj(p, "sourceStatus"), "watchlist", "关注行情", "history", "趋势", "sectors", "板块", "benchmarks", "基准", "aiUpstream", "AI 产业链");
        for (String key : new String[]{"watchlist", "sectors", "benchmarks"}) for (JSONObject n : rows(p, key)) {
            LinearLayout c = u.card(s(n, "label")); u.row(c, "价格", number(n, "price") + (n.has("currency") ? " " + s(n, "currency") : ""));
            u.row(c, "涨跌", unit(n, "changePct", "%")); if (n.has("updatedAt")) u.row(c, "行情时间", date(n, "updatedAt")); }
        for (JSONObject group : rows(p, "aiUpstream")) { LinearLayout c = u.card(s(group, "name")); for (JSONObject n : rows(group, "companies"))
            u.row(c, s(n, "name"), Boolean.TRUE.equals(n.opt("quoteAvailable")) ? number(n, "price") + " " + s(n, "currency") : "暂无报价"); }
        u.message("行情说明", s(obj(p, "disclaimer"), "text"));
    }
    private static void okx(UI u, JSONObject p) {
        LinearLayout c = u.card("账户摘要"); u.fields(c, p, "totalEquityUsd", "总权益 USD", "unrealizedPnlUsd", "未实现盈亏 USD", "notionalUsd", "名义价值 USD", "marginRatio", "保证金比率", "openPositionCount", "持仓数");
        u.row(c, "账户更新时间", date(p, "accountUpdatedAt"));
        for (JSONObject n : rows(p, "assets")) { c = u.card(s(n, "currency")); u.fields(c, n, "equity", "权益", "available", "可用", "frozen", "冻结", "usdValue", "估值 USD"); }
        for (JSONObject n : rows(p, "positions")) { c = u.card(s(n, "instrument")); u.row(c, "方向", state(n, "side"));
            u.fields(c, n, "quantity", "数量", "averageEntryPrice", "开仓均价", "markPrice", "标记价格", "unrealizedPnl", "未实现盈亏", "leverage", "杠杆"); }
    }
    private static void engineering(UI u, JSONObject p) {
        JSONObject coverage = obj(p, "coverage");
        if (Boolean.TRUE.equals(coverage.opt("truncated"))) u.message("日志已截断", "当前返回 " + number(coverage, "returnedItems") + " 条；并非全部历史。");
        for (JSONObject n : rows(p, "repositoryIndex")) { LinearLayout c = u.card(s(n, "name")); u.row(c, "状态", state(n, "state")); u.paragraph(c, s(n, "description")); }
        for (JSONObject n : rows(p, "items")) { LinearLayout c = u.card(s(n, "summary")); u.row(c, "仓库", s(n, "repository")); u.row(c, "时间", date(n, "timestamp")); }
    }
    private static void clipboard(UI u, JSONObject p) {
        u.message("剪贴板状态", source(obj(p, "sourceStatus")) + " · 仅显示元数据");
        int i = 0; for (JSONObject n : rows(p, "items")) { LinearLayout c = u.card("剪贴内容 " + (++i));
            u.row(c, "可见性", flag(n, "isPublic", "公开", "私密")); u.row(c, "创建", date(n, "createdAt")); u.row(c, "到期", date(n, "expiresAt")); }
        empty(u, p, "items", "暂无剪贴内容");
    }
    private static void ledger(UI u, JSONObject p) {
        if (p.optJSONArray("projects") != null) {
            for (JSONObject n : rows(p, "projects")) u.message(s(n, "name"), "选择账本后查看账目");
            empty(u, p, "projects", "暂无可用账本"); return;
        }
        LinearLayout c = u.card(s(obj(p, "project"), "name")); JSONObject summary = obj(p, "summary");
        u.fields(c, summary, "entryCount", "账目", "refundCount", "退款");
        u.amount(c, "人民币参考合计", unit(summary, "cnyTotal", " CNY"));
        u.row(c, "汇率覆盖", flag(summary, "cnyComplete", "完整", "部分币种缺失"));
        for (JSONObject n : rows(summary, "totals")) { c = u.card(s(n, "currency")); u.fields(c, n, "spent", "支出", "refunded", "退款", "net", "净支出"); }
        for (JSONObject n : rows(p, "members")) { c = u.card(s(n, "displayName")); for (JSONObject total : rows(n, "totals")) {
            u.row(c, s(total, "currency") + " 已付", s(total, "paid")); u.row(c, "应分摊 / 差额", s(total, "expense") + " / " + s(total, "net")); } }
        u.message("分摊参考", "差额是账本计算结果，不代表实际还款。");
        for (JSONObject n : rows(p, "entries")) { c = u.card(s(n, "merchant").equals(UNKNOWN) ? s(n, "category") : s(n, "merchant"));
            u.amount(c, "金额", money(n, "amount", "currency")); u.row(c, "退款", money(n, "refundedAmount", "currency"));
            u.row(c, "净额", money(n, "netAmount", "currency")); u.row(c, "类别", s(n, "category")); u.row(c, "付款人", s(n, "payer"));
            u.row(c, "发生时间", s(n, "occurredAt")); }
        JSONObject page = obj(p, "page"); u.message("账目分页", "总计 " + number(page, "total") + " 条 · " + flag(page, "hasMore", "还有下一页", "已到末页"));
    }
    private static void cards(UI u, JSONObject p) {
        for (JSONObject n : rows(p, "cards")) { LinearLayout c = u.card(s(n, "title")); u.paragraph(c, s(n, "description"));
            u.row(c, "配置", flag(n, "customized", "自定义", "默认")); u.row(c, "更新", date(n, "updatedAt")); }
        empty(u, p, "cards", "暂无卡片");
    }
    private static void publishing(UI u, JSONObject p) {
        LinearLayout c = u.card("公众号状态"); u.row(c, "配置", flag(p, "wechatConfigured", "已配置", "未配置"));
        JSONObject caps = obj(p, "capabilities");
        for (String k : new String[]{"drafts", "menu"}) { JSONObject n = obj(caps, k); u.row(c, k.equals("drafts") ? "草稿" : "菜单", Boolean.TRUE.equals(n.opt("available")) ? number(n, "count") : "暂不可用"); }
        JSONObject pub = obj(p, "published"); u.row(c, "已发布", Boolean.TRUE.equals(pub.opt("available")) ? number(pub, "totalCount") : "暂不可用");
        for (JSONObject n : rows(p, "articles")) { c = u.card(s(n, "title")); u.row(c, "发布时间", date(n, "publishedAt")); }
        for (JSONObject n : rows(p, "history")) { c = u.card(s(n, "title")); u.row(c, "草稿创建", date(n, "createdAt")); }
    }
    private static void catalog(UI u, JSONObject p) {
        LinearLayout c = u.card("目录同步"); u.row(c, "版本", number(p, "catalogVersion")); u.row(c, "来源更新", date(p, "sourceGeneratedAt"));
        u.row(c, "个人资料", flag(obj(p, "profile"), "configured", "已配置", "未配置"));
        for (JSONObject n : rows(p, "tools")) { c = u.card(s(n, "title")); u.row(c, "状态", state(n, "state")); u.row(c, "项目数", number(n, "itemCount"));
            u.row(c, "来源", s(obj(n, "source"), "label")); }
    }
    private static void audit(UI u, JSONObject p) {
        LinearLayout c = u.card("管理员绑定"); JSONObject b = obj(p, "binding"); u.row(c, "绑定", flag(b, "configured", "已绑定", "未绑定"));
        u.row(c, "待处理绑定", flag(b, "pending", "有", "无")); u.row(c, "活跃会话", number(b, "activeSessionCount"));
        c = u.card("登录概览"); u.fields(c, obj(p, "summary"), "successCount", "成功", "deniedCount", "拒绝", "returnedEventCount", "返回事件");
        for (JSONObject n : rows(p, "events")) { c = u.card(state(n, "type")); u.row(c, "结果", state(n, "result"));
            u.row(c, "时间", date(n, "occurredAt")); if (!n.isNull("reason")) u.row(c, "原因", state(n, "reason")); }
    }
    private static void prompts(UI u, JSONObject p) {
        LinearLayout c = u.card("资产日报 Prompt"); u.fields(c, p, "task", "任务", "model", "模型", "version", "版本");
        u.row(c, "来源", state(p, "source")); u.row(c, "更新时间", date(p, "updatedAt"));
        // Template, hashes, receipts and request fingerprints stay outside the metadata screen.
    }
    private static void images(UI u, JSONObject p) {
        JSONObject status = obj(p, "status"); LinearLayout c = u.card("图片工坊状态");
        u.row(c, "凭据配置", flag(status, "credentialConfigured", "已配置", "未配置")); u.row(c, "处理服务", flag(status, "processingAvailable", "可用", "不可用"));
        JSONObject inventory = obj(p, "inventory"); u.fields(c, inventory, "total", "图片总数", "returned", "当前返回");
        if (Boolean.TRUE.equals(inventory.opt("truncated"))) u.paragraph(c, "库存已截断");
        for (JSONObject n : rows(inventory, "assets")) { c = u.card("图片 " + number(n, "sequence")); u.row(c, "更新", date(n, "updatedAt"));
            for (JSONObject d : rows(n, "derivatives")) u.row(c, state(d, "role"), number(d, "width") + " × " + number(d, "height") + " · " + unit(d, "bytes", " B")); }
    }
    private static void reports(UI u, JSONObject p) {
        for (JSONObject n : rows(p, "reports")) { LinearLayout c = u.card(n.isNull("title") ? s(n, "hostname") : s(n, "title"));
            u.row(c, "站点", s(n, "hostname")); u.row(c, "状态", state(n, "status")); u.fields(c, n, "visitCount", "访问", "eventCount", "事件");
            u.row(c, "生成时间", date(n, "createdAt")); }
        empty(u, p, "reports", "暂无归档报告");
    }
    private static void secrets(UI u, JSONObject p) {
        for (JSONObject n : rows(p, "services")) { LinearLayout c = u.card(s(n, "label"));
            u.row(c, "配置", flag(n, "configured", "已配置", "未配置")); u.row(c, "更新", date(n, "updatedAt")); }
    }

    private static JSONObject obj(JSONObject p, String key) { JSONObject o = p.optJSONObject(key); return o == null ? new JSONObject() : o; }
    private static JSONArray arr(JSONObject p, String key) { JSONArray a = p.optJSONArray(key); return a == null ? new JSONArray() : a; }
    private static Iterable<JSONObject> rows(JSONObject p, String key) {
        java.util.List<JSONObject> out = new java.util.ArrayList<>(); JSONArray a = arr(p, key);
        for (int i = 0; i < Math.min(MAX_ROWS, a.length()); i++) if (a.optJSONObject(i) != null) out.add(a.optJSONObject(i));
        return out;
    }
    private static int limit(UI u, JSONArray a) { if (a.length() > MAX_ROWS) u.message("显示范围", "当前最多显示 " + MAX_ROWS + " 项"); return Math.min(MAX_ROWS, a.length()); }
    private static void empty(UI u, JSONObject p, String key, String text) { if (arr(p, key).length() == 0) u.message(text, ""); }
    private static String s(JSONObject p, String key) {
        Object v = p.opt(key); if (v == null || v == JSONObject.NULL) return UNKNOWN;
        if (v instanceof String) { String text = ((String) v).trim(); if (text.isEmpty()) return UNKNOWN;
            // Bound user/server supplied labels, reject control characters, never stringify objects.
            if (text.length() > 1200 || text.matches("(?s).*[\\p{Cntrl}&&[^\\n\\t]].*")) return "内容格式异常";
            return text; }
        if (v instanceof Number) return number(p, key);
        if (v instanceof Boolean) return (Boolean) v ? "是" : "否";
        return UNKNOWN;
    }
    private static String number(JSONObject p, String key) {
        Object v = p.opt(key); if (v instanceof Number) { double d = ((Number) v).doubleValue();
            if (!Double.isFinite(d)) return UNKNOWN; NumberFormat f = NumberFormat.getNumberInstance(Locale.SIMPLIFIED_CHINESE); f.setMaximumFractionDigits(6); return f.format(v); }
        if (v instanceof String && ((String) v).matches("-?(0|[1-9][0-9]{0,17})(\\.[0-9]{1,12})?")) return (String) v;
        return UNKNOWN;
    }
    private static String unit(JSONObject p, String key, String unit) { String n = number(p, key); return n.equals(UNKNOWN) ? UNKNOWN : n + unit; }
    private static String money(JSONObject p, String amount, String currency) { String n = number(p, amount); return n.equals(UNKNOWN) ? UNKNOWN : n + " " + s(p, currency); }
    private static String flag(JSONObject p, String key, String yes, String no) {
        Object v = p.opt(key); return v instanceof Boolean ? ((Boolean) v ? yes : no) : UNKNOWN;
    }
    private static String date(JSONObject p, String key) {
        Object v = p.opt(key); if (!(v instanceof String)) return UNKNOWN;
        try { return DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE).withZone(ZoneId.systemDefault()).format(Instant.parse((String) v)); }
        catch (RuntimeException e) { return UNKNOWN; }
    }
    private static String source(JSONObject p) {
        if (p.has("state")) return state(p, "state");
        if (Boolean.FALSE.equals(p.opt("available"))) return "暂不可用";
        if (Boolean.TRUE.equals(p.opt("stale"))) return "数据已陈旧";
        if (Boolean.TRUE.equals(p.opt("cached"))) return "缓存";
        return flag(p, "available", "可用", "暂不可用");
    }
    /** Picker label for one ledger snapshot entry. Blank merchants fall back to the category, matching the entry cards. */
    static String ledgerEntryLabel(JSONObject e) {
        String title = e.optString("merchant").trim(); if (title.isEmpty()) title = e.optString("category").trim(); if (title.isEmpty()) title = "账目";
        String amount = (e.optString("amount") + " " + e.optString("currency")).trim();
        return amount.isEmpty() ? title : title + " · " + amount;
    }
    static String availability(JSONObject p) {
        if (Boolean.FALSE.equals(p.opt("available"))) return "暂不可用";
        if (Boolean.TRUE.equals(p.opt("stale"))) return "数据已陈旧";
        return flag(p, "available", "可用", "暂不可用");
    }
    private static String state(JSONObject p, String key) {
        String raw = s(p, key);
        switch (raw) {
            case "available": case "ready": case "healthy": case "normal": case "operational": return "正常";
            case "active": case "running": case "RUNNING": return "运行中";
            case "maintenance": return "维护中";
            case "unavailable": case "error": case "failed": return "暂不可用";
            case "stale": return "数据已陈旧";
            case "notConfigured": case "not_configured": return "未配置";
            case "needs_auth": return "需要重新登录";
            case "success": case "completed": return "成功";
            case "denied": return "已拒绝";
            case "pending": case "queued": return "待处理";
            case "warning": case "degraded": return "需关注";
            case "critical": case "major_outage": return "异常";
            case "info": return "提示";
            case "good": return "良好";
            case "poor": return "较差";
            case "needs_improvement": return "待改善";
            case "long": return "多头";
            case "short": return "空头";
            case "net": return "净持仓";
            case "login_success": return "登录成功";
            case "login_denied": return "登录被拒绝";
            case "admin_bound": return "管理员已绑定";
            case "admin_revoked": return "管理员已撤销";
            case "binding_challenge_created": return "创建绑定请求";
            case "binding_required": return "需要绑定";
            case "not_bound_admin": return "非已绑定管理员";
            case "wechat-square": return "微信方图";
            case "webp": return "WebP";
            case "default": return "默认";
            case "custom": return "自定义";
            case "unknown": return UNKNOWN;
            default: return raw;
        }
    }
    private static final class UI {
        final Context context; final LinearLayout root; final int ink, muted, background, border; final ConsoleTheme theme;
        UI(Context c, LinearLayout r) { context=c;root=r;theme=new ConsoleTheme(c);ink=theme.ink;muted=theme.muted;background=theme.surface;border=theme.line; }
        int dp(float n) { return Math.round(n * context.getResources().getDisplayMetrics().density); }
        TextView text(String value, int size, boolean bold) {return theme.text(value,size,bold);}
        LinearLayout card(String title) {LinearLayout c=theme.card(root);TextView heading=text(title,16,true);heading.setAccessibilityHeading(true);heading.setPadding(0,0,0,dp(6));c.addView(heading);return c;}
        /** Figure card with label and tinted symbol; opens {@code target} when the host supports it. */
        LinearLayout tile(String label,String symbol,String target){return tile(label,symbol,target,root);}
        LinearLayout tile(String label,String symbol,String target,LinearLayout parent){
            LinearLayout t=new LinearLayout(context);t.setOrientation(LinearLayout.VERTICAL);t.setPadding(dp(18),dp(16),dp(18),dp(16));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(12);parent.addView(t,lp);
            LinearLayout line=new LinearLayout(context);line.setGravity(Gravity.CENTER_VERTICAL);line.addView(text(label,14,false),new LinearLayout.LayoutParams(0,-2,1));
            line.addView(theme.tile(symbol,32),new LinearLayout.LayoutParams(dp(32),dp(32)));t.addView(line);
            if(context instanceof Opener&&FeatureCatalog.find(target)!=null){
                t.setBackground(theme.ripple(theme.card(),24));t.setFocusable(true);t.setClickable(true);t.setOnClickListener(v->((Opener)context).openFeature(target));theme.press(t);
                t.setContentDescription(label+"，查看"+FeatureCatalog.find(target).title);
            } else t.setBackground(theme.card());
            return t;
        }
        void chip(LinearLayout parent,String label,int color){
            LinearLayout chip=new LinearLayout(context);chip.setGravity(Gravity.CENTER_VERTICAL);chip.setPadding(dp(10),dp(5),dp(12),dp(5));chip.setBackground(theme.shape(theme.soft,99));
            chip.addView(theme.dot(color,7));TextView t=text(label,13,true);t.setPadding(dp(6),0,0,0);chip.addView(t);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.rightMargin=dp(8);lp.bottomMargin=dp(6);parent.addView(chip,lp);
        }
        /** One reminder line inside a card: severity dot, title, detail and meta. The level stays as text. */
        void notice(LinearLayout c,String title,String detail,String meta,String level){
            if(c.getChildCount()>1)divider(c);
            LinearLayout r=new LinearLayout(context);r.setPadding(0,dp(10),0,dp(10));
            int color=theme.status(level);View dot=theme.dot(color==0?theme.accent:color,8);LinearLayout.LayoutParams dp8=(LinearLayout.LayoutParams)dot.getLayoutParams();dp8.topMargin=dp(7);dp8.rightMargin=dp(12);r.addView(dot,dp8);
            LinearLayout texts=new LinearLayout(context);texts.setOrientation(LinearLayout.VERTICAL);texts.addView(text(title,15,true));
            if(!detail.equals(UNKNOWN)){TextView d=text(detail,14,false);d.setPadding(0,dp(3),0,0);texts.addView(d);}
            TextView m=text(meta,12,false);m.setTextColor(theme.faint);m.setPadding(0,dp(4),0,0);texts.addView(m);r.addView(texts,new LinearLayout.LayoutParams(0,-2,1));c.addView(r);
        }
        int tone(int t){return t==StatusPage.OK?theme.success:t==StatusPage.WARN?theme.warning:t==StatusPage.BAD?theme.danger:theme.faint;}
        void heading(String title,String note){TextView h=text(title,15,true);h.setAccessibilityHeading(true);h.setPadding(dp(6),dp(18),0,note==null?dp(10):dp(2));root.addView(h);
            if(note!=null){TextView n=text(note,12,false);n.setTextColor(theme.faint);n.setPadding(dp(6),0,0,dp(10));root.addView(n);}}
        LinearLayout group(){LinearLayout c=theme.card(root);c.setPadding(dp(4),dp(4),dp(4),dp(4));if(theme.effects)c.setLayoutTransition(new android.animation.LayoutTransition());return c;}
        /** Answer-first header of 运行状态: verdict, probe time and whether every source answered. */
        LinearLayout statusHero(JSONObject p,int problems,int bad,int unchecked){
            LinearLayout c=theme.card(root);c.setPadding(dp(18),dp(16),dp(18),dp(14));
            LinearLayout top=new LinearLayout(context);top.setGravity(Gravity.CENTER_VERTICAL);View dot=theme.dot(tone(StatusPage.verdictTone(problems,bad,unchecked)),10);((LinearLayout.LayoutParams)dot.getLayoutParams()).rightMargin=dp(10);top.addView(dot);
            TextView v=text(StatusPage.verdict(problems,unchecked),22,true);v.setAccessibilityHeading(true);top.addView(v,new LinearLayout.LayoutParams(0,-2,1));c.addView(top);
            TextView w=text("检测于 "+date(p,"generatedAt")+(Boolean.TRUE.equals(p.opt("cacheHit"))?" · 缓存结果":"")+" · 停留时每 60 秒自动刷新",12,false);w.setTextColor(theme.faint);w.setPadding(0,dp(6),0,0);c.addView(w);
            LinearLayout chips=new LinearLayout(context);chips.setPadding(0,dp(10),0,0);boolean partial=Boolean.TRUE.equals(p.opt("partial"));chip(chips,partial?"部分来源降级":"数据源正常",partial?theme.warning:theme.success);c.addView(chips);
            return c;
        }
        /** Three summary figures; side by side on normal widths, stacked with large text. */
        void figures(LinearLayout c,String[] labels,int[][] counts){
            boolean wide=context.getResources().getConfiguration().screenWidthDp>=360&&context.getResources().getConfiguration().fontScale<1.3f;
            LinearLayout band=new LinearLayout(context);band.setOrientation(wide?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);band.setPadding(0,dp(8),0,dp(4));
            for(int i=0;i<labels.length;i++){int[] k=counts[i];LinearLayout f=new LinearLayout(context);f.setOrientation(LinearLayout.VERTICAL);f.setPadding(0,dp(6),dp(8),dp(6));
                f.addView(text(labels[i],12,false));TextView n=text(k[2]<0?"未知":k[0]+" / "+k[2],20,true);n.setPadding(0,dp(2),0,dp(2));f.addView(n);
                LinearLayout cap=new LinearLayout(context);cap.setGravity(Gravity.CENTER_VERTICAL);View dot=theme.dot(tone(StatusPage.figureTone(k[0],k[1],k[2])),6);((LinearLayout.LayoutParams)dot.getLayoutParams()).rightMargin=dp(5);cap.addView(dot);cap.addView(text(StatusPage.figureCaption(k[0],k[1],k[2]),12,false));f.addView(cap);
                f.setContentDescription(labels[i]+" "+(k[2]<0?"未知":k[0]+" / "+k[2])+"，"+StatusPage.figureCaption(k[0],k[1],k[2]));
                band.addView(f,wide?new LinearLayout.LayoutParams(0,-2,1):new LinearLayout.LayoutParams(-1,-2));}
            c.addView(band);
        }
        /** A named problem inside the summary; tapping opens that row below and scrolls to it. */
        void jump(LinearLayout c,String title,String state,int tone,Runnable reveal){
            if(c.getChildCount()>0&&!"jump".equals(c.getChildAt(c.getChildCount()-1).getTag())){View d=new View(context);d.setBackgroundColor(border);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(.5f));lp.setMargins(0,dp(6),0,dp(2));c.addView(d,lp);}
            LinearLayout r=new LinearLayout(context);r.setTag("jump");r.setGravity(Gravity.CENTER_VERTICAL);r.setMinimumHeight(dp(44));r.setPadding(0,dp(4),0,dp(4));
            View dot=theme.dot(tone(tone),8);((LinearLayout.LayoutParams)dot.getLayoutParams()).rightMargin=dp(10);r.addView(dot);
            TextView t=text(title,15,false);t.setTextColor(theme.ink);r.addView(t,new LinearLayout.LayoutParams(0,-2,1));TextView st=text(state,13,true);st.setTextColor(tone(tone));st.setPadding(dp(8),0,dp(6),0);r.addView(st);
            ImageView chevron=theme.icon("chevron",theme.muted,14);chevron.setAlpha(.45f);r.addView(chevron);
            if(reveal!=null){r.setBackground(theme.ripple(theme.shape(Color.TRANSPARENT,12),12));r.setFocusable(true);r.setClickable(true);r.setOnClickListener(v->reveal.run());}
            r.setContentDescription(title+"，"+state+"，查看详情");c.addView(r);
        }
        LinearLayout details(){LinearLayout d=new LinearLayout(context);d.setOrientation(LinearLayout.VERTICAL);return d;}
        void detail(LinearLayout d,String label,String value,int tone){
            LinearLayout r=new LinearLayout(context);r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(0,dp(5),0,dp(5));
            r.addView(text(label,13,false),new LinearLayout.LayoutParams(0,-2,1));
            if(tone!=StatusPage.NONE){View dot=theme.dot(tone(tone),6);((LinearLayout.LayoutParams)dot.getLayoutParams()).rightMargin=dp(6);r.addView(dot);}
            TextView v=text(value,14,false);v.setTextColor(theme.ink);r.addView(v);d.addView(r);
        }
        void note(LinearLayout d,String value){TextView t=text(value,12,false);t.setTextColor(theme.faint);t.setPadding(0,dp(6),0,dp(2));d.addView(t);}
        /** Expandable status row inside a group; returns an action that opens it and scrolls it into view. */
        Runnable statusRow(LinearLayout group,String key,int tone,String title,String state,String meta,LinearLayout details){
            if(group.getChildCount()>0){View d=new View(context);d.setBackgroundColor(border);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(.5f));lp.setMargins(dp(34),0,dp(12),0);group.addView(d,lp);}
            LinearLayout box=new LinearLayout(context);box.setOrientation(LinearLayout.VERTICAL);
            LinearLayout head=new LinearLayout(context);head.setGravity(Gravity.CENTER_VERTICAL);head.setPadding(dp(14),dp(11),dp(10),dp(11));head.setMinimumHeight(dp(56));
            head.setBackground(theme.ripple(theme.shape(Color.TRANSPARENT,18),18));head.setFocusable(true);head.setClickable(true);
            View dot=theme.dot(tone(tone),8);((LinearLayout.LayoutParams)dot.getLayoutParams()).rightMargin=dp(12);head.addView(dot);
            LinearLayout texts=new LinearLayout(context);texts.setOrientation(LinearLayout.VERTICAL);TextView t=text(title,16,false);t.setTextColor(theme.ink);texts.addView(t);
            TextView m=text(meta,12,false);m.setPadding(0,dp(2),0,0);texts.addView(m);head.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
            TextView st=text(state,13,true);st.setTextColor(tone==StatusPage.OK?theme.muted:tone(tone));st.setPadding(dp(8),0,dp(6),0);head.addView(st);
            boolean open=OPEN.contains(key);ImageView chevron=theme.icon("chevron",theme.muted,14);chevron.setAlpha(.45f);chevron.setRotation(open?-90:90);head.addView(chevron);
            box.addView(head);details.setPadding(dp(34),0,dp(16),dp(10));details.setVisibility(open?View.VISIBLE:View.GONE);box.addView(details);
            head.setContentDescription(title+"，"+state+"，"+meta);head.setStateDescription(open?"已展开":"已收起");
            Runnable toggle=()->{boolean show=details.getVisibility()!=View.VISIBLE;if(show)OPEN.add(key);else OPEN.remove(key);details.setVisibility(show?View.VISIBLE:View.GONE);head.setStateDescription(show?"已展开":"已收起");
                if(theme.effects)chevron.animate().rotation(show?-90:90).setDuration(200).start();else chevron.setRotation(show?-90:90);};
            head.setOnClickListener(v->toggle.run());group.addView(box);
            // Leave room for the floating dock when scrolling a revealed row into view.
            return ()->{if(details.getVisibility()!=View.VISIBLE)toggle.run();box.postDelayed(()->box.requestRectangleOnScreen(new android.graphics.Rect(0,-dp(24),box.getWidth(),box.getHeight()+dp(110)),false),theme.effects?260:0);};
        }
        void divider(LinearLayout c){View d=new View(context);d.setBackgroundColor(border);c.addView(d,new LinearLayout.LayoutParams(-1,dp(.5f)));}
        void row(LinearLayout c, String label, String value) {
            boolean stack=context.getResources().getConfiguration().fontScale>=1.3f||context.getResources().getConfiguration().screenWidthDp<350||value.length()>36;
            if(c.getChildCount()>0&&"row".equals(c.getChildAt(c.getChildCount()-1).getTag()))divider(c);
            LinearLayout r=new LinearLayout(context);r.setTag("row");r.setOrientation(stack?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER_VERTICAL);r.setMinimumHeight(dp(46));r.setPadding(0,dp(8),0,dp(8));
            TextView k=text(label,14,false),v=text(value,15,true);v.setTextDirection(android.view.View.TEXT_DIRECTION_FIRST_STRONG);v.setGravity(stack?Gravity.START:Gravity.END);
            int tone=theme.status(value);
            LinearLayout shown=new LinearLayout(context);shown.setGravity(Gravity.CENTER_VERTICAL|(stack?Gravity.START:Gravity.END));shown.setPadding(stack?0:dp(12),stack?dp(4):0,0,0);
            if(tone!=0){View dot=theme.dot(tone,7);((LinearLayout.LayoutParams)dot.getLayoutParams()).rightMargin=dp(7);shown.addView(dot);}
            shown.addView(v,new LinearLayout.LayoutParams(stack||tone!=0?-2:-1,-2));
            if(stack){r.addView(k);r.addView(shown);}else{r.addView(k,new LinearLayout.LayoutParams(0,-2,1));r.addView(shown,new LinearLayout.LayoutParams(0,-2,1.4f));}c.addView(r);
        }
        void amount(LinearLayout c,String label,String value){
            TextView k=text(label,12,false);k.setPadding(0,dp(8),0,dp(3));c.addView(k);
            TextView v=text(value,28,true);v.setTextColor(theme.ink);v.setPadding(0,0,0,dp(12));c.addView(v);
        }
        void fields(LinearLayout c, JSONObject p, String... pairs) { for (int i = 0; i + 1 < pairs.length; i += 2) row(c, pairs[i + 1], s(p, pairs[i])); }
        void paragraph(LinearLayout c, String value) { if (value.isEmpty() || value.equals(UNKNOWN)) return; TextView t = text(value, 14, false); t.setPadding(0, dp(4), 0, dp(4)); c.addView(t); }
        void message(String title, String value) { paragraph(card(title), value); }
        void sources(JSONObject p, String... pairs) { LinearLayout c = card("数据来源"); for (int i = 0; i + 1 < pairs.length; i += 2) row(c, pairs[i + 1], source(obj(p, pairs[i]))); }
        void freshness(JSONObject p, int maxSeconds) {
            String state = "更新时间未知"; Object generated = p.opt("generatedAt"); int tone = theme.faint;
            if (generated instanceof String) try {
                long age = java.time.Duration.between(Instant.parse((String) generated), Instant.now()).getSeconds();
                state = age < -5 ? "快照时间异常" : age > maxSeconds ? "快照已过期" : "快照时间 " + date(p, "generatedAt");
                tone = age < -5 || age > maxSeconds ? theme.warning : theme.success;
            } catch (RuntimeException ignored) { state = "更新时间异常"; tone = theme.warning; }
            if (Boolean.TRUE.equals(p.opt("stale"))) { state += " · 来源数据陈旧"; tone = theme.warning; }
            if (Boolean.TRUE.equals(p.opt("partial"))) { state += " · 部分数据"; tone = theme.warning; }
            LinearLayout pill = new LinearLayout(context); pill.setGravity(Gravity.CENTER_VERTICAL); pill.setPadding(dp(10), dp(6), dp(12), dp(6)); pill.setBackground(theme.glass(-1));
            pill.addView(theme.dot(tone, 7)); TextView label = text(state, 12, false); label.setPadding(dp(6), 0, 0, 0); pill.addView(label);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.bottomMargin = dp(12); lp.leftMargin = dp(2); root.addView(pill, lp);
        }
    }
}
