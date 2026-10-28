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
 * unreachable lamp never blocks the page; the last reading stays in memory while God is open. The lamp's name, address
 * and token sync to the account's other phones through {@link AppSecretsSync}. The preview build shows a synthetic lamp
 * and sends nothing unless started with {@code preview_real_light}.
 */
final class MiioLightPage {
    private MiioLightPage() {}
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<LinearLayout> shown = new WeakReference<>(null);
    private static MiioLight.Reading reading; private static String error; private static boolean busy, syncing;
    /** Bumped when the lamp's settings change, so an answer about the old address or token is dropped. */
    private static int generation;

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
            "token 在这台手机上用 Android Keystore 加密保存，指令只发给灯自己的地址。",
            "灯的名称、地址和 token 会和心力球的 Key 放在同一份文档里，由 God 网关加密后存进你的 God 账号，登录同一账号的其他安卓手机打开这一页就能直接用；在一台手机上修改或移除，其他手机也会跟着变。灯重新配网后 token 会变，要重新填写。",
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
        String line = !configured(ctx) ? syncing ? "正在从账号读取灯的设置…" : "还没有设置这盏灯" : busy && s == null ? "正在连接…" : error != null ? error
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
            // A drag sends once on release; TalkBack's range actions and arrow keys never start a drag, so their
            // changes are sent too, after a short pause so a run of key presses becomes one command.
            final boolean[] dragging = {false}; final Runnable[] later = {null};
            private void cancel() { if (later[0] != null) MAIN.removeCallbacks(later[0]); later[0] = null; }
            @Override public void onProgressChanged(SeekBar b, int v, boolean user) {
                String now = v + unit; shownValue.setText(now);
                if (!user || dragging[0]) return; cancel(); later[0] = () -> { later[0] = null; commit.accept(v); }; MAIN.postDelayed(later[0], 500);
            }
            @Override public void onStartTrackingTouch(SeekBar b) { dragging[0] = true; cancel(); }
            @Override public void onStopTrackingTouch(SeekBar b) { dragging[0] = false; commit.accept(b.getProgress()); }
        });
        card.addView(bar, new LinearLayout.LayoutParams(-1, t.dp(40)));
    }

    private static void connection(Context ctx, ConsoleTheme t, LinearLayout parent) {
        if (!configured(ctx)) return;
        LinearLayout c = t.card(parent); TextView h = t.text("连接", 15, true); h.setAccessibilityHeading(true); c.addView(h);
        String model = reading != null && reading.model != null ? reading.model : fixture() ? "yeelink.light.lamp22" : MiioLight.model(ctx);
        // One label column, as wide as its widest label at the person's font size, so the values line up.
        TextView probe = t.text("", 14, false); int w = 0; for (String s : new String[]{"地址", "Token", "型号", "同步"}) w = Math.max(w, (int) Math.ceil(probe.getPaint().measureText(s)));
        int column = w + t.dp(12);
        row(t, c, column, "地址", fixture() ? "192.168.1.x · 局域网" : MiioLight.address(ctx));
        row(t, c, column, "Token", "已加密保存");
        row(t, c, column, "型号", model == null ? "连接后读取" : model + (reading != null && reading.firmware != null ? " · 固件 " + reading.firmware : ""));
        // Where the account's copy stands in a word or two; the line under it says why when it is not synced.
        String sync = fixture() ? "已同步到账号" : syncing ? "" : AppSecretsSync.lightState(ctx); int cut = sync.indexOf('：');
        row(t, c, column, "同步", sync.isEmpty() ? "检查中…" : cut < 0 ? sync : sync.substring(0, cut));
        if (cut >= 0) { TextView why = t.text(sync.substring(cut + 1), 12, false); why.setTextColor(t.faint); why.setPadding(column, t.dp(2), 0, 0); c.addView(why); }
        LinearLayout buttons = new LinearLayout(ctx); buttons.setPadding(0, t.dp(12), 0, 0);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1); half.rightMargin = t.dp(8);
        buttons.addView(t.button("修改设置", () -> setup(ctx, t)), half);
        buttons.addView(t.button("移除", () -> remove(ctx, t)), new LinearLayout.LayoutParams(0, -2, 1)); c.addView(buttons);
    }
    private static void row(ConsoleTheme t, LinearLayout card, int column, String label, String value) {
        LinearLayout r = new LinearLayout(card.getContext()); r.setPadding(0, t.dp(8), 0, 0);
        r.addView(t.text(label, 14, false), new LinearLayout.LayoutParams(column, -2));
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
            String n = name.getText().toString().trim();
            if (n.codePointCount(0, n.length()) > AppSecretsSync.NAME_MAX) { name.setError("名称最多 " + AppSecretsSync.NAME_MAX + " 个字"); return; }
            if (fixture()) { toast(ctx, "预览版不保存设置"); d.dismiss(); return; }
            MiioLight.configure(ctx, a, n, k); AppSecretsSync.changed(ctx); d.dismiss(); settingsChanged(); sync(ctx, 0);
        });
    }
    private static EditText field(Context ctx, ConsoleTheme t, LinearLayout box, String hint, String value, int type) {
        EditText e = new EditText(ctx); t.input(e); e.setHint(hint); e.setText(value); e.setSingleLine(true); e.setInputType(type);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = t.dp(8); box.addView(e, lp); return e;
    }
    private static void remove(Context ctx, ConsoleTheme t) {
        AlertDialog d = new AlertDialog.Builder(ctx).setTitle("移除「" + name(ctx) + "」？").setMessage("删除保存的地址和 token，登录同一账号的其他手机也会一起移除。灯和米家里的设置不受影响。")
            .setNegativeButton("取消", null).setPositiveButton("移除", (x, y) -> { if (!fixture()) { MiioLight.forget(ctx); AppSecretsSync.changed(ctx); } settingsChanged(); sync(ctx, 0); }).create();
        d.show(); t.dialog(d);
    }

    /**
     * Brings the lamp from the account (or sends this phone's) on 心力球's sync worker, unless this phone synced within
     * {@code maxAge} and has nothing to send. Opening the page and saving or removing the lamp come here.
     */
    static void sync(Context ctx, long maxAge) {
        if (PreviewMode.isBuild()) return;
        syncing = true; refresh();
        MindBall.syncAsync(ctx, maxAge, () -> { syncing = false; refresh(); });
    }
    /** Another phone's lamp was written here (or removed): on the main thread, start over with it. */
    static void syncedFromAccount() { MAIN.post(MiioLightPage::settingsChanged); }

    /** New settings: forget what the old ones read, let an answer still on its way be dropped, and read the lamp again. */
    private static void settingsChanged() { generation++; busy = false; reading = null; error = null; refresh(); }

    /** Reads the lamp again; the pull-to-refresh and 重试 come here. {@code done} runs on the main thread afterwards. */
    static void load(Context ctx) { load(ctx, null); }
    static void load(Context ctx, Runnable done) {
        if (fixture() || !MiioLight.configured(ctx) || busy) { if (done != null) done.run(); return; }
        Context app = ctx.getApplicationContext(); String address = MiioLight.address(app); byte[] token = MiioLight.savedToken(app);
        if (token == null) { error = "这台手机读不出保存的 token，请在「修改设置」里重新填写。"; refresh(); if (done != null) done.run(); return; }
        busy = true; int gen = generation; refresh();
        WORK.execute(() -> {
            MiioLight.Reading r = null; String e = null;
            try { r = MiioLight.read(address, token); MiioLight.remember(app, r.model); } catch (MiioLight.LightException x) { e = x.getMessage(); }
            MiioLight.Reading result = r; String failure = e;
            MAIN.post(() -> { if (gen == generation) { busy = false; error = failure; if (result != null) reading = result; refresh(); } if (done != null) done.run(); });
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
        busy = true; int gen = generation; refresh();
        WORK.execute(() -> {
            String e = null; MiioLight.Reading r = null;
            try { MiioLight.set(address, token, piid, value); r = MiioLight.read(address, token); } catch (MiioLight.LightException x) { e = x.getMessage(); }
            MiioLight.Reading result = r; String failure = e;
            MAIN.post(() -> { if (gen != generation) return; busy = false; if (result != null) { reading = result; error = null; } else { error = failure; toast(ctx, failure); } refresh(); });
        });
    }
    private static void toast(Context ctx, String s) { Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show(); }
}
