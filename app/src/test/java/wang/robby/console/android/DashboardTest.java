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
            assertTrue(c.id, "overview".equals(c.path) || FeatureCatalog.all().stream().anyMatch(f -> f.path.equals(c.path)));
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
}
