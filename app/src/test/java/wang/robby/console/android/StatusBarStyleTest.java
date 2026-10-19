package wang.robby.console.android;

import static org.junit.Assert.*;
import org.junit.Test;

/** 隐藏状态栏's four styles: the saved choice, where a page starts, and the island around the camera cutout. */
public final class StatusBarStyleTest {
    @Test public void oldSwitchBecomesHideAndMoveUp() {
        assertEquals(StatusBarStyle.HIDE_UP, StatusBarStyle.resolve(null, true));
        assertEquals(StatusBarStyle.SHOWN, StatusBarStyle.resolve(null, false));
        assertEquals(StatusBarStyle.ISLAND, StatusBarStyle.resolve("island", true));
        assertEquals("unknown values fall back", StatusBarStyle.SHOWN, StatusBarStyle.resolve("weird", false));
    }
    @Test public void eachStyleStartsThePageWhereItSays() {
        int status = 110, cutout = 100, visible = 110, islandBottom = 95, gap = 18;
        assertEquals("shown: the system inset", 110, StatusBarStyle.top(StatusBarStyle.SHOWN, true, status, cutout, visible, islandBottom, gap));
        assertEquals("hide: page stays put", 110, StatusBarStyle.top(StatusBarStyle.HIDE, true, status, cutout, 0, islandBottom, gap));
        assertEquals("hide + up: tab root at the edge", 18, StatusBarStyle.top(StatusBarStyle.HIDE_UP, true, status, cutout, 0, islandBottom, gap));
        assertEquals("hide + up: detail header clears the cutout", 100, StatusBarStyle.top(StatusBarStyle.HIDE_UP, false, status, cutout, 0, islandBottom, gap));
        assertEquals("island: below the island, never above the old bar", 113, StatusBarStyle.top(StatusBarStyle.ISLAND, true, status, cutout, 0, islandBottom, gap));
    }
    @Test public void islandWrapsTheHoleWithRoomForTimeAndBattery() {
        int[] hole = {700, 40, 740, 80};
        int[] island = StatusBarStyle.island(1440, hole, 130, 14, 84, 18);
        assertEquals("centred on the hole", 720, (island[0] + island[2]) / 2);
        assertEquals("centred vertically", 60, (island[1] + island[3]) / 2);
        assertTrue("covers the hole", island[0] < 700 && island[2] > 740 && island[1] < 40 && island[3] > 80);
        assertEquals(40 + 2 * 14 + 2 * 130, island[2] - island[0]);
        int[] none = StatusBarStyle.island(1440, null, 130, 14, 84, 18);
        assertEquals("no cutout: centred pill at the fallback top", 18, none[1]);
        assertEquals(720, (none[0] + none[2]) / 2);
    }
}
