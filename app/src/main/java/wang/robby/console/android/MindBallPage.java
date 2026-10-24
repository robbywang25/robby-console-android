package wang.robby.console.android;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Paint;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.ref.WeakReference;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * The 心力球 page: the recording switch and its state, then one day's timeline of 关键事项 and 待办 as DeepSeek
 * arranged it, the transcript it came from, and the two keys. Everything shown is read from this phone.
 */
final class MindBallPage {
    private MindBallPage() {}
    interface Host { void mindBallSwitch(boolean on); }
    /** The day being read; process memory only, back to today on a new launch. */
    private static String selected;
    private static final Set<String> OPEN = new HashSet<>();
    private static WeakReference<LinearLayout> shown = new WeakReference<>(null);

    static void render(Context ctx, LinearLayout container) {
        shown = new WeakReference<>(container);
        MindBall.listener = () -> { LinearLayout c = shown.get(); if (c != null && c.isAttachedToWindow()) render(c.getContext(), c); };
        container.removeAllViews();
        ConsoleTheme t = new ConsoleTheme(ctx); boolean preview = PreviewMode.isBuild();
        List<String> days = preview ? List.of(MindBall.today()) : MindBall.days(ctx);
        if (selected == null || !days.contains(selected)) selected = MindBall.today();
        JSONObject day = preview ? PreviewMode.mindBall() : MindBall.day(ctx, selected);
        // The status line counts today: the day file is read once when today is the day shown.
        control(ctx, t, container, preview, selected.equals(MindBall.today()) ? day : null);
        if (days.size() > 1) dayChips(ctx, t, container, days);
        timeline(ctx, t, container, day);
        transcript(ctx, t, container, day);
        settings(ctx, t, container, preview);
        container.addView(t.info(List.of(
            "录音只在「心力球」开启时进行：系统状态栏会显示麦克风标志，通知栏常驻一条「心力球正在录音」，点「停止录音」即可关掉。",
            "每 10 分钟一段；几乎没有说话声的片段直接删除，不上传。有说话的片段发给你设置的转写服务转成文字，转写成功后删除音频。",
            "文字最多每小时交给 DeepSeek 整理一次，生成当天的关键事项和待办；停止录音或下拉页面时立即整理。",
            "录音、文字和时间线只存在这台手机上 God 的私有目录里，保留 " + MindBall.KEEP_DAYS + " 天，不经过 God 服务器，也不随系统备份。两个 API Key 用 Android Keystore 加密保存。",
            "录音会录到身边其他人的声音。请事先告知对方，并遵守当地关于录音的法律。",
            "OPPO / ColorOS 需在 设置 → 应用 → God → 耗电管理 中允许后台行为；手机重启或 God 更新后，需要打开一次 God 才能继续录音。")));
    }
    static void refresh() { Runnable r = MindBall.listener; if (r != null) r.run(); }

