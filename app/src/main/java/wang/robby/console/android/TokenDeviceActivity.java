package wang.robby.console.android;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;
import wang.robby.console.android.auth.AuthCoordinator;

/**
 * Native screen for `GET /api/mobile/v1/tokens/devices`: per-account machine presence,
 * login status, running coding agents, and active sessions.
 */
public final class TokenDeviceActivity extends Activity {
    static final String EXTRA_ACCOUNT = "account";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ConsoleTheme theme;
    private LinearLayout content;
    private String selectedAccount;
    private volatile boolean alive = true;

    static final class AgentInfo {
        final String id, name, product, status, observedAt, statusBasis;
        final int sessions;
        AgentInfo(String id, String name, String product, String status, String observedAt, String statusBasis, int sessions) {
            this.id = id;
            this.name = name;
            this.product = product;
            this.status = status;
            this.observedAt = observedAt;
            this.statusBasis = statusBasis;
            this.sessions = sessions;
        }
    }

    static final class DeviceInfo {
        final String id, name, platform, loginStatus, status, observedAt;
        final List<AgentInfo> agents;
        DeviceInfo(String id, String name, String platform, String loginStatus, String status, String observedAt, List<AgentInfo> agents) {
            this.id = id;
            this.name = name;
            this.platform = platform;
            this.loginStatus = loginStatus;
            this.status = status;
            this.observedAt = observedAt;
            this.agents = agents;
        }
    }

    static final class AccountDevices {
        final String accountId, status, updatedAt;
        final int onlineDevices, workingAgents, workingSessions;
        final boolean truncated;
        final List<DeviceInfo> devices;
        AccountDevices(String accountId, String status, String updatedAt, int onlineDevices,
                       int workingAgents, int workingSessions, boolean truncated, List<DeviceInfo> devices) {
            this.accountId = accountId;
            this.status = status;
            this.updatedAt = updatedAt;
            this.onlineDevices = onlineDevices;
            this.workingAgents = workingAgents;
            this.workingSessions = workingSessions;
            this.truncated = truncated;
            this.devices = devices;
        }
    }

    static final class Model {
        final String generatedAt;
        final int accounts, readyAccounts, onlineDevices, workingAgents, workingSessions;
        final Map<String, AccountDevices> byAccount;
        Model(String generatedAt, int accounts, int readyAccounts, int onlineDevices,
              int workingAgents, int workingSessions, Map<String, AccountDevices> byAccount) {
            this.generatedAt = generatedAt;
            this.accounts = accounts;
            this.readyAccounts = readyAccounts;
            this.onlineDevices = onlineDevices;
            this.workingAgents = workingAgents;
            this.workingSessions = workingSessions;
            this.byAccount = byAccount;
        }
    }

