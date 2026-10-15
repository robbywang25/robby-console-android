package wang.robby.console.android;

/**
 * Wording and severity for the 运行状态 page. It mirrors status.robby.wang so the phone and the web page name the
 * same probe result the same way, and it is pure Java so the decisions are covered by JVM tests.
 */
final class StatusPage {
    private StatusPage() {}
    static final int NONE = 0, OK = 1, WARN = 2, BAD = 3;

    /** A null {@code online} means this round has no probe result for the site (site feed unavailable). */
    static String site(Boolean online, Boolean reachable, String expectation, Boolean dnsResolved) {
        if (online == null) return "未检测";
        if (online) return "auth_gateway".equals(expectation) ? "认证门可达" : "service_reachable".equals(expectation) ? "服务可达" : "在线";
        if (Boolean.TRUE.equals(reachable) && "auth_gateway".equals(expectation)) return "认证边界异常";
        return Boolean.FALSE.equals(dnsResolved) ? "DNS 未解析" : "暂不可达";
    }
    static int siteTone(Boolean online) { return online == null ? NONE : online ? OK : BAD; }
    static String expectation(String e) {
        switch (e == null ? "" : e) {
            case "public_reachable": return "公开访问";
            case "auth_gateway": return "认证门";
            case "service_reachable": return "服务可达";
            default: return "未知";
        }
    }
    static String upstream(boolean available, boolean stale, String state) {
        if (stale) return "上次状态 · 已过期";
        if (!available) return "暂不可读";
        switch (state == null ? "" : state) {
            case "operational": return "全部正常";
            case "degraded": return "部分影响";
            case "maintenance": return "计划维护";
            case "outage": return "服务异常";
            default: return "暂不可读";
        }
    }
    static int upstreamTone(boolean available, boolean stale, String state) {
        if (stale) return WARN;
        if (!available) return NONE;
        switch (state == null ? "" : state) {
            case "operational": return OK;
            case "degraded": case "maintenance": return WARN;
            case "outage": return BAD;
            default: return NONE;
        }
    }
    static String component(String status) {
        switch (status == null ? "" : status) {
            case "operational": return "正常";
            case "degraded_performance": return "性能下降";
            case "partial_outage": return "部分中断";
            case "major_outage": return "重大中断";
            case "under_maintenance": return "维护中";
            default: return "未知";
        }
    }
    static int componentTone(String status) {
        switch (status == null ? "" : status) {
            case "operational": return OK;
            case "degraded_performance": case "partial_outage": case "under_maintenance": return WARN;
            case "major_outage": return BAD;
            default: return NONE;
        }
    }
    /** Answer-first headline: problems outrank unchecked items, and only a fully checked, clean round is "符合预期". */
    static String verdict(int problems, int unchecked) {
        if (problems > 0) return problems + " 项需要关注";
        if (unchecked > 0) return unchecked + " 项尚未检测";
        return "全部符合预期";
    }
    static int verdictTone(int problems, int bad, int unchecked) { return bad > 0 ? BAD : problems > 0 ? WARN : unchecked > 0 ? NONE : OK; }
    /** Caption under one summary figure, e.g. "1 项异常" or "全部符合预期". Negative or inconsistent counts read as unknown. */
    static String figureCaption(int online, int known, int total) {
        if (online < 0 || known < online || total < known) return "计数未知";
        if (known < total) return (total - known) + " 项未检测";
        return online < known ? (known - online) + " 项异常" : "全部符合预期";
    }
    static int figureTone(int online, int known, int total) {
        if (online < 0 || known < online || total < known) return NONE;
        if (online < known) return BAD;
        return known < total ? NONE : OK;
    }
}
