package wang.robby.console.android;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Pure Java parser + native UI renderer for `/api/mobile/v1/dispatch/overview` and
 * `/api/mobile/v1/dispatch/control` (`pause`, `resume`, `release_retry`).
 */
final class DispatchPage {
    private DispatchPage() {}
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("MM/dd HH:mm", Locale.SIMPLIFIED_CHINESE);

    interface Host {
        void dispatchControl(JSONObject body, Runnable onDone, Consumer<String> onError);
    }

    static final class Claim {
        final String memoId, title, state, agentId, agentName, machine, model, effort, note, ticket, claimedAt, updatedAt, leaseUntil, memoUrl, appDeepLink, latestCommand;
        final int attempt;
        final boolean stale, canRetryRelease;
        Claim(String memoId, String title, String state, boolean stale, String agentId, String agentName,
              String machine, String model, String effort, int attempt, String note, String ticket,
              boolean canRetryRelease, String claimedAt, String updatedAt, String leaseUntil,
              String memoUrl, String appDeepLink, String latestCommand) {
            this.memoId = memoId;
            this.title = title;
            this.state = state;
            this.stale = stale;
            this.agentId = agentId;
            this.agentName = agentName;
            this.machine = machine;
            this.model = model;
            this.effort = effort;
            this.attempt = attempt;
            this.note = note;
            this.ticket = ticket;
            this.canRetryRelease = canRetryRelease;
            this.claimedAt = claimedAt;
            this.updatedAt = updatedAt;
            this.leaseUntil = leaseUntil;
            this.memoUrl = memoUrl;
            this.appDeepLink = appDeepLink;
            this.latestCommand = latestCommand;
        }
    }

    static final class Machine {
        final String name, detail, seenAt;
        final boolean online;
        Machine(String name, boolean online, String seenAt, String detail) {
            this.name = name;
            this.online = online;
            this.seenAt = seenAt;
            this.detail = detail;
        }
    }

    static final class Agent {
        final String id, name, machine, status, model, effort;
        Agent(String id, String name, String machine, String status, String model, String effort) {
            this.id = id;
            this.name = name;
            this.machine = machine;
            this.status = status;
            this.model = model;
            this.effort = effort;
        }
    }

    static final class Model {
        final String status, webUrl, host, version, mode, seenAt;
        final boolean partial, online, running, paused;
        final int queue, totalAgents, onlineAgents, workingAgents, activeClaims, blockedClaims, reviewClaims, pendingCommands;
        final List<Machine> machines;
        final List<Agent> agents;
        final List<Claim> inProgress, blocked, recentCompleted;
        Model(String status, boolean partial, String webUrl, boolean online, boolean running, boolean paused,
              String host, String version, String mode, int queue, String seenAt,
              int totalAgents, int onlineAgents, int workingAgents, int activeClaims,
              int blockedClaims, int reviewClaims, int pendingCommands,
              List<Machine> machines, List<Agent> agents,
              List<Claim> inProgress, List<Claim> blocked, List<Claim> recentCompleted) {
            this.status = status;
            this.partial = partial;
            this.webUrl = webUrl;
            this.online = online;
            this.running = running;
            this.paused = paused;
            this.host = host;
            this.version = version;
            this.mode = mode;
            this.queue = queue;
            this.seenAt = seenAt;
            this.totalAgents = totalAgents;
            this.onlineAgents = onlineAgents;
            this.workingAgents = workingAgents;
            this.activeClaims = activeClaims;
            this.blockedClaims = blockedClaims;
            this.reviewClaims = reviewClaims;
            this.pendingCommands = pendingCommands;
            this.machines = machines;
            this.agents = agents;
            this.inProgress = inProgress;
            this.blocked = blocked;
            this.recentCompleted = recentCompleted;
        }
    }

