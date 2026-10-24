package wang.robby.console.android;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.ref.WeakReference;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The UU 远程 page: whether UU 远程 can take God's links, the computers bound to this phone, and a button to bind one
 * more. A row's 远控 opens that computer full screen; tapping the row offers 小窗 (picture-in-picture, which reports
 * back), renaming and unbinding. The preview build shows two synthetic computers and opens nothing unless started with
 * {@code preview_real_uu}.
 */
final class UURemotePage {
    private UURemotePage() {}
    private static WeakReference<LinearLayout> shown = new WeakReference<>(null);
    /** The stale computer a 重新绑定 replaces once the new bind succeeds; process memory only. */
    private static String replacing;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M月d日");

    static void refresh() { LinearLayout c = shown.get(); if (c != null && c.isAttachedToWindow()) render(c.getContext(), c); }
    private static boolean fixture() { return PreviewMode.isBuild() && !PreviewMode.realUU(); }
    private static List<UURemote.Device> devices(Context ctx) { return fixture() ? PreviewMode.uuDevices() : UURemote.devices(ctx); }

    static void render(Context ctx, LinearLayout container) {
        shown = new WeakReference<>(container); container.removeAllViews();
        ConsoleTheme t = new ConsoleTheme(ctx);
        String version = fixture() ? "4.42.0" : UURemote.installedVersion(ctx); boolean ready = fixture() || UURemote.ready(ctx);
        app(ctx, t, container, version, ready);
        computers(ctx, t, container, ready);
        container.addView(t.info(List.of(
            "绑定时 God 会打开 UU 远程，你在里面选一台电脑；UU 远程只交回一个加密的设备标识，God 拿不到电脑的名称、地址或 UU 账号。",
            "只能绑定和远控 Windows 与 Mac 电脑，需要 UU 远程 " + UURemote.MIN_VERSION + " 或更高版本。电脑上要登录同一个 UU 账号并允许被控。",
            "「远控」直接全屏打开；点一台电脑可以选「小窗远控」，远控画面以画中画浮在桌面上，结果会回到 God。",
            "设备标识只保存在这台手机上 God 的私有目录里，不经过 God 服务器，不随账号同步，也不随系统备份。UU 远程换账号后要重新绑定。",
            "接入方式来自网易 UU 远程开源的接入说明 netease-uuyc/uuremote-integration-guide。")));
    }

