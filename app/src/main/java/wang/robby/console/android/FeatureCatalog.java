package wang.robby.console.android;

import java.util.Arrays;
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
        f("wechat-login-audit", "微信登录审计", "绑定状态与脱敏登录事件", "设置", "wechat/audit", "god.mobile.wechat.read"),
        f("secrets", "凭据配置", "服务配置状态", "设置", "secrets/status", "god.mobile.read")
    ));
    public static List<Feature> all() { return FEATURES; }
    public static Feature find(String id) {
        for (Feature feature : FEATURES) if (feature.id.equals(id)) return feature;
        return null;
    }
}
