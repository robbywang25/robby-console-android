package wang.robby.console.android;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Explicit presentation allowlists. Never traverses or prints arbitrary JSON, credentials or clipboard content. */
public final class FeatureRenderer {
    private FeatureRenderer() {}
    private static final String UNKNOWN = "未知";
    private static final int MAX_ROWS = 100;

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
            case "wechat-publishing": return version(p, 1) && p.optJSONArray("articles") != null;
            case "miniprogram-catalog": return version(p, 1) && p.optJSONArray("tools") != null;
            case "wechat-login-audit": return version(p, 1) && p.optJSONObject("binding") != null && p.optJSONArray("events") != null;
            case "prompts": return version(p, 1) && p.opt("version") instanceof String && p.opt("model") instanceof String;
            case "image-workshop": return version(p, 1) && p.optJSONObject("inventory") != null;
            case "visit-insight-reports": return version(p, 1) && p.optJSONArray("reports") != null;
            default: return false;
        }
    }
    private static boolean api(JSONObject p) { return "2026-07-01".equals(p.opt("apiVersion")); }
    private static boolean version(JSONObject p, int n) {
        Object value = p.opt("schemaVersion"); return value instanceof Number && ((Number) value).doubleValue() == n;
    }
    private static void overview(UI u, JSONObject p) {
        JSONObject s = obj(p, "summary");
        boolean wide=u.context.getResources().getConfiguration().screenWidthDp>=360&&u.context.getResources().getConfiguration().fontScale<1.3f;
        String[][] metrics={{"在线服务",number(s,"servicesOnline"),"已知 "+number(s,"servicesKnown")+" / 总计 "+number(s,"servicesTotal"),"pulse"},{"云实例",number(s,"infrastructureInstances"),"基础设施","server"},{"域名资产",number(s,"domainAssets"),"已登记域名","globe"}};
        LinearLayout band=null;
        for(int i=0;i<metrics.length;i++){
            if(i==0||i==1||!wide){band=new LinearLayout(u.context);band.setOrientation(LinearLayout.HORIZONTAL);u.root.addView(band,new LinearLayout.LayoutParams(-1,-2));}
            LinearLayout tile=new LinearLayout(u.context);tile.setOrientation(LinearLayout.VERTICAL);tile.setPadding(u.dp(18),u.dp(16),u.dp(18),u.dp(16));tile.setBackground(u.theme.card());
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.bottomMargin=u.dp(12);if(i==1&&wide)lp.rightMargin=u.dp(10);band.addView(tile,lp);
            LinearLayout line=new LinearLayout(u.context);line.setGravity(Gravity.CENTER_VERTICAL);TextView label=u.text(metrics[i][0],14,false);line.addView(label,new LinearLayout.LayoutParams(0,-2,1));line.addView(u.theme.icon(metrics[i][3],u.theme.accent,20));tile.addView(line);
            TextView value=u.text(metrics[i][1],i==0?34:28,true);value.setPadding(0,u.dp(6),0,u.dp(2));tile.addView(value);tile.addView(u.text(metrics[i][2],12,false));
        }
        LinearLayout c;
        JSONArray notices = arr(p, "notices");
        if (notices.length() == 0) u.message("待办提醒", "暂无提醒");
        for (int i = 0; i < limit(u, notices); i++) { JSONObject n = notices.optJSONObject(i); if (n == null) continue;
            c = u.card(s(n, "title")); u.row(c, "级别", state(n, "severity")); u.paragraph(c, s(n, "detail"));
            if (n.has("expiresAt")) u.row(c, "到期", date(n, "expiresAt")); }
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
    private static void health(UI u, JSONObject p) {
        u.freshness(p, 600); LinearLayout c = u.card("健康概览");
        u.fields(c, obj(p, "summary"), "publicOnline", "公开站在线", "publicKnown", "公开站已知", "publicTotal", "公开站总数",
            "privateOnline", "私有站在线", "privateKnown", "私有站已知", "privateTotal", "私有站总数", "upstreamOperational", "正常上游", "upstreamTotal", "上游总数");
        for (JSONObject n : rows(p, "sites")) { c = u.card(s(n, "label")); u.paragraph(c, s(n, "hostname"));
            u.row(c, "服务", flag(n, "online", "在线", "离线")); u.row(c, "可达", flag(n, "reachable", "可达", "不可达"));
            u.row(c, "HTTP", number(n, "status")); u.row(c, "延迟", unit(n, "latencyMs", " ms"));
            u.row(c, "DNS", flag(obj(n, "dns"), "resolved", "已解析", "未解析")); }
        for (JSONObject n : rows(p, "upstreams")) { c = u.card(s(n, "name")); u.row(c, "状态", source(n));
            u.row(c, "运行", state(n, "state")); for (JSONObject part : rows(n, "components")) u.row(c, s(part, "name"), state(part, "status")); }
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
        LinearLayout card(String title) {LinearLayout c=theme.card(root);TextView heading=text(title,17,true);heading.setAccessibilityHeading(true);heading.setPadding(0,0,0,dp(10));c.addView(heading);return c;}
        void row(LinearLayout c, String label, String value) {
            boolean stack=context.getResources().getConfiguration().fontScale>=1.3f||context.getResources().getConfiguration().screenWidthDp<350||value.length()>36;
            LinearLayout r=new LinearLayout(context);r.setOrientation(stack?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER_VERTICAL);r.setMinimumHeight(dp(44));r.setPadding(0,dp(7),0,dp(7));
            TextView k=text(label,13,false),v=text(value,15,true);v.setTextDirection(android.view.View.TEXT_DIRECTION_FIRST_STRONG);v.setGravity(stack?Gravity.START:Gravity.END);v.setPadding(stack?0:dp(12),stack?dp(4):0,0,0);
            if(stack){r.addView(k);r.addView(v);}else{r.addView(k,new LinearLayout.LayoutParams(0,-2,1));r.addView(v,new LinearLayout.LayoutParams(0,-2,1.4f));}c.addView(r);
        }
        void amount(LinearLayout c,String label,String value){
            TextView k=text(label,12,false);k.setPadding(0,dp(8),0,dp(3));c.addView(k);
            TextView v=text(value,26,true);v.setTextColor(theme.accentStrong);v.setPadding(0,0,0,dp(12));c.addView(v);
        }
        void fields(LinearLayout c, JSONObject p, String... pairs) { for (int i = 0; i + 1 < pairs.length; i += 2) row(c, pairs[i + 1], s(p, pairs[i])); }
        void paragraph(LinearLayout c, String value) { if (value.isEmpty() || value.equals(UNKNOWN)) return; TextView t = text(value, 14, false); t.setPadding(0, dp(2), 0, dp(8)); c.addView(t); }
        void message(String title, String value) { paragraph(card(title), value); }
        void sources(JSONObject p, String... pairs) { LinearLayout c = card("数据来源"); for (int i = 0; i + 1 < pairs.length; i += 2) row(c, pairs[i + 1], source(obj(p, pairs[i]))); }
        void freshness(JSONObject p, int maxSeconds) {
            String state = "更新时间未知"; Object generated = p.opt("generatedAt");
            if (generated instanceof String) try {
                long age = java.time.Duration.between(Instant.parse((String) generated), Instant.now()).getSeconds();
                state = age < -5 ? "快照时间异常" : age > maxSeconds ? "快照已过期" : "快照时间 " + date(p, "generatedAt");
            } catch (RuntimeException ignored) { state = "更新时间异常"; }
            if (Boolean.TRUE.equals(p.opt("stale"))) state += " · 来源数据陈旧";
            if (Boolean.TRUE.equals(p.opt("partial"))) state += " · 部分数据";
            TextView label = text(state, 12, false); label.setPadding(dp(3), dp(3), dp(3), dp(14)); root.addView(label);
        }
    }
}