    static Model parse(JSONObject root) {
        if (root == null || root.optInt("schemaVersion", -1) != 1) return null;
        JSONObject summary = root.optJSONObject("summary");
        int accounts = summary == null ? 0 : summary.optInt("accounts", 0);
        int ready = summary == null ? 0 : summary.optInt("readyAccounts", 0);
        int online = summary == null ? 0 : summary.optInt("onlineDevices", 0);
        int working = summary == null ? 0 : summary.optInt("workingAgents", 0);
        int sessions = summary == null ? 0 : summary.optInt("workingSessions", 0);

        Map<String, AccountDevices> map = new LinkedHashMap<>();
        JSONObject rawBy = root.optJSONObject("byAccount");
        if (rawBy != null) {
            for (Iterator<String> it = rawBy.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONObject acc = rawBy.optJSONObject(id);
                if (acc == null) continue;
                JSONObject s = acc.optJSONObject("summary");
                List<DeviceInfo> devices = new ArrayList<>();
                JSONArray devArr = acc.optJSONArray("devices");
                if (devArr != null) {
                    for (int i = 0; i < devArr.length() && i < 100; i++) {
                        JSONObject d = devArr.optJSONObject(i);
                        if (d == null) continue;
                        List<AgentInfo> agents = new ArrayList<>();
                        JSONArray agArr = d.optJSONArray("agents");
                        if (agArr != null) {
                            for (int j = 0; j < agArr.length() && j < 50; j++) {
                                JSONObject ag = agArr.optJSONObject(j);
                                if (ag == null) continue;
                                agents.add(new AgentInfo(
                                    clean(ag.optString("id", "")),
                                    clean(ag.optString("name", "Agent")),
                                    clean(ag.optString("product", "")),
                                    clean(ag.optString("status", "unknown")),
                                    clean(ag.optString("observedAt", "")),
                                    clean(ag.optString("statusBasis", "")),
                                    Math.max(0, ag.optInt("sessions", 0))
                                ));
                            }
                        }
                        devices.add(new DeviceInfo(
                            clean(d.optString("id", "")),
                            clean(d.optString("name", "未命名设备")),
                            clean(d.optString("platform", "")),
                            clean(d.optString("loginStatus", "")),
                            clean(d.optString("status", "unknown")),
                            clean(d.optString("observedAt", "")),
                            agents
                        ));
                    }
                }
                map.put(id, new AccountDevices(
                    id,
                    clean(acc.optString("status", "unavailable")),
                    clean(acc.optString("updatedAt", "")),
                    s == null ? 0 : s.optInt("onlineDevices", 0),
                    s == null ? 0 : s.optInt("workingAgents", 0),
                    s == null ? 0 : s.optInt("workingSessions", 0),
                    Boolean.TRUE.equals(acc.opt("truncated")),
                    devices
                ));
            }
        }
        return new Model(clean(root.optString("generatedAt", "")), accounts, ready, online, working, sessions, map);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        PreviewMode.configure(this, getIntent());
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        theme = new ConsoleTheme(this);
        selectedAccount = sanitizeAccount(getIntent() == null ? null : getIntent().getStringExtra(EXTRA_ACCOUNT));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackground(theme.backdrop());
        page.setFitsSystemWindows(true);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(theme.dp(12), theme.dp(10), theme.dp(12), theme.dp(10));
        FrameLayout back = theme.circle("back", "返回", this::finish);
        header.addView(back, new LinearLayout.LayoutParams(theme.dp(48), theme.dp(48)));
        TextView title = theme.text("设备与运行状态", 20, true);
        title.setAccessibilityHeading(true);
        title.setPadding(theme.dp(8), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        FrameLayout reload = theme.circle("refresh", "刷新", this::load);
        header.addView(reload, new LinearLayout.LayoutParams(theme.dp(48), theme.dp(48)));
        page.addView(header);

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(theme.dp(16), theme.dp(4), theme.dp(16), theme.dp(28));
        scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);

        load();
    }

    @Override protected void onDestroy() {
        alive = false;
        worker.shutdownNow();
        super.onDestroy();
    }

    private void load() {
        content.removeAllViews();
        TextView wait = theme.text("正在读取各账号设备与 Agent 状态…", 14, false);
        wait.setTextColor(theme.muted);
        wait.setPadding(theme.dp(4), theme.dp(14), 0, theme.dp(14));
        content.addView(wait);

        String route = selectedAccount == null
            ? AccountCenter.TOKENS_DEVICES
            : AccountCenter.TOKENS_DEVICES + "?account=" + selectedAccount;
        worker.execute(() -> {
            JSONObject raw = null;
            Exception err = null;
            try {
                if (PreviewMode.isBuild()) {
                    raw = PreviewMode.data(AccountCenter.TOKENS_DEVICES);
                } else {
                    MobileApi api = new MobileApi(AuthCoordinator.get(this).session());
                    raw = api.get(route);
                }
            } catch (Exception e) {
                err = e;
            }
            JSONObject finalRaw = raw;
            Exception finalErr = err;
            runOnUiThread(() -> {
                if (!alive || isDestroyed()) return;
                render(finalRaw, finalErr);
            });
        });
    }

