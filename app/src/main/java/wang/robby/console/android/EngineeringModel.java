package wang.robby.console.android;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Pure Java model + filter/expand/GitHub external link rendering for `/api/mobile/v1/engineering/v2`
 * (and legacy `engineering` fallback).
 */
final class EngineeringModel {
    private EngineeringModel() {}
    private static final Set<String> ALLOWED_OWNERS = Set.of("robbywang25", "robbyisrobby");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.SIMPLIFIED_CHINESE);

    /** Process-memory repo filter selection, matching Tokens/Calendar tab state. */
    private static String selectedRepo = null;

    static final class Repo {
        final String repository, name, description, state, url;
        Repo(String repository, String name, String description, String state, String url) {
            this.repository = repository;
            this.name = name;
            this.description = description;
            this.state = state;
            this.url = url;
        }
    }

    static final class Item {
        final String id, repository, sha, timestamp, type, summary, description, commitUrl;
        Item(String id, String repository, String sha, String timestamp, String type,
             String summary, String description, String commitUrl) {
            this.id = id;
            this.repository = repository;
            this.sha = sha;
            this.timestamp = timestamp;
            this.type = type;
            this.summary = summary;
            this.description = description;
            this.commitUrl = commitUrl;
        }
    }

    static final class Model {
        final int schemaVersion;
        final boolean truncated;
        final int returnedItems;
        final List<Repo> repos;
        final List<Item> items;
        Model(int schemaVersion, boolean truncated, int returnedItems, List<Repo> repos, List<Item> items) {
            this.schemaVersion = schemaVersion;
            this.truncated = truncated;
            this.returnedItems = returnedItems;
            this.repos = repos;
            this.items = items;
        }
    }

    static Model parse(JSONObject payload) {
        if (payload == null) return new Model(0, false, 0, List.of(), List.of());
        int schema = payload.optInt("schemaVersion", 1);
        JSONObject coverage = payload.optJSONObject("coverage");
        boolean truncated = coverage != null && Boolean.TRUE.equals(coverage.opt("truncated"));
        int returned = coverage == null ? 0 : coverage.optInt("returnedItems", 0);

        List<Repo> repos = new ArrayList<>();
        JSONArray repoArr = payload.optJSONArray("repositoryIndex");
        if (repoArr != null) {
            for (int i = 0; i < repoArr.length() && i < 200; i++) {
                JSONObject r = repoArr.optJSONObject(i);
                if (r == null) continue;
                String slug = clean(r.optString("repository", ""));
                String name = clean(r.optString("name", slug.isEmpty() ? "未命名项目" : slug));
                repos.add(new Repo(
                    slug.isEmpty() ? name : slug,
                    name,
                    clean(r.optString("description", "")),
                    clean(r.optString("state", "")),
                    validatedGithubUrl(r.optString("url", null))
                ));
            }
        }

        List<Item> items = new ArrayList<>();
        JSONArray itemArr = payload.optJSONArray("items");
        if (itemArr != null) {
            for (int i = 0; i < itemArr.length() && i < 1000; i++) {
                JSONObject it = itemArr.optJSONObject(i);
                if (it == null) continue;
                String id = clean(it.optString("id", ""));
                String repo = clean(it.optString("repository", ""));
                String sha = clean(it.optString("sha", ""));
                items.add(new Item(
                    id.isEmpty() ? repo + "|" + sha + "|" + i : id,
                    repo,
                    sha,
                    clean(it.optString("timestamp", "")),
                    clean(it.optString("type", "")),
                    clean(it.optString("summary", "无摘要")),
                    clean(it.optString("description", "")),
                    validatedGithubUrl(it.optString("commitUrl", null))
                ));
            }
        }
        return new Model(schema, truncated, returned > 0 ? returned : items.size(), repos, items);
    }

    static List<Item> filterItems(Model model, String repoFilter) {
        if (model == null) return List.of();
        if (repoFilter == null || repoFilter.isEmpty()) return model.items;
        List<Item> out = new ArrayList<>();
        for (Item item : model.items) {
            if (repoFilter.equalsIgnoreCase(item.repository)) out.add(item);
        }
        return out;
    }

    /** Only HTTPS github.com URLs under authorized owner paths (`robbywang25` / `robbyisrobby`) are allowed. */
    static String validatedGithubUrl(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > 300 || trimmed.contains("..")) return null;
        try {
            URI uri = URI.create(trimmed);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return null;
            if (!"github.com".equalsIgnoreCase(uri.getHost())) return null;
            if (uri.getRawUserInfo() != null) return null;
            String path = uri.getRawPath();
            if (path == null || !path.startsWith("/")) return null;
            String[] segs = path.substring(1).split("/", 3);
            if (segs.length < 2) return null;
            String owner = segs[0].toLowerCase(Locale.ROOT);
            if (!ALLOWED_OWNERS.contains(owner)) return null;
            if (!segs[1].matches("[A-Za-z0-9._-]+")) return null;
            return uri.toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static void renderPage(Context ctx, LinearLayout root, JSONObject payload) {
        ConsoleTheme theme = new ConsoleTheme(ctx);
        Model m = parse(payload);
        if (selectedRepo != null && !hasRepo(m, selectedRepo)) selectedRepo = null;

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        root.addView(box);
        draw(ctx, theme, box, m);
    }

    private static void draw(Context ctx, ConsoleTheme theme, LinearLayout box, Model m) {
        box.removeAllViews();

        LinearLayout overview = theme.card(box);
        TextView oh = theme.text("工程概览", 15, true);
        oh.setAccessibilityHeading(true);
        overview.addView(oh);
        LinearLayout metrics = new LinearLayout(ctx);
        metrics.setPadding(0, theme.dp(8), 0, 0);
        addMetric(ctx, theme, metrics, "项目", String.valueOf(m.repos.size()));
        addMetric(ctx, theme, metrics, "日志", String.valueOf(m.items.size()));
        addMetric(ctx, theme, metrics, "覆盖", m.truncated ? "已截断" : "完整");
        overview.addView(metrics);

        if (m.truncated) {
            TextView note = theme.text("日志已截断：当前返回 " + m.returnedItems + " 条，并非全部历史。", 12, false);
            note.setTextColor(theme.faint);
            note.setPadding(theme.dp(6), 0, theme.dp(6), theme.dp(8));
            box.addView(note);
        }

        if (!m.repos.isEmpty()) {
            sectionHeading(theme, box, "全部项目");
            LinearLayout list = theme.card(box);
            list.setPadding(theme.dp(4), theme.dp(4), theme.dp(4), theme.dp(4));
            for (int i = 0; i < m.repos.size(); i++) {
                Repo r = m.repos.get(i);
                if (i > 0) addDivider(ctx, theme, list);
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(theme.dp(12), theme.dp(10), theme.dp(12), theme.dp(10));
                LinearLayout top = new LinearLayout(ctx);
                top.setGravity(Gravity.CENTER_VERTICAL);
                TextView title = theme.text(r.name, 15, true);
                top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
                String state = stateLabel(r.state);
                if (!state.isEmpty()) {
                    TextView st = theme.text(state, 12, true);
                    int tone = theme.status(state);
                    st.setTextColor(tone == 0 ? theme.muted : tone);
                    top.addView(st);
                }
                row.addView(top);
                if (!r.description.isEmpty()) {
                    TextView desc = theme.text(r.description, 12, false);
                    desc.setTextColor(theme.muted);
                    desc.setPadding(0, theme.dp(2), 0, 0);
                    row.addView(desc);
                }
                if (r.url != null) {
                    TextView link = theme.text("打开 GitHub 仓库 ↗", 12, true);
                    link.setTextColor(theme.accent);
                    link.setPadding(0, theme.dp(6), 0, 0);
                    link.setFocusable(true);
                    link.setClickable(true);
                    link.setOnClickListener(v -> openUrl(ctx, r.url));
                    row.addView(link);
                }
                list.addView(row);
            }
        }

        List<String> repoNames = collectRepoNames(m);
        if (!repoNames.isEmpty()) {
            FlowRow pills = new FlowRow(ctx, theme.dp(6), theme.dp(6));
            pills.setPadding(theme.dp(2), theme.dp(4), theme.dp(2), theme.dp(8));
            addFilterPill(ctx, theme, pills, "全部 (" + m.items.size() + ")", selectedRepo == null, () -> {
                selectedRepo = null;
                draw(ctx, theme, box, m);
            });
            for (String repo : repoNames) {
                addFilterPill(ctx, theme, pills, repo, repo.equals(selectedRepo), () -> {
                    selectedRepo = repo.equals(selectedRepo) ? null : repo;
                    draw(ctx, theme, box, m);
                });
            }
            box.addView(pills);
        }

        List<Item> shown = filterItems(m, selectedRepo);
        sectionHeading(theme, box, selectedRepo == null ? "工程迭代日志" : selectedRepo + " 迭代日志");
        if (shown.isEmpty()) {
            LinearLayout empty = theme.card(box);
            empty.addView(theme.text("暂无匹配的迭代日志", 14, false));
            return;
        }
        LinearLayout logCard = theme.card(box);
        logCard.setPadding(theme.dp(4), theme.dp(4), theme.dp(4), theme.dp(4));
        for (int i = 0; i < shown.size() && i < 150; i++) {
            Item item = shown.get(i);
            if (i > 0) addDivider(ctx, theme, logCard);
            addExpandableItem(ctx, theme, logCard, item);
        }
    }

    private static void addExpandableItem(Context ctx, ConsoleTheme theme, LinearLayout group, Item item) {
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(theme.dp(12), theme.dp(10), theme.dp(12), theme.dp(10));
        head.setBackground(theme.ripple(theme.shape(Color.TRANSPARENT, 14), 14));
        head.setFocusable(true);
        head.setClickable(true);

        TextView summary = theme.text(item.summary, 14, true);
        head.addView(summary);
        String shortSha = item.sha.length() > 7 ? item.sha.substring(0, 7) : item.sha;
        String meta = joinNonEmpty(" · ", item.repository, shortSha, formatTime(item.timestamp));
        if (!meta.isEmpty()) {
            TextView sub = theme.text(meta, 12, false);
            sub.setTextColor(theme.muted);
            sub.setPadding(0, theme.dp(3), 0, 0);
            head.addView(sub);
        }
        wrap.addView(head);

        LinearLayout details = new LinearLayout(ctx);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(theme.dp(12), 0, theme.dp(12), theme.dp(10));
        details.setVisibility(android.view.View.GONE);
        if (!item.description.isEmpty()) {
            TextView desc = theme.text(item.description, 13, false);
            desc.setPadding(0, 0, 0, theme.dp(6));
            details.addView(desc);
        }
        if (!item.type.isEmpty()) {
            TextView tp = theme.text("类型：" + item.type, 12, false);
            tp.setTextColor(theme.faint);
            details.addView(tp);
        }
        if (!item.sha.isEmpty()) {
            TextView sh = theme.text("提交 SHA：" + item.sha, 12, false);
            sh.setTextColor(theme.faint);
            details.addView(sh);
        }
        if (item.commitUrl != null) {
            TextView ext = theme.text("在 GitHub 查看提交 ↗", 13, true);
            ext.setTextColor(theme.accent);
            ext.setPadding(0, theme.dp(6), 0, 0);
            ext.setFocusable(true);
            ext.setClickable(true);
            ext.setOnClickListener(v -> openUrl(ctx, item.commitUrl));
            details.addView(ext);
        }
        wrap.addView(details);

        head.setOnClickListener(v -> details.setVisibility(
            details.getVisibility() == android.view.View.VISIBLE ? android.view.View.GONE : android.view.View.VISIBLE));
        group.addView(wrap);
    }

    private static void addFilterPill(Context ctx, ConsoleTheme theme, FlowRow parent, String label, boolean selected, Runnable action) {
        TextView pill = theme.text(label, 12, selected);
        pill.setTextColor(selected ? Color.WHITE : theme.ink);
        pill.setBackground(theme.ripple(theme.shape(selected ? theme.accent : theme.soft, 99), 99));
        pill.setPadding(theme.dp(12), theme.dp(5), theme.dp(12), theme.dp(5));
        pill.setFocusable(true);
        pill.setClickable(true);
        pill.setSelected(selected);
        pill.setOnClickListener(v -> action.run());
        parent.addView(pill);
    }

    private static void addMetric(Context ctx, ConsoleTheme theme, LinearLayout row, String label, String val) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(theme.text(label, 12, false));
        TextView v = theme.text(val, 17, true);
        v.setPadding(0, theme.dp(2), 0, 0);
        col.addView(v);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private static void sectionHeading(ConsoleTheme theme, LinearLayout root, String text) {
        TextView h = theme.text(text, 15, true);
        h.setAccessibilityHeading(true);
        h.setPadding(theme.dp(6), theme.dp(12), 0, theme.dp(8));
        root.addView(h);
    }

    private static void addDivider(Context ctx, ConsoleTheme theme, LinearLayout parent) {
        android.view.View d = new android.view.View(ctx);
        d.setBackgroundColor(theme.line);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, theme.dp(.5f)));
        lp.setMargins(theme.dp(12), 0, theme.dp(12), 0);
        parent.addView(d, lp);
    }

    private static boolean hasRepo(Model m, String target) {
        for (Repo r : m.repos) if (target.equals(r.repository)) return true;
        for (Item it : m.items) if (target.equals(it.repository)) return true;
        return false;
    }

    private static List<String> collectRepoNames(Model m) {
        List<String> out = new ArrayList<>();
        for (Repo r : m.repos) if (!r.repository.isEmpty() && !out.contains(r.repository)) out.add(r.repository);
        for (Item it : m.items) if (!it.repository.isEmpty() && !out.contains(it.repository)) out.add(it.repository);
        return out;
    }

    private static void openUrl(Context ctx, String url) {
        String safe = validatedGithubUrl(url);
        if (safe == null) return;
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(safe)).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(ctx, "未找到可打开链接的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    private static String stateLabel(String raw) {
        if ("operational".equals(raw) || "healthy".equals(raw) || "ok".equals(raw) || "active".equals(raw)) return "正常";
        if ("archived".equals(raw)) return "已归档";
        if ("maintenance".equals(raw)) return "维护中";
        return raw;
    }

    private static String formatTime(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        try {
            return TS_FMT.withZone(ZoneId.systemDefault()).format(Instant.parse(raw));
        } catch (RuntimeException e) {
            return raw;
        }
    }

    private static String joinNonEmpty(String sep, String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) if (p != null && !p.isEmpty()) out.add(p);
        return String.join(sep, out);
    }

    private static String clean(String v) {
        return v == null ? "" : v.replaceAll("[\\u0000-\\u001f\\u007f-\\u009f]", " ").trim();
    }
}
