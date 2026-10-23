package wang.robby.console.android;

import static org.junit.Assert.*;
import org.junit.Test;

/** 常亮展示: the idle time before the black screen, how it reads, and that it stays on this phone. */
public final class AlwaysOnTest {
    @Test public void theIdleTimeFallsBackToFiveMinutes() {
        assertEquals(300, AlwaysOn.saver(0));
        assertEquals("not offered", 300, AlwaysOn.saver(45));
        assertEquals(30, AlwaysOn.saver(30));
        assertEquals(3600, AlwaysOn.saver(3600));
        assertEquals(300_000L, AlwaysOn.saverMillis(-1));
        assertEquals(30_000L, AlwaysOn.saverMillis(30));
    }
    @Test public void everyChoiceReadsInItsOwnUnit() {
        assertEquals("30 秒", AlwaysOn.saverLabel(30));
        assertEquals("1 分钟", AlwaysOn.saverLabel(60));
        assertEquals("15 分钟", AlwaysOn.saverLabel(900));
        assertEquals("1 小时", AlwaysOn.saverLabel(3600));
        assertEquals("无操作 5 分钟后显示纯黑画面，轻触恢复", AlwaysOn.saverDetail(0));
        for (int s : AlwaysOn.SAVER_CHOICES) assertFalse("a black screen always comes: " + s, AlwaysOn.saverLabel(s).contains("从不"));
    }
    @Test public void theSummaryOnlySpeaksWhenItIsOn() {
        assertEquals("", AlwaysOn.summary(false, 600));
        assertEquals(" · 常亮，10 分钟后屏保", AlwaysOn.summary(true, 600));
    }
    @Test public void theSettingNeverReachesTheAccount() {
        assertFalse(PreferenceSync.KEYS.contains(AlwaysOn.KEY));
        assertFalse(PreferenceSync.KEYS.contains(AlwaysOn.SAVER));
        assertTrue(PreferenceSync.LOCAL_ONLY.contains(AlwaysOn.KEY));
        assertTrue(PreferenceSync.LOCAL_ONLY.contains(AlwaysOn.SAVER));
    }
}
