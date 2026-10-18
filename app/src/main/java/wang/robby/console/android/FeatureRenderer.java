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
        // 总览 carries its snapshot time inside the summary card instead of a separate row.
        // 28 天挑战 shows its own sync line; the quota pages count a reading older than two minutes as stale.
        if (!feature.id.equals("overview") && !feature.id.equals("tibo")) ui.freshness(payload, feature.id.equals("travel-ledger") ? 30 : feature.path.equals("tokens/quotas") ? 120 : 600);
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
            case "tokens": tokens(ui, payload); break;
            case "quota-calendar": TokensPages.calendar(context, container, payload); break;
            case "tibo": TokensPages.challenge(context, container, payload); break;
            default: ui.message("暂未接入", "此功能尚无原生数据视图。");
        }
        if (!ui.explanations.isEmpty()) container.addView(ui.theme.info(ui.explanations));
    }

    /** Feature whose contract a dashboard read must satisfy; several 总览 cards share the overview read. */
    static FeatureCatalog.Feature byPath(String path) {
        if ("overview".equals(path)) return FeatureCatalog.find("overview");
        for (FeatureCatalog.Feature f : FeatureCatalog.all()) if (f.path.equals(path)) return f;
        return null;
    }
    /** Empty dashboard card (header only) sized by {@code params}; the body is filled by {@link #widget}. */
    static LinearLayout widgetShell(Context context, LinearLayout parent, Dashboard.Card card, LinearLayout.LayoutParams params) {
        UI u = new UI(context, parent); LinearLayout c = new LinearLayout(context); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(u.dp(14), u.dp(12), u.dp(14), u.dp(12));
        c.setTag(card.id); LinearLayout head = new LinearLayout(context); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(u.theme.tile(card.symbol, 22), new LinearLayout.LayoutParams(u.dp(22), u.dp(22)));
        TextView t = u.text(card.title, 13, false); t.setPadding(u.dp(8), 0, 0, 0); head.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        if (card.target != null && context instanceof Opener && FeatureCatalog.find(card.target) != null) {
            ImageView chevron = u.theme.icon("chevron", u.theme.muted, 12); chevron.setAlpha(.4f); head.addView(chevron, new LinearLayout.LayoutParams(u.dp(12), u.dp(12)));
            c.setBackground(u.theme.ripple(u.theme.card(), 24)); c.setFocusable(true); c.setClickable(true); u.theme.press(c);
            c.setOnClickListener(v -> ((Opener) context).openFeature(card.target));
        } else c.setBackground(u.theme.card());
        c.addView(head); parent.addView(c, params); return c;
    }
    /**
     * While a card's read is under way: the Token card draws the rows it showed last time (product labels only, kept on
     * the phone by account id) with placeholders for the figures, so the list is there at once and the numbers follow.
     */
    static void widgetLoading(Context context, LinearLayout shell) {
        List<String[]> rows = "tokens".equals(shell.getTag()) ? Dashboard.tokenRows(context) : List.of();
        if (rows.isEmpty()) { widgetMessage(context, shell, "正在读取…"); return; }
        UI u = new UI(context, shell); while (shell.getChildCount() > 1) shell.removeViewAt(1);
        for (int i = 0; i < rows.size(); i++) {
            LinearLayout r = new LinearLayout(context); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(0, u.dp(i == 0 ? 8 : 5), 0, 0);
            r.addView(u.theme.dot(u.theme.line, 7));
            TextView name = u.text(rows.get(i)[1], 14, false); name.setTextColor(u.theme.ink); name.setPadding(u.dp(8), 0, 0, 0); name.setSingleLine(true); r.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            View bar = new View(context); bar.setBackground(u.theme.shape(u.theme.soft, 6)); r.addView(bar, new LinearLayout.LayoutParams(u.dp(64), u.dp(12)));
            if (u.theme.effects) { android.animation.ObjectAnimator pulse = android.animation.ObjectAnimator.ofFloat(bar, View.ALPHA, 1f, .35f); pulse.setDuration(700); pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE); pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE); pulse.start(); }
            shell.addView(r);
        }
        shell.setContentDescription("Token 额度，正在读取");
    }
    /** A one-line body for loading and failures, so a broken source never blanks the whole 看板. */
    static void widgetMessage(Context context, LinearLayout shell, String message) {
        UI u = new UI(context, shell); while (shell.getChildCount() > 1) shell.removeViewAt(1);
        TextView t = u.text(message, 13, false); t.setTextColor(u.theme.faint); t.setPadding(0, u.dp(10), 0, u.dp(2)); shell.addView(t);
    }
    /** The few figures each 看板 card shows; everything else stays one tap away in its feature. */
    static void widget(Context context, LinearLayout shell, Dashboard.Card card, JSONObject p) {
        UI u = new UI(context, shell); while (shell.getChildCount() > 1) shell.removeViewAt(1);
        FeatureCatalog.Feature contract = byPath(card.path);
        if (contract == null || !isCompatible(contract, p)) { widgetMessage(context, shell, "数据格式与当前版本不匹配"); return; }
        JSONObject s = obj(p, "summary");
        switch (card.id) {
            case "services": {
                SpannableStringBuilder figure = new SpannableStringBuilder(number(s, "servicesOnline"));
                if (!number(s, "servicesTotal").equals(UNKNOWN)) { int from = figure.length(); figure.append(" / ").append(number(s, "servicesTotal"));
                    figure.setSpan(new AbsoluteSizeSpan(15, true), from, figure.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    figure.setSpan(new ForegroundColorSpan(u.theme.muted), from, figure.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); }
                u.big(figure, "在线服务 · 已知 " + number(s, "servicesKnown"));
                FlowRow chips = new FlowRow(context, u.dp(6), u.dp(6)); chips.setPadding(0, u.dp(8), 0, 0); shell.addView(chips);
                Object online = s.opt("servicesOnline"), known = s.opt("servicesKnown"), total = s.opt("servicesTotal");
                if (online instanceof Number && known instanceof Number && total instanceof Number) {
                    long down = ((Number) known).longValue() - ((Number) online).longValue(), unknown = ((Number) total).longValue() - ((Number) known).longValue();
                    if (down <= 0 && unknown <= 0) u.pill(chips, "全部在线", u.theme.success);
                    if (down > 0) u.pill(chips, down + " 项未在线", u.theme.danger);
                    if (unknown > 0) u.pill(chips, unknown + " 项状态未知", u.theme.warning);
                } else u.pill(chips, "服务状态未知", u.theme.faint);
                String[] fresh = u.fresh(p, 600); u.pill(chips, fresh[0], fresh[1].equals("ok") ? u.theme.success : fresh[1].equals("warn") ? u.theme.warning : u.theme.faint);
                break; }
            case "reminders": {
                JSONArray notices = arr(p, "notices");
                if (notices.length() == 0) { LinearLayout r = new LinearLayout(context); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(0, u.dp(10), 0, u.dp(2));
                    r.addView(u.theme.dot(u.theme.success, 7)); TextView t = u.text("暂无提醒", 14, false); t.setTextColor(u.theme.ink); t.setPadding(u.dp(8), 0, 0, 0); r.addView(t); shell.addView(r); break; }
                for (int i = 0; i < Math.min(3, notices.length()); i++) { JSONObject n = notices.optJSONObject(i); if (n == null) continue; String level = state(n, "severity");
                    u.notice(shell, s(n, "title"), s(n, "detail"), "级别 " + level + (n.has("expiresAt") ? " · 到期 " + date(n, "expiresAt") : ""), level); }
                if (notices.length() > 3) { TextView more = u.text("另有 " + (notices.length() - 3) + " 条提醒", 12, false); more.setTextColor(u.theme.faint); shell.addView(more); }
                break; }
            case "instances": u.big(number(s, "infrastructureInstances"), "台实例"); break;
            case "domains": u.big(number(s, "domainAssets"), "个已登记域名"); break;
            case "openai": { JSONObject totals = obj(p, "totals"); boolean cost = Boolean.TRUE.equals(obj(obj(p, "costStatus"), "total").opt("available"));
                u.big(number(totals, "requests"), cost ? "次请求 · 成本 " + number(totals, "costsUsd") + " USD" : "次请求 · 输入 " + number(totals, "inputTokens") + " Token"); break; }
            case "visits": { JSONObject m = obj(p, "metrics"), range = obj(m, "selectedRange"), sites = obj(m, "sites");
                u.big(number(range, "visitCount"), "次访问 · 事件 " + number(range, "eventCount") + " · 在线 " + number(sites, "online") + "/" + number(sites, "total")); break; }
            case "okx": u.big(number(p, "totalEquityUsd"), "总权益 USD · 持仓 " + number(p, "openPositionCount")); break;
            case "clipboard": u.big(String.valueOf(arr(p, "items").length()), "条剪贴内容 · 来源 " + source(obj(p, "sourceStatus"))); break;
            case "tokens": {
                // One row per connected account; accounts not set up are left off the board (the page lists them).
                long now = System.currentTimeMillis(); int shown = 0; java.text.DecimalFormat whole = new java.text.DecimalFormat("0");
                List<JSONObject> accounts = tokenAccounts(p); Set<String> hidden = Dashboard.hiddenTokens(context); boolean anyHidden = false; List<String[]> rowsShown = new ArrayList<>();
                for (JSONObject a : accounts) { String st = a.optString("status");
                    if (!TokensModel.shownOnPage(a)) continue; if (hidden.contains(a.optString("id"))) { anyHidden = true; continue; } if (shown >= 8) continue; shown++; rowsShown.add(new String[]{a.optString("id"), s(a, "label")});
                    List<TokensModel.Period> periods = TokensModel.boardWindows(a);
                    Double min = null; for (TokensModel.Period pd : periods) { Double pc = TokensModel.remaining(pd.window); if (pc != null && (min == null || pc < min)) min = pc; }
                    // The countdown is the first window's: Claude's 5 hours, otherwise the tightest window.
                    long reset = periods.isEmpty() ? 0 : millis(periods.get(0).window, "resetsAt");
                    LinearLayout r = new LinearLayout(context); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(0, u.dp(shown == 1 ? 8 : 5), 0, 0);
                    boolean fresh = "connected".equals(st); int level = fresh ? TokensModel.level(min) : 0;
                    r.addView(u.theme.dot(level == 1 ? u.theme.success : level == 2 ? u.theme.warning : level == 3 ? u.theme.danger : u.theme.faint, 7));
                    TextView name = u.text(s(a, "label"), 14, false); name.setTextColor(u.theme.ink); name.setPadding(u.dp(8), 0, 0, 0); name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END); r.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                    String cd = fresh ? TokensModel.countdown(reset, now) : null; if (cd != null) r.addView(u.text(cd, 11, false));
                    if (min == null) { TextView val = u.text(TokensModel.status(st), 14, true); val.setPadding(u.dp(10), 0, 0, 0); r.addView(val); }
                    else for (int i = 0; i < periods.size(); i++) { TokensModel.Period pd = periods.get(i); Double pc = TokensModel.remaining(pd.window); if (pc == null) continue;
                        String prefix = (!fresh && i == 0 ? "上次 " : "") + (pd.label == null ? "" : pd.label);
                        if (!prefix.isEmpty()) { TextView l = u.text(prefix, 11, false); l.setPadding(u.dp(10), 0, u.dp(3), 0); r.addView(l); }
                        TextView val = u.text(whole.format(pc) + "%", 14, true); if (prefix.isEmpty()) val.setPadding(u.dp(10), 0, 0, 0); r.addView(val); }
                    shell.addView(r);
                    // Codex: Points and reset cards on a small second line, aligned with the account name.
                    String extras = TokensModel.boardExtras(a, fresh, now, ZoneId.systemDefault());
                    if (extras != null) { TextView x = u.text(extras, 11, false); x.setTextColor(u.theme.faint); x.setPadding(u.dp(15), u.dp(1), 0, 0); shell.addView(x); } }
                if (shown == 0) widgetMessage(context, shell, anyHidden ? "账号都已在编辑看板里隐藏" : "还没有已接入的账号");
                Dashboard.saveTokenRows(context, rowsShown);
                break; }
            case "calendar": {
                // The next few resets of every connected account, soonest first; the page has the month.
                long now = System.currentTimeMillis(); ZoneId zone = ZoneId.systemDefault();
                QuotaCalendar.Model m = QuotaCalendar.build(tokenAccounts(p), zone, now);
                List<QuotaCalendar.Event> next = new ArrayList<>(); for (QuotaCalendar.Event e : m.events) if (e.at > now) next.add(e);
                if (next.isEmpty()) { widgetMessage(context, shell, "暂时没有已知的下一次重置"); break; }
                for (int i = 0; i < Math.min(4, next.size()); i++) { QuotaCalendar.Event e = next.get(i); boolean live = QuotaCalendar.SCHEDULED.equals(e.status);
                    LinearLayout r = new LinearLayout(context); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(0, u.dp(i == 0 ? 8 : 5), 0, 0);
                    TextView when = u.text(QuotaCalendar.shortDay(e.day, m.today) + " " + DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(e.at).atZone(zone)), 13, true);
                    when.setTextColor(live ? u.theme.ink : u.theme.muted); when.setSingleLine(true); r.addView(when, new LinearLayout.LayoutParams(u.dp(84), -2));
                    r.addView(u.theme.dot(live ? TokensPages.tint(u.theme, e.product) : u.theme.faint, 7));
                    TextView what = u.text(e.platform + " " + e.shortLabel(), 14, false); what.setTextColor(u.theme.ink); what.setPadding(u.dp(7), 0, 0, 0); what.setSingleLine(true); what.setEllipsize(android.text.TextUtils.TruncateAt.END); r.addView(what, new LinearLayout.LayoutParams(0, -2, 1));
                    TextView who = u.text(live ? QuotaCalendar.shortAccount(e.account) : "待核实", 11, false); who.setTextColor(u.theme.faint); who.setSingleLine(true); who.setEllipsize(android.text.TextUtils.TruncateAt.END); who.setMaxWidth(u.dp(84)); who.setPadding(u.dp(8), 0, 0, 0); r.addView(who);
                    r.setContentDescription(QuotaCalendar.relativeDay(e.day, m.today) + " " + DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(e.at).atZone(zone)) + "，" + e.platform + " " + e.label + "，" + e.account + (live ? "" : "，待核实")); shell.addView(r); }
                if (next.size() > 4) { TextView more = u.text("之后还有 " + (next.size() - 4) + " 项", 12, false); more.setTextColor(u.theme.faint); more.setPadding(0, u.dp(4), 0, 0); shell.addView(more); }
                break; }
            case "market": {
                JSONArray list = arr(p, "watchlist"); if (list.length() == 0) { widgetMessage(context, shell, "关注列表为空"); break; }
                for (int i = 0; i < Math.min(4, list.length()); i++) { JSONObject n = list.optJSONObject(i); if (n == null) continue;
                    LinearLayout r = new LinearLayout(context); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(0, u.dp(i == 0 ? 8 : 5), 0, 0);
                    TextView name = u.text(s(n, "label"), 14, false); name.setTextColor(u.theme.ink); r.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                    r.addView(u.text(unit(n, "changePct", "%"), 12, false)); TextView price = u.text(number(n, "price") + (n.has("currency") ? " " + s(n, "currency") : ""), 14, true); price.setPadding(u.dp(10), 0, 0, 0); r.addView(price);
                    shell.addView(r); }
                break; }
            default: widgetMessage(context, shell, "暂不支持这张卡片");
        }
    }

    /** 时区时钟: one row per chosen zone, ticking every second while on screen; no read is involved. */
    static void clocks(Context context, LinearLayout shell, List<Dashboard.Clock> clocks) {
        UI u = new UI(context, shell); while (shell.getChildCount() > 1) shell.removeViewAt(1);
        if (clocks.isEmpty()) { widgetMessage(context, shell, "长按这张卡片，在编辑看板里添加时区"); return; }
        ZoneId phone = ZoneId.systemDefault(); DateTimeFormatter hm = DateTimeFormatter.ofPattern("HH:mm"), day = DateTimeFormatter.ofPattern("M/d E", Locale.SIMPLIFIED_CHINESE);
        for (int i = 0; i < clocks.size(); i++) { Dashboard.Clock c = clocks.get(i); ZoneId zone = ZoneId.of(c.zone);
            LinearLayout r = new LinearLayout(context); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(0, u.dp(i == 0 ? 8 : 6), 0, 0);
            TextView name = u.text(c.name, 15, false); name.setTextColor(u.theme.ink); name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END); r.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView meta = u.text("", 12, false); meta.setTextColor(u.theme.faint); meta.setPadding(u.dp(8), 0, u.dp(10), 0); r.addView(meta);
            TextView time = u.text("", 20, true); time.setTextColor(u.theme.ink); r.addView(time);
            TokensPages.tick(meta, at -> day.format(Instant.ofEpochMilli(at).atZone(zone)) + " · " + Dashboard.offsetLabel(c.zone, phone, at));
            TokensPages.tick(time, at -> { String t = hm.format(Instant.ofEpochMilli(at).atZone(zone)); r.setContentDescription(c.name + " " + t + "，" + meta.getText()); return t; });
            shell.addView(r); }
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
            case "tokens": case "quota-calendar": return version(p, 1) && p.optJSONArray("accounts") != null;
            case "tibo": return version(p, 1) && p.optJSONArray("events") != null;
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
    /**
     * 总览 in one card: the three figures side by side (each opens its detail), then service health, snapshot time and
     * the reminder count as chips. Reminders get their own compact card only when there are any.
     */
    private static void overview(UI u, JSONObject p) {
        JSONObject s = obj(p, "summary");
        LinearLayout c = u.theme.card(u.root); c.setPadding(u.dp(10), u.dp(10), u.dp(10), u.dp(12));
        boolean wide = u.context.getResources().getConfiguration().fontScale < 1.3f;
        LinearLayout band = new LinearLayout(u.context); band.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL); c.addView(band);
        SpannableStringBuilder services = new SpannableStringBuilder(number(s, "servicesOnline"));
        if (!number(s, "servicesTotal").equals(UNKNOWN)) { int from = services.length(); services.append(" / ").append(number(s, "servicesTotal"));
            services.setSpan(new AbsoluteSizeSpan(16, true), from, services.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            services.setSpan(new ForegroundColorSpan(u.theme.muted), from, services.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); }
        u.figure(band, "在线服务", services, "已知 " + number(s, "servicesKnown"), "status", wide);
        u.figure(band, "云实例", number(s, "infrastructureInstances"), "基础设施", "infrastructure", wide);
        u.figure(band, "域名资产", number(s, "domainAssets"), "已登记", "domains", wide);
        FlowRow chips = new FlowRow(u.context, u.dp(6), u.dp(6)); chips.setPadding(u.dp(6), u.dp(10), u.dp(6), 0); c.addView(chips);
        Object online = s.opt("servicesOnline"), known = s.opt("servicesKnown"), total = s.opt("servicesTotal");
        if (online instanceof Number && known instanceof Number && total instanceof Number) {
            long down = ((Number) known).longValue() - ((Number) online).longValue(), unknown = ((Number) total).longValue() - ((Number) known).longValue();
            if (down <= 0 && unknown <= 0) u.pill(chips, "全部在线", u.theme.success);
            if (down > 0) u.pill(chips, down + " 项未在线", u.theme.danger);
            if (unknown > 0) u.pill(chips, unknown + " 项状态未知", u.theme.warning);
        } else u.pill(chips, "服务状态未知", u.theme.faint);
        JSONArray notices = arr(p, "notices");
        u.pill(chips, notices.length() == 0 ? "暂无提醒" : notices.length() + " 条提醒", notices.length() == 0 ? u.theme.success : u.theme.warning);
        String[] fresh = u.fresh(p, 600); u.pill(chips, fresh[0], fresh[1].equals("ok") ? u.theme.success : fresh[1].equals("warn") ? u.theme.warning : u.theme.faint);
        if (notices.length() == 0) return;
        LinearLayout list = u.card("待办提醒");
        for (int i = 0; i < limit(u, notices); i++) { JSONObject n = notices.optJSONObject(i); if (n == null) continue;
            String level = state(n, "severity"); u.notice(list, s(n, "title"), s(n, "detail"), "级别 " + level + (n.has("expiresAt") ? " · 到期 " + date(n, "expiresAt") : ""), level); }
    }
    /** Mirrors the infrastructure web page: instance and running counts with the monthly cost first, then instances. */
    private static void infrastructure(UI u, JSONObject p) {
        if (p.length() == 0) { u.message("基础设施", "当前快照未提供实例数据"); return; }
        u.freshness(p, 600); JSONObject cost = obj(p, "costSummary"); int total = 0, running = 0;
        for (JSONObject n : rows(p, "instances")) { total++; if (state(n, "state").matches("运行中|正常")) running++; }
        List<String> costs = new ArrayList<>(); for (JSONObject m : rows(cost, "totals")) costs.add(number(m, "amount") + " " + s(m, "currency"));
        LinearLayout c = u.card("概览");
        u.metrics(c, "实例", String.valueOf(total), "运行中", String.valueOf(running), "月成本", costs.isEmpty() ? UNKNOWN : String.join("\n", costs));
        u.row(c, "账单口径", flag(cost, "actualBill", "实际账单", "价格估算"));
        LinearLayout g = u.list("实例");
        for (JSONObject n : rows(p, "instances")) { JSONObject m = obj(n, "metrics"), price = obj(n, "monthlyPrice");
            u.item(g, s(n, "name"), state(n, "state"), join(s(n, "region"), "CPU " + unit(m, "cpuUsage", "%"), "内存 " + unit(m, "memoryUsage", "%"), "磁盘 " + unit(m, "diskUsage", "%")),
                "区域", s(n, "region"), "CPU 核数", s(n, "cpuCores"), "内存 GB", s(n, "memoryGb"), "磁盘 GB", s(n, "diskGb"),
                "CPU", unit(m, "cpuUsage", "%"), "内存", unit(m, "memoryUsage", "%"), "磁盘", unit(m, "diskUsage", "%"),
                "月价", money(price, "amount", "currency"), "到期", date(n, "expiresAt")); }
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
    /** Mirrors the Visit Observer web page: range figures with all-time totals, the daily trend, sites, then regions. */
    private static void observer(UI u, JSONObject p) {
        u.sources(obj(p, "sourceStatus"), "overview", "访问数据", "siteStatus", "站点探测", "analytics", "趋势数据");
        JSONObject metrics = obj(p, "metrics"), range = obj(metrics, "selectedRange"), all = obj(metrics, "allTime"), sites = obj(metrics, "sites");
        String days = number(obj(p, "range"), "days");
        LinearLayout c = u.card(days.equals(UNKNOWN) ? "所选时段" : "近 " + days + " 天");
        u.metrics(c, "访问", number(range, "visitCount"), "事件", number(range, "eventCount"), "站点在线", number(sites, "online") + " / " + number(sites, "total"),
            "累计访问", number(all, "visitCount"), "累计事件", number(all, "eventCount"), "已检查", number(sites, "checked"));
        JSONArray daily = arr(p, "daily");
        if (daily.length() > 1) { double[] v = new double[daily.length()];
            for (int i = 0; i < v.length; i++) { JSONObject d = daily.optJSONObject(i); Object x = d == null ? null : d.opt("visitCount"); v[i] = x instanceof Number ? ((Number) x).doubleValue() : Double.NaN; }
            u.trend(c, "每日访问", v, dayLabel(daily.optJSONObject(0), "day"), dayLabel(daily.optJSONObject(daily.length() - 1), "day")); }
        LinearLayout g = u.list("站点");
        for (JSONObject n : rows(p, "sites")) u.item(g, s(n, "label"), flag(n, "online", "在线", "离线"),
            join(s(n, "hostname"), "访问 " + number(n, "visitCount"), "事件 " + number(n, "eventCount"), n.opt("httpStatus") instanceof Number ? "HTTP " + number(n, "httpStatus") : "", unit(n, "latencyMs", " ms")),
            "站点", s(n, "hostname"), "访问", number(n, "visitCount"), "事件", number(n, "eventCount"), "DNS", flag(n, "dnsResolved", "已解析", "未解析"),
            "HTTP", number(n, "httpStatus"), "延迟", unit(n, "latencyMs", " ms"), "健康", flag(n, "online", "在线", "离线"));
        empty(u, p, "sites", "暂无站点");
        if (arr(p, "regions").length() > 0) { g = u.list("地区");
            for (JSONObject n : rows(p, "regions")) { String region = s(n, "region"), code = s(n, "countryCode");
                u.item(g, region.equals(UNKNOWN) ? code : region, unit(n, "visitCount", " 次访问"), region.equals(UNKNOWN) || code.equals(UNKNOWN) ? "" : code); } }
    }
    private static void umami(UI u, JSONObject p) {
        for (JSONObject n : rows(p, "sites")) { LinearLayout c = u.card(s(n, "name")); u.paragraph(c, s(n, "hostname"));
            JSONObject status = obj(n, "sourceStatus"), current = obj(obj(n, "summary"), "current"); u.row(c, "汇总来源", flag(status, "summary", "可用", "不可用"));
            u.metrics(c, "浏览量", number(current, "pageviews"), "访客", number(current, "visitors"), "访问", number(current, "visits"), "跳出", number(current, "bounces"));
            if (arr(n, "paths").length() > 0) { u.subhead(c, "热门路径"); for (JSONObject item : rows(n, "paths")) u.row(c, s(item, "path"), unit(item, "visitorCount", " 位访客")); }
            if (arr(n, "sources").length() > 0) { u.subhead(c, "来源"); for (JSONObject item : rows(n, "sources")) u.row(c, s(item, "hostname"), unit(item, "visitorCount", " 位访客")); }
        }
        empty(u, p, "sites", "暂无统计站点");
    }
    private static void posthog(UI u, JSONObject p) {
        u.sources(obj(p, "sourceStatus"), "eventTrend", "事件趋势", "funnel", "转化漏斗", "webVitals", "Web Vitals");
        JSONObject summary = obj(p, "summary"); LinearLayout c = u.card(s(obj(p, "project"), "label"));
        u.metrics(c, "可见事件", number(summary, "visibleEvents"), "可用板块", number(summary, "availableSections"),
            "较差体验指标", number(summary, "poorVitalCount"), "漏斗转化", unit(summary, "funnelConversionPercent", "%"));
        if (arr(obj(p, "eventTrend"), "series").length() > 0) { u.subhead(c, "事件趋势"); for (JSONObject n : rows(obj(p, "eventTrend"), "series")) u.row(c, s(n, "label"), number(n, "visibleTotal")); }
        JSONObject funnel = obj(p, "funnel");
        if (funnel.length() > 0) { c = u.card(s(funnel, "label")); for (JSONObject n : rows(funnel, "steps"))
            u.row(c, s(n, "label"), Boolean.TRUE.equals(n.opt("suppressed")) ? "小样本隐藏" : number(n, "count")); }
        JSONObject vitals = obj(p, "webVitals");
        if (vitals.length() > 0) { c = u.card("Web Vitals"); for (JSONObject n : rows(vitals, "metrics"))
            u.row(c, s(n, "key"), unit(obj(n, "latest"), "value", " " + s(n, "unit")) + " · " + state(obj(n, "latest"), "rating")); }
    }
    /** Mirrors the OpenAI web page: period figures, the daily token trend, the 7-day insight block, then models. */
    private static void openai(UI u, JSONObject p) {
        JSONObject totals = obj(p, "totals"), cost = obj(obj(p, "costStatus"), "total"), ins = obj(p, "insights");
        String days = number(p, "periodDays");
        LinearLayout c = u.card(days.equals(UNKNOWN) ? "组织用量" : "近 " + days + " 天用量");
        u.metrics(c, "Token", sum(totals, "inputTokens", "outputTokens"), "请求", number(totals, "requests"),
            "成本 USD", Boolean.TRUE.equals(cost.opt("available")) ? number(totals, "costsUsd") : "暂不可用",
            "输入 Token", number(totals, "inputTokens"), "输出 Token", number(totals, "outputTokens"), "缓存输入 Token", number(totals, "cachedInputTokens"));
        JSONArray daily = arr(p, "daily");
        if (daily.length() > 1) { double[] v = new double[daily.length()];
            for (int i = 0; i < v.length; i++) { JSONObject d = daily.optJSONObject(i); Object in = d == null ? null : d.opt("inputTokens"), out = d == null ? null : d.opt("outputTokens");
                v[i] = in instanceof Number && out instanceof Number ? ((Number) in).doubleValue() + ((Number) out).doubleValue() : Double.NaN; }
            u.trend(c, "每日 Token", v, epochDay(daily.optJSONObject(0)), epochDay(daily.optJSONObject(daily.length() - 1))); }
        u.row(c, "用量 / 费用口径", flag(obj(p, "scope"), "directlyComparable", "可直接比较", "口径不同"));
        u.row(c, "当前周期", flag(obj(p, "scope"), "currentBucketMayBePartial", "可能尚未完整", "完整"));
        u.row(c, "缓存状态", state(obj(p, "cacheState"), "state"));
        if (ins.length() > 0) {
            LinearLayout k = u.card("近 7 个完整日");
            u.row(k, "Token", change(obj(ins, "tokens7d")));
            u.row(k, "请求", change(obj(ins, "requests7d")));
            u.row(k, "成本 USD", change(obj(ins, "costs7d")));
            u.row(k, "缓存输入占比", unit(ins, "cachedInputShare", "%"));
            JSONObject peak = obj(ins, "peakDay"); if (peak.length() > 0) u.row(k, "用量最高日", epochDay(peak) + " · " + number(peak, "tokens") + " Token");
            JSONObject top = obj(ins, "topModel"); if (top.length() > 0) u.row(k, "主要模型", s(top, "label") + " · " + unit(top, "share", "%"));
        }
        LinearLayout g = u.list("模型用量");
        for (JSONObject n : rows(p, "models")) u.item(g, s(n, "label"), unit(n, "requests", " 次请求"), "输入 " + number(n, "inputTokens") + " / 输出 " + number(n, "outputTokens"));
        empty(u, p, "models", "暂无模型用量");
        // apiKeys and raw identifiers are deliberately never rendered.
    }
    /** "12,345 · 上升 18%" for one 7-day insight; unavailable comparisons say so instead of showing zero. */
    private static String change(JSONObject x) {
        String current = number(x, "current"), trend;
        switch (x.optString("trend")) { case "up": trend = "上升"; break; case "down": trend = "下降"; break; case "flat": trend = "持平"; break;
            case "new_activity": trend = "新出现"; break; case "partial": trend = "数据不完整"; break; default: trend = "暂无对比"; }
        String pct = x.opt("changePercent") instanceof Number ? " " + new java.text.DecimalFormat("0.#").format(Math.abs(((Number) x.opt("changePercent")).doubleValue())) + "%" : "";
        return current.equals(UNKNOWN) ? trend : current + " · " + trend + (x.optString("trend").matches("up|down") ? pct : "");
    }
    private static String sum(JSONObject p, String a, String b) {
        Object x = p.opt(a), y = p.opt(b); if (!(x instanceof Number) || !(y instanceof Number)) return UNKNOWN;
        NumberFormat f = NumberFormat.getNumberInstance(Locale.SIMPLIFIED_CHINESE); f.setMaximumFractionDigits(0); return f.format(((Number) x).doubleValue() + ((Number) y).doubleValue());
    }
    /** "10-15" from a {startTime: epoch seconds} bucket. */
    private static String epochDay(JSONObject d) {
        if (d == null || !(d.opt("startTime") instanceof Number)) return "";
        return DateTimeFormatter.ofPattern("MM-dd", Locale.SIMPLIFIED_CHINESE).withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(((Number) d.opt("startTime")).longValue()));
    }
    /** "10-15" from a {day: "2026-10-15"} row. */
    private static String dayLabel(JSONObject d, String key) {
        String v = d == null ? "" : d.optString(key); return v.matches("\\d{4}-\\d{2}-\\d{2}.*") ? v.substring(5, 10) : "";
    }
    private static void domains(UI u, JSONObject p) {
        u.sources(obj(p, "sources"), "dns", "DNS", "registration", "注册", "pricing", "价格", "icp", "备案");
        JSONObject sum = obj(p, "summary");
        u.metrics(u.card("域名概览"), "域名", number(sum, "domains"), "账号内注册", number(sum, "registered"), "DNS 管理", number(sum, "dnsManaged"), "60 天内到期", number(sum, "expiringWithin60Days"));
        LinearLayout g = u.list("域名");
        for (JSONObject n : rows(p, "domains")) { JSONObject r = obj(n, "registration"); String left = unit(r, "expiryDays", " 天");
            u.item(g, s(n, "name"), left.equals(UNKNOWN) ? state(r, "status") : "剩余 " + left,
                join("到期 " + s(r, "expiresAt"), flag(r, "autoRenew", "自动续费", "未开自动续费"), flag(obj(n, "dns"), "managed", "DNS 已管理", "DNS 未管理")),
                "注册", state(r, "status"), "到期", s(r, "expiresAt"), "剩余", left, "自动续费", flag(r, "autoRenew", "已开启", "未开启"),
                "年续费参考", money(obj(r, "annualRenewalCost"), "amount", "currency"), "DNS", flag(obj(n, "dns"), "managed", "已管理", "未管理"),
                "备案匹配", flag(obj(n, "icp"), "matched", "已匹配", "未匹配，需官方核验")); }
        empty(u, p, "domains", "暂无域名");
    }
    private static void maintenance(UI u, JSONObject p) {
        LinearLayout c = u.card("维护标记"); u.row(c, "一致性", flag(p, "stateHealthy", "正常", "需核对"));
        LinearLayout g = u.list("站点");
        for (JSONObject n : rows(p, "sites")) u.item(g, s(n, "label"), state(n, "state"), join(s(n, "hostname"), "更新 " + date(n, "updatedAt")),
            "站点", s(n, "hostname"), "状态", state(n, "state"), "影响", s(n, "impact"), "更新", date(n, "updatedAt"));
        empty(u, p, "sites", "暂无站点");
    }
    private static void market(UI u, JSONObject p) {
        u.sources(obj(p, "sourceStatus"), "watchlist", "关注行情", "history", "趋势", "sectors", "板块", "benchmarks", "基准", "aiUpstream", "AI 产业链");
        String[][] groups = {{"watchlist", "关注行情"}, {"sectors", "板块"}, {"benchmarks", "市场基准"}};
        for (String[] key : groups) { if (arr(p, key[0]).length() == 0) continue; LinearLayout g = u.list(key[1]);
            for (JSONObject n : rows(p, key[0])) u.item(g, s(n, "label"), number(n, "price") + (n.has("currency") ? " " + s(n, "currency") : ""),
                join("涨跌 " + unit(n, "changePct", "%"), n.has("updatedAt") ? "行情 " + date(n, "updatedAt") : "")); }
        for (JSONObject group : rows(p, "aiUpstream")) { LinearLayout c = u.card(s(group, "name")); for (JSONObject n : rows(group, "companies"))
            u.row(c, s(n, "name"), Boolean.TRUE.equals(n.opt("quoteAvailable")) ? number(n, "price") + " " + s(n, "currency") : "暂无报价"); }
        u.explain(s(obj(p, "disclaimer"), "text"));
    }
    private static void okx(UI u, JSONObject p) {
        LinearLayout c = u.card("账户摘要");
        u.metrics(c, "总权益 USD", number(p, "totalEquityUsd"), "未实现盈亏 USD", number(p, "unrealizedPnlUsd"), "名义价值 USD", number(p, "notionalUsd"),
            "保证金比率", number(p, "marginRatio"), "持仓数", number(p, "openPositionCount"));
        u.row(c, "账户更新时间", date(p, "accountUpdatedAt"));
        if (arr(p, "assets").length() > 0) { LinearLayout g = u.list("资产");
            for (JSONObject n : rows(p, "assets")) u.item(g, s(n, "currency"), unit(n, "usdValue", " USD"),
                join("权益 " + number(n, "equity"), "可用 " + number(n, "available"), "冻结 " + number(n, "frozen")),
                "权益", number(n, "equity"), "可用", number(n, "available"), "冻结", number(n, "frozen"), "估值 USD", number(n, "usdValue")); }
        if (arr(p, "positions").length() > 0) { LinearLayout g = u.list("持仓");
            for (JSONObject n : rows(p, "positions")) u.item(g, s(n, "instrument"), state(n, "side"),
                join("数量 " + number(n, "quantity"), "标记价 " + number(n, "markPrice"), "未实现 " + number(n, "unrealizedPnl")),
                "方向", state(n, "side"), "数量", number(n, "quantity"), "开仓均价", number(n, "averageEntryPrice"), "标记价格", number(n, "markPrice"),
                "未实现盈亏", number(n, "unrealizedPnl"), "杠杆", number(n, "leverage")); }
    }
    /** Mirrors the 源码与工程 web page: projects, then the iteration log. */
    private static void engineering(UI u, JSONObject p) {
        JSONObject coverage = obj(p, "coverage");
        u.metrics(u.card("工程概览"), "项目", String.valueOf(arr(p, "repositoryIndex").length()), "日志", String.valueOf(arr(p, "items").length()),
            "覆盖", Boolean.TRUE.equals(coverage.opt("truncated")) ? "已截断" : "完整");
        if (Boolean.TRUE.equals(coverage.opt("truncated"))) u.note("日志已截断：当前返回 " + number(coverage, "returnedItems") + " 条，并非全部历史。");
        if (arr(p, "repositoryIndex").length() > 0) { LinearLayout g = u.list("全部项目");
            for (JSONObject n : rows(p, "repositoryIndex")) u.item(g, s(n, "name"), state(n, "state"), s(n, "description")); }
        LinearLayout g = u.list("工程迭代日志");
        for (JSONObject n : rows(p, "items")) u.item(g, s(n, "summary"), "", join(s(n, "repository"), date(n, "timestamp")));
        empty(u, p, "items", "暂无迭代日志");
    }
    private static void clipboard(UI u, JSONObject p) {
        int publicCount = 0, total = 0; for (JSONObject n : rows(p, "items")) { total++; if (Boolean.TRUE.equals(n.opt("isPublic"))) publicCount++; }
        LinearLayout c = u.card("当前内容"); u.metrics(c, "条数", String.valueOf(total), "公开", String.valueOf(publicCount), "私密", String.valueOf(total - publicCount));
        u.row(c, "来源", source(obj(p, "sourceStatus")));
        LinearLayout g = u.list("剪贴内容"); int i = 0;
        for (JSONObject n : rows(p, "items")) u.item(g, "剪贴内容 " + (++i), flag(n, "isPublic", "公开", "私密"), join("创建 " + date(n, "createdAt"), "到期 " + date(n, "expiresAt")),
            "可见性", flag(n, "isPublic", "公开", "私密"), "创建", date(n, "createdAt"), "到期", date(n, "expiresAt"));
        empty(u, p, "items", "暂无剪贴内容");
        u.explain("只显示元数据：应用不请求、不显示剪贴内容正文。");
    }
    private static void ledger(UI u, JSONObject p) {
        if (p.optJSONArray("projects") != null) {
            LinearLayout g = u.list("账本"); for (JSONObject n : rows(p, "projects")) u.item(g, s(n, "name"), "", "选择账本后查看账目");
            empty(u, p, "projects", "暂无可用账本"); return;
        }
        LinearLayout c = u.card(s(obj(p, "project"), "name")); JSONObject summary = obj(p, "summary");
        u.amount(c, "人民币参考合计", unit(summary, "cnyTotal", " CNY"));
        u.metrics(c, "账目", number(summary, "entryCount"), "退款", number(summary, "refundCount"), "汇率覆盖", flag(summary, "cnyComplete", "完整", "部分币种缺失"));
        if (arr(summary, "totals").length() > 0) { LinearLayout g = u.list("币种合计");
            for (JSONObject n : rows(summary, "totals")) u.item(g, s(n, "currency"), "净支出 " + s(n, "net"), join("支出 " + s(n, "spent"), "退款 " + s(n, "refunded")),
                "支出", s(n, "spent"), "退款", s(n, "refunded"), "净支出", s(n, "net")); }
        if (arr(p, "members").length() > 0) { LinearLayout g = u.list("成员分摊");
            for (JSONObject n : rows(p, "members")) { List<String> lines = new ArrayList<>(); List<String> details = new ArrayList<>();
                for (JSONObject total : rows(n, "totals")) { String cur = s(total, "currency");
                    lines.add(cur + " 已付 " + s(total, "paid") + " · 差额 " + s(total, "net"));
                    details.add(cur + " 已付"); details.add(s(total, "paid")); details.add(cur + " 应分摊 / 差额"); details.add(s(total, "expense") + " / " + s(total, "net")); }
                u.item(g, s(n, "displayName"), "", String.join("\n", lines), details.toArray(new String[0])); }
            u.explain("差额是账本计算结果，不代表实际还款。"); }
        LinearLayout g = u.list("账目");
        for (JSONObject n : rows(p, "entries")) u.item(g, s(n, "merchant").equals(UNKNOWN) ? s(n, "category") : s(n, "merchant"), money(n, "amount", "currency"),
            join(s(n, "category"), s(n, "payer"), s(n, "occurredAt")),
            "金额", money(n, "amount", "currency"), "退款", money(n, "refundedAmount", "currency"), "净额", money(n, "netAmount", "currency"),
            "类别", s(n, "category"), "付款人", s(n, "payer"), "发生时间", s(n, "occurredAt"));
        empty(u, p, "entries", "本页没有账目");
        JSONObject page = obj(p, "page"); u.note("总计 " + number(page, "total") + " 条 · " + flag(page, "hasMore", "还有下一页", "已到末页"));
    }
    private static void cards(UI u, JSONObject p) {
        int total = 0, custom = 0; for (JSONObject n : rows(p, "cards")) { total++; if (Boolean.TRUE.equals(n.opt("customized"))) custom++; }
        u.metrics(u.card("卡片概览"), "卡片", String.valueOf(total), "自定义", String.valueOf(custom), "默认", String.valueOf(total - custom));
        LinearLayout g = u.list("卡片");
        for (JSONObject n : rows(p, "cards")) u.item(g, s(n, "title"), flag(n, "customized", "自定义", "默认"), join(s(n, "description"), "更新 " + date(n, "updatedAt")));
        empty(u, p, "cards", "暂无卡片");
        u.explain("编辑、二维码和分享仍在网页管理端。");
    }
    /** Mirrors the 博客转公众号 web page: the account figures first, then created drafts and published articles. */
    private static void publishing(UI u, JSONObject p) {
        JSONObject caps = obj(p, "capabilities"), drafts = obj(caps, "drafts"), menu = obj(caps, "menu"), pub = obj(p, "published");
        LinearLayout c = u.card("公众号资产概览");
        u.metrics(c, "草稿", Boolean.TRUE.equals(drafts.opt("available")) ? number(drafts, "count") : "暂不可用",
            "菜单", Boolean.TRUE.equals(menu.opt("available")) ? number(menu, "count") : "暂不可用",
            "已发布", Boolean.TRUE.equals(pub.opt("available")) ? number(pub, "totalCount") : "暂不可用");
        u.row(c, "公众号配置", flag(p, "wechatConfigured", "已配置", "未配置"));
        if (arr(p, "history").length() > 0) { LinearLayout g = u.list("已创建草稿"); for (JSONObject n : rows(p, "history")) u.item(g, s(n, "title"), "", "创建 " + date(n, "createdAt")); }
        if (arr(p, "articles").length() > 0) { LinearLayout g = u.list("已发布文章"); for (JSONObject n : rows(p, "articles")) u.item(g, s(n, "title"), "", "发布 " + date(n, "publishedAt")); }
    }
    /** Mirrors the 万用格数据同步 web page: overall figures, then each source. */
    private static void catalog(UI u, JSONObject p) {
        int sources = 0; double items = 0; boolean itemsKnown = true;
        for (JSONObject n : rows(p, "tools")) { sources++; Object k = n.opt("itemCount"); if (k instanceof Number) items += ((Number) k).doubleValue(); else itemsKnown = false; }
        LinearLayout c = u.card("目录同步");
        u.metrics(c, "版本", number(p, "catalogVersion"), "来源", String.valueOf(sources), "项目合计", itemsKnown ? String.valueOf((long) items) : UNKNOWN);
        u.row(c, "来源更新", date(p, "sourceGeneratedAt")); u.row(c, "个人资料", flag(obj(p, "profile"), "configured", "已配置", "未配置"));
        LinearLayout g = u.list("来源状态");
        for (JSONObject n : rows(p, "tools")) u.item(g, s(n, "title"), state(n, "state"), join("项目 " + number(n, "itemCount"), "来源 " + s(obj(n, "source"), "label")));
        empty(u, p, "tools", "暂无目录");
    }
    /** Mirrors the 微信登录日志 web page: binding, sessions and results as figures, then the event record. */
    private static void audit(UI u, JSONObject p) {
        JSONObject b = obj(p, "binding"), sum = obj(p, "summary"); LinearLayout c = u.card("概览");
        u.metrics(c, "管理员绑定", flag(b, "configured", "已绑定", "未绑定"), "活跃会话", number(b, "activeSessionCount"), "待处理绑定", flag(b, "pending", "有", "无"),
            "登录成功", number(sum, "successCount"), "登录拒绝", number(sum, "deniedCount"), "返回事件", number(sum, "returnedEventCount"));
        LinearLayout g = u.list("事件记录");
        for (JSONObject n : rows(p, "events")) u.item(g, state(n, "type"), state(n, "result"), join(date(n, "occurredAt"), n.isNull("reason") ? "" : "原因 " + state(n, "reason")));
        empty(u, p, "events", "暂无登录事件");
        u.explain("事件已脱敏；首次绑定等管理操作仍在网页管理端。");
    }
    private static void prompts(UI u, JSONObject p) {
        LinearLayout c = u.card("Kimi 资产日报"); u.metrics(c, "模型", s(p, "model"), "版本", s(p, "version"), "来源", state(p, "source"));
        u.row(c, "任务", s(p, "task")); u.row(c, "更新时间", date(p, "updatedAt"));
        u.explain("模板正文、版本差异与保存仍在网页管理端。");
        // Template, hashes, receipts and request fingerprints stay outside the metadata screen.
    }
    private static void images(UI u, JSONObject p) {
        JSONObject status = obj(p, "status"), inventory = obj(p, "inventory"); LinearLayout c = u.card("图片工坊状态");
        u.row(c, "凭据配置", flag(status, "credentialConfigured", "已配置", "未配置")); u.row(c, "处理服务", flag(status, "processingAvailable", "可用", "不可用"));
        u.metrics(c, "图片总数", number(inventory, "total"), "当前返回", number(inventory, "returned"));
        if (Boolean.TRUE.equals(inventory.opt("truncated"))) u.paragraph(c, "库存已截断");
        if (arr(inventory, "assets").length() > 0) { LinearLayout g = u.list("图片");
            for (JSONObject n : rows(inventory, "assets")) { List<String> details = new ArrayList<>(); int count = 0;
                for (JSONObject d : rows(n, "derivatives")) { count++; details.add(state(d, "role")); details.add(number(d, "width") + " × " + number(d, "height") + " · " + unit(d, "bytes", " B")); }
                u.item(g, "图片 " + number(n, "sequence"), "", join("更新 " + date(n, "updatedAt"), count + " 个派生图"), details.toArray(new String[0])); } }
    }
    private static void reports(UI u, JSONObject p) {
        LinearLayout g = u.list("报告");
        for (JSONObject n : rows(p, "reports")) u.item(g, n.isNull("title") ? s(n, "hostname") : s(n, "title"), state(n, "status"),
            join(s(n, "hostname"), "访问 " + number(n, "visitCount"), "事件 " + number(n, "eventCount"), date(n, "createdAt")),
            "站点", s(n, "hostname"), "状态", state(n, "status"), "访问", number(n, "visitCount"), "事件", number(n, "eventCount"), "生成时间", date(n, "createdAt"));
        empty(u, p, "reports", "暂无归档报告");
    }
    /**
     * Token 额度, as the 账号额度 grid of tokens.robby.wang: no summary card, connected accounts only (never the two ChatGPT
     * entries the web keeps out), grouped OpenAI / Claude / Grok / Cursor / TodayAI in stable slots. TodayAI arrives in
     * the additive additionalAccounts array beside the original nine accounts.
     */
    private static void tokens(UI u, JSONObject p) {
        long now = System.currentTimeMillis(); ZoneId zone = ZoneId.systemDefault();
        List<JSONObject> all = tokenAccounts(p);
        JSONObject build = null; for (JSONObject a : all) if ("grok".equals(a.optString("id"))) build = a;
        Map<String, List<JSONObject>> sections = new LinkedHashMap<>(); for (String s : TokensModel.SECTIONS) sections.put(s, new ArrayList<>());
        for (JSONObject a : all) if (TokensModel.shownOnPage(a)) sections.get(TokensModel.section(a)).add(a);
        int shown = 0;
        for (Map.Entry<String, List<JSONObject>> e : sections.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            e.getValue().sort(TokensModel.SLOT_ORDER); u.heading(e.getKey(), null);
            for (JSONObject a : e.getValue()) { tokenTile(u, a, sharedPlan(a, build), e.getKey(), now, zone, p.optJSONObject("tiboReset")); shown++; }
        }
        if (shown == 0) { LinearLayout c = u.theme.card(u.root); TextView t = u.text("还没有已接入的账号", 15, false); t.setTextColor(u.theme.muted); c.addView(t); }
    }
    /** The original nine accounts followed by the additive additionalAccounts (TodayAI), as the web combines them. */
    static List<JSONObject> tokenAccounts(JSONObject p) {
        List<JSONObject> out = new ArrayList<>(); for (JSONObject a : rows(p, "accounts")) out.add(a); for (JSONObject a : rows(p, "additionalAccounts")) out.add(a); return out;
    }
    /** Grok Bot signed in with Grok Build's email shares Build's subscription, as the web shows it. */
    private static String sharedPlan(JSONObject a, JSONObject build) {
        if (build == null || !"grok-bot".equals(a.optString("id")) || a.optJSONObject("subscription") != null || build.optJSONObject("subscription") == null) return null;
        String bot = s(obj(a, "profile"), "email"), main = s(obj(build, "profile"), "email");
        return bot.equals(UNKNOWN) || !bot.equalsIgnoreCase(main) ? null : TokensModel.plan(build);
    }
    private static int tokenTint(UI u, String section) {
        switch (section) {
            case "OpenAI": return u.theme.accent;
            case "Claude": return u.theme.dark ? 0xffe39a7e : 0xffb8532f;
            case "Grok": return u.theme.dark ? 0xffb19cf5 : 0xff6a4cc0;
            case "Cursor": case "TodayAI": return u.theme.dark ? 0xff6fcfab : 0xff1d7f5f;
            default: return u.theme.ink;
        }
    }
    /** One account tile: identity and value, price and plan, balances, pace, the hour's curve, resets and 重置卡明细. */
    private static void tokenTile(UI u, JSONObject a, String sharedPlan, String section, long now, ZoneId zone, JSONObject tibo) {
        boolean fresh = "connected".equals(TokensModel.effectiveStatus(a, now)); int tint = fresh ? tokenTint(u, section) : u.theme.muted;
        LinearLayout c = u.theme.card(u.root); c.setPadding(u.dp(16), u.dp(14), u.dp(16), u.dp(14));
        LinearLayout top = new LinearLayout(u.context); top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout who = new LinearLayout(u.context); who.setOrientation(LinearLayout.VERTICAL);
        TextView title = u.text(TokensModel.displayName(a), 15, true); title.setTextColor(u.theme.ink); title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END); title.setAccessibilityHeading(true); who.addView(title);
        LinearLayout product = new LinearLayout(u.context); product.setGravity(Gravity.CENTER_VERTICAL); product.setPadding(0, u.dp(3), 0, 0);
        product.addView(u.theme.dot(fresh ? tint : u.theme.faint, 7));
        TextView name = u.text(TokensModel.productName(a), 13, false); name.setTextColor(fresh ? tint : u.theme.muted); name.setPadding(u.dp(6), 0, 0, 0); product.addView(name); who.addView(product);
        top.addView(who, new LinearLayout.LayoutParams(0, -2, 1));
        String headline = TokensModel.headline(a, now);
        TextView value = u.text(headline, headline.endsWith("%") ? 24 : 16, true); value.setTextColor(tint); value.setPadding(u.dp(10), 0, 0, 0); top.addView(value); c.addView(top);
        LinearLayout meta = new LinearLayout(u.context); meta.setPadding(0, u.dp(6), 0, 0);
        TextView price = u.text(TokensModel.monthly(a), 13, false); price.setTextColor(u.theme.muted); meta.addView(price, new LinearLayout.LayoutParams(0, -2, 1));
        TextView plan = u.text(sharedPlan != null ? sharedPlan : TokensModel.plan(a), 13, false); plan.setTextColor(u.theme.muted); meta.addView(plan); c.addView(meta);
        List<String[]> blocks = TokensModel.balances(a, now);
        if (!blocks.isEmpty()) {
            LinearLayout b = new LinearLayout(u.context); b.setPadding(0, u.dp(10), 0, 0);
            for (String[] block : blocks) {
                LinearLayout col = new LinearLayout(u.context); col.setOrientation(LinearLayout.VERTICAL);
                TextView l = u.text(block[0], 11, false); l.setTextColor(u.theme.faint); col.addView(l);
                TextView v = u.text(block[1], 16, true); v.setTextColor(u.theme.ink); col.addView(v); b.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            }
            c.addView(b);
        }
        // An official Tibo announcement takes the pace badge's place on Codex tiles, counting down as on the web.
        TiboChallenge.Post signal = "codex".equals(a.optString("product")) && tibo != null && ("scheduled".equals(tibo.optString("state")) || "announced".equals(tibo.optString("state"))) ? new TiboChallenge.Post(tibo) : null;
        String pace = TokensModel.pace(a, now);
        if (signal != null && signal.url != null) { LinearLayout row = new LinearLayout(u.context); row.setPadding(0, u.dp(10), 0, 0); u.pill(row, signal.cards() ? "Tibo 重置卡发放倒计时" : "Tibo 额度重置倒计时", u.theme.danger);
            TextView count = u.text("", 13, true); count.setTextColor(u.theme.danger); count.setPadding(u.dp(8), 0, 0, 0); row.setGravity(Gravity.CENTER_VERTICAL); row.addView(count);
            if (signal.deadline > 0) TokensPages.tick(count, at -> TiboChallenge.countdown(signal.deadline, at)); else count.setText("已预告，时间未公布"); c.addView(row); }
        else if (pace != null) { LinearLayout row = new LinearLayout(u.context); row.setPadding(0, u.dp(10), 0, 0); u.pill(row, pace, "即将重置".equals(pace) ? u.theme.danger : u.theme.warning); c.addView(row); }
        tokenTrend(u, c, a, fresh, tint);
        for (String line : TokensModel.resetLines(a, zone, now)) { TextView t = u.text(line, 12, false); t.setTextColor(u.theme.muted); t.setPadding(0, u.dp(4), 0, 0); c.addView(t); }
        TokensModel.Cards cards = TokensModel.cards(a, zone, now);
        if (cards != null) {
            View divider = new View(u.context); divider.setBackgroundColor(u.theme.line); LinearLayout.LayoutParams d = new LinearLayout.LayoutParams(-1, Math.max(1, u.dp(.5f))); d.topMargin = u.dp(12); c.addView(divider, d);
            TextView h = u.text(cards.heading, 14, true); h.setTextColor(u.theme.ink); h.setPadding(0, u.dp(10), 0, u.dp(2)); c.addView(h);
            for (String note : cards.notes) { TextView t = u.text(note, 12, false); t.setTextColor(u.theme.muted); t.setPadding(0, u.dp(2), 0, 0); c.addView(t); }
            for (String[] item : cards.items) {
                LinearLayout row = new LinearLayout(u.context); row.setPadding(0, u.dp(8), 0, 0);
                TextView n = u.text(item[0], 13, true); n.setTextColor(u.theme.ink); row.addView(n, new LinearLayout.LayoutParams(0, -2, 1));
                TextView st = u.text(item[1], 12, false); st.setTextColor(u.theme.muted); row.addView(st); c.addView(row);
                TextView ex = u.text(item[2], 12, false); ex.setTextColor(u.theme.ink); c.addView(ex);
                TextView gr = u.text(item[3], 12, false); gr.setTextColor(u.theme.faint); c.addView(gr);
            }
            if (cards.pending != null) { TextView t = u.text(cards.pending, 12, false); t.setTextColor(u.theme.muted); t.setPadding(0, u.dp(6), 0, 0); c.addView(t); }
        }
    }
    /** 近 1 小时 curve: Claude's is its 5-hour balance; points accounts draw points on their own scale. */
    private static void tokenTrend(UI u, LinearLayout c, JSONObject a, boolean fresh, int tint) {
        JSONObject trend = a.optJSONObject("usageTrend"); if (trend == null) return;
        JSONArray points = trend.optJSONArray("balancePoints"); int n = points == null ? 0 : points.length(), count = 0;
        boolean percentMetric = !"points".equals(trend.optString("metric")); double latest = Double.NaN; double[] values = new double[n];
        for (int i = 0; i < n; i++) { JSONObject pt = points.optJSONObject(i); Object v = pt == null ? null : pt.opt("remaining"); values[i] = v instanceof Number ? ((Number) v).doubleValue() : Double.NaN; if (!Double.isNaN(values[i])) { latest = values[i]; count++; } }
        LinearLayout head = new LinearLayout(u.context); head.setPadding(0, u.dp(12), 0, 0);
        TextView label = u.text("近 1 小时 " + ("claude".equals(a.optString("product")) ? "5小时" : "额度"), 12, false); label.setTextColor(u.theme.muted); head.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        String right = count >= 2 ? "剩余" + TokensModel.number(latest) + (percentMetric ? "%" : " 点") : "collecting".equals(trend.optString("status")) ? "采集中" : "暂无曲线";
        TextView r = u.text(right, 12, true); r.setTextColor(fresh ? tint : u.theme.muted); head.addView(r); c.addView(head);
        if (count >= 2) { MiniLine line = new MiniLine(u.context, values, percentMetric, fresh ? tint : u.theme.faint, u.theme.line, "近 1 小时剩余曲线，" + right);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, u.dp(36)); lp.topMargin = u.dp(4); c.addView(line, lp); }
    }
    /** How old a snapshot is by its generatedAt, the same clock as the 快照 pill; unknown counts as old. */
    static long ageMillis(JSONObject p) {
        Object generated = p.opt("generatedAt");
        if (!(generated instanceof String)) return Long.MAX_VALUE;
        try { return System.currentTimeMillis() - Instant.parse((String) generated).toEpochMilli(); } catch (RuntimeException e) { return Long.MAX_VALUE; }
    }
    private static Double percent(JSONObject w) { Object v = w.opt("remainingPercent"); return v instanceof Number ? ((Number) v).doubleValue() : null; }
    private static long millis(JSONObject p, String key) { Object v = p.opt(key); if (!(v instanceof String)) return 0; try { return Instant.parse((String) v).toEpochMilli(); } catch (RuntimeException e) { return 0; } }
    private static String clock(JSONObject p) { long ms = p == null ? 0 : millis(p, "at"); return ms == 0 ? "" : DateTimeFormatter.ofPattern("HH:mm", Locale.SIMPLIFIED_CHINESE).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms)); }
    private static String unitLabel(String unit) {
        switch (unit) { case "tokens": return " Token"; case "requests": return " 次"; case "messages": return " 条"; case "credits": return " 点"; case "USD": return " USD"; default: return ""; }
    }
    private static void secrets(UI u, JSONObject p) {
        int total = 0, configured = 0; for (JSONObject n : rows(p, "services")) { total++; if (Boolean.TRUE.equals(n.opt("configured"))) configured++; }
        u.metrics(u.card("配置概览"), "服务", String.valueOf(total), "已配置", String.valueOf(configured), "未配置", String.valueOf(total - configured));
        LinearLayout g = u.list("服务");
        for (JSONObject n : rows(p, "services")) u.item(g, s(n, "label"), flag(n, "configured", "已配置", "未配置"), "更新 " + date(n, "updatedAt"));
        empty(u, p, "services", "暂无服务");
        u.explain("只显示配置状态，不返回凭据值；写入与轮换仍在网页管理端。");
    }
    /** " · "-joined summary of the parts that carry a value; unknown parts stay visible in the expanded details. */
    private static String join(String... parts) {
        StringBuilder b = new StringBuilder();
        for (String part : parts) { if (part == null || part.isEmpty() || part.equals(UNKNOWN) || part.endsWith(" " + UNKNOWN) || part.endsWith(" " + UNKNOWN + "%")) continue;
            if (b.length() > 0) b.append(" · "); b.append(part); }
        return b.toString();
    }

    private static JSONObject obj(JSONObject p, String key) { JSONObject o = p.optJSONObject(key); return o == null ? new JSONObject() : o; }
    private static JSONArray arr(JSONObject p, String key) { JSONArray a = p.optJSONArray(key); return a == null ? new JSONArray() : a; }
    private static Iterable<JSONObject> rows(JSONObject p, String key) {
        java.util.List<JSONObject> out = new java.util.ArrayList<>(); JSONArray a = arr(p, key);
        for (int i = 0; i < Math.min(MAX_ROWS, a.length()); i++) if (a.optJSONObject(i) != null) out.add(a.optJSONObject(i));
        return out;
    }
    private static int limit(UI u, JSONArray a) { if (a.length() > MAX_ROWS) u.message("显示范围", "当前最多显示 " + MAX_ROWS + " 项"); return Math.min(MAX_ROWS, a.length()); }
    private static void empty(UI u, JSONObject p, String key, String text) { if (arr(p, key).length() == 0) u.emptyLine(text); }
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
        LinearLayout card(String title) {LinearLayout c=theme.card(root);TextView heading=text(title,15,true);heading.setAccessibilityHeading(true);heading.setPadding(0,0,0,dp(4));c.addView(heading);return c;}
        /** Section heading plus one grouped list card. */
        LinearLayout list(String title){heading(title,null);return group();}
        void subhead(LinearLayout c,String title){TextView t=text(title,12,false);t.setTextColor(theme.faint);t.setPadding(0,dp(10),0,dp(2));c.addView(t);}
        /** Explanation without status or figures: kept for the page's ⓘ instead of a paragraph. */
        final List<String> explanations = new ArrayList<>();
        void explain(String value){if(value!=null&&!value.isEmpty()&&!value.equals(UNKNOWN))explanations.add(value);}
        void note(String value){if(value==null||value.isEmpty()||value.equals(UNKNOWN))return;TextView t=text(value,12,false);t.setTextColor(theme.faint);t.setPadding(dp(6),dp(2),dp(6),dp(10));root.addView(t);}
        void emptyLine(String value){LinearLayout c=group();TextView t=text(value,14,false);t.setGravity(Gravity.CENTER);t.setPadding(dp(12),dp(14),dp(12),dp(14));c.addView(t);}
        /** Short figures as a grid: three columns normally, two with large text; values wrap instead of shrinking. */
        void metrics(LinearLayout c,String... pairs){
            boolean wide=context.getResources().getConfiguration().screenWidthDp>=360&&context.getResources().getConfiguration().fontScale<1.3f;int columns=wide?3:2;
            LinearLayout band=null;
            for(int i=0;i+1<pairs.length;i+=2){int index=i/2;
                if(index%columns==0){band=new LinearLayout(context);band.setOrientation(LinearLayout.HORIZONTAL);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);bp.topMargin=dp(index==0?4:8);c.addView(band,bp);}
                LinearLayout cell=new LinearLayout(context);cell.setOrientation(LinearLayout.VERTICAL);cell.setPadding(0,0,dp(8),0);
                cell.addView(text(pairs[i],12,false));TextView v=text(pairs[i+1],17,true);v.setPadding(0,dp(1),0,0);cell.addView(v);cell.setContentDescription(pairs[i]+" "+pairs[i+1]);
                band.addView(cell,new LinearLayout.LayoutParams(0,-2,1));}
            if(band!=null)for(int k=band.getChildCount();k<columns;k++)band.addView(new View(context),new LinearLayout.LayoutParams(0,1,1));
        }
        /**
         * Compact list row: title, trailing value, one summary line. Label/value pairs, when given, expand in place.
         * A status word in the trailing value gets its colour dot; the word itself always stays visible.
         */
        void item(LinearLayout group,String title,String trailing,String meta,String... details){
            if(group.getChildCount()>0){View d=new View(context);d.setBackgroundColor(border);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(.5f));lp.setMargins(dp(14),0,dp(12),0);group.addView(d,lp);}
            LinearLayout box=new LinearLayout(context);box.setOrientation(LinearLayout.VERTICAL);
            LinearLayout head=new LinearLayout(context);head.setGravity(Gravity.CENTER_VERTICAL);head.setPadding(dp(14),dp(9),dp(10),dp(9));head.setMinimumHeight(dp(48));
            int tone=theme.status(trailing);
            LinearLayout texts=new LinearLayout(context);texts.setOrientation(LinearLayout.VERTICAL);TextView t=text(title,15,false);t.setTextColor(theme.ink);texts.addView(t);
            if(meta!=null&&!meta.isEmpty()){TextView m=text(meta,12,false);m.setPadding(0,dp(2),0,0);texts.addView(m);}
            head.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
            if(trailing!=null&&!trailing.isEmpty()){LinearLayout right=new LinearLayout(context);right.setGravity(Gravity.CENTER_VERTICAL);right.setPadding(dp(10),0,0,0);
                if(tone!=0){View dot=theme.dot(tone,6);((LinearLayout.LayoutParams)dot.getLayoutParams()).rightMargin=dp(5);right.addView(dot);}
                TextView v=text(trailing,14,true);v.setGravity(Gravity.END);right.addView(v);head.addView(right);}
            box.addView(head);
            String description=title+(trailing==null||trailing.isEmpty()?"":"，"+trailing)+(meta==null||meta.isEmpty()?"":"，"+meta);head.setContentDescription(description);
            if(details.length>=2){
                LinearLayout body=details();body.setPadding(dp(14),0,dp(16),dp(8));for(int i=0;i+1<details.length;i+=2)detail(body,details[i],details[i+1],StatusPage.NONE);
                String key=title+"|"+meta;boolean open=OPEN.contains(key);body.setVisibility(open?View.VISIBLE:View.GONE);box.addView(body);
                ImageView chevron=theme.icon("chevron",theme.muted,12);chevron.setAlpha(.4f);chevron.setRotation(open?-90:90);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(12),dp(12));cp.leftMargin=dp(6);head.addView(chevron,cp);
                head.setBackground(theme.ripple(theme.shape(Color.TRANSPARENT,16),16));head.setFocusable(true);head.setClickable(true);head.setStateDescription(open?"已展开":"已收起");
                head.setOnClickListener(v->{boolean show=body.getVisibility()!=View.VISIBLE;if(show)OPEN.add(key);else OPEN.remove(key);body.setVisibility(show?View.VISIBLE:View.GONE);head.setStateDescription(show?"已展开":"已收起");
                    if(theme.effects)chevron.animate().rotation(show?-90:90).setDuration(200).start();else chevron.setRotation(show?-90:90);});
            }
            group.addView(box);
        }
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
        /** One overview figure column; opens {@code target} when the host can navigate. */
        void figure(LinearLayout band,String label,CharSequence value,String caption,String target,boolean wide){
            LinearLayout f=new LinearLayout(context);f.setOrientation(LinearLayout.VERTICAL);f.setPadding(dp(8),dp(6),dp(6),dp(6));
            f.addView(text(label,12,false));TextView v=text("",24,true);v.setText(value);v.setPadding(0,dp(2),0,dp(1));f.addView(v);TextView cap=text(caption,11,false);cap.setTextColor(theme.faint);f.addView(cap);
            if(context instanceof Opener&&FeatureCatalog.find(target)!=null){f.setBackground(theme.ripple(theme.shape(Color.TRANSPARENT,14),14));f.setFocusable(true);f.setClickable(true);f.setOnClickListener(x->((Opener)context).openFeature(target));theme.press(f);
                f.setContentDescription(label+" "+value+"，"+caption+"，查看"+FeatureCatalog.find(target).title);}
            band.addView(f,wide?new LinearLayout.LayoutParams(0,-2,1):new LinearLayout.LayoutParams(-1,-2));
        }
        void pill(android.view.ViewGroup parent,String label,int color){
            LinearLayout chip=new LinearLayout(context);chip.setGravity(Gravity.CENTER_VERTICAL);chip.setPadding(dp(9),dp(4),dp(10),dp(4));chip.setBackground(theme.shape(theme.soft,99));
            chip.addView(theme.dot(color,6));TextView t=text(label,12,false);t.setTextColor(theme.ink);t.setPadding(dp(5),0,0,0);chip.addView(t);parent.addView(chip);
        }
        /** Daily bars with the first and last day under them; used where the web page shows a trend chart. */
        void trend(LinearLayout c,String title,double[] values,String first,String last){
            subhead(c,title);double peak=0,latest=Double.NaN;for(double v:values)if(!Double.isNaN(v)){peak=Math.max(peak,v);latest=v;}
            NumberFormat f=NumberFormat.getNumberInstance(Locale.SIMPLIFIED_CHINESE);f.setMaximumFractionDigits(0);
            String description=title+"，"+values.length+" 天，最高 "+f.format(peak)+(Double.isNaN(latest)?"":"，最近 "+f.format(latest));
            MiniBars bars=new MiniBars(context,values,theme.accent,theme.line,description);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(56));bp.topMargin=dp(4);c.addView(bars,bp);
            LinearLayout axis=new LinearLayout(context);TextView a=text(first,11,false);a.setTextColor(theme.faint);axis.addView(a,new LinearLayout.LayoutParams(0,-2,1));
            TextView m=text("最高 "+f.format(peak),11,false);m.setTextColor(theme.faint);m.setGravity(Gravity.CENTER);axis.addView(m,new LinearLayout.LayoutParams(0,-2,1));
            TextView b=text(last,11,false);b.setTextColor(theme.faint);b.setGravity(Gravity.END);axis.addView(b,new LinearLayout.LayoutParams(0,-2,1));axis.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);c.addView(axis);
        }
        /** Large figure with a one-line caption under it, for 看板 cards. */
        void big(CharSequence value,String caption){TextView v=text("",24,true);v.setText(value);v.setPadding(0,dp(8),0,0);root.addView(v);TextView c=text(caption,12,false);c.setPadding(0,dp(1),0,0);root.addView(c);}
        void chip(LinearLayout parent,String label,int color){
            LinearLayout chip=new LinearLayout(context);chip.setGravity(Gravity.CENTER_VERTICAL);chip.setPadding(dp(10),dp(5),dp(12),dp(5));chip.setBackground(theme.shape(theme.soft,99));
            chip.addView(theme.dot(color,7));TextView t=text(label,13,true);t.setPadding(dp(6),0,0,0);chip.addView(t);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.rightMargin=dp(8);lp.bottomMargin=dp(6);parent.addView(chip,lp);
        }
        /** One reminder line inside a card: severity dot, title, detail and meta. The level stays as text. */
        void notice(LinearLayout c,String title,String detail,String meta,String level){
            if(c.getChildCount()>1)divider(c);
            LinearLayout r=new LinearLayout(context);r.setPadding(0,dp(7),0,dp(7));
            int color=theme.status(level);View dot=theme.dot(color==0?theme.accent:color,7);LinearLayout.LayoutParams dp8=(LinearLayout.LayoutParams)dot.getLayoutParams();dp8.topMargin=dp(6);dp8.rightMargin=dp(10);r.addView(dot,dp8);
            LinearLayout texts=new LinearLayout(context);texts.setOrientation(LinearLayout.VERTICAL);texts.addView(text(title,14,true));
            if(!detail.equals(UNKNOWN)){TextView d=text(detail,13,false);d.setPadding(0,dp(2),0,0);texts.addView(d);}
            TextView m=text(meta,11,false);m.setTextColor(theme.faint);m.setPadding(0,dp(2),0,0);texts.addView(m);r.addView(texts,new LinearLayout.LayoutParams(0,-2,1));c.addView(r);
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
            LinearLayout r=new LinearLayout(context);r.setTag("row");r.setOrientation(stack?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER_VERTICAL);r.setMinimumHeight(dp(38));r.setPadding(0,dp(6),0,dp(6));
            TextView k=text(label,13,false),v=text(value,14,true);v.setTextDirection(android.view.View.TEXT_DIRECTION_FIRST_STRONG);v.setGravity(stack?Gravity.START:Gravity.END);
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
        /** Data sources as one wrapping chip row; each chip keeps its name and state text. */
        void sources(JSONObject p, String... pairs) {
            LinearLayout c=theme.card(root);c.setPadding(dp(14),dp(10),dp(14),dp(10));TextView h=text("数据来源",12,false);h.setTextColor(theme.faint);h.setPadding(0,0,0,dp(6));c.addView(h);
            FlowRow flow=new FlowRow(context,dp(6),dp(6));
            for(int i=0;i+1<pairs.length;i+=2){String value=source(obj(p,pairs[i]));int tone=theme.status(value);
                LinearLayout chip=new LinearLayout(context);chip.setGravity(Gravity.CENTER_VERTICAL);chip.setPadding(dp(9),dp(4),dp(10),dp(4));chip.setBackground(theme.shape(theme.soft,99));
                if(tone!=0){View dot=theme.dot(tone,6);((LinearLayout.LayoutParams)dot.getLayoutParams()).rightMargin=dp(5);chip.addView(dot);}
                TextView t=text(pairs[i+1]+" "+value,12,false);t.setTextColor(theme.ink);chip.addView(t);chip.setContentDescription(pairs[i+1]+"，"+value);flow.addView(chip);}
            c.addView(flow);
        }
        /** Snapshot age as {text, "ok" | "warn" | "none"}; stale or partial sources never read as fresh. */
        String[] fresh(JSONObject p, int maxSeconds) {
            String state = "更新时间未知", tone = "none"; Object generated = p.opt("generatedAt");
            if (generated instanceof String) try {
                long age = java.time.Duration.between(Instant.parse((String) generated), Instant.now()).getSeconds();
                state = age < -5 ? "快照时间异常" : age > maxSeconds ? "快照已过期" : "快照 " + date(p, "generatedAt");
                tone = age < -5 || age > maxSeconds ? "warn" : "ok";
            } catch (RuntimeException ignored) { state = "更新时间异常"; tone = "warn"; }
            if (Boolean.TRUE.equals(p.opt("stale"))) { state += " · 来源数据陈旧"; tone = "warn"; }
            if (Boolean.TRUE.equals(p.opt("partial"))) { state += " · 部分数据"; tone = "warn"; }
            return new String[]{state, tone};
        }
        void freshness(JSONObject p, int maxSeconds) {
            String[] f = fresh(p, maxSeconds); int tone = f[1].equals("ok") ? theme.success : f[1].equals("warn") ? theme.warning : theme.faint;
            LinearLayout pill = new LinearLayout(context); pill.setGravity(Gravity.CENTER_VERTICAL); pill.setPadding(dp(10), dp(5), dp(12), dp(5)); pill.setBackground(theme.glass(-1));
            pill.addView(theme.dot(tone, 7)); TextView label = text(f[0].startsWith("快照 ") ? "快照时间 " + f[0].substring(3) : f[0], 12, false); label.setPadding(dp(6), 0, 0, 0); pill.addView(label);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.bottomMargin = dp(10); lp.leftMargin = dp(2); root.addView(pill, lp);
        }
    }
}
