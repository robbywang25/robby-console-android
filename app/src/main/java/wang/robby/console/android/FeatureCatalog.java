package wang.robby.console.android;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Map;
import java.util.Collections;
import java.util.List;

/** Native surfaces for the released God mobile read API. Paths are relative to /api/mobile/v1. */
public final class FeatureCatalog {
    private FeatureCatalog() {}
    public static final class Feature {
        public final String id, title, subtitle, group, path, webPath, scope;
        public Feature(String id, String title, String subtitle, String group, String path, String webPath, String scope) {
            this.id = id; this.title = title; this.subtitle = subtitle; this.group = group;
            this.path = path; this.webPath = webPath; this.scope = scope;
        }
        public Feature(String id, String title, String subtitle, String group, String path, String scope) {
            this(id, title, subtitle, group, path, "", scope);
        }
        /** Works on the phone alone: no God read, no path and no scope. */
        public boolean local() { return path.isEmpty(); }
    }
    private static Feature f(String id, String title, String subtitle, String group, String path, String scope) {
        return new Feature(id, title, subtitle, group, path, scope);
    }
    private static final List<Feature> FEATURES = Collections.unmodifiableList(Arrays.asList(
        f("overview", "工作台", "服务、资产与待办提醒", "工作台", "overview", "god.mobile.read"),
        f("observer", "Visit Observer", "访问聚合与站点健康", "洞察", "analytics/overview", "god.mobile.analytics.read"),
        f("umami", "访问统计", "Umami 站点、路径与来源", "洞察", "umami/overview", "god.mobile.read"),
        f("posthog", "产品分析", "事件、漏斗与 Web Vitals", "洞察", "posthog/overview", "god.mobile.analytics.read"),
        f("visit-insight-reports", "访问洞察报告", "既有报告归档", "洞察", "visit-insight-reports", "god.mobile.analytics.read"),
        f("market", "市场看板", "关注行情、板块与市场基准", "洞察", "market", "god.mobile.read"),
        f("okx", "OKX 账户", "资产、持仓与风险摘要", "洞察", "okx/overview", "god.mobile.okx.read"),
        f("openai", "OpenAI 用量", "Token、请求与成本", "洞察", "openai/usage", "god.mobile.read"),
        f("tokens", "Token 额度", "多账号剩余额度与重置时间", "洞察", "tokens/quotas", "god.mobile.tokens.read"),
        f("quota-calendar", "重置日历", "所有账号的额度重置日期", "洞察", "tokens/quotas", "god.mobile.tokens.read"),
        f("tibo", "28 天挑战", "Tibo 的重置预告、每日进展与提醒", "洞察", "tokens/tibo", "god.mobile.tokens.read"),
        f("wechat-messages", "微信消息", "汇总、日报与看板", "洞察", "wechat/digest", "god.mobile.wechat.read"),
        f("infrastructure", "基础设施", "实例、资源指标与月价", "资产", "overview", "god.mobile.read"),
        f("domains", "域名资产", "注册、到期、解析与备案", "资产", "domains/overview", "god.mobile.domains.read"),
        f("status", "运行状态", "站点健康与上游服务", "资产", "overview", "god.mobile.read"),
        f("site-maintenance", "站点维护状态", "站点状态与标记一致性", "资产", "site-maintenance/overview", "god.mobile.sites.health.read"),
        f("engineering", "源码与工程", "仓库索引与迭代日志", "资产", "engineering", "god.mobile.read"),
        f("clipboard", "云剪贴板", "可见性与到期时间", "工具", "clipboard", "god.mobile.clipboard.read"),
        f("travel-ledger", "旅行记账", "多账本、支出与同行人", "工具", "travel-ledger/current/projects", "god.mobile.travel.current.read"),
        f("image-workshop", "图片工坊", "公开派生图片库存", "工具", "image-workshop/overview", "god.mobile.read"),
        f("wechat-cards", "微信卡片", "卡片标题与定制状态", "工具", "wechat/cards", "god.mobile.wechat.read"),
        f("wechat-publishing", "公众号发布", "文章、草稿历史与发布状态", "工具", "wechat/blog-drafts/overview", "god.mobile.wechat.read"),
        f("miniprogram-catalog", "小程序目录", "目录内容与同步状态", "工具", "miniprogram/catalog-sync/status", "god.mobile.wechat.read"),
        f("prompts", "Prompt 配置", "模型、来源与版本", "工具", "prompts/asset-daily-briefing", "god.mobile.prompts.read"),
        f("deepseek", "DeepSeek 模型", "官方模型列表、上下文、价格与能力", "工具", "deepseek/models", "god.mobile.read"),
        f("mindball", "心力球", "全天录音，DeepSeek 整理关键事项与待办", "工具", "", ""),
        f("uu-remote", "UU 远程", "绑定电脑，一键远控 Windows 与 Mac", "工具", "", ""),
        f("wechat-login-audit", "微信登录审计", "绑定状态与脱敏登录事件", "设置", "wechat/audit", "god.mobile.wechat.read"),
        f("secrets", "凭据配置", "服务配置状态", "设置", "secrets/status", "god.mobile.read")
    ));
    public static List<Feature> all() { return FEATURES; }

