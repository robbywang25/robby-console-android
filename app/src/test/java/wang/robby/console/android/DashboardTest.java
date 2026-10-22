package wang.robby.console.android;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Test;

/** The saved 看板 combination must survive bad input, keep its order, and never read the same path twice. */
public final class DashboardTest {
    @Test public void neverSavedMeansDefaultAndEmptyMeansEmpty() {
        assertEquals(Dashboard.DEFAULT, Dashboard.parse(null));
        assertTrue(Dashboard.parse("").isEmpty());
    }
    @Test public void unknownAndRepeatedCardsAreDropped() {
        assertEquals(List.of("openai", "services"), Dashboard.parse("openai, nope ,services,openai"));
        assertEquals("openai,services", Dashboard.serialize(List.of("openai", "x", "services", "openai")));
    }
    @Test public void toggleAddsAtTheEndOrRemoves() {
        assertEquals(List.of("services", "market"), Dashboard.toggle(List.of("services"), "market"));
        assertEquals(List.of("market"), Dashboard.toggle(List.of("services", "market"), "services"));
        assertEquals(List.of("services"), Dashboard.toggle(List.of("services"), "not-a-card"));
    }
    @Test public void moveStaysInBounds() {
        List<String> ids = List.of("services", "instances", "domains");
        assertEquals(List.of("instances", "services", "domains"), Dashboard.move(ids, "instances", -1));
        assertEquals(List.of("services", "domains", "instances"), Dashboard.move(ids, "instances", 1));
        assertEquals(ids, Dashboard.move(ids, "services", -1));
        assertEquals(ids, Dashboard.move(ids, "domains", 1));
        assertEquals(ids, Dashboard.move(ids, "market", 1));
    }
    @Test public void sharedReadsHappenOnce() {
        assertEquals(List.of("overview", "tokens/quotas"), Dashboard.paths(Dashboard.DEFAULT));
        assertEquals(List.of("overview", "openai/usage", "market"), Dashboard.paths(List.of("services", "openai", "domains", "market")));
    }
    @Test public void everyCardReadsAReleasedFeature() {
        for (Dashboard.Card c : Dashboard.ALL) {
            // A card without a read (时区时钟) is drawn on the phone.
            assertTrue(c.id, c.path == null || "overview".equals(c.path) || FeatureCatalog.all().stream().anyMatch(f -> f.path.equals(c.path)));
            assertTrue(c.id, c.target == null || FeatureCatalog.find(c.target) != null);
        }
    }
    @Test public void hiddenTokenAccountsAreRememberedById() {
        assertTrue(Dashboard.hiddenTokens((String) null).isEmpty());
        String saved = Dashboard.toggleHidden(null, "today-cn");
        assertEquals("today-cn", saved);
        saved = Dashboard.toggleHidden(saved, "codex-2");
        assertEquals(java.util.Set.of("today-cn", "codex-2"), Dashboard.hiddenTokens(saved));
        assertEquals("codex-2", Dashboard.toggleHidden(saved, "today-cn"));
        assertEquals("odd text is dropped", java.util.Set.of("grok"), Dashboard.hiddenTokens("grok, ,Bad Id,<x>"));
    }
    @Test public void clocksDefaultToTiboAndBeijingAndKeepOnlyValidZones() {
        List<Dashboard.Clock> d = Dashboard.clocks(null);
        assertEquals("Tibo", d.get(0).name); assertEquals("America/Los_Angeles", d.get(0).zone); assertEquals("北京时间", d.get(1).name);
        assertEquals("太平洋时间", d.get(2).name); assertEquals("America/Los_Angeles", d.get(2).zone);
        String saved = Dashboard.serializeClocks(List.of(new Dashboard.Clock("新西兰", "Pacific/Auckland"), new Dashboard.Clock("坏|名字\n", "Asia/Tokyo"), new Dashboard.Clock("无效", "Mars/Base")));
        List<Dashboard.Clock> back = Dashboard.clocks(saved);
        assertEquals(2, back.size()); assertEquals("新西兰", back.get(0).name); assertEquals("坏 名字", back.get(1).name);
        assertTrue("removing every clock is kept, not reset to the defaults", Dashboard.clocks("").isEmpty());
        assertEquals("names are cut to 12 characters", 12, Dashboard.clockName("一二三四五六七八九十一二三四").length());
        assertNull(Dashboard.clockName("  | "));
    }
    @Test public void offsetsReadAgainstThePhone() {
        long at = java.time.Instant.parse("2026-10-18T10:00:00Z").toEpochMilli(); java.time.ZoneId beijing = java.time.ZoneId.of("Asia/Shanghai");
        assertEquals("晚 15 小时", Dashboard.offsetLabel("America/Los_Angeles", beijing, at));
        assertEquals("同本机", Dashboard.offsetLabel("Asia/Shanghai", beijing, at));
        assertEquals("早 5 小时", Dashboard.offsetLabel("Pacific/Auckland", beijing, at));
        assertEquals("晚 2.5 小时", Dashboard.offsetLabel("Asia/Kolkata", beijing, at));
    }
    @Test public void pacificClocksNameTheirStandardOrDaylightTime() {
        long summer = java.time.Instant.parse("2026-10-22T10:00:00Z").toEpochMilli(), winter = java.time.Instant.parse("2026-11-02T10:00:00Z").toEpochMilli();
        assertEquals("PDT · 太平洋夏令时间 · Pacific Daylight Time", Dashboard.zoneTitle("America/Los_Angeles", summer));
        assertEquals("PST · 太平洋标准时间 · Pacific Standard Time", Dashboard.zoneTitle("America/Los_Angeles", winter));
        assertNull("other zones get no extra line", Dashboard.zoneTitle("Asia/Shanghai", winter));
    }
    @Test public void clockCardNeedsNoRead() {
        assertEquals(List.of("tokens/quotas"), Dashboard.paths(List.of("clocks", "tokens", "calendar")));
    }
    @Test public void tokenBoardKeepsBothTodayAiRowsAndMoreThanEight() throws Exception {
        List<JSONObject> accounts = new ArrayList<>();
        accounts.add(account("codex-1", "codex", "connected"));
        accounts.add(account("codex-2", "codex", "connected"));
        accounts.add(account("chatgpt-dots", "chatgpt-dots", "connected"));
        accounts.add(account("chatgpt-chat", "chatgpt-chat", "connected"));
        accounts.add(account("claude-1", "claude", "connected"));
        accounts.add(account("claude-2", "claude", "not_configured"));
        accounts.add(account("grok", "grok", "needs_auth"));
        accounts.add(account("grok-bot", "grok-bot", "connected"));
        accounts.add(account("cursor", "cursor", "unavailable"));
        accounts.add(account("cursor-2", "cursor", "connected"));
        accounts.add(account("today-cn", "today-cn", "connected"));
        accounts.add(account("today-global", "today-global", "stale"));
        List<JSONObject> shown = Dashboard.tokenBoardAccounts(accounts, Set.of());
        List<String> ids = new ArrayList<>(); for (JSONObject a : shown) ids.add(a.optString("id"));
        assertEquals(List.of("codex-1", "codex-2", "claude-1", "grok", "grok-bot", "cursor", "cursor-2", "today-cn", "today-global"), ids);
        assertEquals("today-cn", ids.get(ids.size() - 2));
        assertEquals("today-global", ids.get(ids.size() - 1));
        assertTrue(ids.indexOf("today-cn") < ids.indexOf("today-global"));
        List<JSONObject> hidden = Dashboard.tokenBoardAccounts(accounts, Set.of("today-global"));
        List<String> left = new ArrayList<>(); for (JSONObject a : hidden) left.add(a.optString("id"));
        assertEquals(List.of("codex-1", "codex-2", "claude-1", "grok", "grok-bot", "cursor", "cursor-2", "today-cn"), left);
        assertFalse(left.contains("today-global"));
        assertFalse(left.contains("claude-2"));
        assertFalse(left.contains("chatgpt-dots"));
        assertFalse(left.contains("chatgpt-chat"));
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < 8; i++) rows.add(new String[]{"acct-" + i, "Label " + i});
        rows.add(new String[]{"today-global", "TodayAI 全球版"});
        List<String[]> back = Dashboard.tokenRows(Dashboard.serializeTokenRows(rows));
        assertTrue(back.size() > 8);
        assertEquals(9, back.size());
        assertEquals("today-global", back.get(8)[0]);
        assertEquals("TodayAI 全球版", back.get(8)[1]);
        assertEquals(32, Dashboard.MAX_TOKEN_ROWS);
        List<JSONObject> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) many.add(account("row-" + i, "codex", "connected"));
        assertEquals(Dashboard.MAX_TOKEN_ROWS, Dashboard.tokenBoardAccounts(many, Set.of()).size());
    }
    private static JSONObject account(String id, String product, String status) throws Exception {
        return new JSONObject().put("id", id).put("product", product).put("status", status).put("label", product);
    }
    @Test public void tokenRowsKeepIdsAndProductLabelsOnly() {
        String saved = Dashboard.serializeTokenRows(List.of(new String[]{"codex-1", "ChatGPT Codex"}, new String[]{"today-cn", "TodayAI 中国版"}, new String[]{"Bad Id", "x"}, new String[]{"grok", "a|b\nc"}));
        List<String[]> back = Dashboard.tokenRows(saved);
        assertEquals(3, back.size()); assertEquals("ChatGPT Codex", back.get(0)[1]); assertEquals("TodayAI 中国版", back.get(1)[1]); assertEquals("a b c", back.get(2)[1]);
        assertTrue(Dashboard.tokenRows((String) null).isEmpty());
    }
    @Test public void calendarCardShowsTheChosenDayRange() {
        java.time.LocalDate today = java.time.LocalDate.of(2026, 10, 22); long now = 1_000_000L, h = 3_600_000L;
        java.util.function.BiFunction<Long, Integer, QuotaCalendar.Event> ev = (at, plus) -> new QuotaCalendar.Event("a", "Codex", "5 小时", "codex", QuotaCalendar.SCHEDULED, at, today.plusDays(plus));
        List<QuotaCalendar.Event> all = List.of(ev.apply(now + 30 * h, 1), ev.apply(now - h, 0), ev.apply(now + 2 * h, 0), ev.apply(now + 200 * h, 8), ev.apply(now + 60 * h, 2));
        assertEquals("today only, past ones dropped", 1, Dashboard.calendarWithin(all, now, today, 1).size());
        List<QuotaCalendar.Event> three = Dashboard.calendarWithin(all, now, today, 3);
        assertEquals("today, tomorrow and the day after", 3, three.size());
        assertTrue("soonest first", three.get(0).at < three.get(1).at && three.get(1).at < three.get(2).at);
        assertEquals("a week leaves out day 8", 3, Dashboard.calendarWithin(all, now, today, 7).size());
        assertEquals(4, Dashboard.calendarWithin(all, now, today, 14).size());
        assertEquals(7, Dashboard.calendarDays((String) null)); assertEquals(14, Dashboard.calendarDays("14")); assertEquals("only offered ranges", 7, Dashboard.calendarDays("5"));
        assertEquals("今天", Dashboard.calendarRange(1)); assertEquals("未来 30 天", Dashboard.calendarRange(30));
    }
}
