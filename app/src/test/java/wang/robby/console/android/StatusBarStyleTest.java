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
    @Test public void theHoleComesFromTheRealOutlineWhenADeviceDescribesIt() {
        // Pixel-style: a declared band plus the round outline of the hole itself, off to wherever the camera sits.
        int[] pixel = StatusBarStyle.hole(new int[]{520, 0, 640, 145}, new int[]{545, 34, 615, 104});
        assertArrayEquals(new int[]{545, 34, 615, 104}, pixel);
        int[] island = StatusBarStyle.island(1280, pixel, 12);
        assertEquals("centred on the real hole", 580, (island[0] + island[2]) / 2);
        assertEquals(69, (island[1] + island[3]) / 2);
        // OPPO-style: the outline is the same tall band, so the hole is inferred from it.
        assertArrayEquals(new int[]{678, 38, 762, 122}, StatusBarStyle.hole(new int[]{678, 0, 762, 160}, new int[]{678, 0, 762, 160}));
        assertArrayEquals("no outline (Android 11): inferred too", new int[]{678, 38, 762, 122}, StatusBarStyle.hole(new int[]{678, 0, 762, 160}, null));
        assertNull("no cutout", StatusBarStyle.hole(null, null));
    }
    @Test public void eachSideShowsItsChosenItem() {
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        long now = java.time.ZonedDateTime.of(2026, 10, 20, 7, 5, 0, 0, zone).toInstant().toEpochMilli();
        assertEquals("time", StatusBarStyle.item(null, true)); assertEquals("battery", StatusBarStyle.item("bogus", false));
        assertEquals("7:05", StatusBarStyle.islandText("time", now, zone, 73, 0));
        assertEquals("10/20", StatusBarStyle.islandText("date", now, zone, 73, 0));
        assertEquals("周二", StatusBarStyle.islandText("weekday", now, zone, 73, 0));
        assertEquals("73%", StatusBarStyle.islandText("battery", now, zone, 73, 0));
        assertEquals("unknown battery shows nothing", "", StatusBarStyle.islandText("battery", now, zone, -1, 0));
        assertEquals("Tibo 2:20", StatusBarStyle.islandText("tibo", now, zone, 73, now + (2 * 60 + 20) * 60_000L));
        assertEquals("no upcoming announcement", "", StatusBarStyle.islandText("tibo", now, zone, 73, 0));
        assertEquals("", StatusBarStyle.islandText("none", now, zone, 73, 0));
    }
    @Test public void anEmptyIslandRestsNarrowerButStillCoversTheHole() {
        assertEquals("96/440 of the Find's 1440 px", 314, StatusBarStyle.compactWidth(1440, new int[]{678, 38, 762, 122}, 12));
        assertTrue(StatusBarStyle.compactWidth(1440, null, 12) < Math.round(1440 * StatusBarStyle.ISLAND_WIDTH));
        assertEquals("a wide hole wins", 424, StatusBarStyle.compactWidth(1440, new int[]{520, 0, 920, 120}, 12));
    }
}