    /**
     * Features that ship hidden because they do not read yet, with the reason 已隐藏的功能 shows. From the signed
     * device read check of 2026-10-19: umami/overview answers 503, and every PostHog source reports notConfigured.
     * The person can show them again; an entry leaves this list once its source works.
     */
    /** Features that never call God; each is listed here so a missing path cannot slip in unnoticed. */
    public static final java.util.Set<String> LOCAL = java.util.Set.of("mindball", "uu-remote");
    public static final Map<String, String> DEFAULT_HIDDEN_REASONS = Map.of(
        "umami", "暂未接通 · 接口暂不可用",
        "posthog", "暂未接通 · 数据源未配置");
    public static final java.util.Set<String> DEFAULT_HIDDEN = DEFAULT_HIDDEN_REASONS.keySet();

    /** The sections of the 功能 tab, in order; 设置 entries stay in 我 but are still found by search. */
    public static final List<String> SECTIONS = List.of("洞察", "资产", "工具");
    // Only static feature metadata is searched, as on iOS: never account data, credentials or clipboard contents.
    private static final Map<String, String> ALIASES = Map.ofEntries(
        Map.entry("tokens", "额度 配额 quota balance Codex Claude Cursor Grok TodayAI 重置"),
        Map.entry("quota-calendar", "日历 重置 额度 calendar reset"),
        Map.entry("tibo", "Tibo Codex 重置 预告 挑战 提醒 闹钟 reset challenge alarm"),
        Map.entry("wechat-messages", "微信 聊天 消息 日报 看板 wechat"),
        Map.entry("infrastructure", "服务器 云主机 server cloud"),
        Map.entry("domains", "域名 domain DNS 备案"),
        Map.entry("status", "服务 健康 health uptime"),
        Map.entry("site-maintenance", "站点 网站 维护 health"),
        Map.entry("engineering", "代码 仓库 GitHub repository timeline"),
        Map.entry("market", "股票 行情 stock market"),
        Map.entry("okx", "欧易 持仓 加密货币 crypto"),
        Map.entry("openai", "API 费用 usage cost"),
        Map.entry("observer", "访问 流量 visit analytics"),
        Map.entry("umami", "访问 流量 统计 analytics"),
        Map.entry("posthog", "埋点 事件 漏斗 analytics"),
        Map.entry("visit-insight-reports", "报告 访问 report"),
        Map.entry("clipboard", "剪贴板 复制 粘贴 clipboard"),
        Map.entry("travel-ledger", "账本 记账 旅行 ledger expense"),
        Map.entry("image-workshop", "图片 压缩 转换 图床 image WebP"),
        Map.entry("wechat-cards", "微信 卡片 wechat"),
        Map.entry("wechat-publishing", "公众号 文章 草稿 publish"),
        Map.entry("miniprogram-catalog", "小程序 mini program"),
        Map.entry("prompts", "提示词 prompt"),
        Map.entry("deepseek", "深度求索 模型 价格 上下文 定价 API model pricing context deepseek.robby.wang"),
        Map.entry("mindball", "录音 记录 转写 待办 时间线 备忘 会议 纪要 OPPO 心力球 recorder memo todo timeline"),
        Map.entry("uu-remote", "远程 远控 远程桌面 电脑 Windows Mac 网易 UU远程 画中画 小窗 remote desktop control"),
        Map.entry("wechat-login-audit", "微信 登录 审计 wechat login"),
        Map.entry("secrets", "密钥 凭证 credential"));

    /**
     * Features whose title, description, id, section or aliases contain every term of the query, in catalog order.
     * Case, width and accents are ignored; a blank query returns everything except the internal 工作台 entry.
     */
    public static List<Feature> search(String query) {
        String[] terms = normalize(query == null ? "" : query).trim().split("\\s+");
        List<Feature> out = new ArrayList<>();
        for (Feature f : FEATURES) {
            if (f.id.equals("overview")) continue;
            String text = normalize(String.join(" ", f.id, f.title, f.subtitle, f.group, ALIASES.getOrDefault(f.id, "")));
            boolean all = true; for (String term : terms) if (!term.isEmpty() && !text.contains(term)) { all = false; break; }
            if (all) out.add(f);
        }
        return out;
    }
    static String normalize(String value) {
        String wide = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        return wide.toLowerCase(java.util.Locale.ROOT);
    }
    public static Feature find(String id) {
        for (Feature feature : FEATURES) if (feature.id.equals(id)) return feature;
        return null;
    }
}