    private static void control(Context ctx, ConsoleTheme t, LinearLayout parent, boolean preview, JSONObject today) {
        boolean on = !preview && MindBall.enabled(ctx), live = on && MindBall.recording; Host host = ctx instanceof Host ? (Host) ctx : null;
        LinearLayout c = t.card(parent);
        LinearLayout top = new LinearLayout(ctx); top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(t.tile("mic", 36), new LinearLayout.LayoutParams(t.dp(36), t.dp(36)));
        LinearLayout texts = new LinearLayout(ctx); texts.setOrientation(LinearLayout.VERTICAL); texts.setPadding(t.dp(12), 0, t.dp(8), 0);
        texts.addView(t.text("全天录音", 16, true));
        TextView state = t.text(preview ? "预览版不录音" : live ? "正在录音" : on ? "已开启 · 等待麦克风" : "未开启", 12, false);
        state.setTextColor(live ? t.success : on ? t.warning : t.muted); texts.addView(state);
        top.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(ctx); toggle.setChecked(on); toggle.setEnabled(!preview && host != null); toggle.setContentDescription("心力球全天录音");
        toggle.setOnCheckedChangeListener((v, checked) -> { if (host != null) host.mindBallSwitch(checked); v.post(MindBallPage::refresh); });
        top.addView(toggle); c.addView(top);
        if (preview) return;
        TextView status = t.text(MindBall.status(ctx, today != null ? today : MindBall.day(ctx, MindBall.today())), 12, false); status.setTextColor(t.faint); status.setPadding(0, t.dp(8), 0, 0); c.addView(status);
        if (live) {
            // The segment being recorded is not in the day yet: show how far it is, ticking while the page is in view.
            TextView now = t.text(MindBall.live(), 13, false); now.setTextColor(t.ink); now.setPadding(0, t.dp(6), 0, 0); c.addView(now);
            Runnable tick = new Runnable() { @Override public void run() { if (!now.isAttachedToWindow()) return; now.setText(MindBall.live()); now.postDelayed(this, 15_000L); } };
            now.postDelayed(tick, 15_000L);
        }
        List<String> problems = new ArrayList<>();
        if (!on) problems.add("全天录音还没开启：打开上方开关并允许麦克风后才会开始录音");
        else if (!MindBall.micAllowed(ctx)) problems.add("没有麦克风权限：在系统设置里为 God 允许麦克风");
        else if (!live) problems.add("已开启但还没在录音：重新打开一次 God 即可继续");
        if (!MindBall.hasSecret(ctx, MindBall.KEY_ASR)) problems.add("还没有填写转写服务的 API Key");
        if (!MindBall.hasSecret(ctx, MindBall.KEY_DEEPSEEK)) problems.add("还没有填写 DeepSeek API Key");
        // A missing key is already said above; its error only adds that the audio waits on the phone.
        String error = MindBall.lastError(ctx); if (!error.isEmpty() && !error.startsWith("未填写")) problems.add(error);
        for (String p : problems) {
            LinearLayout r = new LinearLayout(ctx); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(0, t.dp(8), 0, 0);
            View dot = t.dot(t.warning, 7); ((LinearLayout.LayoutParams) dot.getLayoutParams()).rightMargin = t.dp(8); r.addView(dot);
            TextView w = t.text(p, 13, false); w.setTextColor(t.ink); r.addView(w, new LinearLayout.LayoutParams(0, -2, 1)); c.addView(r);
        }
        Button now = t.button(MindBall.busy ? "正在转写和整理…" : "立即整理", () -> MindBall.processNow(ctx, null));
        now.setEnabled(!MindBall.busy); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = t.dp(12); c.addView(now, lp);
    }

