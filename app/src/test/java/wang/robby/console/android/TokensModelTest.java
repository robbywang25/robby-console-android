package wang.robby.console.android;

import static org.junit.Assert.*;
import org.junit.Test;

/** Token 额度 must never call an unknown or stale quota healthy, and must only warn about real waste. */
public final class TokensModelTest {
    private static final long H = 3600_000L, NOW = 1_800_000_000_000L;
    @Test public void statusAndReasonsMatchTheWebAndIos() {
        assertEquals("已更新", TokensModel.status("connected"));
        assertEquals("待更新", TokensModel.status("stale"));
        assertEquals("未接入", TokensModel.status("not_configured"));
        assertEquals("状态未知", TokensModel.status("something_new"));
        assertEquals("数据暂时不可确认，不代表余额为零。", TokensModel.reason("network_error"));
    }
    @Test public void levelsNeverInventAValue() {
        assertEquals(0, TokensModel.level(null));
        assertEquals(1, TokensModel.level(80.0));
        assertEquals(2, TokensModel.level(35.0));
        assertEquals(3, TokensModel.level(5.0));
    }
    @Test public void resettingSoonOnlyWhenQuotaWouldBeWasted() {
        long start = NOW - 6 * 24 * H, reset = NOW + 12 * H; // 7-day window, 12 h left (~7% of the period)
        assertTrue(TokensModel.resettingSoon(true, 60.0, start, reset, NOW));
        assertFalse("stale readings never warn", TokensModel.resettingSoon(false, 60.0, start, reset, NOW));
        assertFalse("little left is not waste", TokensModel.resettingSoon(true, 10.0, start, reset, NOW));
        assertFalse("reset more than a day away", TokensModel.resettingSoon(true, 60.0, start, NOW + 30 * H, NOW));
        assertFalse("used ahead of the period", TokensModel.resettingSoon(true, 30.0, NOW - 2 * H, NOW + 22 * H, NOW));
        assertFalse("already reset", TokensModel.resettingSoon(true, 60.0, start, NOW - H, NOW));
    }
    @Test public void countdownIsHumanAndNeverNegative() {
        assertEquals("30 分钟后重置", TokensModel.countdown(NOW + 30 * 60_000L, NOW));
        assertEquals("3 小时 20 分后重置", TokensModel.countdown(NOW + 200 * 60_000L, NOW));
        assertEquals("3 天后重置", TokensModel.countdown(NOW + 72 * H, NOW));
        assertNull(TokensModel.countdown(NOW - 1, NOW));
    }
    static org.json.JSONObject window(String id, String label, double remaining, String group) throws Exception {
        return new org.json.JSONObject().put("id", id).put("label", label).put("remainingPercent", remaining).put("quotaGroup", group == null ? org.json.JSONObject.NULL : group);
    }
    static java.util.List<String> shown(org.json.JSONObject account) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (TokensModel.Period p : TokensModel.boardWindows(account)) out.add(p.label + "=" + p.window.optString("id"));
        return out;
    }
    @Test public void claudeShowsItsFiveHourAndWeeklyBalancesOthersTheTightest() throws Exception {
        org.json.JSONObject claude = new org.json.JSONObject().put("product", "claude").put("windows", new org.json.JSONArray()
                .put(window("five_hour", "5 小时", 72, "default")).put(window("seven_day", "每周", 30, null)).put(window("seven_day_opus", "Opus 每周", 3, null)));
        assertEquals(java.util.List.of("5 小时=five_hour", "每周=seven_day"), shown(claude));
        org.json.JSONObject labelled = new org.json.JSONObject().put("product", "claude").put("windows", new org.json.JSONArray()
                .put(window("five", "5 小时", 72, null)).put(window("week", "每周", 30, null)));
        assertEquals("matched by the collector's labels when ids differ", java.util.List.of("5 小时=five", "每周=week"), shown(labelled));
        org.json.JSONObject codex = new org.json.JSONObject().put("product", "codex").put("windows", new org.json.JSONArray()
                .put(window("primary", "5 小时", 64, "default")).put(window("secondary", "每周", 18, null)).put(window("spare", "备用", 1, "reserve")));
        assertEquals("tightest, never a reserve window", java.util.List.of("null=secondary"), shown(codex));
        assertEquals(java.util.List.of(), shown(new org.json.JSONObject().put("product", "grok")));
    }
}
