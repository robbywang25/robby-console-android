package wang.robby.console.android;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.Instant;
import java.util.List;
import org.json.JSONObject;

/** 微信消息: summary, daily report and the board. Conversation names stay on this page and the overview card. */
final class WechatPages {
    private WechatPages() {}

    static void render(Context ctx, LinearLayout root, JSONObject payload) {
        ConsoleTheme t = new ConsoleTheme(ctx);
        WechatDigest.Doc d = WechatDigest.parseResponse(payload);
        if (d == null) {
            LinearLayout c = t.card(root); TextView m = t.text(WechatDigest.INVALID, 15, false); m.setTextColor(t.muted); c.addView(m);
            return;
        }
        Instant now = Instant.now();
        TextView head = t.text(WechatDigestText.header(d, now), 12, false); head.setTextColor(t.faint); head.setPadding(t.dp(6), t.dp(4), t.dp(6), t.dp(2)); root.addView(head);
        heading(t, root, "汇总");
        LinearLayout summary = t.card(root);
        window(ctx, t, summary, "今天", d.today, 0);
        window(ctx, t, summary, "近 7 天", d.last7d, 12);
        window(ctx, t, summary, "近 30 天", d.last30d, 12);
        heading(t, root, "日报");
        LinearLayout report = t.card(root);
        TextView title = t.text(d.reportTitle, 16, true); title.setAccessibilityHeading(true); report.addView(title);
        for (int i = 0; i < d.reportItems.size(); i++) { TextView item = t.text(d.reportItems.get(i), 14, false); item.setPadding(0, t.dp(i == 0 ? 8 : 6), 0, 0); report.addView(item); }
        heading(t, root, "看板");
        daily(ctx, t, root, d);
        hourly(ctx, t, root, d);
        types(ctx, t, root, d);
        conversations(ctx, t, root, d, now);
        domains(ctx, t, root, d);
    }

    /** Overview card body: today's count, the 7-day average, and the busiest conversation. */
    static void card(Context ctx, LinearLayout shell, JSONObject payload) {
        ConsoleTheme t = new ConsoleTheme(ctx);
        WechatDigest.Doc d = WechatDigest.parseResponse(payload);
        if (d == null) { TextView m = t.text(WechatDigest.INVALID, 13, false); m.setTextColor(t.faint); m.setPadding(0, t.dp(10), 0, t.dp(2)); shell.addView(m); return; }
        String name = d.conversations.isEmpty() ? null : d.conversations.get(0).name;
        TextView value = t.text(WechatDigestText.count(d.today.messages), 24, true); value.setPadding(0, t.dp(8), 0, 0); shell.addView(value);
        TextView caption = t.text(WechatDigestText.boardCaption(d.last7d.messages, name), 12, false);
        caption.setPadding(0, t.dp(1), 0, 0); caption.setSingleLine(true); caption.setEllipsize(TextUtils.TruncateAt.END); shell.addView(caption);
    }

