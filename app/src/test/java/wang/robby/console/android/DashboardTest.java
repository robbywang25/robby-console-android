package wang.robby.console.android;

import static org.junit.Assert.*;
import java.util.List;
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
    @Test public void clockCardNeedsNoRead() {
        assertEquals(List.of("tokens/quotas"), Dashboard.paths(List.of("clocks", "tokens", "calendar")));
    }
    @Test public void tokenRowsKeepIdsAndProductLabelsOnly() {
        String saved = Dashboard.serializeTokenRows(List.of(new String[]{"codex-1", "ChatGPT Codex"}, new String[]{"today-cn", "TodayAI 中国版"}, new String[]{"Bad Id", "x"}, new String[]{"grok", "a|b\nc"}));
        List<String[]> back = Dashboard.tokenRows(saved);
        assertEquals(3, back.size()); assertEquals("ChatGPT Codex", back.get(0)[1]); assertEquals("TodayAI 中国版", back.get(1)[1]); assertEquals("a b c", back.get(2)[1]);
        assertTrue(Dashboard.tokenRows((String) null).isEmpty());
    }
}
