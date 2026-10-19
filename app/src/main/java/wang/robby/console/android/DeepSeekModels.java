package wang.robby.console.android;

import org.json.JSONArray;
import org.json.JSONObject;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Wording for the DeepSeek 模型 page. Each official model record is named field by field; a field this app does not
 * know yet is still listed by its own key so nothing the official GET /models returns is hidden. Prices come only from
 * deepseek.robby.wang's verified policy and are never inferred: an unpriced model says so instead of showing zero.
 */
final class DeepSeekModels {
    private DeepSeekModels() {}
    static final String UNKNOWN = "未知";
    static final String PRICING_PAGE = "https://api-docs.deepseek.com/quick_start/pricing/";
    /** Official model fields with a named row; any other key goes under its own name. */
    static final Set<String> KNOWN = Set.of("id", "object", "owned_by", "name", "context_window", "max_output_tokens",
        "input_modalities", "output_modalities", "effort", "api_capabilities");

    static String title(JSONObject m) {
        String name = plain(official(m).opt("name"));
        if (name.isEmpty()) name = plain(policy(m).opt("name"));
        return name.isEmpty() ? plain(m.opt("id")) : name;
    }
    static JSONObject official(JSONObject m) { JSONObject o = m.optJSONObject("official"); return o == null ? new JSONObject() : o; }
    static JSONObject policy(JSONObject m) { JSONObject o = m.optJSONObject("policy"); return o == null ? new JSONObject() : o; }
    static boolean priced(JSONObject m) { return m.optJSONObject("policy") != null; }