    private static void window(Context ctx, ConsoleTheme t, LinearLayout card, String label, WechatDigest.Window w, int top) {
        LinearLayout row = new LinearLayout(ctx); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, t.dp(top), 0, 0);
        TextView name = t.text(label, 14, false); name.setTextColor(t.ink); row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(t.text(WechatDigestText.count(w.messages), 20, true));
        String detail = "发出 " + WechatDigestText.count(w.sent) + " / 收到 " + WechatDigestText.count(w.received) + " / " + WechatDigestText.count(w.conversations) + " 个会话";
        row.setContentDescription(label + " " + WechatDigestText.count(w.messages) + " 条，" + detail);
        card.addView(row);
        TextView sub = t.text(detail, 12, false); sub.setTextColor(t.faint); sub.setPadding(0, t.dp(2), 0, 0); card.addView(sub);
    }
    private static void daily(Context ctx, ConsoleTheme t, LinearLayout root, WechatDigest.Doc d) {
        LinearLayout c = t.card(root); subhead(t, c, "近 30 天每日消息");
        long[] values = new long[d.daily.size()]; for (int i = 0; i < values.length; i++) values[i] = d.daily.get(i).messages;
        int peak = WechatDigestText.peakIndex(values);
        String description = "近 30 天每日消息，最高 " + WechatDigestText.count(values[peak]) + "，今天 " + WechatDigestText.count(d.today.messages);
        c.addView(new WechatBars(ctx, WechatDigestText.normalize(values), t.accent, t.line, values.length - 1, description), chart(t, 96));
        axis(ctx, t, c, WechatDigestText.dayLabel(d.daily.get(0).date), "最高 " + WechatDigestText.count(values[peak]), "今天");
    }
    private static void hourly(Context ctx, ConsoleTheme t, LinearLayout root, WechatDigest.Doc d) {
        LinearLayout c = t.card(root); subhead(t, c, "24 小时分布");
        int peak = WechatDigestText.peakIndex(d.hourly);
        String description = "近 30 天 24 小时分布，最高 " + WechatDigestText.count(d.hourly[peak]) + "，在 " + peak + " 时";
        c.addView(new WechatBars(ctx, WechatDigestText.normalize(d.hourly), t.accent, t.line, -1, description), chart(t, 72));
        axis(ctx, t, c, "0时", "12时", "23时");
    }
    private static void types(Context ctx, ConsoleTheme t, LinearLayout root, WechatDigest.Doc d) {
        LinearLayout c = t.card(root); subhead(t, c, "消息类型");
        List<WechatDigestText.Share> shares = WechatDigestText.shares(d.types);
        for (int i = 0; i < shares.size(); i++) {
            WechatDigestText.Share s = shares.get(i);
            LinearLayout row = new LinearLayout(ctx); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, t.dp(i == 0 ? 8 : 7), 0, 0);
            TextView name = t.text(s.name, 13, false); name.setTextColor(t.ink); row.addView(name, new LinearLayout.LayoutParams(t.dp(40), -2));
            LinearLayout track = new LinearLayout(ctx); track.setBackground(t.shape(t.soft, 8));
            View fill = new View(ctx); fill.setBackground(t.shape(t.accent, 8));
            track.addView(fill, new LinearLayout.LayoutParams(0, t.dp(8), Math.max(s.tenths, 0)));
            if (s.tenths < 1000) track.addView(new View(ctx), new LinearLayout.LayoutParams(0, t.dp(8), 1000 - s.tenths));
            row.addView(track, new LinearLayout.LayoutParams(0, t.dp(8), 1));
            TextView pct = t.text(s.percent, 12, false); pct.setGravity(Gravity.END); pct.setPadding(t.dp(8), 0, 0, 0); row.addView(pct, new LinearLayout.LayoutParams(t.dp(52), -2));
            row.setContentDescription(s.name + " " + WechatDigestText.count(s.count) + "，" + s.percent);
            c.addView(row);
        }
    }
    private static void conversations(Context ctx, ConsoleTheme t, LinearLayout root, WechatDigest.Doc d, Instant now) {
        LinearLayout c = t.card(root); subhead(t, c, "最活跃会话");
        if (d.conversations.isEmpty()) { TextView empty = t.text("近 7 天没有会话", 14, false); empty.setPadding(0, t.dp(8), 0, 0); c.addView(empty); return; }
        for (WechatDigest.Conversation item : d.conversations) {
            LinearLayout row = new LinearLayout(ctx); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, t.dp(8), 0, 0);
            TextView name = t.text(item.name, 15, true); name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView n = t.text(WechatDigestText.count(item.messages7d), 15, true); n.setPadding(t.dp(8), 0, 0, 0); row.addView(n);
            String meta = WechatDigestText.conversationMeta(item.kind, item.sent7d, item.lastAt, now);
            row.setContentDescription(item.name + "，" + WechatDigestText.kindLabel(item.kind) + "，近 7 天 " + WechatDigestText.count(item.messages7d) + " 条，发出 " + WechatDigestText.count(item.sent7d) + "，" + WechatDigestText.ago(item.lastAt, now));
            c.addView(row);
            TextView sub = t.text(meta, 12, false); sub.setTextColor(t.faint); sub.setPadding(0, t.dp(1), 0, 0); c.addView(sub);
        }
    }
    private static void domains(Context ctx, ConsoleTheme t, LinearLayout root, WechatDigest.Doc d) {
        LinearLayout c = t.card(root); subhead(t, c, "链接域名");
        if (d.domains.isEmpty()) { TextView empty = t.text("近 7 天没有链接", 14, false); empty.setPadding(0, t.dp(8), 0, 0); c.addView(empty); return; }
        for (int i = 0; i < d.domains.size(); i++) {
            WechatDigest.Domain item = d.domains.get(i);
            LinearLayout row = new LinearLayout(ctx); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, t.dp(i == 0 ? 8 : 6), 0, 0);
            TextView name = t.text(item.domain, 14, false); name.setTextColor(t.ink); name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView n = t.text(WechatDigestText.count(item.count), 14, true); n.setPadding(t.dp(8), 0, 0, 0); row.addView(n);
            row.setContentDescription(item.domain + "，" + WechatDigestText.count(item.count));
            c.addView(row);
        }
        TextView foot = t.text("近 7 天 " + WechatDigestText.count(d.linkCount7d) + " 个链接", 12, false); foot.setTextColor(t.faint); foot.setPadding(0, t.dp(8), 0, 0); c.addView(foot);
    }
    private static void heading(ConsoleTheme t, LinearLayout root, String title) {
        TextView h = t.text(title, 15, true); h.setAccessibilityHeading(true); h.setPadding(t.dp(6), t.dp(12), 0, t.dp(8)); root.addView(h);
    }
    private static void subhead(ConsoleTheme t, LinearLayout card, String title) {
        TextView h = t.text(title, 15, true); h.setAccessibilityHeading(true); card.addView(h);
    }
    private static LinearLayout.LayoutParams chart(ConsoleTheme t, int dp) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, t.dp(dp)); p.topMargin = t.dp(4); return p; }
    private static void axis(Context ctx, ConsoleTheme t, LinearLayout card, String left, String middle, String right) {
        LinearLayout axis = new LinearLayout(ctx);
        TextView a = t.text(left, 11, false); a.setTextColor(t.faint); axis.addView(a, new LinearLayout.LayoutParams(0, -2, 1));
        TextView b = t.text(middle, 11, false); b.setTextColor(t.faint); b.setGravity(Gravity.CENTER); axis.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        TextView c = t.text(right, 11, false); c.setTextColor(t.faint); c.setGravity(Gravity.END); axis.addView(c, new LinearLayout.LayoutParams(0, -2, 1));
        axis.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS); card.addView(axis);
    }
}
