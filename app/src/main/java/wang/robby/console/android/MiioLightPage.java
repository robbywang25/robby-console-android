package wang.robby.console.android;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The 米家灯光 page: the lamp's state with a power button and brightness and colour temperature sliders, then the
 * connection (address, token, model) behind 设置. Every read and write runs on one background thread, so a slow or
 * unreachable lamp never blocks the page; the last reading stays in memory while God is open. The preview build shows
 * a synthetic lamp and sends nothing unless started with {@code preview_real_light}.
 */
final class MiioLightPage {
    private MiioLightPage() {}
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<LinearLayout> shown = new WeakReference<>(null);
    private static MiioLight.Reading reading; private static String error; private static boolean busy;

    private static boolean fixture() { return PreviewMode.isBuild() && !PreviewMode.realLight(); }
    private static boolean configured(Context ctx) { return fixture() || MiioLight.configured(ctx); }
    private static String name(Context ctx) { return fixture() ? "显示器挂灯" : MiioLight.name(ctx); }

    static void refresh() { LinearLayout c = shown.get(); if (c != null && c.isAttachedToWindow()) render(c.getContext(), c); }

    static void render(Context ctx, LinearLayout container) {
        shown = new WeakReference<>(container); container.removeAllViews();
        ConsoleTheme t = new ConsoleTheme(ctx);
        if (fixture() && reading == null) reading = PreviewMode.light();
        lamp(ctx, t, container); connection(ctx, t, container);
        container.addView(t.info(List.of(
            "God 和米家 App 一样，在家里的 Wi-Fi 里直接给灯发 miIO 指令（UDP 54321 端口，用 token 加密），不经过小米云，也不经过 God 服务器。",
            "所以只有手机和灯连着同一个 Wi-Fi 时才能控制；在外面打开这一页会提示灯没有回应。",
            "token 是这盏灯的 32 位十六进制密钥，需要你用米家账号自己取（例如 Xiaomi Cloud Tokens Extractor），God 不会登录你的米家账号。",
            "token 用 Android Keystore 加密保存在这台手机上 God 的私有目录里，只发给灯自己的地址，不同步、不备份。灯重新配网后 token 会变，要重新填写。",
            "God 按 MIoT 灯光服务读写开关、亮度和色温；不同型号的属性编号不一样，God 先读型号再选择。")));
        if (configured(ctx) && reading == null && error == null && !busy) load(ctx);
    }

    private static void lamp(Context ctx, ConsoleTheme t, LinearLayout parent) {
        LinearLayout c = t.card(parent);
        LinearLayout top = new LinearLayout(ctx); top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(t.tile("bulb", 36), new LinearLayout.LayoutParams(t.dp(36), t.dp(36)));
        LinearLayout texts = new LinearLayout(ctx); texts.setOrientation(LinearLayout.VERTICAL); texts.setPadding(t.dp(12), 0, t.dp(8), 0);
        TextView title = t.text(name(ctx), 16, true); title.setAccessibilityHeading(true); texts.addView(title);
        MiioLight.State s = reading == null ? null : reading.state;
        String line = !configured(ctx) ? "还没有设置这盏灯" : busy && s == null ? "正在连接…" : error != null ? error
            : s == null ? "" : (s.on ? "已开 · 亮度 " + s.brightness + "% · " + s.ct + "K" : "已关") + (busy ? " · 更新中…" : "");
        TextView state = t.text(line, 12, false); state.setTextColor(error != null ? t.warning : s != null && s.on ? t.success : t.faint);
        state.setAccessibilityLiveRegion(TextView.ACCESSIBILITY_LIVE_REGION_POLITE); texts.addView(state);
        top.addView(texts, new LinearLayout.LayoutParams(0, -2, 1)); c.addView(top);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = t.dp(12);
        if (!configured(ctx)) { Button setup = t.button("设置这盏灯", () -> setup(ctx, t)); t.button(setup, true); c.addView(setup, lp); return; }
        if (s == null) { if (error != null) { Button retry = t.button("重试", () -> load(ctx)); retry.setEnabled(!busy); c.addView(retry, lp); } return; }
        Button power = t.button(s.on ? "关灯" : "开灯", () -> set(ctx, 1, !s.on)); t.button(power, !s.on); power.setEnabled(!busy); c.addView(power, lp);
        MiioLight.Layout l = reading.layout;
        slider(ctx, t, c, "亮度", s.brightness, 1, 100, "%", s.on && !busy, v -> set(ctx, l.brightness, v));
        slider(ctx, t, c, "色温", s.ct, l.minCt, l.maxCt, "K", s.on && !busy, v -> set(ctx, l.ct, v));
    }