    private void render(JSONObject raw, Exception err) {
        content.removeAllViews();
        if (err != null) {
            LinearLayout card = theme.card(content);
            String msg = err instanceof MobileApi.ApiError && ((MobileApi.ApiError) err).status == 403
                ? "当前登录没有读取 Token 设备的权限，请退出后用 Passkey 重新登录。"
                : safeMsg(err);
            TextView t = theme.text(msg, 14, false);
            t.setTextColor(theme.danger);
            t.setPadding(0, 0, 0, theme.dp(12));
            card.addView(t);
            Button retry = theme.button("重试", this::load);
            card.addView(retry, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        Model m = parse(raw);
        if (m == null) {
            LinearLayout card = theme.card(content);
            card.addView(theme.text("设备状态接口返回格式不兼容。", 14, false));
            return;
        }

        LinearLayout sum = theme.card(content);
        TextView h = theme.text("运行总览", 16, true);
        h.setAccessibilityHeading(true);
        sum.addView(h);
        if (!m.generatedAt.isEmpty()) {
            TextView gen = theme.text("采集时间 " + shortTime(m.generatedAt), 12, false);
            gen.setTextColor(theme.faint);
            gen.setPadding(0, theme.dp(2), 0, theme.dp(8));
            sum.addView(gen);
        }
        metrics(sum,
            "就绪账号", m.readyAccounts + " / " + m.accounts,
            "在线设备", String.valueOf(m.onlineDevices),
            "运行 Agent", String.valueOf(m.workingAgents),
            "活跃会话", String.valueOf(m.workingSessions));

        if (!m.byAccount.isEmpty()) {
            FlowRow filters = new FlowRow(this, theme.dp(6), theme.dp(6));
            filters.setPadding(0, 0, 0, theme.dp(10));
            addFilterPill(filters, "全部", selectedAccount == null, () -> {
                selectedAccount = null;
                load();
            });
            for (String id : m.byAccount.keySet()) {
                addFilterPill(filters, id, id.equals(selectedAccount), () -> {
                    selectedAccount = id;
                    load();
                });
            }
            content.addView(filters);
        }

        boolean any = false;
        for (AccountDevices acc : m.byAccount.values()) {
            if (selectedAccount != null && !selectedAccount.equals(acc.accountId)) continue;
            any = true;
            drawAccount(acc);
        }
        if (!any) {
            LinearLayout card = theme.card(content);
            card.addView(theme.text("没有匹配的账号设备信息", 14, false));
        }
    }

    private void drawAccount(AccountDevices acc) {
        LinearLayout card = theme.card(content);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = theme.text(acc.accountId, 16, true);
        title.setAccessibilityHeading(true);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        String statusText = statusLabel(acc.status);
        View dot = theme.dot(statusTone(acc.status), 7);
        ((LinearLayout.LayoutParams) dot.getLayoutParams()).rightMargin = theme.dp(6);
        top.addView(dot);
        TextView st = theme.text(statusText, 13, true);
        top.addView(st);
        card.addView(top);

        TextView meta = theme.text(
            "在线设备 " + acc.onlineDevices + " · 运行 Agent " + acc.workingAgents + " · 会话 " + acc.workingSessions
                + (acc.updatedAt.isEmpty() ? "" : " · " + shortTime(acc.updatedAt)),
            12, false);
        meta.setTextColor(theme.muted);
        meta.setPadding(0, theme.dp(3), 0, theme.dp(6));
        card.addView(meta);

        if (acc.devices.isEmpty()) {
            TextView empty = theme.text("not_configured".equals(acc.status) ? "未接通设备观测" : "当前无在线设备记录", 13, false);
            empty.setTextColor(theme.faint);
            card.addView(empty);
            return;
        }
        for (DeviceInfo d : acc.devices) {
            View div = new View(this);
            div.setBackgroundColor(theme.line);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, Math.max(1, theme.dp(.5f)));
            dlp.setMargins(0, theme.dp(8), 0, theme.dp(8));
            card.addView(div, dlp);

            LinearLayout dRow = new LinearLayout(this);
            dRow.setGravity(Gravity.CENTER_VERTICAL);
            dRow.addView(theme.dot(deviceTone(d.status), 7));
            TextView dName = theme.text(d.name, 14, true);
            dName.setPadding(theme.dp(8), 0, theme.dp(8), 0);
            dName.setSingleLine(true);
            dName.setEllipsize(TextUtils.TruncateAt.END);
            dRow.addView(dName, new LinearLayout.LayoutParams(0, -2, 1));
            TextView dState = theme.text(deviceStateLabel(d.status, d.loginStatus), 12, false);
            dState.setTextColor(theme.muted);
            dRow.addView(dState);
            card.addView(dRow);

            String sub = joinNonEmpty(" · ", d.platform, d.observedAt.isEmpty() ? "" : "观测 " + shortTime(d.observedAt));
            if (!sub.isEmpty()) {
                TextView dSub = theme.text(sub, 11, false);
                dSub.setTextColor(theme.faint);
                dSub.setPadding(theme.dp(15), theme.dp(2), 0, 0);
                card.addView(dSub);
            }

            for (AgentInfo ag : d.agents) {
                LinearLayout aRow = new LinearLayout(this);
                aRow.setGravity(Gravity.CENTER_VERTICAL);
                aRow.setPadding(theme.dp(15), theme.dp(5), 0, 0);
                aRow.addView(theme.dot(agentTone(ag.status), 5));
                String aTitle = ag.name + (ag.sessions > 0 ? " (" + ag.sessions + " 会话)" : "");
                TextView aName = theme.text(aTitle, 13, false);
                aName.setPadding(theme.dp(6), 0, theme.dp(6), 0);
                aRow.addView(aName, new LinearLayout.LayoutParams(0, -2, 1));
                TextView aState = theme.text(agentStatusLabel(ag.status), 12, true);
                aState.setTextColor("working".equals(ag.status) ? theme.success : theme.muted);
                aRow.addView(aState);
                card.addView(aRow);
                if (!ag.statusBasis.isEmpty()) {
                    TextView basis = theme.text("依据：" + ag.statusBasis, 11, false);
                    basis.setTextColor(theme.faint);
                    basis.setPadding(theme.dp(26), theme.dp(1), 0, 0);
                    card.addView(basis);
                }
            }
        }
        if (acc.truncated) {
            TextView tr = theme.text("设备列表已按服务端上限截断展示", 11, false);
            tr.setTextColor(theme.faint);
            tr.setPadding(0, theme.dp(8), 0, 0);
            card.addView(tr);
        }
    }

    private void metrics(LinearLayout card, String... pairs) {
        LinearLayout row = new LinearLayout(this);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView k = theme.text(pairs[i], 11, false);
            k.setTextColor(theme.faint);
            col.addView(k);
            TextView v = theme.text(pairs[i + 1], 16, true);
            v.setPadding(0, theme.dp(2), 0, 0);
            col.addView(v);
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        }
        card.addView(row);
    }