    static Model parse(JSONObject root) {
        if (root == null || root.optInt("schemaVersion", -1) != 1) return null;
        String status = clean(root.optString("status", "ready"));
        boolean partial = Boolean.TRUE.equals(root.opt("partial"));
        String webUrl = safeFloboUrl(root.optString("webUrl", null));
        JSONObject disp = root.optJSONObject("dispatcher");
        boolean online = disp != null && Boolean.TRUE.equals(disp.opt("online"));
        boolean running = disp != null && Boolean.TRUE.equals(disp.opt("running"));
        boolean paused = disp != null && Boolean.TRUE.equals(disp.opt("paused"));
        String host = disp == null ? "" : clean(disp.optString("host", ""));
        String version = disp == null ? "" : clean(disp.optString("version", ""));
        String mode = disp == null ? "" : clean(disp.optString("mode", ""));
        int queue = disp == null ? 0 : Math.max(0, disp.optInt("queue", 0));
        String seenAt = disp == null ? "" : clean(disp.optString("seenAt", ""));

        JSONObject sum = root.optJSONObject("summary");
        int totalAgents = sum == null ? 0 : sum.optInt("totalAgents", 0);
        int onlineAgents = sum == null ? 0 : sum.optInt("onlineAgents", 0);
        int workingAgents = sum == null ? 0 : sum.optInt("workingAgents", 0);
        int activeClaims = sum == null ? 0 : sum.optInt("activeClaims", 0);
        int blockedClaims = sum == null ? 0 : sum.optInt("blockedClaims", 0);
        int reviewClaims = sum == null ? 0 : sum.optInt("reviewClaims", 0);
        int pendingCommands = sum == null ? 0 : sum.optInt("pendingCommands", 0);

        List<Machine> machines = new ArrayList<>();
        JSONArray mArr = root.optJSONArray("machines");
        if (mArr != null) {
            for (int i = 0; i < mArr.length() && i < 50; i++) {
                JSONObject m = mArr.optJSONObject(i);
                if (m == null) continue;
                String name = clean(m.optString("name", m.optString("id", "未知主机")));
                machines.add(new Machine(name, Boolean.TRUE.equals(m.opt("online")), clean(m.optString("seenAt", "")), clean(m.optString("host", ""))));
            }
        }

        List<Agent> agents = new ArrayList<>();
        JSONArray aArr = root.optJSONArray("agents");
        if (aArr != null) {
            for (int i = 0; i < aArr.length() && i < 100; i++) {
                JSONObject a = aArr.optJSONObject(i);
                if (a == null) continue;
                agents.add(new Agent(
                    clean(a.optString("id", "")),
                    clean(a.optString("name", a.optString("id", "Agent"))),
                    clean(a.optString("machine", "")),
                    clean(a.optString("status", "")),
                    clean(a.optString("model", "")),
                    clean(a.optString("effort", ""))
                ));
            }
        }

        JSONObject cards = root.optJSONObject("cards");
        List<Claim> inProgress = parseClaims(cards == null ? null : cards.optJSONArray("inProgress"));
        List<Claim> blocked = parseClaims(cards == null ? null : cards.optJSONArray("blocked"));
        List<Claim> completed = parseClaims(cards == null ? null : cards.optJSONArray("recentCompleted"));

        return new Model(status, partial, webUrl, online, running, paused, host, version, mode, queue, seenAt,
            totalAgents, onlineAgents, workingAgents, activeClaims, blockedClaims, reviewClaims, pendingCommands,
            machines, agents, inProgress, blocked, completed);
    }