    /** Trailing state of one row, in the words of deepseek.robby.wang. */
    static String state(JSONObject m) {
        if (!Boolean.TRUE.equals(m.opt("listed"))) return "官方未列出";
        if (!priced(m)) return "待核验";
        JSONObject site = m.optJSONObject("site");
        return site != null && Boolean.TRUE.equals(site.opt("available")) ? "可用" : "本站未启用";
    }
    /** "deepseek-flash · 上下文 1M · 输出 384K · 文本、图片" from the official record. */
    static String summary(JSONObject m) {
        JSONObject o = official(m); List<String> parts = new ArrayList<>(); parts.add(plain(m.opt("id")));
        if (o.opt("context_window") instanceof Number) parts.add("上下文 " + shortTokens(((Number) o.opt("context_window")).longValue()));
        if (o.opt("max_output_tokens") instanceof Number) parts.add("输出 " + shortTokens(((Number) o.opt("max_output_tokens")).longValue()));
        String in = modalities(o.optJSONArray("input_modalities")); if (!in.equals(UNKNOWN)) parts.add(in);
        return String.join(" · ", parts);
    }
    /** 1,048,576 → "1M", 393,216 → "384K", 8,000 → "8K"; binary units as the official limits use them. */
    static String shortTokens(long n) {
        if (n >= 1048576 && n % 1048576 == 0) return n / 1048576 + "M";
        if (n >= 1024 && n % 1024 == 0) return n / 1024 + "K";
        return grouped(n);
    }
    static String tokens(Object v) { return v instanceof Number ? grouped(((Number) v).longValue()) + " Token" : UNKNOWN; }
    static String grouped(long n) { return NumberFormat.getIntegerInstance(Locale.SIMPLIFIED_CHINESE).format(n); }
    static String modalities(JSONArray a) {
        if (a == null || a.length() == 0) return UNKNOWN; List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) { String v = plain(a.opt(i));
            out.add(switch (v) { case "text" -> "文本"; case "image" -> "图片"; case "audio" -> "音频"; case "video" -> "视频"; case "file" -> "文件"; default -> v; }); }
        return String.join("、", out);
    }
    static String levels(JSONObject effort) {
        JSONArray a = effort == null ? null : effort.optJSONArray("supported_levels"); if (a == null || a.length() == 0) return UNKNOWN;
        List<String> out = new ArrayList<>(); for (int i = 0; i < a.length(); i++) out.add(plain(a.opt(i))); return String.join("、", out);
    }
    static String systemPromptUpdate(String v) {
        switch (v) {
            case "leading-only": return "只认对话开头的 system（leading-only）";
            case "in-history": return "可在历史中追加，最新一条生效（in-history）";
            default: return v.isEmpty() ? UNKNOWN : v;
        }
    }
    /** USD per 1M tokens, keeping the official precision: 0.3 → "$0.30", 0.006 → "$0.006". */
    static String usd(Object v) {
        if (!(v instanceof Number) || !Double.isFinite(((Number) v).doubleValue())) return UNKNOWN;
        return "$" + new DecimalFormat("0.00####", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(((Number) v).doubleValue());
    }
    static String day(Object v) { String s = plain(v); return s.length() >= 10 && s.matches("\\d{4}-\\d{2}-\\d{2}.*") ? s.substring(0, 10) : UNKNOWN; }
    static String source(Object v) {
        String s = plain(v); if (s.equals(PRICING_PAGE)) return "DeepSeek 官方定价页";
        try { java.net.URI u = new java.net.URI(s); return u.getHost() == null ? UNKNOWN : u.getHost() + (u.getPath() == null ? "" : u.getPath()); }
        catch (java.net.URISyntaxException e) { return UNKNOWN; }
    }
    /** Every official field as label / value pairs: named ones first, then unknown keys verbatim, nested values as JSON. */
    static List<String> officialRows(JSONObject m) {
        JSONObject o = official(m); List<String> rows = new ArrayList<>(); if (o.length() == 0) return rows;
        add(rows, "模型 ID", plain(o.opt("id")));
        if (o.has("name")) add(rows, "显示名称", plain(o.opt("name")));
        if (o.has("owned_by")) add(rows, "所有者", plain(o.opt("owned_by")));
        if (o.has("object")) add(rows, "对象类型", plain(o.opt("object")));
        if (o.has("context_window")) add(rows, "上下文窗口", tokens(o.opt("context_window")));
        if (o.has("max_output_tokens")) add(rows, "单次最大输出", tokens(o.opt("max_output_tokens")));
        if (o.has("input_modalities")) add(rows, "可输入", modalities(o.optJSONArray("input_modalities")));
        if (o.has("output_modalities")) add(rows, "可输出", modalities(o.optJSONArray("output_modalities")));
        JSONObject effort = o.optJSONObject("effort");
        if (effort != null) { add(rows, "思考强度", levels(effort)); if (effort.has("default_level")) add(rows, "默认强度", plain(effort.opt("default_level"))); }
        JSONObject apis = o.optJSONObject("api_capabilities");
        if (apis != null) for (String api : sorted(apis)) {
            JSONObject caps = apis.optJSONObject(api);
            if (api.equals("anthropic_messages") && caps != null && caps.length() == 1 && caps.opt("system_prompt_update") instanceof String)
                add(rows, "Anthropic 接口 · system 提示", systemPromptUpdate(caps.optString("system_prompt_update")));
            else add(rows, "接口能力 · " + api, json(apis.opt(api)));
        }
        for (String key : sorted(o)) if (!KNOWN.contains(key)) add(rows, key, json(o.opt(key)));
        return rows;
    }
    /** The site's price and capability registration, with when and where it was checked. */
    static List<String> policyRows(JSONObject m) {
        List<String> rows = new ArrayList<>();
        if (!priced(m)) { add(rows, "价格", "本站尚未登记，以官方定价页为准"); return rows; }
        JSONObject p = policy(m);
        add(rows, "输入 · 缓存未命中", usd(p.opt("input")));
        add(rows, "输入 · 缓存命中", usd(p.opt("cachedInput")));
        add(rows, "输出", usd(p.opt("output")));
        add(rows, "深度思考", flag(p.opt("thinking")));
        add(rows, "图片输入", flag(p.opt("vision")));
        JSONArray efforts = p.optJSONArray("efforts"); List<String> e = new ArrayList<>(); if (efforts != null) for (int i = 0; i < efforts.length(); i++) e.add(plain(efforts.opt(i)));
        add(rows, "本站可选强度", e.isEmpty() ? "无" : String.join("、", e));
        add(rows, "本站上下文上限", tokens(p.opt("context")));
        add(rows, "本站输出上限", tokens(p.opt("maxOutput")));
        add(rows, "价格核对日期", day(p.opt("verifiedAt")));
        add(rows, "价格有效至", day(p.opt("expiresAt")));
        add(rows, "价格来源", source(p.opt("source")));
        return rows;
    }
    static List<String> siteRows(JSONObject m) {
        List<String> rows = new ArrayList<>(); JSONObject site = m.optJSONObject("site");
        add(rows, "官方列表", Boolean.TRUE.equals(m.opt("listed")) ? "在列" : "不在列");
        add(rows, "本站状态", site == null ? "未登记" : plain(site.opt("reason")));
        return rows;
    }
    /** Pretty JSON of the complete official response; the gateway already bounds it (64 KiB from DeepSeek, 256 KiB in all). */
    static String pretty(Object body) {
        if (!(body instanceof JSONObject)) return "暂无成功返回";
        try { return ((JSONObject) body).toString(2); } catch (org.json.JSONException e) { return "返回格式异常"; }
    }
    /** Response line: "正常", "刷新失败 · 显示上次结果" or "数据已陈旧". */
    static String listState(JSONObject response) {
        if (response.opt("body") == null || response.isNull("body")) return "暂不可用";
        if (Boolean.TRUE.equals(response.opt("refreshFailed"))) return "需关注";
        if (Boolean.TRUE.equals(response.opt("stale"))) return "数据已陈旧";
        return "正常";
    }
    static int available(JSONArray models) { int n = 0; for (int i = 0; models != null && i < models.length(); i++) { JSONObject m = models.optJSONObject(i); if (m != null && state(m).equals("可用")) n++; } return n; }
    static int pending(JSONArray models) { int n = 0; for (int i = 0; models != null && i < models.length(); i++) { JSONObject m = models.optJSONObject(i); if (m != null && state(m).equals("待核验")) n++; } return n; }
    static int listed(JSONArray models) { int n = 0; for (int i = 0; models != null && i < models.length(); i++) { JSONObject m = models.optJSONObject(i); if (m != null && Boolean.TRUE.equals(m.opt("listed"))) n++; } return n; }

    private static void add(List<String> rows, String label, String value) { rows.add(label); rows.add(value == null || value.isEmpty() ? UNKNOWN : value); }
    private static String flag(Object v) { return v instanceof Boolean ? ((Boolean) v ? "支持" : "不支持") : UNKNOWN; }
    private static List<String> sorted(JSONObject o) { List<String> keys = new ArrayList<>(); java.util.Iterator<String> it = o.keys(); while (it.hasNext()) keys.add(it.next()); java.util.Collections.sort(keys); return keys; }
    /** Strings as text without control characters; anything else as compact JSON; never more than 600 characters. */
    static String json(Object v) {
        String s = v == null || v == JSONObject.NULL ? "null" : v instanceof String ? (String) v : v instanceof JSONObject || v instanceof JSONArray ? v.toString() : String.valueOf(v);
        s = s.replaceAll("[\\p{Cntrl}]", " "); return s.length() > 600 ? s.substring(0, 600) + "…" : s;
    }
    static String plain(Object v) { return v instanceof String ? ((String) v).replaceAll("[\\p{Cntrl}]", " ").trim() : v instanceof Number || v instanceof Boolean ? String.valueOf(v) : ""; }
}
