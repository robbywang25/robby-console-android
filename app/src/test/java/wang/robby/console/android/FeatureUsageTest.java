package wang.robby.console.android;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.*;

/** 常用 weighs how often and how lately each page was opened, and fills gaps with the old defaults. */
public final class FeatureUsageTest {
    private static final long NOW = 1_800_000_000_000L, DAY = 86_400_000L;
    private static Map<String, FeatureUsage.Entry> open(Map<String, FeatureUsage.Entry> m, String id, long at, int times) { for (int i = 0; i < times; i++) m = FeatureUsage.record(m, id, at); return m; }
    @Test public void frequentAndRecentBothCount() {
        Map<String, FeatureUsage.Entry> m = new LinkedHashMap<>();
        m = open(m, "okx", NOW - 30 * DAY, 20);       // a habit a month ago: 20 / 2^(30/7) ≈ 1.04
        m = open(m, "tokens", NOW - DAY, 3);          // three opens yesterday ≈ 2.71
        m = open(m, "tibo", NOW, 1);                  // once just now = 1
        m = open(m, "market", NOW - 2 * DAY, 6);      // six opens two days ago ≈ 4.9
        assertEquals(List.of("market", "tokens", "okx", "tibo"), FeatureUsage.top(m, NOW, 4, id -> true));
    }
    @Test public void emptyOrUnknownFallsBackToTheDefaults() {
        assertEquals(FeatureUsage.DEFAULT, FeatureUsage.top(new LinkedHashMap<>(), NOW, 4, id -> true));
        Map<String, FeatureUsage.Entry> m = open(new LinkedHashMap<>(), "removed-feature", NOW, 5);
        m = open(m, "clipboard", NOW, 2);
        assertEquals("unknown ids are skipped and defaults fill without repeats", List.of("clipboard", "travel-ledger", "openai", "domains"), FeatureUsage.top(m, NOW, 4, id -> !id.equals("removed-feature")));
    }
    @Test public void storageRoundTripsAndStaysBounded() {
        Map<String, FeatureUsage.Entry> m = new LinkedHashMap<>();
        for (int i = 0; i < 60; i++) m = FeatureUsage.record(m, "f" + i, NOW + i);
        assertEquals(FeatureUsage.KEEP, m.size()); assertFalse("the weakest are dropped first", m.containsKey("f0"));
        Map<String, FeatureUsage.Entry> back = FeatureUsage.parse(FeatureUsage.serialize(m));
        assertEquals(m.keySet(), back.keySet()); assertEquals(m.get("f59").last, back.get("f59").last);
        assertTrue(FeatureUsage.parse("bad|x|y\nUPPER|1|2\nok|1.5|" + NOW).containsKey("ok"));
        assertEquals(1, FeatureUsage.parse("bad|x|y\nUPPER|1|2\nok|1.5|" + NOW).size());
    }
}