    private static List<Claim> parseClaims(JSONArray arr) {
        List<Claim> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length() && i < 100; i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null) continue;
            out.add(new Claim(
                clean(c.optString("memoId", "")),
                clean(c.optString("title", "未命名任务")),
                clean(c.optString("state", "")),
                Boolean.TRUE.equals(c.opt("stale")),
                clean(c.optString("agentId", "")),
                clean(c.optString("agentName", "")),
                clean(c.optString("machine", "")),
                clean(c.optString("model", "")),
                clean(c.optString("effort", "")),
                Math.max(0, c.optInt("attempt", 0)),
                clean(c.optString("note", "")),
                clean(c.optString("ticket", "")),
                Boolean.TRUE.equals(c.opt("canRetryRelease")),
                clean(c.optString("claimedAt", "")),
                clean(c.optString("updatedAt", "")),
                clean(c.optString("leaseUntil", "")),
                safeFloboUrl(c.optString("memoUrl", null)),
                safeDeepLink(c.optString("appDeepLink", null)),
                clean(c.optString("latestCommand", ""))
            ));
        }
        return out;
    }

    static JSONObject controlBody(String action) {
        if (!"pause".equals(action) && !"resume".equals(action)) {
            throw new IllegalArgumentException("无效调度动作");
        }
        try {
            return new JSONObject().put("schemaVersion", 1).put("action", action);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static JSONObject releaseRetryBody(String memoId, String ticket, boolean force) {
        if (memoId == null || memoId.trim().isEmpty()) throw new IllegalArgumentException("缺少 memoId");
        try {
            JSONObject body = new JSONObject()
                .put("schemaVersion", 1)
                .put("action", "release_retry")
                .put("memoId", memoId.trim());
            if (ticket != null && !ticket.trim().isEmpty()) body.put("ticket", ticket.trim());
            if (force) body.put("force", true);
            return body;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean isCompatible(JSONObject p) {
        return p != null && p.optInt("schemaVersion", -1) == 1 && (p.has("status") || p.optJSONObject("dispatcher") != null);
    }

    static void renderCard(Context ctx, LinearLayout shell, JSONObject p) {
        ConsoleTheme theme = new ConsoleTheme(ctx);
        Model m = parse(p);
        if (m == null) {
            FeatureRenderer.widgetMessage(ctx, shell, "调度状态暂不可读");
            return;
        }
        if ("not_configured".equals(m.status)) {
            FeatureRenderer.widgetMessage(ctx, shell, "调度服务未配置");
            return;
        }
        String stateText = m.paused ? "已暂停" : m.running ? "运行中" : m.online ? "在线空闲" : "离线";
        TextView big = theme.text(stateText, 22, true);
        big.setTextColor(m.paused ? theme.warning : m.online ? theme.success : theme.danger);
        big.setPadding(0, theme.dp(8), 0, 0);
        shell.addView(big);
        TextView cap = theme.text(
            "Agent " + m.workingAgents + "/" + m.totalAgents + " 工作中 · 进行中 " + m.activeClaims
                + (m.blockedClaims > 0 ? " · 阻塞 " + m.blockedClaims : ""),
            12, false);
        cap.setTextColor(theme.muted);
        cap.setPadding(0, theme.dp(2), 0, 0);
        shell.addView(cap);
    }

    static void render(Context ctx, LinearLayout root, JSONObject p) {
        ConsoleTheme theme = new ConsoleTheme(ctx);
        Model m = parse(p);
        if (m == null) {
            LinearLayout card = theme.card(root);
            card.addView(theme.text("调度总控返回格式不兼容", 14, false));
            return;
        }
        if ("not_configured".equals(m.status)) {
            LinearLayout card = theme.card(root);
            card.addView(theme.text("调度服务尚未配置", 16, true));
            TextView sub = theme.text("网关未配置 Flobo 调度凭据，当前仅显示状态说明。", 13, false);
            sub.setTextColor(theme.muted);
            sub.setPadding(0, theme.dp(6), 0, 0);
            card.addView(sub);
            return;
        }

        LinearLayout hero = theme.card(root);
        LinearLayout top = new LinearLayout(ctx);
        top.setGravity(Gravity.CENTER_VERTICAL);
        String dispLabel = m.paused ? "调度器已暂停" : m.running ? "调度器运行中" : m.online ? "调度器在线" : "调度器离线";
        int dispTone = m.paused ? theme.warning : m.online ? theme.success : theme.danger;
        top.addView(theme.dot(dispTone, 9));
        TextView ht = theme.text(dispLabel, 18, true);
        ht.setAccessibilityHeading(true);
        ht.setPadding(theme.dp(8), 0, 0, 0);
        top.addView(ht, new LinearLayout.LayoutParams(0, -2, 1));
        if (m.queue > 0) {
            TextView q = theme.text("队列 " + m.queue, 12, true);
            q.setTextColor(theme.accent);
            top.addView(q);
        }
        hero.addView(top);

        String sub = joinNonEmpty(" · ",
            m.host.isEmpty() ? "" : "主机 " + m.host,
            m.mode,
            m.version.isEmpty() ? "" : "v" + m.version,
            m.seenAt.isEmpty() ? "" : "心跳 " + shortTime(m.seenAt));
        if (!sub.isEmpty()) {
            TextView meta = theme.text(sub, 12, false);
            meta.setTextColor(theme.faint);
            meta.setPadding(0, theme.dp(4), 0, 0);
            hero.addView(meta);
        }

        LinearLayout metricRow = new LinearLayout(ctx);
        metricRow.setPadding(0, theme.dp(10), 0, 0);
        addMetric(theme, metricRow, "Agent", m.workingAgents + " / " + m.totalAgents);
        addMetric(theme, metricRow, "进行中", String.valueOf(m.activeClaims));
        addMetric(theme, metricRow, "阻塞", String.valueOf(m.blockedClaims));
        addMetric(theme, metricRow, "待审", String.valueOf(m.reviewClaims));
        hero.addView(metricRow);

        LinearLayout actions = new LinearLayout(ctx);
        actions.setPadding(0, theme.dp(12), 0, 0);
        if (ctx instanceof Host host) {
            String nextAction = m.paused ? "resume" : "pause";
            Button ctl = theme.button(m.paused ? "恢复调度" : "暂停调度", () -> confirmControl(ctx, theme, host,
                m.paused ? "恢复调度？" : "暂停调度？",
                m.paused ? "确认恢复自动认领新任务。" : "暂停后不会认领新卡片，正在进行的任务将继续。",
                controlBody(nextAction)));
            theme.button(ctl, true);
            actions.addView(ctl, new LinearLayout.LayoutParams(0, -2, 1));
        }
        String floboUrl = m.webUrl != null ? m.webUrl : Bootstrap.cached(ctx).floboWeb;
        Button web = theme.button("打开 Flobo ↗", () -> openExternal(ctx, floboUrl, null));
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1);
        if (actions.getChildCount() > 0) wp.leftMargin = theme.dp(8);
        actions.addView(web, wp);
        hero.addView(actions);

        if (!m.blocked.isEmpty()) {
            section(theme, root, "阻塞认领 (" + m.blocked.size() + ")");
            for (Claim c : m.blocked) drawClaim(ctx, theme, root, c, true);
        }
        section(theme, root, "进行中 (" + m.inProgress.size() + ")");
        if (m.inProgress.isEmpty()) {
            LinearLayout card = theme.card(root);
            card.addView(theme.text("暂无进行中的认领任务", 14, false));
        } else {
            for (Claim c : m.inProgress) drawClaim(ctx, theme, root, c, false);
        }

        if (!m.machines.isEmpty()) {
            section(theme, root, "宿主机");
            LinearLayout card = theme.card(root);
            for (int i = 0; i < m.machines.size(); i++) {
                Machine mc = m.machines.get(i);
                if (i > 0) divider(ctx, theme, card);
                LinearLayout row = new LinearLayout(ctx);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, theme.dp(8), 0, theme.dp(8));
                row.addView(theme.dot(mc.online ? theme.success : theme.danger, 7));
                TextView name = theme.text(mc.name, 14, true);
                name.setPadding(theme.dp(8), 0, 0, 0);
                row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                TextView st = theme.text(mc.online ? "在线" : "离线", 12, true);
                st.setTextColor(mc.online ? theme.success : theme.danger);
                row.addView(st);
                card.addView(row);
            }
        }

        if (!m.agents.isEmpty()) {
            section(theme, root, "Agent 队列");
            LinearLayout card = theme.card(root);
            for (int i = 0; i < m.agents.size(); i++) {
                Agent ag = m.agents.get(i);
                if (i > 0) divider(ctx, theme, card);
                LinearLayout row = new LinearLayout(ctx);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, theme.dp(8), 0, theme.dp(8));
                LinearLayout col = new LinearLayout(ctx);
                col.setOrientation(LinearLayout.VERTICAL);
                col.addView(theme.text(ag.name, 14, true));
                String meta = joinNonEmpty(" · ", ag.machine, ag.model, ag.effort);
                if (!meta.isEmpty()) {
                    TextView subT = theme.text(meta, 12, false);
                    subT.setTextColor(theme.muted);
                    col.addView(subT);
                }
                row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
                TextView st = theme.text(ag.status.isEmpty() ? "未知" : ag.status, 12, true);
                row.addView(st);
                card.addView(row);
            }
        }

        if (!m.recentCompleted.isEmpty()) {
            section(theme, root, "最近完成");
            for (Claim c : m.recentCompleted) drawClaim(ctx, theme, root, c, false);
        }
    }

    private static void drawClaim(Context ctx, ConsoleTheme theme, LinearLayout root, Claim c, boolean highlightWarn) {
        LinearLayout card = theme.card(root);
        LinearLayout top = new LinearLayout(ctx);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = theme.text(c.title, 15, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView st = theme.text(c.stale ? "租约过期" : c.state, 12, true);
        st.setTextColor(highlightWarn || c.stale ? theme.warning : theme.muted);
        top.addView(st);
        card.addView(top);

        String who = joinNonEmpty(" · ", c.agentName.isEmpty() ? c.agentId : c.agentName, c.machine, c.model,
            c.attempt > 0 ? "第 " + c.attempt + " 次" : "");
        if (!who.isEmpty()) {
            TextView wv = theme.text(who, 12, false);
            wv.setTextColor(theme.muted);
            wv.setPadding(0, theme.dp(4), 0, 0);
            card.addView(wv);
        }
        if (!c.note.isEmpty()) {
            TextView nv = theme.text(c.note, 13, false);
            nv.setPadding(0, theme.dp(6), 0, 0);
            card.addView(nv);
        }
        LinearLayout actions = new LinearLayout(ctx);
        actions.setPadding(0, theme.dp(8), 0, 0);
        if (c.canRetryRelease && ctx instanceof Host host) {
            Button retry = theme.button("释放并重试", () -> confirmControl(ctx, theme, host,
                "释放当前认领并重试？",
                "将释放「" + c.title + "」的当前锁并通知调度器重新派单。",
                releaseRetryBody(c.memoId, c.ticket, false)));
            retry.setTextColor(theme.warning);
            actions.addView(retry, new LinearLayout.LayoutParams(0, -2, 1));
        }
        if (c.memoUrl != null || c.appDeepLink != null) {
            Button link = theme.button("查看 Flobo ↗", () -> openExternal(ctx, c.memoUrl, c.appDeepLink));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (actions.getChildCount() > 0) lp.leftMargin = theme.dp(8);
            actions.addView(link, lp);
        }
        if (actions.getChildCount() > 0) card.addView(actions);
    }

    private static void confirmControl(Context ctx, ConsoleTheme theme, Host host, String title, String msg, JSONObject body) {
        AlertDialog dlg = new AlertDialog.Builder(ctx)
            .setTitle(title)
            .setMessage(msg)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认执行", (d, w) -> host.dispatchControl(body,
                () -> Toast.makeText(ctx, "调度指令已下发", Toast.LENGTH_SHORT).show(),
                err -> {
                    AlertDialog errDlg = new AlertDialog.Builder(ctx).setTitle("指令未完成").setMessage(err).setPositiveButton("知道了", null).create();
                    errDlg.show();
                    theme.dialog(errDlg);
                }))
            .create();
        dlg.show();
        theme.dialog(dlg);
    }

    static String safeFloboUrl(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            URI u = URI.create(raw.trim());
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null || u.getRawUserInfo() != null) return null;
            String host = u.getHost().toLowerCase(Locale.ROOT);
            if (!"robby.wang".equals(host) && !host.endsWith(".robby.wang")) return null;
            return u.toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String safeDeepLink(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            URI u = URI.create(raw.trim());
            return "flobo".equalsIgnoreCase(u.getScheme()) ? u.toString() : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void openExternal(Context ctx, String webUrl, String deepLink) {
        String safeDeep = safeDeepLink(deepLink);
        if (safeDeep != null) {
            try {
                ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(safeDeep)));
                return;
            } catch (ActivityNotFoundException ignored) { }
        }
        String safeWeb = safeFloboUrl(webUrl);
        if (safeWeb == null) safeWeb = Bootstrap.cached(ctx).floboWeb;
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(safeWeb)).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(ctx, "未找到可打开 Flobo 的应用或浏览器", Toast.LENGTH_SHORT).show();
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

    private static void divider(Context ctx, ConsoleTheme theme, LinearLayout parent) {
        View d = new View(ctx);
        d.setBackgroundColor(theme.line);
        parent.addView(d, new LinearLayout.LayoutParams(-1, Math.max(1, theme.dp(.5f))));
    }

    private static String shortTime(String iso) {
        try {
            return TS_FMT.withZone(ZoneId.systemDefault()).format(Instant.parse(iso));
        } catch (RuntimeException e) {
            return iso;
        }
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
