package wang.robby.console.android;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Pure Java parser + native renderer for `GET /api/mobile/v1/infra/sentinel`
 * (alerts, compute servers & expiry, storage probes, domain/HTTPS certs, model API balances,
 * release evidence, and WeChat digest freshness).
 */
final class OpsReadPages {
    private OpsReadPages() {}

    static final class Alert {
        final String severity, area, id, title, detail;
        Alert(String severity, String area, String id, String title, String detail) {
            this.severity = severity;
            this.area = area;
            this.id = id;
            this.title = title;
            this.detail = detail;
        }
        int rank() {
            return "critical".equals(severity) ? 0 : "warning".equals(severity) ? 1 : 2;
        }
    }

    static final class SentinelSummary {
        final String generatedAt, serversStatus, storageStatus, domainsStatus, modelApisStatus, releaseStatus, wechatStatus;
        final boolean partial, storageStale, wechatStale;
        final int criticalCount, warningCount, infoCount, nearestExpiryDays, totalStores, upStores, downStores, degradedStores, unverifiedBackups, wechatAgeMinutes;
        final List<Alert> alerts;
        SentinelSummary(String generatedAt, boolean partial, List<Alert> alerts, String serversStatus, int nearestExpiryDays,
                        String storageStatus, boolean storageStale, int totalStores, int upStores, int degradedStores, int downStores, int unverifiedBackups,
                        String domainsStatus, String modelApisStatus, String releaseStatus, String wechatStatus, int wechatAgeMinutes, boolean wechatStale) {
            this.generatedAt = generatedAt;
            this.partial = partial;
            this.alerts = alerts;
            int crit = 0, warn = 0, inf = 0;
            for (Alert a : alerts) {
                if ("critical".equals(a.severity)) crit++;
                else if ("warning".equals(a.severity)) warn++;
                else inf++;
            }
            this.criticalCount = crit;
            this.warningCount = warn;
            this.infoCount = inf;
            this.serversStatus = serversStatus;
            this.nearestExpiryDays = nearestExpiryDays;
            this.storageStatus = storageStatus;
            this.storageStale = storageStale;
            this.totalStores = totalStores;
            this.upStores = upStores;
            this.degradedStores = degradedStores;
            this.downStores = downStores;
            this.unverifiedBackups = unverifiedBackups;
            this.domainsStatus = domainsStatus;
            this.modelApisStatus = modelApisStatus;
            this.releaseStatus = releaseStatus;
            this.wechatStatus = wechatStatus;
            this.wechatAgeMinutes = wechatAgeMinutes;
            this.wechatStale = wechatStale;
        }
    }

    static SentinelSummary parseSentinel(JSONObject p) {
        if (p == null || p.optInt("schemaVersion", -1) != 1) return null;
        List<Alert> alerts = new ArrayList<>();
        JSONArray aArr = p.optJSONArray("alerts");
        if (aArr != null) {
            for (int i = 0; i < aArr.length() && i < 100; i++) {
                JSONObject a = aArr.optJSONObject(i);
                if (a == null) continue;
                alerts.add(new Alert(
                    clean(a.optString("severity", "info")),
                    clean(a.optString("area", "")),
                    clean(a.optString("id", "")),
                    clean(a.optString("title", "未命名告警")),
                    clean(a.optString("detail", ""))
                ));
            }
        }
        alerts.sort(Comparator.comparingInt(Alert::rank).thenComparing(a -> a.area));

        JSONObject servers = p.optJSONObject("servers");
        JSONObject storage = p.optJSONObject("storage");
        JSONObject sSum = storage == null ? null : storage.optJSONObject("summary");
        JSONObject domains = p.optJSONObject("domainsAndCerts");
        JSONObject models = p.optJSONObject("modelApis");
        JSONObject release = p.optJSONObject("releaseEvidence");
        JSONObject wechat = p.optJSONObject("wechatDigest");

        return new SentinelSummary(
            clean(p.optString("generatedAt", "")),
            Boolean.TRUE.equals(p.opt("partial")),
            alerts,
            clean(servers == null ? "" : servers.optString("status", "")),
            servers == null ? -1 : servers.optInt("nearestExpiryDays", -1),
            clean(storage == null ? "" : storage.optString("status", "")),
            storage != null && Boolean.TRUE.equals(storage.opt("collectorStale")),
            sSum == null ? 0 : sSum.optInt("totalStores", 0),
            sSum == null ? 0 : sSum.optInt("up", 0),
            sSum == null ? 0 : sSum.optInt("degraded", 0),
            sSum == null ? 0 : sSum.optInt("down", 0),
            sSum == null ? 0 : sSum.optInt("backupUnverified", 0),
            clean(domains == null ? "" : domains.optString("status", "")),
            clean(models == null ? "" : models.optString("status", "")),
            clean(release == null ? "" : release.optString("status", "")),
            clean(wechat == null ? "" : wechat.optString("status", "")),
            wechat == null ? -1 : wechat.optInt("ageMinutes", -1),
            wechat != null && Boolean.TRUE.equals(wechat.opt("stale"))
        );
    }

