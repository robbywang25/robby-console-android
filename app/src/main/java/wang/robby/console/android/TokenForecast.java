package wang.robby.console.android;

import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Quota burn-rate and depletion ETA model (`ios-tokens-insights.js` parity).
 * Consumes backend `insights` / convenience fields when available and computes
 * an ordinary least-squares trend fallback from `usageTrend.balancePoints`.
 */
final class TokenForecast {
    private TokenForecast() {}
    private static final DateTimeFormatter CLOCK_FMT = DateTimeFormatter.ofPattern("HH:mm", Locale.SIMPLIFIED_CHINESE);
    private static final DateTimeFormatter DAY_CLOCK_FMT = DateTimeFormatter.ofPattern("M/d HH:mm", Locale.SIMPLIFIED_CHINESE);

    static final class Result {
        final String source, unit, paceStatus, reason;
        final Double burnPerHour, paceDifference;
        final Long minutesLeft;
        final long etaAtMs, resetsAtMs;
        final Boolean beforeReset;

        Result(String source, Double burnPerHour, String unit, Long minutesLeft, long etaAtMs,
               Boolean beforeReset, long resetsAtMs, String paceStatus, Double paceDifference, String reason) {
            this.source = source;
            this.burnPerHour = burnPerHour;
            this.unit = unit;
            this.minutesLeft = minutesLeft;
            this.etaAtMs = etaAtMs;
            this.beforeReset = beforeReset;
            this.resetsAtMs = resetsAtMs;
            this.paceStatus = paceStatus;
            this.paceDifference = paceDifference;
            this.reason = reason;
        }
    }

    static Result analyze(JSONObject account, long nowMs) {
        if (account == null) return new Result("none", null, "percentage-points", null, 0L, null, 0L, null, null, null);
        JSONObject insights = account.optJSONObject("insights");
        String paceStatus = null;
        Double paceDiff = null;
        if (insights != null) {
            JSONObject pace = insights.optJSONObject("cyclePace");
            if (pace != null) {
                String st = pace.optString("status", "");
                if ("ahead".equals(st) || "behind".equals(st) || "on_pace".equals(st)) paceStatus = st;
                if (pace.opt("difference") instanceof Number n) paceDiff = n.doubleValue();
            }
        }

        Double burn = optDouble(account, "burnRatePerHour");
        if (burn == null && insights != null) burn = optDouble(insights, "burnPerHour");
        String unit = insights != null && !insights.optString("unit", "").isEmpty()
            ? insights.optString("unit")
            : ("points".equals(optTrendMetric(account)) ? "points" : "percentage-points");
        Long mins = optLong(account, "etaMinutesLeft");
        if (mins == null && insights != null) mins = optLong(insights, "minutesLeft");
        long etaAt = optInstant(account.opt("etaAt"));
        if (etaAt <= 0 && insights != null) etaAt = optInstant(insights.opt("runsOutAt"));
        Boolean beforeReset = optBool(account, "etaBeforeReset");
        if (beforeReset == null && insights != null) beforeReset = optBool(insights, "beforeReset");
        long resetsAt = insights != null ? optInstant(insights.opt("resetsAt")) : 0L;
        if (resetsAt <= 0) resetsAt = mainResetAt(account, nowMs);
        String reason = insights == null ? null : nonEmpty(insights.optString("reason", ""));

        if (etaAt > 0 || burn != null || paceStatus != null) {
            if (mins == null && etaAt > nowMs) mins = Math.max(0L, (etaAt - nowMs) / 60_000L);
            if (beforeReset == null && etaAt > 0 && resetsAt > nowMs) beforeReset = etaAt < resetsAt;
            return new Result("server", burn, unit, mins, etaAt, beforeReset, resetsAt, paceStatus, paceDiff, reason);
        }

        Result linear = fallbackLinear(account, nowMs, resetsAt);
        if (linear != null) return linear;
        return new Result("none", null, unit, null, 0L, null, resetsAt, null, null, reason);
    }

    /** Compute a linear OLS ETA fallback from `usageTrend.balancePoints` when backend insights are absent. */
    static Result fallbackLinear(JSONObject account, long nowMs, long resetsAt) {
        JSONObject trend = account.optJSONObject("usageTrend");
        if (trend == null) return null;
        JSONArray arr = trend.optJSONArray("balancePoints");
        if (arr == null || arr.length() < 2) return null;

        List<double[]> pts = new ArrayList<>();
        long t0 = 0;
        long lastAt = 0;
        double lastRem = Double.NaN;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject pt = arr.optJSONObject(i);
            if (pt == null || !(pt.opt("remaining") instanceof Number)) continue;
            long at = optInstant(pt.opt("at"));
            if (at <= 0) continue;
            if (t0 == 0) t0 = at;
            double rem = ((Number) pt.opt("remaining")).doubleValue();
            pts.add(new double[]{(at - t0) / 60_000.0, rem});
            lastAt = at;
            lastRem = rem;
        }
        if (pts.size() < 2 || Double.isNaN(lastRem)) return null;
        double spanMinutes = pts.get(pts.size() - 1)[0] - pts.get(0)[0];
        if (spanMinutes < 5.0) return null;

