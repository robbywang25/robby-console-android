package wang.robby.console.android;

import static org.junit.Assert.*;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** DeepSeek 模型 must show every official field, name prices only from the site's policy and never call a gap free. */
public final class DeepSeekModelsTest {
    private static final String FLASH = "{\"id\":\"deepseek-flash\",\"object\":\"model\",\"owned_by\":\"deepseek\",\"name\":\"DeepSeek-V4.1-Flash\","
        + "\"context_window\":1048576,\"max_output_tokens\":393216,\"input_modalities\":[\"text\",\"image\"],\"output_modalities\":[\"text\"],"
        + "\"effort\":{\"supported_levels\":[\"low\",\"high\",\"max\"],\"default_level\":\"high\"},"
        + "\"api_capabilities\":{\"anthropic_messages\":{\"system_prompt_update\":\"in-history\"}},\"future_field\":{\"a\":[1,null]}}";
    private static final String POLICY = "{\"name\":\"DeepSeek V4.1 Flash\",\"input\":0.3,\"cachedInput\":0.006,\"output\":1.2,\"context\":1000000,"
        + "\"maxOutput\":393216,\"vision\":true,\"thinking\":true,\"efforts\":[\"low\",\"high\",\"max\"],\"enabled\":true,"
        + "\"verifiedAt\":\"2026-10-15T06:15:00.000Z\",\"expiresAt\":\"2026-10-22T06:15:00.000Z\",\"source\":\"https://api-docs.deepseek.com/quick_start/pricing/\"}";
    private static JSONObject model(boolean listed, String official, String policy, boolean available) throws Exception {
        return new JSONObject().put("id", "deepseek-flash").put("listed", listed).put("official", official == null ? JSONObject.NULL : new JSONObject(official))
            .put("policy", policy == null ? JSONObject.NULL : new JSONObject(policy)).put("site", new JSONObject().put("available", available).put("reason", available ? "可用" : "价格待重新核验"));
    }
    private static String value(List<String> rows, String label) {
        for (int i = 0; i + 1 < rows.size(); i += 2) if (rows.get(i).equals(label)) return rows.get(i + 1);
        fail("missing row " + label); return null;
    }

