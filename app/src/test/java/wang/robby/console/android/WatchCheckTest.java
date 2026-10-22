package wang.robby.console.android;

import static org.junit.Assert.*;
import org.junit.Test;

/** The five-minute check must not leave a status notification behind. */
public final class WatchCheckTest {
    @Test public void finishedCheckRemovesTheForegroundNoticeAndStops() {
        WatchCheck.End end = WatchCheck.afterCheck();
        assertTrue("the foreground notification comes down", end.removeForeground);
        assertTrue("the service stops", end.stopService);
        WatchCheck.Copy copy = WatchCheck.notification();
        assertEquals("god-check-v1", copy.channel);
        assertFalse("a new channel, not the old always-on one", copy.channel.equals("tibo-watch-v1"));
        assertEquals(1, WatchCheck.MIN_IMPORTANCE);
        assertEquals("God 正在检查提醒", copy.title);
        assertTrue(copy.silent);
        assertTrue(copy.onlyAlertOnce);
        assertFalse(copy.mentionsLastCheck());
        assertFalse(copy.title.contains("上次检查"));
        assertFalse(copy.text.contains("上次检查"));
        assertFalse(copy.title.contains("已开启"));
        assertFalse(copy.text.contains("已开启"));
    }
}