    private static void slider(Context ctx, ConsoleTheme t, LinearLayout card, String label, int value, int min, int max, String unit, boolean enabled, java.util.function.IntConsumer commit) {
        LinearLayout head = new LinearLayout(ctx); head.setPadding(0, t.dp(14), 0, t.dp(2));
        TextView name = t.text(label, 14, false); name.setTextColor(t.ink); head.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView shownValue = t.text(MiioLight.clamp(value, min, max) + unit, 14, false); head.addView(shownValue); card.addView(head);
        SeekBar bar = new SeekBar(ctx); bar.setMin(min); bar.setMax(max); bar.setProgress(MiioLight.clamp(value, min, max)); bar.setEnabled(enabled);
        bar.setProgressTintList(ColorStateList.valueOf(t.accent)); bar.setThumbTintList(ColorStateList.valueOf(t.accent)); bar.setContentDescription(label);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int v, boolean user) { String now = v + unit; shownValue.setText(now); }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) { commit.accept(b.getProgress()); }
        });
        card.addView(bar, new LinearLayout.LayoutParams(-1, t.dp(40)));
    }

    private static void connection(Context ctx, ConsoleTheme t, LinearLayout parent) {
        if (!configured(ctx)) return;
        LinearLayout c = t.card(parent); TextView h = t.text("连接", 15, true); h.setAccessibilityHeading(true); c.addView(h);
        String model = reading != null && reading.model != null ? reading.model : fixture() ? "yeelink.light.lamp22" : MiioLight.model(ctx);
        row(t, c, "地址", fixture() ? "192.168.1.x · 局域网" : MiioLight.address(ctx));
        row(t, c, "Token", "已加密保存");
        row(t, c, "型号", model == null ? "连接后读取" : model + (reading != null && reading.firmware != null ? " · 固件 " + reading.firmware : ""));
        LinearLayout buttons = new LinearLayout(ctx); buttons.setPadding(0, t.dp(12), 0, 0);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1); half.rightMargin = t.dp(8);
        buttons.addView(t.button("修改设置", () -> setup(ctx, t)), half);
        buttons.addView(t.button("移除这盏灯", () -> remove(ctx, t)), new LinearLayout.LayoutParams(0, -2, 1)); c.addView(buttons);
    }
    private static void row(ConsoleTheme t, LinearLayout card, String label, String value) {
        LinearLayout r = new LinearLayout(card.getContext()); r.setPadding(0, t.dp(8), 0, 0);
        r.addView(t.text(label, 14, false), new LinearLayout.LayoutParams(t.dp(64), -2));
        TextView v = t.text(value, 14, false); v.setTextColor(t.ink); r.addView(v, new LinearLayout.LayoutParams(0, -2, 1)); card.addView(r);
    }

    private static void setup(Context ctx, ConsoleTheme t) {
        boolean has = !fixture() && MiioLight.configured(ctx);
        LinearLayout b = new LinearLayout(ctx); b.setOrientation(LinearLayout.VERTICAL); b.setPadding(t.dp(20), t.dp(8), t.dp(20), 0);
        EditText name = field(ctx, t, b, "名称", name(ctx), InputType.TYPE_CLASS_TEXT);
        EditText address = field(ctx, t, b, "灯的局域网地址，例如 192.168.1.15", fixture() ? "" : MiioLight.address(ctx), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText token = field(ctx, t, b, has ? "Token（留空保持不变）" : "Token（32 位十六进制）", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        AlertDialog d = new AlertDialog.Builder(ctx).setTitle(has ? "修改灯的设置" : "设置米家灯").setView(b).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        d.show(); t.dialog(d);
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String a = address.getText().toString().trim(), k = token.getText().toString().trim();
            if (!MiioLight.validAddress(a)) { address.setError("填家里局域网的 IPv4 地址，例如 192.168.1.15"); return; }
            if (!(has && k.isEmpty()) && !MiioLight.validToken(k)) { token.setError("token 是 32 位十六进制字符"); return; }
            if (fixture()) { toast(ctx, "预览版不保存设置"); d.dismiss(); return; }
            MiioLight.configure(ctx, a, name.getText().toString(), k); reading = null; error = null; d.dismiss(); refresh();
        });
    }
    private static EditText field(Context ctx, ConsoleTheme t, LinearLayout box, String hint, String value, int type) {
        EditText e = new EditText(ctx); t.input(e); e.setHint(hint); e.setText(value); e.setSingleLine(true); e.setInputType(type);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = t.dp(8); box.addView(e, lp); return e;
    }
    private static void remove(Context ctx, ConsoleTheme t) {
        AlertDialog d = new AlertDialog.Builder(ctx).setTitle("移除「" + name(ctx) + "」？").setMessage("只删除这台手机上保存的地址和 token，灯和米家里的设置不受影响。")
            .setNegativeButton("取消", null).setPositiveButton("移除", (x, y) -> { if (!fixture()) MiioLight.forget(ctx); reading = null; error = null; refresh(); }).create();
        d.show(); t.dialog(d);
    }

    /** Reads the lamp again; the pull-to-refresh and 重试 come here. {@code done} runs on the main thread afterwards. */
    static void load(Context ctx) { load(ctx, null); }
    static void load(Context ctx, Runnable done) {
        if (fixture() || !MiioLight.configured(ctx) || busy) { if (done != null) done.run(); return; }
        Context app = ctx.getApplicationContext(); String address = MiioLight.address(app); byte[] token = MiioLight.savedToken(app);
        if (token == null) { error = "这台手机读不出保存的 token，请在「修改设置」里重新填写。"; refresh(); if (done != null) done.run(); return; }
        busy = true; refresh();
        WORK.execute(() -> {
            MiioLight.Reading r = null; String e = null;
            try { r = MiioLight.read(address, token); MiioLight.remember(app, r.model); } catch (MiioLight.LightException x) { e = x.getMessage(); }
            MiioLight.Reading result = r; String failure = e;
            MAIN.post(() -> { busy = false; error = failure; if (result != null) reading = result; refresh(); if (done != null) done.run(); });
        });
    }
    private static void set(Context ctx, int piid, Object value) {
        if (fixture()) {
            MiioLight.State s = reading.state; MiioLight.Layout l = reading.layout;
            MiioLight.State next = piid == 1 ? new MiioLight.State((Boolean) value, s.brightness, s.ct) : piid == l.brightness ? new MiioLight.State(s.on, (Integer) value, s.ct) : new MiioLight.State(s.on, s.brightness, (Integer) value);
            reading = new MiioLight.Reading(reading.model, reading.firmware, next, l); refresh(); return;
        }
        Context app = ctx.getApplicationContext(); String address = MiioLight.address(app); byte[] token = MiioLight.savedToken(app);
        if (token == null || busy) return;
        busy = true; refresh();
        WORK.execute(() -> {
            String e = null; MiioLight.Reading r = null;
            try { MiioLight.set(address, token, piid, value); r = MiioLight.read(address, token); } catch (MiioLight.LightException x) { e = x.getMessage(); }
            MiioLight.Reading result = r; String failure = e;
            MAIN.post(() -> { busy = false; if (result != null) { reading = result; error = null; } else { error = failure; toast(ctx, failure); } refresh(); });
        });
    }
    private static void toast(Context ctx, String s) { Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show(); }
}
