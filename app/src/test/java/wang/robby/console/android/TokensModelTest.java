package wang.robby.console.android;

import static org.junit.Assert.*;
import org.junit.Test;

/** Token 额度 must never call an unknown or stale quota healthy, and must only warn about real waste. */
public final class TokensModelTest {
    private static final long H = 3600_000L, NOW = 1_800_000_000_000L;
    @Test public void statusAndReasonsMatchTheWebAndIos() {
        assertEquals("已更新", TokensModel.status("connected"));
        assertEquals("待更新", TokensModel.status("stale"));
        assertEquals("待接入", TokensModel.status("not_configured"));
        assertEquals("待授权", TokensModel.status("needs_auth"));
        assertEquals("状态未知", TokensModel.status("something_new"));
    }
    @Test public void levelsNeverInventAValue() {
        assertEquals(0, TokensModel.level(null));
        assertEquals(1, TokensModel.level(80.0));
        assertEquals(2, TokensModel.level(35.0));
        assertEquals(3, TokensModel.level(5.0));
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
    @Test public void codexBoardLineShowsPointsAndResetCardsLikeIos() throws Exception {
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        long now = java.time.Instant.parse("2026-10-17T02:00:00Z").toEpochMilli();
        org.json.JSONObject codex = new org.json.JSONObject().put("product", "codex")
                .put("windows", new org.json.JSONArray().put(window("primary", "5 小时", 64, "default")))
                .put("credits", new org.json.JSONObject().put("remaining", 1250).put("unlimited", false))
                .put("resetCredits", new org.json.JSONObject().put("available", 2).put("applicable", 1).put("cards", new org.json.JSONArray()
                        .put(new org.json.JSONObject().put("status", "available").put("expiresAt", "2026-10-20T04:00:00Z"))
                        .put(new org.json.JSONObject().put("status", "redeemed").put("expiresAt", "2026-10-18T04:00:00Z"))));
        assertEquals("Points 1,250 · 重置卡 2 张 · 最近 10月20日到期", TokensModel.boardExtras(codex, true, now, zone));
        assertEquals("上次 Points 1,250 · 重置卡 2 张 · 最近 10月20日到期", TokensModel.boardExtras(codex, false, now, zone));
        codex.getJSONArray("windows").getJSONObject(0).put("remainingPercent", 0);
        assertTrue(TokensModel.boardExtras(codex, true, now, zone).startsWith("正在用 Points 1,250"));
        codex.getJSONArray("windows").getJSONObject(0).put("remainingPercent", 40);
        codex.put("usageRoute", new org.json.JSONObject().put("defaultAllowed", false));
        assertTrue("default quota not allowed", TokensModel.usesPoints(codex));
        org.json.JSONObject plain = new org.json.JSONObject().put("product", "codex").put("resetCredits", new org.json.JSONObject().put("available", 0).put("cards", new org.json.JSONArray()));
        assertEquals("重置卡 0 张", TokensModel.boardExtras(plain, true, now, zone));
        assertNull("nothing to add", TokensModel.boardExtras(new org.json.JSONObject().put("product", "codex"), true, now, zone));
        assertNull("only Codex gets the line", TokensModel.boardExtras(new org.json.JSONObject().put("product", "claude").put("credits", new org.json.JSONObject().put("remaining", 5).put("unlimited", false)), true, now, zone));
    }
    static final java.time.ZoneId SHANGHAI = java.time.ZoneId.of("Asia/Shanghai");
    static final long T = java.time.Instant.parse("2026-10-17T02:00:00Z").toEpochMilli();
    static String iso(long ms) { return java.time.Instant.ofEpochMilli(ms).toString(); }
    static org.json.JSONObject codexTile() throws Exception {
        return new org.json.JSONObject().put("id", "codex-1").put("provider", "OpenAI").put("product", "codex").put("label", "ChatGPT Codex").put("status", "connected")
                .put("lastSuccessAt", iso(T - 30_000)).put("profile", new org.json.JSONObject().put("email", "me@example.com"))
                .put("subscription", new org.json.JSONObject().put("planName", "ChatGPT Pro 500").put("planMultiplier", 25))
                .put("monthlyCost", new org.json.JSONObject().put("basis", "official-price").put("amountMinor", 50000).put("currency", "USD"))
                .put("windows", new org.json.JSONArray().put(window("primary", "5 小时", 13, "default").put("resetsAt", iso(T + 5 * H))))
                .put("credits", new org.json.JSONObject().put("remaining", 22832.98).put("unlimited", false))
                .put("resetCredits", new org.json.JSONObject().put("available", 1).put("applicable", 0).put("cards", new org.json.JSONArray()
                        .put(new org.json.JSONObject().put("status", "available").put("expiresAt", "2026-11-06T23:26:00Z").put("grantedAt", "2026-10-07T23:26:00Z"))));
    }
    @Test public void tileReadsLikeTheTokensWebGrid() throws Exception {
        org.json.JSONObject codex = codexTile();
        assertEquals("me@example.com", TokensModel.displayName(codex));
        assertEquals("Codex", TokensModel.productName(codex));
        assertEquals("13%", TokensModel.headline(codex, T));
        assertEquals("参考 US$500", TokensModel.monthly(codex));
        assertEquals("Pro 25x", TokensModel.plan(codex));
        assertEquals("Points 点数", TokensModel.balances(codex, T).get(0)[0]);
        assertEquals("22,832.98", TokensModel.balances(codex, T).get(0)[1]);
        assertEquals("1 张", TokensModel.balances(codex, T).get(1)[1]);
        assertEquals(java.util.List.of("额度重置： 10/17 15:00"), TokensModel.resetLines(codex, SHANGHAI, T));
        TokensModel.Cards cards = TokensModel.cards(codex, SHANGHAI, T);
        assertEquals("重置卡明细", cards.heading);
        assertEquals(java.util.List.of("当前可使用 0 张"), cards.notes);
        assertArrayEquals(new String[] {"重置卡 1", "未使用", "2026/11/07 07:26 到期", "2026/10/08 07:26 领取"}, cards.items.get(0));
        assertNull(cards.pending);
    }
    @Test public void staleIsTwoMinutesWithoutAReadingOrAPassedReset() throws Exception {
        org.json.JSONObject codex = codexTile();
        assertEquals("connected", TokensModel.effectiveStatus(codex, T));
        assertEquals("stale", TokensModel.effectiveStatus(codex, T + 121_000));
        assertEquals("待更新", TokensModel.headline(codex, T + 121_000));
        assertEquals("上次 Points", TokensModel.balances(codex, T + 121_000).get(0)[0]);
        assertEquals("重置卡明细（上次记录）", TokensModel.cards(codex, SHANGHAI, T + 121_000).heading);
        assertEquals("stale", TokensModel.effectiveStatus(codex, T + 5 * H + 1));
        assertEquals("待授权", TokensModel.headline(codex.put("status", "needs_auth"), T));
        assertEquals("暂无额度接口", TokensModel.headline(codex.put("status", "unavailable").put("reason", "unsupported"), T));
    }
    @Test public void claudeShowsUpdatedWithBalancesAndBothResets() throws Exception {
        org.json.JSONObject claude = new org.json.JSONObject().put("id", "claude-1").put("provider", "Anthropic").put("product", "claude").put("status", "connected").put("lastSuccessAt", iso(T))
                .put("profile", new org.json.JSONObject().put("email", "me@example.com"))
                .put("monthlyCost", new org.json.JSONObject().put("basis", "receipt").put("amountMinor", 2165).put("currency", "USD"))
                .put("subscription", new org.json.JSONObject().put("planName", "Claude Pro"))
                .put("windows", new org.json.JSONArray().put(window("five_hour", "5 小时", 68, "default").put("resetsAt", "2026-10-17T19:49:00Z")).put(window("seven_day", "每周", 47, null).put("resetsAt", "2026-10-21T21:59:00Z")));
        assertEquals("已更新", TokensModel.headline(claude, T));
        assertEquals("月付 US$21.65", TokensModel.monthly(claude));
        assertEquals("Pro", TokensModel.plan(claude));
        assertEquals("5 小时余额", TokensModel.balances(claude, T).get(0)[0]);
        assertEquals("68%", TokensModel.balances(claude, T).get(0)[1]);
        assertEquals("47%", TokensModel.balances(claude, T).get(1)[1]);
        assertEquals(java.util.List.of("5 小时重置：10/18 03:49", "每周重置：10/22 05:59"), TokensModel.resetLines(claude, SHANGHAI, T));
        assertEquals("重置卡明细暂未读取", TokensModel.cards(claude, SHANGHAI, T).notes.get(0));
    }
    @Test public void identityPaceSectionsAndOrder() throws Exception {
        org.json.JSONObject today = new org.json.JSONObject().put("id", "today-cn").put("provider", "TodayAI").put("product", "today-cn").put("label", "TodayAI 中国版").put("status", "connected")
                .put("profile", new org.json.JSONObject().put("name", "手机尾号 5320")).put("monthlyCost", JSONObjectNull())
                .put("resetCredits", new org.json.JSONObject().put("available", 0).put("cards", new org.json.JSONArray()));
        assertEquals("手机尾号 5320", TokensModel.displayName(today));
        assertEquals("TodayAI", TokensModel.section(today));
        assertEquals("月付 —", TokensModel.monthly(today));
        assertEquals(java.util.List.of("暂无可用卡片"), TokensModel.cards(today, SHANGHAI, T).notes);
        assertEquals("Claude 身份待核实", TokensModel.displayName(new org.json.JSONObject().put("product", "claude").put("label", "Claude").put("status", "stale")));
        assertFalse("ChatGPT Dots stays off the grid", TokensModel.shownOnPage(new org.json.JSONObject().put("product", "chatgpt-dots").put("status", "connected")));
        assertFalse(TokensModel.shownOnPage(new org.json.JSONObject().put("product", "claude").put("status", "not_configured")));
        java.util.List<org.json.JSONObject> slots = new java.util.ArrayList<>(java.util.List.of(new org.json.JSONObject().put("id", "codex-10"), new org.json.JSONObject().put("id", "codex-2")));
        slots.sort(TokensModel.SLOT_ORDER); assertEquals("codex-2", slots.get(0).optString("id"));
        // A weekly window 6 days in with 60% left: behind schedule; with under a day to go and 20% or more left, 即将重置.
        org.json.JSONObject grok = new org.json.JSONObject().put("product", "grok").put("status", "connected").put("lastSuccessAt", iso(T))
                .put("windows", new org.json.JSONArray().put(window("weekly", "每周", 99, "default").put("startsAt", iso(T - 3 * 24 * H)).put("resetsAt", iso(T + 4 * 24 * H))));
        assertEquals("使用偏慢", TokensModel.pace(grok, T));
        grok.getJSONArray("windows").getJSONObject(0).put("startsAt", iso(T - 6 * 24 * H - 12 * H)).put("resetsAt", iso(T + 12 * H)).put("remainingPercent", 60);
        assertEquals("即将重置", TokensModel.pace(grok, T));
        assertNull("stale readings never get a pace", TokensModel.pace(grok.put("status", "stale"), T));
    }
    static Object JSONObjectNull() { return org.json.JSONObject.NULL; }
    @Test public void claudeBoardLineCountsDownTheWeeklyReset() throws Exception {
        long now = NOW; java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        org.json.JSONObject claude = new org.json.JSONObject().put("product", "claude").put("windows", new org.json.JSONArray()
            .put(window("five_hour", "5 小时", 72, null).put("resetsAt", java.time.Instant.ofEpochMilli(now + 2 * H).toString()))
            .put(window("seven_day", "每周", 30, null).put("resetsAt", java.time.Instant.ofEpochMilli(now + 72 * H).toString())));
        assertEquals("每周额度 3 天后重置", TokensModel.boardExtras(claude, true, now, zone));
        claude.getJSONArray("windows").getJSONObject(1).put("resetsAt", java.time.Instant.ofEpochMilli(now + 47 * H + 59 * 60_000L).toString());
        assertEquals("每周额度 1 天 23 小时后重置", TokensModel.boardExtras(claude, true, now, zone));
        claude.getJSONArray("windows").getJSONObject(1).put("resetsAt", java.time.Instant.ofEpochMilli(now + 5 * H).toString());
        assertEquals("under a day, the usual countdown", "每周额度 " + TokensModel.countdown(now + 5 * H, now), TokensModel.boardExtras(claude, true, now, zone));
        claude.getJSONArray("windows").getJSONObject(1).put("resetsAt", java.time.Instant.ofEpochMilli(now - 1).toString());
        assertNull("a passed reset says nothing", TokensModel.boardExtras(claude, true, now, zone));
    }
    @org.junit.Test public void boardRefreshFollowsTheServerWithinBounds() throws Exception {
        org.junit.Assert.assertEquals(30_000L, TokensModel.refreshMs(new org.json.JSONObject().put("refreshAfterSeconds", 30)));
        org.junit.Assert.assertEquals(30_000L, TokensModel.refreshMs(new org.json.JSONObject().put("refreshAfterSeconds", 5)));
        org.junit.Assert.assertEquals(300_000L, TokensModel.refreshMs(new org.json.JSONObject().put("refreshAfterSeconds", 3600)));
        org.junit.Assert.assertEquals(60_000L, TokensModel.refreshMs(new org.json.JSONObject()));
        org.junit.Assert.assertEquals(60_000L, TokensModel.refreshMs(null));
        org.junit.Assert.assertEquals(60_000L, TokensModel.refreshMs(new org.json.JSONObject().put("refreshAfterSeconds", 0)));
    }
}
