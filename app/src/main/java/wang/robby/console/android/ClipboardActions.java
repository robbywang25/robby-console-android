package wang.robby.console.android;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Cloud clipboard write actions: create private text item (7d / 30d), toggle visibility
 * (`publish` / `unpublish`), and move to trash (`POST clipboard/<id>/actions/trash`).
 * All requests carry lowercase UUID v4 `Idempotency-Key` and exact SHA-256 confirmations.
 * Handles federated sessions and `403 insufficient_scope` without altering `AuthSession.SCOPES`.
 */
final class ClipboardActions {
    private ClipboardActions() {}

    interface Host {
        void clipboardCreate(String content, int ttlDays, Runnable onDone, Consumer<String> onError);
        void clipboardToggleVisibility(JSONObject itemMeta, boolean makePublic, Runnable onDone, Consumer<String> onError);
        void clipboardTrash(JSONObject itemMeta, Runnable onDone, Consumer<String> onError);
    }

    static String sha256Hex(String content) {
        if (content == null) throw new IllegalArgumentException("内容为空");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static JSONObject createBody(String content, int ttlDays) {
        if (content == null || content.trim().isEmpty()) throw new IllegalArgumentException("请输入剪贴内容");
        if (ttlDays != 7 && ttlDays != 30) throw new IllegalArgumentException("有效期仅支持 7 天或 30 天");
        if (content.getBytes(StandardCharsets.UTF_8).length > 1_048_576) throw new IllegalArgumentException("内容超过 1 MiB 上限");
        String hash = sha256Hex(content);
        try {
            return new JSONObject()
                .put("schemaVersion", 1)
                .put("content", content)
                .put("ttlDays", ttlDays)
                .put("confirmation", "create-private:" + ttlDays + ":" + hash);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static JSONObject visibilityBody(String id, int expectedRevision, String expectedContentHash, boolean makePublic) {
        if (!ClipboardReveal.validId(id) || expectedRevision < 1 || !validHash(expectedContentHash)) {
            throw new IllegalArgumentException("条目元数据无效，请先刷新");
        }
        String verb = makePublic ? "publish" : "unpublish";
        try {
            return new JSONObject()
                .put("schemaVersion", 1)
                .put("expectedRevision", expectedRevision)
                .put("expectedContentHash", expectedContentHash)
                .put("isPublic", makePublic)
                .put("confirmation", verb + ":" + id + ":" + expectedRevision + ":" + expectedContentHash);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static JSONObject trashBody(String id, int expectedRevision, String expectedContentHash, boolean expectedIsPublic) {
        if (!ClipboardReveal.validId(id) || expectedRevision < 1 || !validHash(expectedContentHash)) {
            throw new IllegalArgumentException("条目元数据无效，请先刷新");
        }
        try {
            return new JSONObject()
                .put("schemaVersion", 1)
                .put("expectedRevision", expectedRevision)
                .put("expectedContentHash", expectedContentHash)
                .put("expectedIsPublic", expectedIsPublic)
                .put("confirmation", "trash:" + id + ":" + expectedRevision + ":" + expectedContentHash + ":" + (expectedIsPublic ? "1" : "0"));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static String visibilityRoute(String id) {
        if (!ClipboardReveal.validId(id)) throw new IllegalArgumentException("条目无效");
        return "clipboard/" + id + "/actions/set-visibility";
    }

    static String trashRoute(String id) {
        if (!ClipboardReveal.validId(id)) throw new IllegalArgumentException("条目无效");
        return "clipboard/" + id + "/actions/trash";
    }

    static JSONObject findItemById(JSONObject statePayload, String id) {
        if (statePayload == null || !ClipboardReveal.validId(id)) return null;
        JSONArray items = statePayload.optJSONArray("items");
        if (items == null) return null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && id.equals(item.optString("id", ""))) return item;
        }
        return null;
    }

    static boolean hasCasFields(JSONObject item) {
        return item != null
            && item.opt("revision") instanceof Number n && n.intValue() >= 1
            && validHash(item.optString("contentHash", ""));
    }

    static boolean validHash(String hash) {
        return hash != null && hash.matches("[a-f0-9]{64}");
    }

    /** Human-readable error message tailored for federated/scope/CAS denials. */
    static String explainError(Exception e) {
        if (e instanceof MobileApi.ApiError api) {
            if (api.status == 403 || "insufficient_scope".equals(api.code)) {
                return "当前登录未授予云剪贴板写入或回收站权限（403 insufficient_scope），请在网页端完成写入或使用具备对应权限的会话。";
            }
            if (api.status == 409) {
                if ("full".equals(api.code)) return "回收站已满（最多保留 64 条），请先在网页端清理。";
                return "该条目在云端已变化或已被移动，请下拉刷新后再试。";
            }
            if (api.status == 404) return "云剪贴板写入接口尚未启用或条目已过期。";
        }
        String msg = e == null ? null : e.getMessage();
        return msg != null && msg.matches("[\\p{IsHan}，。、；：！？ ·…A-Za-z0-9()-]{1,120}")
            ? msg
            : "云剪贴板操作未完成，请稍后重试。";
    }

    static String federatedNotice() {
        return "使用 Google 联合登录时当前页面仅限只读；需要创建、切换公开状态或删除，请使用 Passkey 登录或前往网页端操作。";
    }

    static void addCreateHeaderCard(Context ctx, ConsoleTheme theme, LinearLayout root) {
        if (!(ctx instanceof Host host)) return;
        LinearLayout bar = new LinearLayout(ctx);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = theme.dp(10);
        Button createBtn = theme.button("新建私密剪贴", () -> showCreateDialog(ctx, theme, host));
        theme.button(createBtn, true);
        bar.addView(createBtn, new LinearLayout.LayoutParams(-1, -2));
        root.addView(bar, lp);
    }

    static void fillItemControls(Context ctx, ConsoleTheme theme, LinearLayout body, JSONObject itemMeta) {
        String id = itemMeta == null ? "" : itemMeta.optString("id", "");
        if (!ClipboardReveal.validId(id) || !(ctx instanceof Host host)) return;
        boolean isPublic = Boolean.TRUE.equals(itemMeta.opt("isPublic"));

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, theme.dp(8), 0, 0);

        TextView feedback = theme.text("", 12, false);
        feedback.setPadding(0, theme.dp(6), 0, 0);
        feedback.setVisibility(android.view.View.GONE);

        Button visBtn = theme.button(isPublic ? "设为私密" : "设为公开", () -> {
            feedback.setVisibility(android.view.View.VISIBLE);
            feedback.setTextColor(theme.muted);
            feedback.setText("正在更新可见性…");
            host.clipboardToggleVisibility(itemMeta, !isPublic,
                () -> {
                    feedback.setTextColor(theme.success);
                    feedback.setText(isPublic ? "已设为私密" : "已设为公开");
                },
                err -> {
                    feedback.setTextColor(theme.danger);
                    feedback.setText(err);
                });
        });
        row.addView(visBtn, new LinearLayout.LayoutParams(0, -2, 1));

        Button trashBtn = theme.button("移入回收站", () -> showTrashConfirm(ctx, theme, host, itemMeta, feedback));
        trashBtn.setTextColor(theme.danger);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
        tp.leftMargin = theme.dp(8);
        row.addView(trashBtn, tp);

        body.addView(row);
        body.addView(feedback);
    }

    private static void showCreateDialog(Context ctx, ConsoleTheme theme, Host host) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = theme.dp(20);
        box.setPadding(p, theme.dp(8), p, 0);

        EditText input = new EditText(ctx);
        theme.input(input);
        input.setHint("输入要保存的私密文本…");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(3);
        input.setMaxLines(6);
        box.addView(input);

        TextView ttlLabel = theme.text("保留时长", 13, true);
        ttlLabel.setPadding(0, theme.dp(12), 0, theme.dp(4));
        box.addView(ttlLabel);

        RadioGroup ttlGroup = new RadioGroup(ctx);
        ttlGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton d7 = new RadioButton(ctx);
        d7.setText("7 天");
        d7.setId(android.view.View.generateViewId());
        RadioButton d30 = new RadioButton(ctx);
        d30.setText("30 天");
        d30.setId(android.view.View.generateViewId());
        ttlGroup.addView(d7);
        LinearLayout.LayoutParams lp30 = new LinearLayout.LayoutParams(-2, -2);
        lp30.leftMargin = theme.dp(16);
        ttlGroup.addView(d30, lp30);
        d7.setChecked(true);
        box.addView(ttlGroup);

        AlertDialog dlg = new AlertDialog.Builder(ctx)
            .setTitle("新建私密剪贴")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("创建", null)
            .create();
        dlg.show();
        theme.dialog(dlg);
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String text = input.getText().toString();
            if (text.trim().isEmpty()) {
                input.setError("请输入内容");
                return;
            }
            int ttlDays = d30.isChecked() ? 30 : 7;
            dlg.dismiss();
            host.clipboardCreate(text, ttlDays, () -> { }, msg -> showAlert(ctx, theme, "创建未完成", msg));
        });
    }

    private static void showTrashConfirm(Context ctx, ConsoleTheme theme, Host host, JSONObject itemMeta, TextView feedback) {
        AlertDialog dlg = new AlertDialog.Builder(ctx)
            .setTitle("移入回收站？")
            .setMessage("该剪贴内容将从可见列表移除，并在原有效期内保留于回收站（不支持永久删除，恢复仅在有效期内可用）。")
            .setNegativeButton("取消", null)
            .setPositiveButton("移入回收站", (d, w) -> {
                feedback.setVisibility(android.view.View.VISIBLE);
                feedback.setTextColor(theme.muted);
                feedback.setText("正在移入回收站…");
                host.clipboardTrash(itemMeta,
                    () -> {
                        feedback.setTextColor(theme.success);
                        feedback.setText("已移入回收站");
                    },
                    err -> {
                        feedback.setTextColor(theme.danger);
                        feedback.setText(err);
                    });
            })
            .create();
        dlg.show();
        theme.dialog(dlg);
    }

    private static void showAlert(Context ctx, ConsoleTheme theme, String title, String msg) {
        AlertDialog dlg = new AlertDialog.Builder(ctx)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("知道了", null)
            .create();
        dlg.show();
        theme.dialog(dlg);
    }
}
