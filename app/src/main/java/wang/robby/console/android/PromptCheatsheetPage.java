package wang.robby.console.android;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Prompt cheatsheet (`GET /api/mobile/v1/prompts`) with graceful fallback on 403/offline/`not_configured`
 * to built-in curated prompts plus locally saved templates in `SharedPreferences`.
 * Supports category pills, `{{variable}}` fill dialog, and 1-tap `ClipboardManager` copy.
 */
final class PromptCheatsheetPage {
    private PromptCheatsheetPage() {}
    private static final String PREFS = "god-prompts", KEY_LOCAL = "localPrompts";
    private static final Pattern VARIABLE_RE = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]{0,40})\\s*}}");

    private static String activeCategory = "全部";
    private static String searchQuery = "";

    static final class PromptItem {
        final String id, title, body;
        final List<String> variables, tags, agents;
        final boolean pinned, local;
        PromptItem(String id, String title, String body, List<String> variables, List<String> tags,
                   List<String> agents, boolean pinned, boolean local) {
            this.id = id;
            this.title = title;
            this.body = body;
            this.variables = variables;
            this.tags = tags;
            this.agents = agents;
            this.pinned = pinned;
            this.local = local;
        }
    }

    static List<String> extractVariables(String body) {
        List<String> vars = new ArrayList<>();
        if (body == null || body.isEmpty()) return vars;
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = VARIABLE_RE.matcher(body);
        while (m.find()) {
            String name = m.group(1);
            if (seen.add(name)) vars.add(name);
        }
        return vars;
    }

    static String fillVariables(String body, Map<String, String> values) {
        if (body == null) return "";
        if (values == null || values.isEmpty()) return body;
        Matcher m = VARIABLE_RE.matcher(body);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String name = m.group(1);
            String val = values.getOrDefault(name, m.group(0));
            m.appendReplacement(out, Matcher.quoteReplacement(val));
        }
        m.appendTail(out);
        return out.toString();
    }

    static List<PromptItem> builtinPrompts() {
        return List.of(
            new PromptItem("builtin-mece-audit", "MECE 代码审查",
                "请对 {{target}} 进行 MECE 拆解审计，检查契约一致性、边界错误与测试覆盖，最后列出 P0/P1 修复清单。",
                List.of("target"), List.of("工程", "内置"), List.of("codex", "claude"), true, false),
            new PromptItem("builtin-bug-repro", "最小复现排查",
                "当前在 {{module}} 出现问题：{{symptom}}。请定位根因、不要兜圈改无关代码，并写出最小单测复现。",
                List.of("module", "symptom"), List.of("调试", "内置"), List.of("codex"), false, false),
            new PromptItem("builtin-diff-review", "发布前 Diff 把关",
                "请以资深 Reviewer 视角审查当前变更，重点排查：1) 重复逻辑 2) 状态不一致 3) 权限与敏感信息泄露风险 4) 回退兼容性。",
                List.of(), List.of("工程", "内置"), List.of("claude"), true, false),
            new PromptItem("builtin-daily-brief", "日报要点提炼",
                "请将以下 {{topic}} 的零散记录整理成结构化简报：核心结论（不超过 3 条）、推进阻塞、明日优先事项。",
                List.of("topic"), List.of("写作", "内置"), List.of("deepseek"), false, false)
        );
    }

    static List<PromptItem> parseServer(JSONObject payload) {
        List<PromptItem> out = new ArrayList<>();
        if (payload == null || payload.optInt("schemaVersion", -1) != 1) return out;
        if ("not_configured".equals(payload.optString("status", ""))) return out;
        JSONArray arr = payload.optJSONArray("prompts");
        if (arr == null) return out;
        for (int i = 0; i < arr.length() && i < 200; i++) {
            JSONObject p = arr.optJSONObject(i);
            if (p == null) continue;
            String body = p.optString("body", "");
            if (body.trim().isEmpty()) continue;
            List<String> vars = strings(p.optJSONArray("variables"));
            if (vars.isEmpty()) vars = extractVariables(body);
            out.add(new PromptItem(
                clean(p.optString("id", p.optString("slug", "srv-" + i))),
                clean(p.optString("title", p.optString("slug", "未命名提示词"))),
                body,
                vars,
                strings(p.optJSONArray("tags")),
                strings(p.optJSONArray("agents")),
                Boolean.TRUE.equals(p.opt("pinned")),
                false
            ));
        }
        return out;
    }

    static List<PromptItem> loadLocal(Context ctx) {
        List<PromptItem> out = new ArrayList<>();
        if (ctx == null) return out;
        String raw = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LOCAL, null);
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length() && i < 100; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String body = o.optString("body", "");
                if (body.trim().isEmpty()) continue;
                String tag = clean(o.optString("tag", "本机"));
                out.add(new PromptItem(
                    clean(o.optString("id", "local-" + i)),
                    clean(o.optString("title", "自定义提示词")),
                    body,
                    extractVariables(body),
                    tag.isEmpty() ? List.of("本机") : List.of(tag, "本机"),
                    List.of(),
                    true,
                    true
                ));
            }
        } catch (JSONException ignored) { }
        return out;
    }

    private static void saveLocal(Context ctx, List<PromptItem> items) {
        JSONArray arr = new JSONArray();
        for (PromptItem it : items) {
            if (!it.local) continue;
            try {
                String tag = "本机";
                for (String t : it.tags) if (!"本机".equals(t)) { tag = t; break; }
                arr.put(new JSONObject().put("id", it.id).put("title", it.title).put("body", it.body).put("tag", tag));
            } catch (JSONException ignored) { }
        }
        ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LOCAL, arr.toString()).apply();
    }

    static boolean isCompatible(JSONObject p) {
        return p != null && p.optInt("schemaVersion", -1) == 1 && (p.has("status") || p.optJSONArray("prompts") != null);
    }

    static void render(Context ctx, LinearLayout root, JSONObject serverPayload) {
        boolean notConfigured = serverPayload != null && "not_configured".equals(serverPayload.optString("status", ""));
        String notice = notConfigured ? "云端提示词库未配置，当前使用内置与本机提示词。" : null;
        renderCombined(ctx, root, parseServer(serverPayload), notice);
    }

    static void renderFallback(Context ctx, LinearLayout root, String reason) {
        renderCombined(ctx, root, List.of(), "云端提示词库暂不可用（" + reason + "），已自动切换至内置与本机提示词。");
    }

    private static void renderCombined(Context ctx, LinearLayout root, List<PromptItem> serverItems, String fallbackNote) {
        root.removeAllViews();
        ConsoleTheme theme = new ConsoleTheme(ctx);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        root.addView(box);
        draw(ctx, theme, box, serverItems, fallbackNote);
    }

    private static void draw(Context ctx, ConsoleTheme theme, LinearLayout box, List<PromptItem> serverItems, String fallbackNote) {
        box.removeAllViews();
        List<PromptItem> localItems = loadLocal(ctx);
        List<PromptItem> all = new ArrayList<>();
        all.addAll(localItems);
        all.addAll(serverItems);
        if (serverItems.isEmpty()) all.addAll(builtinPrompts());

        if (fallbackNote != null) {
            LinearLayout banner = theme.card(box);
            TextView nt = theme.text(fallbackNote, 13, false);
            nt.setTextColor(theme.warning);
            banner.addView(nt);
        }

        LinearLayout toolbar = new LinearLayout(ctx);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        EditText filter = new EditText(ctx);
        theme.input(filter);
        filter.setHint("筛选提示词或 {{变量}}…");
        filter.setSingleLine(true);
        filter.setText(searchQuery);
        toolbar.addView(filter, new LinearLayout.LayoutParams(0, -2, 1));
        Button addBtn = theme.button("+ 本机模板", () -> showAddDialog(ctx, theme, box, serverItems, fallbackNote));
        theme.button(addBtn, true);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
        ap.leftMargin = theme.dp(8);
        toolbar.addView(addBtn, ap);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.bottomMargin = theme.dp(8);
        box.addView(toolbar, tlp);

        Set<String> categories = new LinkedHashSet<>();
        categories.add("全部");
        categories.add("置顶");
        for (PromptItem it : all) categories.addAll(it.tags);
        if (!categories.contains(activeCategory)) activeCategory = "全部";

        FlowRow pills = new FlowRow(ctx, theme.dp(6), theme.dp(6));
        pills.setPadding(0, 0, 0, theme.dp(10));
        for (String cat : categories) {
            boolean selected = cat.equals(activeCategory);
            TextView pill = theme.text(cat, 12, selected);
            pill.setTextColor(selected ? Color.WHITE : theme.ink);
            pill.setBackground(theme.ripple(theme.shape(selected ? theme.accent : theme.soft, 99), 99));
            pill.setPadding(theme.dp(12), theme.dp(5), theme.dp(12), theme.dp(5));
            pill.setFocusable(true);
            pill.setClickable(true);
            pill.setOnClickListener(v -> {
                activeCategory = cat;
                draw(ctx, theme, box, serverItems, fallbackNote);
            });
            pills.addView(pill);
        }
        box.addView(pills);

        LinearLayout listHolder = new LinearLayout(ctx);
        listHolder.setOrientation(LinearLayout.VERTICAL);
        box.addView(listHolder);

        Runnable renderList = () -> {
            listHolder.removeAllViews();
            String q = searchQuery.trim().toLowerCase(Locale.ROOT);
            int shown = 0;
            for (PromptItem it : all) {
                if ("置顶".equals(activeCategory) && !it.pinned) continue;
                if (!"全部".equals(activeCategory) && !"置顶".equals(activeCategory) && !it.tags.contains(activeCategory)) continue;
                if (!q.isEmpty()) {
                    String hay = (it.title + "\n" + it.body + "\n" + String.join(" ", it.tags)).toLowerCase(Locale.ROOT);
                    if (!hay.contains(q)) continue;
                }
                shown++;
                drawPromptCard(ctx, theme, listHolder, it, localItems, () -> draw(ctx, theme, box, serverItems, fallbackNote));
            }
            if (shown == 0) {
                LinearLayout empty = theme.card(listHolder);
                empty.addView(theme.text("没有匹配的提示词模板", 14, false));
            }
        };
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s == null ? "" : s.toString();
                renderList.run();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        renderList.run();
    }

    private static void drawPromptCard(Context ctx, ConsoleTheme theme, LinearLayout parent, PromptItem it,
                                       List<PromptItem> localItems, Runnable onMutateLocal) {
        LinearLayout card = theme.card(parent);
        LinearLayout top = new LinearLayout(ctx);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = theme.text(it.title, 15, true);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        if (!it.variables.isEmpty()) {
            TextView vCount = theme.text(it.variables.size() + " 变量", 11, true);
            vCount.setTextColor(theme.accent);
            top.addView(vCount);
        }
        card.addView(top);

        String tagLine = String.join(" · ", it.tags) + (it.agents.isEmpty() ? "" : " · " + String.join("/", it.agents));
        if (!tagLine.isEmpty()) {
            TextView meta = theme.text(tagLine, 11, false);
            meta.setTextColor(theme.faint);
            meta.setPadding(0, theme.dp(3), 0, 0);
            card.addView(meta);
        }

        TextView body = theme.text(it.body, 13, false);
        body.setTypeface(Typeface.MONOSPACE);
        body.setMaxLines(4);
        body.setPadding(theme.dp(10), theme.dp(8), theme.dp(10), theme.dp(8));
        body.setBackground(theme.shape(theme.soft, 10));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = theme.dp(8);
        card.addView(body, bp);

        LinearLayout actions = new LinearLayout(ctx);
        actions.setPadding(0, theme.dp(10), 0, 0);
        Button copyBtn = theme.button(it.variables.isEmpty() ? "一键复制" : "填变量并复制",
            () -> { if (it.variables.isEmpty()) copyToClipboard(ctx, it.body); else showVariableDialog(ctx, theme, it); });
        theme.button(copyBtn, true);
        actions.addView(copyBtn, new LinearLayout.LayoutParams(0, -2, 1));
        if (it.local) {
            Button delBtn = theme.button("删除", () -> {
                List<PromptItem> next = new ArrayList<>();
                for (PromptItem x : localItems) if (!x.id.equals(it.id)) next.add(x);
                saveLocal(ctx, next);
                onMutateLocal.run();
            });
            delBtn.setTextColor(theme.danger);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-2, -2);
            dp.leftMargin = theme.dp(8);
            actions.addView(delBtn, dp);
        }
        card.addView(actions);
    }

    private static void showVariableDialog(Context ctx, ConsoleTheme theme, PromptItem it) {
        LinearLayout form = new LinearLayout(ctx);
        form.setOrientation(LinearLayout.VERTICAL);
        int p = theme.dp(20);
        form.setPadding(p, theme.dp(8), p, 0);

        Map<String, EditText> inputs = new LinkedHashMap<>();
        for (String name : it.variables) {
            TextView lbl = theme.text("{{" + name + "}}", 12, true);
            lbl.setTextColor(theme.accent);
            lbl.setPadding(0, theme.dp(8), 0, theme.dp(2));
            form.addView(lbl);
            EditText ed = new EditText(ctx);
            theme.input(ed);
            ed.setSingleLine(true);
            ed.setHint("输入 " + name + "…");
            form.addView(ed);
            inputs.put(name, ed);
        }

        AlertDialog dlg = new AlertDialog.Builder(ctx)
            .setTitle(it.title)
            .setView(form)
            .setNegativeButton("取消", null)
            .setPositiveButton("填充并复制", (d, w) -> {
                Map<String, String> vals = new LinkedHashMap<>();
                for (Map.Entry<String, EditText> e : inputs.entrySet()) {
                    String txt = e.getValue().getText().toString().trim();
                    if (!txt.isEmpty()) vals.put(e.getKey(), txt);
                }
                copyToClipboard(ctx, fillVariables(it.body, vals));
            })
            .create();
        dlg.show();
        theme.dialog(dlg);
    }

    private static void showAddDialog(Context ctx, ConsoleTheme theme, LinearLayout box,
                                      List<PromptItem> serverItems, String fallbackNote) {
        LinearLayout form = new LinearLayout(ctx);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(theme.dp(20), theme.dp(8), theme.dp(20), 0);

        EditText titleIn = new EditText(ctx);
        theme.input(titleIn);
        titleIn.setHint("模板名称");
        titleIn.setSingleLine(true);
        form.addView(titleIn);

        EditText tagIn = new EditText(ctx);
        theme.input(tagIn);
        tagIn.setHint("分类标签（可选，默认 常用）");
        tagIn.setSingleLine(true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = theme.dp(8);
        form.addView(tagIn, tlp);

        EditText bodyIn = new EditText(ctx);
        theme.input(bodyIn);
        bodyIn.setHint("提示词正文，可用 {{变量名}} 占位");
        bodyIn.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        bodyIn.setMinLines(3);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = theme.dp(8);
        form.addView(bodyIn, blp);

        AlertDialog dlg = new AlertDialog.Builder(ctx)
            .setTitle("新建本机提示词")
            .setView(form)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create();
        dlg.show();
        theme.dialog(dlg);
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String title = titleIn.getText().toString().trim();
            String body = bodyIn.getText().toString().trim();
            if (title.isEmpty() || body.isEmpty()) {
                Toast.makeText(ctx, "请填写名称与正文", Toast.LENGTH_SHORT).show();
                return;
            }
            String tag = clean(tagIn.getText().toString());
            if (tag.isEmpty()) tag = "常用";
            List<PromptItem> next = loadLocal(ctx);
            next.add(0, new PromptItem("local-" + System.currentTimeMillis(), title, body, extractVariables(body), List.of(tag, "本机"), List.of(), true, true));
            saveLocal(ctx, next);
            dlg.dismiss();
            draw(ctx, theme, box, serverItems, fallbackNote);
        });
    }

    private static void copyToClipboard(Context ctx, String text) {
        ClipboardManager mgr = ctx.getSystemService(ClipboardManager.class);
        if (mgr == null) return;
        mgr.setPrimaryClip(ClipData.newPlainText("God 提示词", text));
        Toast.makeText(ctx, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
    }

    private static List<String> strings(JSONArray arr) {
        List<String> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length() && i < 30; i++) {
            String v = clean(arr.optString(i, ""));
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    private static String clean(String v) {
        return v == null ? "" : v.replaceAll("[\\u0000-\\u001f\\u007f-\\u009f]", " ").trim();
    }
}
