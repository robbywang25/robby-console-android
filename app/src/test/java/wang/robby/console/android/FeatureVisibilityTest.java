package wang.robby.console.android;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.*;

/** 功能 hides what the person hid plus the default-hidden features they have not shown again. */
public final class FeatureVisibilityTest {
    private static final Set<String> DEFAULTS = Set.of("umami", "posthog");
    private static Set<String> set(String... ids) { return new LinkedHashSet<>(Arrays.asList(ids)); }

    @Test public void defaultsAreHiddenUntilShown() {
        assertEquals(DEFAULTS, FeatureVisibility.hidden(set(), set(), DEFAULTS));
        List<Set<String>> next = FeatureVisibility.show(set(), set(), "umami", DEFAULTS);
        assertEquals(Set.of("posthog"), FeatureVisibility.hidden(next.get(0), next.get(1), DEFAULTS));
        assertEquals("showing a default is remembered", Set.of("umami"), next.get(1));
    }
    @Test public void hidingAndShowingRoundTrip() {
        List<Set<String>> hid = FeatureVisibility.hide(set(), set(), "okx");
        assertTrue(FeatureVisibility.hidden(hid.get(0), hid.get(1), DEFAULTS).contains("okx"));
        List<Set<String>> back = FeatureVisibility.show(hid.get(0), hid.get(1), "okx", DEFAULTS);
        assertFalse(FeatureVisibility.hidden(back.get(0), back.get(1), DEFAULTS).contains("okx"));
        assertTrue("a normal feature is not remembered as shown", back.get(1).isEmpty());
    }
    @Test public void hidingAShownDefaultForgetsTheShow() {
        List<Set<String>> shown = FeatureVisibility.show(set(), set(), "posthog", DEFAULTS);
        List<Set<String>> again = FeatureVisibility.hide(shown.get(0), shown.get(1), "posthog");
        assertFalse(again.get(1).contains("posthog"));
        assertTrue(FeatureVisibility.hidden(again.get(0), again.get(1), DEFAULTS).contains("posthog"));
    }
    @Test public void storageKeepsOnlyFeatureIds() {
        assertEquals(set("okx", "market"), FeatureVisibility.parse("okx,market,,Bad Id,../x"));
        assertEquals("okx,market", FeatureVisibility.serialize(set("okx", "market")));
        assertTrue(FeatureVisibility.parse(null).isEmpty());
    }
    @Test public void defaultHiddenFeaturesExistAndSayWhy() {
        for (String id : FeatureCatalog.DEFAULT_HIDDEN) {
            assertNotNull(id + " is in the catalog", FeatureCatalog.find(id));
            assertTrue(id + " has a reason", FeatureCatalog.DEFAULT_HIDDEN_REASONS.get(id).startsWith("暂未接通"));
        }
    }
    @Test public void glassBlendKeepsEndsAndNeverBrightensBetweenThem() {
        int clear = 0x1affffff, solid = 0xff1a1e26;
        assertEquals(clear, ConsoleTheme.blend(clear, solid, 0f));
        assertEquals(solid, ConsoleTheme.blend(clear, solid, 1f));
        int mid = ConsoleTheme.blend(clear, solid, .5f);
        assertTrue("alpha rises", (mid >>> 24) > 0x1a && (mid >>> 24) < 0xff);
        // Over black, the premultiplied middle is never brighter than either end.
        float shown = ((mid >> 16) & 0xff) * (mid >>> 24) / 255f;
        assertTrue(shown <= Math.max(0xff * 0x1a / 255f, 0x1a) + 1);
    }
}