    static boolean isSentinelCompatible(JSONObject p) {
        return p != null && p.optInt("schemaVersion", -1) == 1 && (p.has("alerts") || p.has("servers") || p.has("storage"));
    }

    static void renderSentinelCard(Context ctx, LinearLayout shell, JSONObject p) {
        ConsoleTheme theme = new ConsoleTheme(ctx);
        SentinelSummary m = parseSentinel(p);
        if (m == null) {
            FeatureRenderer.widgetMessage(ctx, shell, "哨兵状态暂不可读");
            return;
        }
        int urgent = m.criticalCount + m.warningCount;
        TextView big = theme.text(urgent > 0 ? urgent + " 项关注" : "全部正常", 22, true);
        big.setTextColor(m.criticalCount > 0 ? theme.danger : m.warningCount > 0 ? theme.warning : theme.success);
        big.setPadding(0, theme.dp(8), 0, 0);
        shell.addView(big);

        String capText = "存储 " + m.upStores + "/" + m.totalStores
            + (m.nearestExpiryDays >= 0 ? " · 最近到期 " + m.nearestExpiryDays + "天" : "")
            + (m.wechatStale ? " · 微信超过2h未同步" : "");
        TextView cap = theme.text(capText, 12, false);
        cap.setTextColor(theme.muted);
        cap.setPadding(0, theme.dp(2), 0, 0);
        shell.addView(cap);
    }