    private static void app(Context ctx, ConsoleTheme t, LinearLayout parent, String version, boolean ready) {
        LinearLayout c = t.card(parent);
        LinearLayout top = new LinearLayout(ctx); top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(t.tile("desktop", 36), new LinearLayout.LayoutParams(t.dp(36), t.dp(36)));
        LinearLayout texts = new LinearLayout(ctx); texts.setOrientation(LinearLayout.VERTICAL); texts.setPadding(t.dp(12), 0, t.dp(8), 0);
        texts.addView(t.text("UU 远程", 16, true));
        TextView state = t.text(version == null ? "这台手机还没有安装 UU 远程" : ready ? "已安装 " + version + (fixture() ? " · 预览版不打开 UU 远程" : "")
            : UURemote.compareVersions(version, UURemote.MIN_VERSION) < 0 ? "已安装 " + version + " · 需要 " + UURemote.MIN_VERSION + " 或更高" : "已安装 " + version + " · 暂时无法接收 God 的请求", 12, false);
        state.setTextColor(ready ? t.success : t.warning); texts.addView(state);
        top.addView(texts, new LinearLayout.LayoutParams(0, -2, 1)); c.addView(top);
        if (ready) return;
        Button store = t.button(version == null ? "安装 UU 远程" : "更新 UU 远程", () -> UURemote.openStore(ctx)); t.button(store, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = t.dp(12); c.addView(store, lp);
    }

    private static void computers(Context ctx, ConsoleTheme t, LinearLayout parent, boolean ready) {
        List<UURemote.Device> all = devices(ctx);
        LinearLayout c = t.card(parent); TextView h = t.text("我的电脑" + (all.isEmpty() ? "" : " · " + all.size()), 15, true); h.setAccessibilityHeading(true); c.addView(h);
        if (all.isEmpty()) { TextView e = t.text("还没有绑定电脑。绑定后在这里一键远控。", 14, false); e.setPadding(0, t.dp(8), 0, t.dp(4)); c.addView(e); }
        for (UURemote.Device d : all) row(ctx, t, c, d, ready);
        Button bind = t.button("绑定电脑", () -> { replacing = null; bind(ctx); }); t.button(bind, all.isEmpty()); bind.setEnabled(ready);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = t.dp(12); c.addView(bind, lp);
    }

    private static void row(Context ctx, ConsoleTheme t, LinearLayout card, UURemote.Device d, boolean ready) {
        View divider = new View(ctx); divider.setBackgroundColor(t.line); LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, t.dp(.5f)); dl.topMargin = t.dp(8); card.addView(divider, dl);
        LinearLayout r = new LinearLayout(ctx); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(0, t.dp(8), 0, 0); r.setMinimumHeight(t.dp(56));
        r.setBackground(t.ripple(t.shape(android.graphics.Color.TRANSPARENT, 14), 14)); r.setFocusable(true); r.setClickable(true);
        LinearLayout labels = new LinearLayout(ctx); labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = t.text(d.name, 16, false); name.setTextColor(t.ink); labels.addView(name);
        String last = UURemote.lastLabel(d.last), when = d.boundAt > 0 ? "绑定于 " + DAY.format(Instant.ofEpochMilli(d.boundAt).atZone(ZoneId.systemDefault())) : "";
        TextView sub = t.text(last.isEmpty() ? when : when.isEmpty() ? last : when + " · " + last, 12, false);
        sub.setTextColor(d.stale() || !d.last.isEmpty() && !"success".equals(d.last) && !"user_cancel".equals(d.last) ? t.warning : t.faint); sub.setPadding(0, t.dp(2), 0, 0); labels.addView(sub);
        r.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        Button go = t.button(d.stale() ? "重新绑定" : "远控", () -> { if (d.stale()) rebind(ctx, d); else control(ctx, d, UURemote.FULL_SCREEN); });
        go.setEnabled(ready); go.setContentDescription((d.stale() ? "重新绑定 " : "全屏远控 ") + d.name); go.setPadding(t.dp(16), 0, t.dp(16), 0);
        r.addView(go, new LinearLayout.LayoutParams(-2, t.dp(40)));
        r.setContentDescription(d.name + "，" + sub.getText());
        r.setOnClickListener(v -> menu(ctx, t, d, ready));
        card.addView(r);
    }

    private static void menu(Context ctx, ConsoleTheme t, UURemote.Device d, boolean ready) {
        String[] items = ready ? new String[]{"全屏远控", "小窗远控", "重新绑定", "重命名", "解除绑定"} : new String[]{"重命名", "解除绑定"};
        show(t, new AlertDialog.Builder(ctx).setTitle(d.name).setNegativeButton("取消", null).setItems(items, (dlg, w) -> {
            switch (items[w]) {
                case "全屏远控" -> control(ctx, d, UURemote.FULL_SCREEN);
                case "小窗远控" -> control(ctx, d, UURemote.PICTURE_IN_PICTURE);
                case "重新绑定" -> rebind(ctx, d);
                case "重命名" -> rename(ctx, t, d, "重命名");
                default -> show(t, new AlertDialog.Builder(ctx).setTitle("解除绑定「" + d.name + "」？").setMessage("只删除这台手机上保存的设备标识，UU 远程和电脑上的设置不受影响。之后可以再绑定。")
                    .setNegativeButton("取消", null).setPositiveButton("解除绑定", (x, y) -> { if (!fixture()) UURemote.remove(ctx, d.key); refresh(); }));
            }
        }));
    }