    private static void dayChips(Context ctx, ConsoleTheme t, LinearLayout parent, List<String> days) {
        HorizontalScrollView scroll = new HorizontalScrollView(ctx); scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(ctx); row.setPadding(t.dp(2), 0, t.dp(2), t.dp(10)); scroll.addView(row);
        for (String d : days.subList(0, Math.min(days.size(), 14))) {
            boolean on = d.equals(selected); TextView chip = t.text(dayLabel(d), 13, on);
            chip.setTextColor(on ? 0xffffffff : t.ink); chip.setGravity(Gravity.CENTER); chip.setMinHeight(t.dp(36)); chip.setPadding(t.dp(14), 0, t.dp(14), 0);
            chip.setBackground(t.ripple(t.shape(on ? t.accent : t.soft, 99), 99)); chip.setFocusable(true); chip.setClickable(true); chip.setSelected(on);
            chip.setOnClickListener(v -> { selected = d; refresh(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.rightMargin = t.dp(8); row.addView(chip, lp);
        }
        parent.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
    }
    static String dayLabel(String day) {
        LocalDate d = LocalDate.parse(day), today = LocalDate.now(ZoneId.systemDefault());
        if (d.equals(today)) return "今天"; if (d.equals(today.minusDays(1))) return "昨天";
        return d.getMonthValue() + "月" + d.getDayOfMonth() + "日";
    }

    private static void timeline(Context ctx, ConsoleTheme t, LinearLayout parent, JSONObject day) {
        JSONObject tl = day.optJSONObject("timeline"); JSONArray segs = day.optJSONArray("segments");
        JSONArray items = tl == null ? null : tl.optJSONArray("items"); JSONObject marks = day.optJSONObject("done"); if (marks == null) marks = new JSONObject();
        LinearLayout c = t.card(parent);
        TextView h = t.text(dayLabel(selected) + "的时间线", 15, true); h.setAccessibilityHeading(true); c.addView(h);
        int events = 0, todos = 0, open = 0;
        if (items != null) for (int i = 0; i < items.length(); i++) { JSONObject it = items.optJSONObject(i); if ("todo".equals(it.optString("kind"))) { todos++; if (!marks.has(MindBall.todoKey(it))) open++; } else events++; }
        LinearLayout figures = new LinearLayout(ctx); figures.setPadding(0, t.dp(10), 0, t.dp(4));
        figure(t, figures, "关键事项", tl == null ? "—" : String.valueOf(events)); figure(t, figures, "待办未完成", tl == null ? "—" : open + " / " + todos);
        figure(t, figures, "录音", MindBall.minutes(segs) + " 分钟"); c.addView(figures);
        String summary = tl == null ? "" : tl.optString("summary"); if (!summary.isEmpty()) { TextView s = t.text(summary, 14, false); s.setTextColor(t.ink); s.setPadding(0, t.dp(6), 0, t.dp(2)); c.addView(s); }
        List<String> meta = new ArrayList<>();
        if (day.optLong("summarizedAt") > 0) meta.add("整理于 " + MindBall.clock(day.optLong("summarizedAt")) + (tl.optString("model").isEmpty() ? "" : " · " + tl.optString("model")));
        int fresh = 0; if (segs != null) for (int i = 0; i < segs.length(); i++) if (segs.optJSONObject(i).optLong("end") > day.optLong("summarizedThrough")) fresh++;
        if (fresh > 0 && tl != null) meta.add(fresh + " 段新录音尚未整理");
        if (tl != null && tl.optBoolean("truncated")) meta.add("回复过长，时间线可能不完整");
        if (!meta.isEmpty()) { TextView m = t.text(String.join(" · ", meta), 12, false); m.setTextColor(t.faint); m.setPadding(0, t.dp(4), 0, 0); c.addView(m); }
        if (items == null || items.length() == 0) {
            TextView e = t.text(segs == null || segs.length() == 0 ? "这一天还没有转写好的录音。" : tl == null ? "已有 " + segs.length() + " 段录音，等待整理。" : "这一天没有需要记下的事项。", 14, false);
            e.setPadding(0, t.dp(12), 0, t.dp(4)); c.addView(e); return;
        }
        for (int i = 0; i < items.length(); i++) item(ctx, t, c, items.optJSONObject(i), marks);
    }
    private static void figure(ConsoleTheme t, LinearLayout band, String label, String value) {
        LinearLayout f = new LinearLayout(band.getContext()); f.setOrientation(LinearLayout.VERTICAL);
        TextView v = t.text(value, 20, true); f.addView(v); TextView l = t.text(label, 12, false); l.setTextColor(t.faint); f.addView(l);
        f.setContentDescription(label + " " + value); band.addView(f, new LinearLayout.LayoutParams(0, -2, 1));
    }
    private static void item(Context ctx, ConsoleTheme t, LinearLayout parent, JSONObject it, JSONObject marks) {
        boolean todo = "todo".equals(it.optString("kind")); String key = MindBall.todoKey(it); boolean done = todo && marks.has(key);
        LinearLayout r = new LinearLayout(ctx); r.setGravity(Gravity.TOP); r.setPadding(0, t.dp(12), 0, 0); r.setMinimumHeight(t.dp(todo ? 48 : 38));
        TextView time = t.text(it.optString("time").isEmpty() ? "—" : it.optString("time"), 13, false); time.setTextColor(t.muted); time.setPadding(0, t.dp(1), 0, 0);
        // One line at any font size: the column grows instead of breaking "09:00" in two.
        time.setSingleLine(true); time.setMinWidth(t.dp(48)); time.setPadding(0, t.dp(1), t.dp(6), 0); r.addView(time, new LinearLayout.LayoutParams(-2, -2));
        View dot = t.dot(todo ? t.accent : t.faint, 8); LinearLayout.LayoutParams dl = (LinearLayout.LayoutParams) dot.getLayoutParams(); dl.topMargin = t.dp(7); dl.rightMargin = t.dp(10); r.addView(dot);
        LinearLayout body = new LinearLayout(ctx); body.setOrientation(LinearLayout.VERTICAL);
        TextView title = t.text(it.optString("title"), 15, false); title.setTextColor(done ? t.faint : t.ink);
        if (done) title.setPaintFlags(title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG); body.addView(title);
        List<String> sub = new ArrayList<>(); sub.add(todo ? "待办" : "事项"); if (!it.optString("due").isEmpty()) sub.add("截止 " + it.optString("due"));
        TextView kind = t.text(String.join(" · ", sub), 12, false); kind.setTextColor(todo ? t.accent : t.faint); kind.setPadding(0, t.dp(2), 0, 0); body.addView(kind);
        if (!it.optString("detail").isEmpty()) { TextView d = t.text(it.optString("detail"), 13, false); d.setPadding(0, t.dp(2), 0, 0); body.addView(d); }
        r.addView(body, new LinearLayout.LayoutParams(0, -2, 1));
        if (todo) {
            CheckBox box = new CheckBox(ctx); box.setChecked(done); box.setContentDescription("完成：" + it.optString("title")); box.setEnabled(!PreviewMode.isBuild());
            box.setOnCheckedChangeListener((v, checked) -> { MindBall.setDone(ctx, selected, key, checked); v.post(MindBallPage::refresh); });
            r.addView(box);
        }
        parent.addView(r);
    }

    private static void transcript(Context ctx, ConsoleTheme t, LinearLayout parent, JSONObject day) {
        JSONArray segs = day.optJSONArray("segments"); if (segs == null || segs.length() == 0) return;
        LinearLayout c = t.card(parent); TextView h = t.text("录音原文 · " + segs.length() + " 段", 15, true); h.setAccessibilityHeading(true); c.addView(h);
        for (int i = segs.length() - 1; i >= 0; i--) {
            JSONObject s = segs.optJSONObject(i); String key = selected + "|" + s.optLong("start"); boolean open = OPEN.contains(key);
            LinearLayout r = new LinearLayout(ctx); r.setOrientation(LinearLayout.VERTICAL); r.setPadding(0, t.dp(10), 0, t.dp(4)); r.setMinimumHeight(t.dp(48));
            r.setBackground(t.ripple(t.shape(android.graphics.Color.TRANSPARENT, 12), 12)); r.setFocusable(true); r.setClickable(true);
            TextView when = t.text(MindBall.clock(s.optLong("start")) + "–" + MindBall.clock(s.optLong("end")), 12, false); when.setTextColor(t.faint); r.addView(when);
            TextView text = t.text(s.optString("text"), 14, false); text.setTextColor(t.ink); text.setTextIsSelectable(open);
            if (!open) { text.setMaxLines(2); text.setEllipsize(android.text.TextUtils.TruncateAt.END); }
            r.addView(text); r.setStateDescription(open ? "已展开" : "已收起");
            r.setOnClickListener(v -> { if (!OPEN.remove(key)) OPEN.add(key); refresh(); });
            c.addView(r);
        }
    }

    private static void settings(Context ctx, ConsoleTheme t, LinearLayout parent, boolean preview) {
        LinearLayout c = t.card(parent); TextView h = t.text("设置", 15, true); h.setAccessibilityHeading(true); c.addView(h);
        setting(ctx, t, c, "DeepSeek API Key", preview ? "预览" : MindBall.hasSecret(ctx, MindBall.KEY_DEEPSEEK) ? "已填写" : "未填写", preview ? null : () -> keyDialog(ctx, t, "DeepSeek API Key", MindBall.KEY_DEEPSEEK, "在 platform.deepseek.com 创建，以 sk- 开头"));
        setting(ctx, t, c, "整理模型", preview ? MindBall.DEFAULT_MODEL : MindBall.model(ctx), preview ? null : () -> serviceDialog(ctx, t));
        setting(ctx, t, c, "转写服务", preview ? "api.siliconflow.cn" : host(MindBall.asrUrl(ctx)) + " · " + shortModel(MindBall.asrModel(ctx)), preview ? null : () -> serviceDialog(ctx, t));
        setting(ctx, t, c, "转写服务 API Key", preview ? "预览" : MindBall.hasSecret(ctx, MindBall.KEY_ASR) ? "已填写" : "未填写", preview ? null : () -> keyDialog(ctx, t, "转写服务 API Key", MindBall.KEY_ASR, "默认是硅基流动（cloud.siliconflow.cn）的 Key；可改用任何兼容 OpenAI /audio/transcriptions 的服务"));
        if (preview) return;
        // The row says where things stand in a word or two; the line under it says why.
        String sync = MindBallSync.state(ctx); int cut = sync.indexOf('：');
        setting(ctx, t, c, "多台手机同步", sync.isEmpty() ? "检查中…" : cut < 0 ? sync : sync.substring(0, cut), () -> MindBall.syncAsync(ctx));
        TextView note = t.text(cut >= 0 ? sync.substring(cut + 1) : "两个 Key 和上面的服务设置会加密保存到你的 God 账号，登录同一账号的其他安卓手机会自动用上。", 12, false);
        note.setTextColor(t.faint); note.setPadding(0, t.dp(2), 0, t.dp(6)); c.addView(note);
        TextView clear = t.text("清除全部录音与记录", 15, false); clear.setTextColor(t.danger); clear.setGravity(Gravity.CENTER_VERTICAL); clear.setMinHeight(t.dp(48));
        clear.setBackground(t.ripple(t.shape(android.graphics.Color.TRANSPARENT, 12), 12)); clear.setFocusable(true); clear.setClickable(true);
        clear.setOnClickListener(v -> show(t, new AlertDialog.Builder(ctx).setTitle("清除全部录音与记录？").setMessage("会删除这台手机上心力球的全部音频、原文和时间线，无法恢复。开关和 API Key 保留。")
            .setNegativeButton("取消", null).setPositiveButton("清除", (d, w) -> MindBall.clearAll(ctx))));
        c.addView(clear);
    }
    private static void setting(Context ctx, ConsoleTheme t, LinearLayout parent, String label, String value, Runnable action) {
        LinearLayout r = new LinearLayout(ctx); r.setGravity(Gravity.CENTER_VERTICAL); r.setMinimumHeight(t.dp(48)); r.setContentDescription(label + "，" + value);
        TextView l = t.text(label, 15, false); l.setTextColor(t.ink); r.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        TextView v = t.text(value, 13, false); v.setSingleLine(true); v.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE); v.setMaxWidth(t.dp(180)); r.addView(v);
        if (action != null) {
            r.addView(t.icon("chevron", t.muted, 14)); r.setBackground(t.ripple(t.shape(android.graphics.Color.TRANSPARENT, 12), 12)); r.setFocusable(true); r.setClickable(true);
            r.setOnClickListener(x -> action.run());
        }
        parent.addView(r);
    }
    static String host(String url) { String s = url.replaceFirst("^https?://", ""); int slash = s.indexOf('/'); return slash < 0 ? s : s.substring(0, slash); }
    static String shortModel(String model) { int slash = model.lastIndexOf('/'); return slash < 0 ? model : model.substring(slash + 1); }

    private static EditText field(Context ctx, ConsoleTheme t, LinearLayout box, String label, String value, boolean secret) {
        TextView l = t.text(label, 12, false); l.setPadding(t.dp(2), t.dp(10), 0, t.dp(4)); box.addView(l);
        EditText e = new EditText(ctx); t.input(e); e.setText(value); e.setSingleLine(true);
        e.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (secret) e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        box.addView(e, new LinearLayout.LayoutParams(-1, -2)); return e;
    }
    private static LinearLayout box(Context ctx, ConsoleTheme t) { LinearLayout b = new LinearLayout(ctx); b.setOrientation(LinearLayout.VERTICAL); b.setPadding(t.dp(20), t.dp(4), t.dp(20), 0); return b; }
    /** The key is never shown again once saved: the field starts empty, and saving it empty removes the key. */
    private static void keyDialog(Context ctx, ConsoleTheme t, String title, String name, String hint) {
        LinearLayout b = box(ctx, t); TextView note = t.text(hint, 12, false); note.setPadding(t.dp(2), t.dp(4), 0, 0); b.addView(note);
        EditText key = field(ctx, t, b, MindBall.hasSecret(ctx, name) ? "新的 Key（留空并保存会删除已填写的 Key）" : "API Key", "", true);
        show(t, new AlertDialog.Builder(ctx).setTitle(title).setView(b).setNegativeButton("取消", (d, w) -> key.setText(""))
            .setPositiveButton("保存", (d, w) -> { MindBall.setSecret(ctx, name, key.getText().toString()); key.setText(""); MindBallSync.changed(ctx); MindBall.processAsync(ctx, false); refresh(); }));
    }
    private static void serviceDialog(Context ctx, ConsoleTheme t) {
        LinearLayout b = box(ctx, t);
        EditText model = field(ctx, t, b, "DeepSeek 整理模型（deepseek-flash 或 deepseek-v4-pro）", MindBall.model(ctx), false);
        EditText url = field(ctx, t, b, "转写服务地址（OpenAI 兼容，到 /v1 为止）", MindBall.asrUrl(ctx), false);
        EditText asr = field(ctx, t, b, "转写模型", MindBall.asrModel(ctx), false);
        show(t, new AlertDialog.Builder(ctx).setTitle("整理与转写").setView(b).setNegativeButton("取消", null).setNeutralButton("恢复默认", (d, w) -> { MindBall.configure(ctx, null, null, null); MindBallSync.changed(ctx); MindBall.syncAsync(ctx); refresh(); })
            .setPositiveButton("保存", (d, w) -> {
                String u = url.getText().toString().trim();
                if (!u.isEmpty() && !u.startsWith("https://")) { show(t, new AlertDialog.Builder(ctx).setTitle("地址需要以 https:// 开头").setMessage("录音只通过加密连接发送。").setPositiveButton("知道了", null)); return; }
                MindBall.configure(ctx, u, asr.getText().toString(), model.getText().toString()); MindBallSync.changed(ctx); MindBall.syncAsync(ctx); refresh(); }));
    }
    private static void show(ConsoleTheme t, AlertDialog.Builder builder) { AlertDialog d = builder.create(); d.show(); t.dialog(d); }
}