    static void renderSentinel(Context ctx, LinearLayout root, JSONObject p) {
        ConsoleTheme theme = new ConsoleTheme(ctx);
        SentinelSummary m = parseSentinel(p);
        if (m == null) {
            LinearLayout card = theme.card(root);
            card.addView(theme.text("基础设施哨兵数据格式不兼容", 14, false));
            return;
        }

        // 1. Alerts banner card
        LinearLayout alertCard = theme.card(root);
        LinearLayout ah = new LinearLayout(ctx);
        ah.setGravity(Gravity.CENTER_VERTICAL);
        int topTone = m.criticalCount > 0 ? theme.danger : m.warningCount > 0 ? theme.warning : theme.success;
        ah.addView(theme.dot(topTone, 9));
        TextView aTitle = theme.text(m.alerts.isEmpty() ? "所有核心链路正常" : "活跃告警 (" + m.alerts.size() + ")", 17, true);
        aTitle.setAccessibilityHeading(true);
        aTitle.setPadding(theme.dp(8), 0, 0, 0);
        ah.addView(aTitle, new LinearLayout.LayoutParams(0, -2, 1));
        if (m.partial) {
            TextView pt = theme.text("部分探测缺失", 12, false);
            pt.setTextColor(theme.warning);
            ah.addView(pt);
        }
        alertCard.addView(ah);
        if (m.alerts.isEmpty()) {
            TextView ok = theme.text("当前未检测到严重异常、存储故障或证书临界告警。", 13, false);
            ok.setTextColor(theme.muted);
            ok.setPadding(0, theme.dp(6), 0, 0);
            alertCard.addView(ok);
        } else {
            for (Alert a : m.alerts) {
                divider(ctx, theme, alertCard, 8);
                LinearLayout row = new LinearLayout(ctx);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.addView(theme.dot("critical".equals(a.severity) ? theme.danger : "warning".equals(a.severity) ? theme.warning : theme.accent, 7));
                TextView t = theme.text((a.area.isEmpty() ? "" : "[" + a.area + "] ") + a.title, 14, true);
                t.setPadding(theme.dp(8), 0, 0, 0);
                row.addView(t);
                alertCard.addView(row);
                if (!a.detail.isEmpty()) {
                    TextView d = theme.text(a.detail, 12, false);
                    d.setTextColor(theme.muted);
                    d.setPadding(theme.dp(15), theme.dp(2), 0, 0);
                    alertCard.addView(d);
                }
            }
        }

        // 2. Servers
        JSONObject servers = p.optJSONObject("servers");
        if (servers != null) {
            section(theme, root, "云主机与续费");
            LinearLayout card = theme.card(root);
            LinearLayout top = new LinearLayout(ctx);
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView st = theme.text("状态：" + statusText(m.serversStatus), 15, true);
            top.addView(st, new LinearLayout.LayoutParams(0, -2, 1));
            if (m.nearestExpiryDays >= 0) {
                TextView ex = theme.text("最近到期 " + m.nearestExpiryDays + " 天", 13, true);
                ex.setTextColor(m.nearestExpiryDays <= 14 ? theme.warning : theme.muted);
                top.addView(ex);
            }
            card.addView(top);
            JSONArray quotes = servers.optJSONArray("monthlyQuote");
            if (quotes != null && quotes.length() > 0) {
                List<String> qList = new ArrayList<>();
                for (int i = 0; i < quotes.length(); i++) {
                    JSONObject q = quotes.optJSONObject(i);
                    if (q != null && q.opt("amount") instanceof Number n) {
                        qList.add(q.optString("currency", "CNY") + " " + new DecimalFormat("#,##0.##").format(n.doubleValue()));
                    }
                }
                if (!qList.isEmpty()) {
                    TextView qv = theme.text("月度费用估算：" + String.join(" + ", qList), 12, false);
                    qv.setTextColor(theme.muted);
                    qv.setPadding(0, theme.dp(4), 0, 0);
                    card.addView(qv);
                }
            }
            JSONArray instances = servers.optJSONArray("instances");
            if (instances != null) {
                for (int i = 0; i < instances.length() && i < 30; i++) {
                    JSONObject ins = instances.optJSONObject(i);
                    if (ins == null) continue;
                    divider(ctx, theme, card, 8);
                    LinearLayout row = new LinearLayout(ctx);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    TextView name = theme.text(clean(ins.optString("name", clean(ins.optString("id", "主机")))), 14, true);
                    row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                    String stLabel = statusText(clean(ins.optString("state", "")));
                    TextView sv = theme.text(stLabel, 12, true);
                    sv.setTextColor("异常".equals(stLabel) ? theme.danger : theme.success);
                    row.addView(sv);
                    card.addView(row);
                    int days = ins.optInt("daysRemaining", -1);
                    String line = joinNonEmpty(" · ",
                        clean(ins.optString("regionLabel", "")),
                        clean(ins.optString("purpose", "")),
                        days >= 0 ? "剩余 " + days + " 天" : "");
                    if (!line.isEmpty()) {
                        TextView lv = theme.text(line, 12, false);
                        lv.setTextColor(theme.muted);
                        lv.setPadding(0, theme.dp(2), 0, 0);
                        card.addView(lv);
                    }
                }
            }
        }

        // 3. Storage
        JSONObject storage = p.optJSONObject("storage");
        if (storage != null) {
            section(theme, root, "存储与备份探针");
            LinearLayout card = theme.card(root);
            LinearLayout mr = new LinearLayout(ctx);
            addMetric(theme, mr, "正常", m.upStores + "/" + m.totalStores);
            addMetric(theme, mr, "降级", String.valueOf(m.degradedStores));
            addMetric(theme, mr, "离线", String.valueOf(m.downStores));
            addMetric(theme, mr, "未校验备份", String.valueOf(m.unverifiedBackups));
            card.addView(mr);
            if (m.storageStale) {
                TextView stale = theme.text("存储探针数据已陈旧，建议检查采集进程。", 12, false);
                stale.setTextColor(theme.warning);
                stale.setPadding(0, theme.dp(6), 0, 0);
                card.addView(stale);
            }
            JSONArray hi = storage.optJSONArray("highlights");
            if (hi != null) {
                for (int i = 0; i < hi.length() && i < 20; i++) {
                    Object item = hi.opt(i);
                    String txt = item instanceof JSONObject o ? clean(o.optString("label", o.optString("title", o.optString("id", "")))) + " · " + clean(o.optString("status", "")) : clean(String.valueOf(item));
                    if (!txt.isEmpty()) {
                        TextView hv = theme.text("• " + txt, 12, false);
                        hv.setTextColor(theme.muted);
                        hv.setPadding(0, theme.dp(4), 0, 0);
                        card.addView(hv);
                    }
                }
            }
        }

        // 4. Domains & Certs
        JSONObject domains = p.optJSONObject("domainsAndCerts");
        if (domains != null) {
            section(theme, root, "域名与 HTTPS 证书");
            LinearLayout card = theme.card(root);
            card.addView(theme.text("整体状态：" + statusText(m.domainsStatus), 14, true));
            JSONArray certs = domains.optJSONArray("certificates");
            if (certs != null) {
                for (int i = 0; i < certs.length() && i < 20; i++) {
                    JSONObject c = certs.optJSONObject(i);
                    if (c == null) continue;
                    int days = c.optInt("daysRemaining", -1);
                    String txt = clean(c.optString("domain", c.optString("host", "证书")))
                        + (days >= 0 ? " · 剩余 " + days + " 天" : "")
                        + (c.has("status") ? " · " + statusText(clean(c.optString("status", ""))) : "");
                    TextView cv = theme.text(txt, 12, false);
                    cv.setTextColor(days >= 0 && days <= 14 ? theme.warning : theme.muted);
                    cv.setPadding(0, theme.dp(4), 0, 0);
                    card.addView(cv);
                }
            }
        }

        // 5. Model APIs
        JSONObject models = p.optJSONObject("modelApis");
        if (models != null) {
            section(theme, root, "模型 API 余额");
            LinearLayout card = theme.card(root);
            card.addView(theme.text("接口状态：" + statusText(m.modelApisStatus), 14, true));
            JSONObject bal = models.optJSONObject("balances");
            if (bal != null) {
                for (String key : new String[]{"kimi", "deepseek"}) {
                    JSONObject b = bal.optJSONObject(key);
                    if (b == null) continue;
                    String label = "kimi".equals(key) ? "Kimi" : "DeepSeek";
                    String amount = b.opt("balance") instanceof Number n
                        ? (b.optString("currency", "CNY") + " " + new DecimalFormat("#,##0.##").format(n.doubleValue()))
                        : statusText(clean(b.optString("status", "")));
                    TextView bv = theme.text(label + "：" + amount, 13, false);
                    bv.setPadding(0, theme.dp(4), 0, 0);
                    card.addView(bv);
                }
            }
        }

        // 6. Release Evidence & WeChat Digest Freshness
        section(theme, root, "发布证据与微信归档鲜度");
        LinearLayout tail = theme.card(root);
        tail.addView(theme.text("发布校验：" + statusText(m.releaseStatus), 14, true));
        JSONObject wechat = p.optJSONObject("wechatDigest");
        if (wechat != null) {
            String wText = "微信日报鲜度：" + (m.wechatStale ? "超过 2 小时未同步" : statusText(m.wechatStatus))
                + (m.wechatAgeMinutes >= 0 ? "（距今 " + m.wechatAgeMinutes + " 分钟）" : "");
            TextView wv = theme.text(wText, 13, false);
            wv.setTextColor(m.wechatStale ? theme.warning : theme.muted);
            wv.setPadding(0, theme.dp(6), 0, 0);
            tail.addView(wv);
        }
    }