    private static void bind(Context ctx) {
        if (fixture()) { toast(ctx, "预览版不打开 UU 远程"); return; }
        if (!UURemote.bind(ctx)) toast(ctx, "无法打开 UU 远程");
    }
    private static void rebind(Context ctx, UURemote.Device d) { replacing = d.key; bind(ctx); }
    private static void control(Context ctx, UURemote.Device d, int windowType) {
        if (fixture()) { toast(ctx, "预览版不打开 UU 远程"); return; }
        if (!UURemote.control(ctx, d, windowType)) toast(ctx, "无法打开 UU 远程");
    }
    private static void rename(Context ctx, ConsoleTheme t, UURemote.Device d, String title) {
        LinearLayout b = new LinearLayout(ctx); b.setOrientation(LinearLayout.VERTICAL); b.setPadding(t.dp(20), t.dp(8), t.dp(20), 0);
        EditText e = new EditText(ctx); t.input(e); e.setText(d.name); e.setSingleLine(true); e.setInputType(InputType.TYPE_CLASS_TEXT); e.setSelectAllOnFocus(true);
        e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(20)}); b.addView(e, new LinearLayout.LayoutParams(-1, -2));
        show(t, new AlertDialog.Builder(ctx).setTitle(title).setView(b).setNegativeButton("取消", null).setPositiveButton("保存", (x, y) -> {
            String n = e.getText().toString().trim(); if (!n.isEmpty() && !fixture()) UURemote.rename(ctx, d.key, n); refresh(); }));
        e.requestFocus();
    }

    /** Tells the person what UU 远程 answered, on whatever page is in front. */
    static void announce(Context ctx, UURemote.Result r) {
        ConsoleTheme t = new ConsoleTheme(ctx); refresh();
        UURemote.Device d = r.device == null ? null : UURemote.device(ctx, r.device);
        if (r.bind) {
            if (!r.ok) { String old = replacing; replacing = null; if ("user_cancel".equals(r.code)) toast(ctx, "已取消绑定"); else show(t, new AlertDialog.Builder(ctx).setTitle(old == null ? "没有绑定成功" : "没有重新绑定成功").setMessage(UURemote.bindText(r.code)).setPositiveButton("知道了", null)); return; }
            if (d == null) return;
            String old = replacing; replacing = null; UURemote.Device prior = old == null || old.equals(d.key) ? null : UURemote.device(ctx, old);
            if (prior != null) { UURemote.remove(ctx, prior.key); UURemote.rename(ctx, d.key, prior.name); refresh(); toast(ctx, "已重新绑定「" + prior.name + "」"); return; }
            if ("new".equals(r.code)) rename(ctx, t, d, "给这台电脑起个名字"); else toast(ctx, "这台电脑已经绑定过了：「" + d.name + "」");
            return;
        }
        String name = d == null ? "电脑" : "「" + d.name + "」";
        if (r.ok) { toast(ctx, "已在小窗打开" + name); return; }
        if ("user_cancel".equals(r.code)) { toast(ctx, "已取消远控"); return; }
        AlertDialog.Builder b = new AlertDialog.Builder(ctx).setTitle("没能打开" + name).setMessage(UURemote.controlText(r.code)).setNegativeButton("知道了", null);
        if (d != null && "device_not_found".equals(r.code)) b.setPositiveButton("重新绑定", (x, y) -> rebind(ctx, d));
        else if (d != null && "pip_unsupported".equals(r.code)) b.setPositiveButton("改用全屏", (x, y) -> control(ctx, d, UURemote.FULL_SCREEN));
        else if (d != null && !"invalid_request".equals(r.code) && !"not_allowed".equals(r.code)) b.setPositiveButton("重试", (x, y) -> control(ctx, d, UURemote.PICTURE_IN_PICTURE));
        show(t, b);
    }
    private static void toast(Context ctx, String s) { Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show(); }
    private static void show(ConsoleTheme t, AlertDialog.Builder builder) { AlertDialog d = builder.create(); d.show(); t.dialog(d); }
}