    @Test public void everyOfficialFieldGetsARowIncludingUnknownOnes() throws Exception {
        List<String> rows = DeepSeekModels.officialRows(model(true, FLASH, POLICY, true));
        assertEquals("deepseek-flash", value(rows, "模型 ID"));
        assertEquals("DeepSeek-V4.1-Flash", value(rows, "显示名称"));
        assertEquals("deepseek", value(rows, "所有者"));
        assertEquals("1,048,576 Token", value(rows, "上下文窗口"));
        assertEquals("393,216 Token", value(rows, "单次最大输出"));
        assertEquals("文本、图片", value(rows, "可输入"));
        assertEquals("文本", value(rows, "可输出"));
        assertEquals("low、high、max", value(rows, "思考强度"));
        assertEquals("high", value(rows, "默认强度"));
        assertEquals("可在历史中追加，最新一条生效（in-history）", value(rows, "Anthropic 接口 · system 提示"));
        assertEquals("{\"a\":[1,null]}", value(rows, "future_field"));
        JSONObject official = new JSONObject(FLASH); int named = 0;
        for (String key : DeepSeekModels.KNOWN) if (official.has(key)) named++;
        assertEquals("one row per field (effort has two)", (named + 1 + 1) * 2, rows.size());
    }
    @Test public void pricesComeFromThePolicyWithTheirCheckAndSource() throws Exception {
        List<String> rows = DeepSeekModels.policyRows(model(true, FLASH, POLICY, true));
        assertEquals("$0.30", value(rows, "输入 · 缓存未命中"));
        assertEquals("$0.006", value(rows, "输入 · 缓存命中"));
        assertEquals("$1.20", value(rows, "输出"));
        assertEquals("2026-10-15", value(rows, "价格核对日期"));
        assertEquals("2026-10-22", value(rows, "价格有效至"));
        assertEquals("DeepSeek 官方定价页", value(rows, "价格来源"));
        assertEquals("支持", value(rows, "图片输入"));
        assertEquals("1,000,000 Token", value(rows, "本站上下文上限"));
    }
    @Test public void anUnpricedModelIsPendingNeverFree() throws Exception {
        JSONObject m = model(true, "{\"id\":\"new-model\",\"object\":\"model\",\"owned_by\":\"deepseek\"}", null, false);
        assertEquals("待核验", DeepSeekModels.state(m));
        List<String> rows = DeepSeekModels.policyRows(m);
        assertEquals(List.of("价格", "本站尚未登记，以官方定价页为准"), rows);
        assertFalse(String.join(" ", rows).contains("$0"));
    }
    @Test public void statesFollowTheSite() throws Exception {
        assertEquals("可用", DeepSeekModels.state(model(true, FLASH, POLICY, true)));
        assertEquals("本站未启用", DeepSeekModels.state(model(true, FLASH, POLICY, false)));
        assertEquals("官方未列出", DeepSeekModels.state(model(false, null, POLICY, false)));
        assertTrue(DeepSeekModels.officialRows(model(false, null, POLICY, false)).isEmpty());
        assertEquals("价格待重新核验", value(DeepSeekModels.siteRows(model(true, FLASH, POLICY, false)), "本站状态"));
        JSONArray all = new JSONArray().put(model(true, FLASH, POLICY, true)).put(model(true, FLASH, null, false)).put(model(false, null, POLICY, false));
        assertEquals(2, DeepSeekModels.listed(all)); assertEquals(1, DeepSeekModels.available(all)); assertEquals(1, DeepSeekModels.pending(all));
    }
    @Test public void summaryAndTitleReadLikeTheOfficialLimits() throws Exception {
        JSONObject m = model(true, FLASH, POLICY, true);
        assertEquals("DeepSeek-V4.1-Flash", DeepSeekModels.title(m));
        assertEquals("deepseek-flash · 上下文 1M · 输出 384K · 文本、图片", DeepSeekModels.summary(m));
        assertEquals("DeepSeek V4.1 Flash", DeepSeekModels.title(model(false, null, POLICY, false)));
        assertEquals("deepseek-flash", DeepSeekModels.title(model(true, "{\"id\":\"deepseek-flash\"}", null, false)));
        assertEquals("8,000", DeepSeekModels.shortTokens(8000));
        assertEquals("128K", DeepSeekModels.shortTokens(131072));
    }
    @Test public void listStateNeverCallsAFailedOrStaleReadFresh() throws Exception {
        JSONObject ok = new JSONObject().put("body", new JSONObject().put("data", new JSONArray())).put("stale", false).put("refreshFailed", false);
        assertEquals("正常", DeepSeekModels.listState(ok));
        assertEquals("需关注", DeepSeekModels.listState(new JSONObject(ok.toString()).put("refreshFailed", true)));
        assertEquals("数据已陈旧", DeepSeekModels.listState(new JSONObject(ok.toString()).put("stale", true)));
        assertEquals("暂不可用", DeepSeekModels.listState(new JSONObject().put("body", JSONObject.NULL)));
        assertEquals("暂无成功返回", DeepSeekModels.pretty(null));
        assertTrue(DeepSeekModels.pretty(new JSONObject(FLASH)).contains("\"future_field\""));
    }
    @Test public void textNeverCarriesControlCharactersAndLongValuesAreBounded() {
        assertEquals("a b", DeepSeekModels.plain("a\u0007b"));
        StringBuilder long900 = new StringBuilder(); for (int i = 0; i < 900; i++) long900.append('x');
        assertEquals(601, DeepSeekModels.json(long900.toString()).length());
        assertEquals("api.example.com/p", DeepSeekModels.source("https://api.example.com/p"));
        assertEquals("$3.96", DeepSeekModels.usd(3.96)); assertEquals("未知", DeepSeekModels.usd("1"));
    }
}