    private static String statusText(String raw) {
        if (raw == null || raw.isEmpty()) return "未知";
        switch (raw) {
            case "healthy":
            case "ok":
            case "ready":
            case "running":
            case "online":
            case "up": return "正常";
            case "warning":
            case "degraded":
            case "stale": return "需关注";
            case "critical":
            case "error":
            case "down":
            case "offline":
            case "unavailable": return "异常";
            case "not_configured": return "未配置";
            default: return raw;
        }
    }

    private static void addMetric(ConsoleTheme theme, LinearLayout row, String label, String val) {
        LinearLayout col = new LinearLayout(theme.context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(theme.text(label, 11, false));
        TextView v = theme.text(val, 16, true);
        v.setPadding(0, theme.dp(2), 0, 0);
        col.addView(v);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private static void section(ConsoleTheme theme, LinearLayout root, String title) {
        TextView t = theme.text(title, 15, true);
        t.setAccessibilityHeading(true);
        t.setPadding(theme.dp(6), theme.dp(12), 0, theme.dp(8));
        root.addView(t);
    }

    private static void divider(Context ctx, ConsoleTheme theme, LinearLayout card, int marginV) {
        View d = new View(ctx);
        d.setBackgroundColor(theme.line);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, theme.dp(.5f)));
        lp.setMargins(0, theme.dp(marginV), 0, theme.dp(marginV));
        card.addView(d, lp);
    }

    private static String joinNonEmpty(String sep, String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) if (p != null && !p.isEmpty()) out.add(p);
        return String.join(sep, out);
    }

    private static String clean(String v) {
        return v == null ? "" : v.replaceAll("[\\u0000-\\u001f\\u007f-\\u009f]", " ").trim();
    }
}