    private void addFilterPill(FlowRow parent, String label, boolean selected, Runnable action) {
        TextView pill = theme.text(label, 12, selected);
        pill.setTextColor(selected ? Color.WHITE : theme.ink);
        pill.setBackground(theme.ripple(theme.shape(selected ? theme.accent : theme.soft, 99), 99));
        pill.setPadding(theme.dp(12), theme.dp(5), theme.dp(12), theme.dp(5));
        pill.setFocusable(true);
        pill.setClickable(true);
        pill.setSelected(selected);
        pill.setOnClickListener(v -> action.run());
        parent.addView(pill);
    }

    private int statusTone(String s) {
        return "ready".equals(s) ? theme.success : "not_configured".equals(s) ? theme.muted : theme.warning;
    }

    private int deviceTone(String s) {
        return "online".equals(s) ? theme.success : "idle".equals(s) ? theme.warning : theme.faint;
    }

    private int agentTone(String s) {
        return "working".equals(s) ? theme.success : "idle".equals(s) ? theme.accent : theme.faint;
    }

    static String statusLabel(String s) {
        switch (s == null ? "" : s) {
            case "ready": return "正常";
            case "not_configured": return "未配置";
            case "unavailable": return "暂不可用";
            default: return s == null || s.isEmpty() ? "未知" : s;
        }
    }

    private static String deviceStateLabel(String status, String loginStatus) {
        String base = "online".equals(status) ? "在线" : "offline".equals(status) ? "离线" : status;
        if ("logged_in".equals(loginStatus)) return base + " · 已登录";
        if ("needs_auth".equals(loginStatus)) return base + " · 待登录";
        return loginStatus.isEmpty() ? base : base + " · " + loginStatus;
    }

    private static String agentStatusLabel(String status) {
        switch (status == null ? "" : status) {
            case "working": return "运行中";
            case "idle": return "空闲";
            case "offline": return "离线";
            default: return status == null || status.isEmpty() ? "未知" : status;
        }
    }

    private static String sanitizeAccount(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        return t.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}") ? t : null;
    }

    private static String clean(String v) {
        return v == null ? "" : v.replaceAll("[\\u0000-\\u001f\\u007f-\\u009f]", " ").trim();
    }

    private static String shortTime(String iso) {
        try {
            return DateTimeFormatter.ofPattern("MM/dd HH:mm", Locale.SIMPLIFIED_CHINESE)
                .withZone(ZoneId.systemDefault())
                .format(Instant.parse(iso));
        } catch (RuntimeException e) {
            return iso;
        }
    }

    private static String joinNonEmpty(String sep, String a, String b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + sep + b;
    }

    private static String safeMsg(Exception e) {
        String m = e.getMessage();
        return m != null && m.matches("[\\p{IsHan}，。、；：！？ ·…A-Za-z0-9-]{1,100}") ? m : "设备状态暂时不可读，请检查网络后重试。";
    }
}
