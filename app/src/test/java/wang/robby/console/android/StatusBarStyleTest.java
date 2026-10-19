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
    @Test public void islandFloatsInIphoneProportionsAroundTheHole() {
        // OPPO Find X9 Ultra: 1440 px wide, cutout declared as (678,0)-(762,160), an 84 px hole.
        int[] island = StatusBarStyle.island(1440, new int[]{678, 0, 762, 160}, 12);
        assertEquals("126/440 of the width", 412, island[2] - island[0]);
        assertEquals("37.33/440 of the width", 122, island[3] - island[1]);
        assertEquals("centred on the hole", 720, (island[0] + island[2]) / 2);
        assertEquals("centred on the hole vertically", 80, (island[1] + island[3]) / 2);
        assertTrue("floats below the screen edge", island[1] > 0);
        assertTrue("hole stays inside with padding", island[1] <= 80 - 42 - 12 && island[3] >= 80 + 42 + 12);
        int[] none = StatusBarStyle.island(1440, null, 12);
        assertEquals("no cutout: where the iPhone's sits", Math.round(1440 * 11f / 440f), none[1]);
        assertEquals(720, (none[0] + none[2]) / 2);
    }
}
