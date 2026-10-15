package wang.robby.console.android;

/**
 * Wording and judgements for the Token 额度 page (tokens.robby.wang via the mobile API). Mirrors the web dashboard
 * and the iOS app so the same quota reads the same everywhere; pure Java for JVM tests.
 */
final class TokensModel {
    private TokensModel() {}
    static String status(String s) {
        switch (s == null ? "" : s) {
            case "connected": return "已更新";
            case "stale": return "待更新";
            case "needs_auth": return "需登录";
            case "unavailable": return "暂不可用";
            case "not_configured": return "未接入";
            default: return "状态未知";
        }
    }
    static String reason(String r) {
        switch (r == null ? "" : r) {
            case "needs_auth": return "请在该账号原有应用中重新登录。";
            case "expired": return "登录已过期，请重新授权。";
            case "reset_passed": return "已到重置时间，等待平台新读数。";
            case "rate_limited": return "平台暂时限流，等待采集重试。";
            case "unsupported": return "此入口尚无独立额度来源。";
            case "not_configured": return "尚未接入此账号的额度来源。";
            case "duplicate": return "此来源与其他账号重复，未重复统计。";
            case "stale": return "保留上次成功读数，等待采集更新。";
            default: return "数据暂时不可确认，不代表余额为零。";
        }
    }
    static String card(String s) {
        switch (s == null ? "" : s) { case "available": return "可用"; case "redeemed": return "已使用"; case "expired": return "已过期"; case "redeeming": return "使用中"; default: return "状态未知"; }
    }
    /** Severity of a remaining share: 1 healthy, 2 low, 3 nearly exhausted; 0 when unknown. */
    static int level(Double remainingPercent) {
        if (remainingPercent == null || remainingPercent.isNaN()) return 0;
        return remainingPercent >= 50 ? 1 : remainingPercent >= 20 ? 2 : 3;
    }
    /**
     * "即将重置": a fresh reading, the window resets within 24 h, at least 20% is left, and more is left than the
     * remaining share of the period, so quota is about to go unused. Stale readings never raise it.
     */
    static boolean resettingSoon(boolean fresh, Double remainingPercent, long startsAtMs, long resetsAtMs, long nowMs) {
        if (!fresh || remainingPercent == null || resetsAtMs <= nowMs || resetsAtMs - nowMs > 24L * 3600_000L || remainingPercent < 20) return false;
        if (startsAtMs <= 0 || startsAtMs >= resetsAtMs) return true;
        double periodLeft = Math.max(0, Math.min(1, (resetsAtMs - nowMs) / (double) (resetsAtMs - startsAtMs)));
        return remainingPercent / 100.0 > periodLeft;
    }
    /** "3 小时 20 分后重置" style countdown; null when past or unknown. */
    static String countdown(long resetsAtMs, long nowMs) {
        if (resetsAtMs <= 0 || resetsAtMs <= nowMs) return null; long minutes = (resetsAtMs - nowMs) / 60_000L;
        if (minutes < 60) return Math.max(1, minutes) + " 分钟后重置";
        long hours = minutes / 60; if (hours < 48) return hours + " 小时" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + " 分") + "后重置";
        return (hours / 24) + " 天后重置";
    }
}
