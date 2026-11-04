package wang.robby.console.android;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.PersistableBundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.function.Consumer;

/**
 * One cloud clipboard item's body, as on iOS: masked until 显示内容, read one item at a time from the reveal route,
 * hidden again after a minute. The text lives only in these views; nothing is cached or written to disk.
 */
final class ClipboardReveal {
    private ClipboardReveal() {}
    /** Implemented by the host, which owns the API and the worker thread; both callbacks run on the main thread. */
    interface Host { void revealClipboard(String id, Consumer<String> shown, Consumer<String> failed); }
    static final long LIFETIME_MS = 60_000L;
    static final int MAX_CHARACTERS = 200_000;

    static boolean validId(String id) { return id != null && id.matches("[a-f0-9]{32}"); }
    static String route(String id) { if (!validId(id)) throw new IllegalArgumentException("条目无效"); return "clipboard/" + id + "/reveal"; }
    /** The reveal response's text, only when it is for exactly the item asked for. */
    static String content(JSONObject response, String id) {
        JSONObject item = response == null ? null : response.optJSONObject("item");
        Object text = item == null ? null : item.opt("content");
        if (item == null || !id.equals(item.optString("id", null)) || !(text instanceof String) || ((String) text).length() > MAX_CHARACTERS)
            throw new IllegalArgumentException("接口返回格式不匹配");
        return (String) text;
    }

    static void fill(Context context, ConsoleTheme theme, LinearLayout body, String id) {
        body.removeAllViews();
        if (!validId(id) || !(context instanceof Host)) { body.addView(note(context, theme, "此条目暂不能显示正文")); return; }
        LinearLayout row = new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(context, 4), 0, 0);
        row.addView(note(context, theme, "正文已遮罩"), new LinearLayout.LayoutParams(0, -2, 1));
        Button show = theme.button("显示内容", () -> {
            body.removeAllViews(); body.addView(note(context, theme, "正在读取这一条正文…"));
            ((Host) context).revealClipboard(id, text -> { if (body.isAttachedToWindow()) shown(context, theme, body, id, text); },
                why -> { if (!body.isAttachedToWindow()) return; fill(context, theme, body, id); TextView t = note(context, theme, why); t.setTextColor(theme.danger); body.addView(t); });
        });
        row.addView(show); body.addView(row);
    }

    private static void shown(Context context, ConsoleTheme theme, LinearLayout body, String id, String text) {
        body.removeAllViews();
        TextView content = theme.text(text.isEmpty() ? "（空白内容）" : text, 15, false); content.setTextColor(theme.ink);
        content.setTypeface(Typeface.MONOSPACE); content.setTextIsSelectable(true); content.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        content.setBackground(theme.shape(theme.soft, 12)); body.addView(content, new LinearLayout.LayoutParams(-1, -2));
        TextView status = note(context, theme, "60 秒后自动隐藏");
        LinearLayout actions = new LinearLayout(context); actions.setPadding(0, dp(context, 8), 0, 0);
        actions.addView(theme.button("复制", () -> { copy(context, text); status.setText("已复制到本机剪贴板 · 60 秒后自动隐藏"); }), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, -2, 1); hp.leftMargin = dp(context, 10);
        actions.addView(theme.button("立即隐藏", () -> fill(context, theme, body, id)), hp);
        body.addView(actions); body.addView(status);
        // Hide again after the lifetime unless this same reveal was already hidden or replaced.
        body.postDelayed(() -> { if (body.indexOfChild(content) >= 0) fill(context, theme, body, id); }, LIFETIME_MS);
    }

    /** Marked sensitive, so Android 13+ hides the text in its copy preview and keyboard suggestions. */
    static void copy(Context context, String text) {
        ClipboardManager manager = context.getSystemService(ClipboardManager.class); if (manager == null) return;
        ClipData clip = ClipData.newPlainText("God 云剪贴板", text);
        PersistableBundle extras = new PersistableBundle(); extras.putBoolean("android.content.extra.IS_SENSITIVE", true); clip.getDescription().setExtras(extras);
        manager.setPrimaryClip(clip);
    }

    private static TextView note(Context context, ConsoleTheme theme, String value) {
        TextView t = theme.text(value, 13, false); t.setPadding(0, dp(context, 6), 0, dp(context, 2)); return t;
    }
    private static int dp(Context context, float n) { return Math.round(n * context.getResources().getDisplayMetrics().density); }
}