        double sumX = 0, sumY = 0;
        for (double[] p : pts) {
            sumX += p[0];
            sumY += p[1];
        }
        double meanX = sumX / pts.size();
        double meanY = sumY / pts.size();
        double num = 0, den = 0;
        for (double[] p : pts) {
            double dx = p[0] - meanX;
            num += dx * (p[1] - meanY);
            den += dx * dx;
        }
        if (den <= 0) return null;
        double slopePerMin = num / den;
        double burnPerHour = -slopePerMin * 60.0;
        String unit = "points".equals(trend.optString("metric")) ? "points" : "percentage-points";
        if (burnPerHour <= 0.05 || lastRem <= 0) return null;

        long minsLeft = Math.max(0L, Math.round(lastRem / (burnPerHour / 60.0)));
        long etaMs = lastAt + minsLeft * 60_000L;
        if (etaMs < nowMs) minsLeft = 0L;
        Boolean before = resetsAt > nowMs ? etaMs < resetsAt : null;
        return new Result("fallback", burnPerHour, unit, minsLeft, etaMs, before, resetsAt, null, null, null);
    }

    /** Human-readable prediction/pace line for account tiles; null when stale or unavailable. */
    static String summaryLine(JSONObject account, ZoneId zone, long nowMs) {
        if (account == null || !"connected".equals(TokensModel.effectiveStatus(account, nowMs))) return null;
        Result r = analyze(account, nowMs);
        if (r.etaAtMs > 0 && r.minutesLeft != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("预计 ").append(duration(r.minutesLeft)).append("耗尽（").append(formatEta(r.etaAtMs, zone, nowMs)).append("）");
            if (Boolean.TRUE.equals(r.beforeReset)) sb.append(" · 早于重置");
            else if (Boolean.FALSE.equals(r.beforeReset)) sb.append(" · 可撑到重置");
            if (r.burnPerHour != null && r.burnPerHour >= 0.1) {
                sb.append(" · ").append(new DecimalFormat("0.#").format(r.burnPerHour))
                    .append("points".equals(r.unit) ? " 点/h" : "%/h");
            }
            return sb.toString();
        }
        if ("ahead".equals(r.paceStatus)) return "消耗偏快 · 高于当前周期时间进度";
        if ("behind".equals(r.paceStatus)) return "消耗从容 · 低于当前周期时间进度";
        if ("on_pace".equals(r.paceStatus)) return "消耗节奏与周期基本一致";
        return null;
    }

    static String duration(long minutes) {
        if (minutes <= 1) return "即将";
        if (minutes < 60) return minutes + " 分钟后";
        long hours = minutes / 60;
        long rem = minutes % 60;
        if (hours < 24) return hours + " 小时" + (rem == 0 ? "" : " " + rem + " 分") + "后";
        long days = hours / 24;
        long remH = hours % 24;
        return days + " 天" + (remH == 0 ? "" : " " + remH + " 小时") + "后";
    }

    private static String formatEta(long etaMs, ZoneId zone, long nowMs) {
        var z = zone == null ? ZoneId.systemDefault() : zone;
        var etaDate = Instant.ofEpochMilli(etaMs).atZone(z).toLocalDate();
        var today = Instant.ofEpochMilli(nowMs).atZone(z).toLocalDate();
        return etaDate.equals(today) ? CLOCK_FMT.format(Instant.ofEpochMilli(etaMs).atZone(z))
            : DAY_CLOCK_FMT.format(Instant.ofEpochMilli(etaMs).atZone(z));
    }

    private static String optTrendMetric(JSONObject account) {
        JSONObject trend = account.optJSONObject("usageTrend");
        return trend == null ? "" : trend.optString("metric", "");
    }

    private static long mainResetAt(JSONObject account, long nowMs) {
        JSONArray windows = account.optJSONArray("windows");
        if (windows == null) return 0L;
        long best = 0L;
        for (int i = 0; i < windows.length(); i++) {
            JSONObject w = windows.optJSONObject(i);
            if (w == null || "reserve".equals(w.opt("quotaGroup"))) continue;
            long at = optInstant(w.opt("resetsAt"));
            if (at > nowMs && (best == 0L || at < best)) best = at;
        }
        return best;
    }

    private static Double optDouble(JSONObject o, String key) {
        Object v = o == null ? null : o.opt(key);
        return v instanceof Number n && Double.isFinite(n.doubleValue()) ? n.doubleValue() : null;
    }

    private static Long optLong(JSONObject o, String key) {
        Object v = o == null ? null : o.opt(key);
        return v instanceof Number n && Double.isFinite(n.doubleValue()) ? Math.max(0L, n.longValue()) : null;
    }

    private static Boolean optBool(JSONObject o, String key) {
        Object v = o == null ? null : o.opt(key);
        return v instanceof Boolean b ? b : null;
    }

    private static String nonEmpty(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static long optInstant(Object value) {
        if (!(value instanceof String)) return 0L;
        try {
            return Instant.parse((String) value).toEpochMilli();
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
