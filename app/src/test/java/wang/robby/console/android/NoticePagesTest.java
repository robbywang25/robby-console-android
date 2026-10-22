package wang.robby.console.android;

import static org.junit.Assert.*;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/** 我 → 通知 is five groups. Opening one never clears the first-read latch. */
public final class NoticePagesTest {
    @Test public void fiveGroupsListTheirSwitchesAndSummarize() {
        assertEquals(5, NoticePages.GROUPS.size());
        assertEquals(List.of("daily", "credits", "low", "reset", "account", "card"), NoticePages.find(NoticePages.QUOTA).keys);
        assertEquals(List.of("sites"), NoticePages.find(NoticePages.SITES).keys);
        assertEquals(List.of("enhanced", "persistent"), NoticePages.find(NoticePages.MODE).keys);
        assertEquals(List.of("tibo"), NoticePages.find(NoticePages.CHALLENGE).keys);
        assertTrue(NoticePages.find(NoticePages.CHECK).keys.isEmpty());
        assertTrue(NoticePages.find(NoticePages.CHECK).readOnly);
        NoticePages.Snapshot none = NoticePages.read(Map.of(), Map.of());
        for (NoticePages.Group g : NoticePages.GROUPS) {
            assertTrue(NoticePages.page(g.id));
            assertEquals(NoticePages.ROOT, NoticePages.parent(g.id));
            assertEquals(g.readOnly ? "每 5 分钟" : "关闭", NoticePages.summary(g, none));
        }
        Map<String, Object> on = new LinkedHashMap<>();
        on.put("on", Set.of("daily", "low", "reset"));
        on.put("enhanced", true);
        on.put("persistent", true);
        on.put("primed", true);
        NoticePages.Snapshot three = NoticePages.read(on, Map.of("enabled", true));
        assertEquals("已开 3 项", NoticePages.summary(NoticePages.find(NoticePages.QUOTA), three));
        assertEquals("关闭", NoticePages.summary(NoticePages.find(NoticePages.SITES), three));
        assertEquals("已开 2 项", NoticePages.summary(NoticePages.find(NoticePages.MODE), three));
        assertEquals("已开 1 项", NoticePages.summary(NoticePages.find(NoticePages.CHALLENGE), three));
        // 持续提醒 is stored on, but it does not count while 增强提醒 is off.
        NoticePages.Snapshot quiet = NoticePages.read(Map.of("persistent", true, "enhanced", false, "primed", true), Map.of());
        assertTrue(quiet.persistent);
        assertEquals("关闭", NoticePages.summary(NoticePages.find(NoticePages.MODE), quiet));
    }

    @Test public void upgradeKeepsTheOldSwitchesAndLines() {
        Map<String, Object> notices = new LinkedHashMap<>();
        notices.put("on", new LinkedHashSet<>(List.of("daily", "low", "reset", "account", "card", "sites")));
        notices.put("daily", 40);
        notices.put("low", 5);
        notices.put("enhanced", true);
        notices.put("persistent", false);
        notices.put("primed", true);
        NoticePages.Snapshot s = NoticePages.read(notices, Map.of("enabled", true));
        assertEquals(Set.of("daily", "low", "reset", "account", "card", "sites"), s.on);
        assertFalse(s.on.contains("credits"));
        assertEquals(40, s.daily);
        assertEquals(5, s.low);
        assertEquals(50, s.creditsLine);
        assertTrue(s.enhanced);
        assertFalse(s.persistent);
        assertTrue(s.tibo);
        assertTrue(s.primed);
    }

    @Test public void browsingDoesNotClearPrimed() {
        Map<String, Object> notices = new LinkedHashMap<>();
        notices.put("on", Set.of("daily", "sites"));
        notices.put("daily", 40);
        notices.put("low", 10);
        notices.put("enhanced", true);
        notices.put("primed", true);
        NoticePages.Snapshot s = NoticePages.read(notices, Map.of("enabled", false));
        for (String id : List.of(NoticePages.ROOT, NoticePages.QUOTA, NoticePages.SITES, NoticePages.MODE, NoticePages.CHALLENGE, NoticePages.CHECK)) {
            NoticePages.Snapshot seen = NoticePages.browse(s, id);
            assertTrue(id, seen.primed);
            assertEquals(s.on, seen.on);
            assertEquals(s.daily, seen.daily);
            assertEquals(s.creditsLine, seen.creditsLine);
            assertEquals(s.enhanced, seen.enhanced);
        }
        assertTrue("the same switch again changes nothing", NoticePages.toggle(s, "daily", true).primed);
        assertTrue(NoticePages.line(s, "daily", 40).primed);
        NoticePages.Snapshot off = NoticePages.toggle(s, "daily", false);
        assertFalse(off.primed);
        assertFalse(off.on.contains("daily"));
        assertFalse(NoticePages.line(s, "creditsLine", 100).primed);
        assertEquals(100, NoticePages.line(s, "creditsLine", 100).creditsLine);
        assertTrue(NoticePages.toggle(s, "enhanced", true).primed);
        assertFalse(NoticePages.toggle(s, "enhanced", false).primed);
    }
}
