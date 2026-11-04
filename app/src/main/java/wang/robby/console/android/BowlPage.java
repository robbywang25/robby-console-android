package wang.robby.console.android;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.List;

/**
 * The 颂钵 page: the bowl to strike and rub, then 番茄钟 (the countdown, its round, start / pause, skip and end, and the
 * phase lengths), then 声音 (ambient noise, 持续唱钵, which bowl, and the two volumes). Everything plays on this phone;
 * only the settings follow the account.
 */
final class BowlPage {
    private BowlPage() {}
    private static WeakReference<LinearLayout> shown = new WeakReference<>(null);

    static void refresh() { LinearLayout c = shown.get(); if (c != null && c.isAttachedToWindow()) render(c.getContext(), c); }

    static void render(Context ctx, LinearLayout container) {
        shown = new WeakReference<>(container); container.removeAllViews(); BowlSession.listener = BowlPage::refresh;
        ConsoleTheme t = new ConsoleTheme(ctx); Pomodoro.Settings s = BowlSession.settings(ctx); BowlSession.apply(ctx, s);
        // A timer still running after God was stopped (an update, a force stop) gets its service and notification back.
        if (!BowlService.running && BowlSession.needsService(ctx)) BowlSession.update(ctx);
        bowl(ctx, t, container, s); timer(ctx, t, container, s); sound(ctx, t, container, s);
        container.addView(t.info(List.of(
            "颂钵的声音是在手机上实时合成的，不是录音：五个不规则泛音，每个由两条相差几赫兹的正弦组成，所以会像真钵一样「嗡嗡」起伏；高泛音衰减得快，基音能响半分钟以上。",
            "敲击处越靠近钵口，音色越亮。沿钵口画圈时，转得越快声音越大，停下后慢慢消散；打开「持续唱钵」会一直替你摩擦。",
            "番茄钟默认专注 25 分钟、休息 5 分钟，每 4 轮长休息 15 分钟。每段结束时颂钵会响（专注结束三声、休息结束一声），手机轻震一下并发一条提醒。",
            "离开 God 或熄屏后，声音和计时会继续，通知栏里可以暂停或停止。来电或别的 App 开始播放时，环境音会自动停下。",
            "时长、钵的大小和两个音量会跟着 God 账号同步到你的其他安卓手机；计时进度和正在播放的声音只在这台手机上。")));
    }

    private static void bowl(Context ctx, ConsoleTheme t, LinearLayout parent, Pomodoro.Settings s) {
        LinearLayout c = t.card(parent); c.setPadding(t.dp(8), t.dp(8), t.dp(8), t.dp(14));
        BowlView view = new BowlView(ctx, t.dark); c.addView(view, new LinearLayout.LayoutParams(-1, -2));
        TextView hint = t.text(BowlSynth.BOWL_NAMES[s.bowl] + " " + BowlSynth.BOWLS[s.bowl] + " Hz · 轻点敲钵 · 沿钵口画圈让它唱", 12, false); hint.setTextColor(t.faint); hint.setGravity(Gravity.CENTER); c.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout sizes = new LinearLayout(ctx); sizes.setGravity(Gravity.CENTER); sizes.setPadding(0, t.dp(10), 0, 0);
        for (int i = 0; i < BowlSynth.BOWLS.length; i++) { int index = i; TextView size = chip(t, BowlSynth.BOWL_NAMES[i], i == s.bowl, () -> BowlSession.save(ctx, BowlSession.settings(ctx).with("bowl", index))); size.setContentDescription(BowlSynth.BOWL_NAMES[i] + " " + BowlSynth.BOWLS[i] + " 赫兹");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMargins(t.dp(4), 0, t.dp(4), 0); sizes.addView(size, lp); }
        c.addView(sizes, new LinearLayout.LayoutParams(-1, -2));
    }

    private static void timer(Context ctx, ConsoleTheme t, LinearLayout parent, Pomodoro.Settings s) {
        LinearLayout c = t.card(parent); Pomodoro.State st = BowlSession.timer(ctx);
        LinearLayout head = new LinearLayout(ctx); head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = t.text("番茄钟", 15, true); title.setAccessibilityHeading(true); head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        // One dot per focus round of the cycle: done ones filled, the current one ringed in the accent.
        Pomodoro.State[] cur = {st};
        View dots = new View(ctx) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                float d = t.dp(8), gap = t.dp(6), y = getHeight() / 2f; Pomodoro.State now = cur[0];
                for (int i = 0; i < s.rounds; i++) {
                    float x = getWidth() - (s.rounds - i) * (d + gap) + d / 2f; int round = i + 1;
                    boolean done = now.active() && (round < now.round || round == now.round && !Pomodoro.FOCUS.equals(now.phase)), current = now.active() && round == now.round && Pomodoro.FOCUS.equals(now.phase);
                    p.setStyle(done ? Paint.Style.FILL : Paint.Style.STROKE); p.setStrokeWidth(t.dp(1.5f)); p.setColor(done || current ? t.accent : t.faint);
                    canvas.drawCircle(x, y, d / 2f - t.dp(.75f), p);
                }
            }
        };
        dots.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        head.addView(dots, new LinearLayout.LayoutParams(s.rounds * t.dp(14), t.dp(16))); c.addView(head);
        TextView phase = t.text("", 13, false); phase.setPadding(0, t.dp(2), 0, 0); c.addView(phase);
        TextView clock = t.text("", 56, false); clock.setTextColor(t.ink); clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL)); clock.setGravity(Gravity.CENTER);
        clock.setIncludeFontPadding(false); clock.setPadding(0, t.dp(10), 0, t.dp(4)); clock.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE); c.addView(clock, new LinearLayout.LayoutParams(-1, -2));
        TextView sub = t.text("", 12, false); sub.setTextColor(t.faint); sub.setGravity(Gravity.CENTER); c.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        float[] progress = {0};
        View bar = new View(ctx) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                float h = getHeight(), w = getWidth(); p.setColor(t.soft | (t.dark ? 0x10000000 : 0x08000000)); canvas.drawRoundRect(0, 0, w, h, h / 2, h / 2, p);
                if (progress[0] > 0) { p.setColor(t.accent); canvas.drawRoundRect(0, 0, Math.max(h, w * progress[0]), h, h / 2, h / 2, p); }
            }
        };
        bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, t.dp(6)); bp.topMargin = t.dp(12); c.addView(bar, bp);
        Runnable paint = () -> {
            Pomodoro.State now = BowlSession.timer(ctx); cur[0] = now; long left = now.left(System.currentTimeMillis()), total = s.minutes(now.active() ? now.phase : Pomodoro.FOCUS) * 60_000L;
            clock.setText(Pomodoro.clock(now.active() ? left : total));
            phase.setText(!now.active() ? "准备开始" : Pomodoro.label(now.phase) + (Pomodoro.FOCUS.equals(now.phase) ? " · 第 " + now.round + "/" + s.rounds + " 轮" : ""));
            phase.setTextColor(now.active() && now.running ? Pomodoro.FOCUS.equals(now.phase) ? t.accent : t.success : t.muted);
            sub.setText(!now.active() ? s.focus + " 分钟专注 · " + s.shortBreak + " 分钟休息" : now.running ? "结束于 " + java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(java.time.Instant.ofEpochMilli(now.endsAt).atZone(java.time.ZoneId.systemDefault())) : "已暂停");
            clock.setContentDescription((now.active() ? "剩余 " : "") + Pomodoro.clock(now.active() ? left : total).replace(":", " 分 ") + " 秒");
            progress[0] = now.active() && total > 0 ? 1f - left / (float) total : 0; bar.invalidate(); dots.invalidate();
        };
        paint.run();
        Runnable tick = new Runnable() { @Override public void run() { if (!clock.isAttachedToWindow()) return; paint.run(); clock.postDelayed(this, 250); } };
        clock.postDelayed(tick, 250);
        LinearLayout buttons = new LinearLayout(ctx); buttons.setPadding(0, t.dp(14), 0, 0);
        Button main = t.button(!st.active() ? "开始专注" : st.running ? "暂停" : "继续", () -> BowlSession.toggleTimer(ctx)); t.button(main, !st.running);
        buttons.addView(main, new LinearLayout.LayoutParams(0, -2, 1));
        if (st.active()) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.leftMargin = t.dp(8);
            buttons.addView(t.button("跳过", () -> BowlSession.skip(ctx)), lp);
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-2, -2); lp2.leftMargin = t.dp(8);
            buttons.addView(t.button("结束", () -> BowlSession.stopTimer(ctx)), lp2);
        }
        c.addView(buttons);
        View divider = new View(ctx); divider.setBackgroundColor(t.line); LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, t.dp(.5f)); dl.topMargin = t.dp(14); c.addView(divider, dl);
        choice(ctx, t, c, "专注", s.focus, Pomodoro.FOCUS_CHOICES, " 分钟", "focus");
        choice(ctx, t, c, "短休息", s.shortBreak, Pomodoro.SHORT_CHOICES, " 分钟", "short");
        choice(ctx, t, c, "长休息", s.longBreak, Pomodoro.LONG_CHOICES, " 分钟", "long");
        choice(ctx, t, c, "长休息间隔", s.rounds, Pomodoro.ROUND_CHOICES, " 轮", "rounds");
        toggle(ctx, t, c, "自动开始下一段", s.auto, on -> BowlSession.save(ctx, BowlSession.settings(ctx).with("auto", on ? 1 : 0)));
    }

    private static void sound(Context ctx, ConsoleTheme t, LinearLayout parent, Pomodoro.Settings s) {
        LinearLayout c = t.card(parent); TextView h = t.text("声音", 15, true); h.setAccessibilityHeading(true); c.addView(h);
        String now = BowlSession.sound(ctx); TextView state = t.text(now.isEmpty() ? "没有在播放" : "正在播放 · " + now, 12, false);
        state.setTextColor(now.isEmpty() ? t.faint : t.success); state.setPadding(0, t.dp(2), 0, t.dp(10)); c.addView(state);
        FlowRow noises = new FlowRow(ctx, t.dp(8), t.dp(8)); int playing = BowlSession.noise(ctx);
        for (int i = 0; i < BowlSynth.NOISES.length; i++) { int index = i; noises.addView(chip(t, BowlSynth.NOISE_NAMES[i], i == playing, () -> BowlSession.setNoise(ctx, index))); }
        c.addView(noises);
        toggle(ctx, t, c, "持续唱钵", BowlSession.singing(), on -> BowlSession.setSing(ctx, on));
        slider(ctx, t, c, "颂钵音量", s.bowlVolume, v -> BowlSession.synth(ctx).setVolumes(v / 100f, BowlSession.settings(ctx).noiseVolume / 100f), v -> BowlSession.save(ctx, BowlSession.settings(ctx).with("bowlVol", v)));
        slider(ctx, t, c, "环境音音量", s.noiseVolume, v -> BowlSession.synth(ctx).setVolumes(BowlSession.settings(ctx).bowlVolume / 100f, v / 100f), v -> BowlSession.save(ctx, BowlSession.settings(ctx).with("noiseVol", v)));
    }

    /** A capsule that reads as selected with the accent fill; the whole capsule is the 48 dp target. */
    private static TextView chip(ConsoleTheme t, String label, boolean selected, Runnable action) {
        TextView v = t.text(label, 14, false); v.setGravity(Gravity.CENTER); v.setMinHeight(t.dp(40)); v.setPadding(t.dp(14), t.dp(8), t.dp(14), t.dp(8));
        v.setTextColor(selected ? 0xffffffff : t.ink); v.setBackground(t.ripple(t.shape(selected ? t.accentStrong : t.soft, 99), -1));
        v.setSelected(selected); v.setStateDescription(selected ? "已选择" : "未选择"); v.setFocusable(true); v.setClickable(true);
        v.setOnClickListener(x -> { if (!selected) action.run(); }); t.press(v); return v;
    }
    private static void choice(Context ctx, ConsoleTheme t, LinearLayout card, String label, int value, int[] options, String unit, String key) {
        LinearLayout row = row(ctx, t, card, label); TextView shown = t.text(value + unit, 14, false); shown.setTextColor(t.accent); row.addView(shown);
        row.setContentDescription(label + " " + value + unit); row.setBackground(t.ripple(t.shape(android.graphics.Color.TRANSPARENT, 12), 12)); row.setFocusable(true);
        row.setOnClickListener(v -> {
            String[] names = new String[options.length]; int at = 0;
            for (int i = 0; i < options.length; i++) { names[i] = options[i] + unit; if (options[i] == value) at = i; }
            AlertDialog[] d = {null};
            d[0] = new AlertDialog.Builder(ctx).setTitle(label).setSingleChoiceItems(names, at, (x, w) -> { BowlSession.save(ctx, BowlSession.settings(ctx).with(key, options[w])); d[0].dismiss(); })
                .setNegativeButton("取消", null).create();
            d[0].show(); t.dialog(d[0]);
        });
    }
    private static void toggle(Context ctx, ConsoleTheme t, LinearLayout card, String label, boolean on, java.util.function.Consumer<Boolean> change) {
        LinearLayout row = row(ctx, t, card, label); Switch sw = new Switch(ctx); sw.setChecked(on); sw.setContentDescription(label);
        sw.setOnCheckedChangeListener((b, checked) -> b.post(() -> change.accept(checked))); row.addView(sw);
    }
    private static LinearLayout row(Context ctx, ConsoleTheme t, LinearLayout card, String label) {
        LinearLayout row = new LinearLayout(ctx); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(t.dp(48));
        TextView name = t.text(label, 15, false); name.setTextColor(t.ink); row.addView(name, new LinearLayout.LayoutParams(0, -2, 1)); card.addView(row, new LinearLayout.LayoutParams(-1, -2)); return row;
    }
    private static void slider(Context ctx, ConsoleTheme t, LinearLayout card, String label, int value, java.util.function.IntConsumer live, java.util.function.IntConsumer commit) {
        LinearLayout head = new LinearLayout(ctx); head.setPadding(0, t.dp(10), 0, t.dp(2));
        TextView name = t.text(label, 15, false); name.setTextColor(t.ink); head.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView shown = t.text(value + "%", 14, false); head.addView(shown); card.addView(head);
        SeekBar bar = new SeekBar(ctx); bar.setMax(100); bar.setProgress(value); bar.setContentDescription(label);
        bar.setProgressTintList(ColorStateList.valueOf(t.accent)); bar.setThumbTintList(ColorStateList.valueOf(t.accent));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            boolean dragging;
            @Override public void onProgressChanged(SeekBar b, int v, boolean user) { shown.setText(v + "%"); if (user) { live.accept(v); if (!dragging) commit.accept(v); } }
            @Override public void onStartTrackingTouch(SeekBar b) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar b) { dragging = false; commit.accept(b.getProgress()); }
        });
        card.addView(bar, new LinearLayout.LayoutParams(-1, t.dp(40)));
    }
}
