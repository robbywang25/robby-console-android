package wang.robby.console.android;

import static org.junit.Assert.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import org.junit.Test;

/** Numbers, relative time, type shares, bar scale and staleness, independent of any view. */
public final class WechatDigestTextTest {
    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");

    @Test public void countsUseTheChineseGroupingOfThisRuntime() {
        assertEquals("312", WechatDigestText.count(312));
        assertEquals("187", WechatDigestText.count(187));
        java.text.NumberFormat format = java.text.NumberFormat.getIntegerInstance(Locale.SIMPLIFIED_CHINESE);
        assertEquals(format.format(10_000_000_000L), WechatDigestText.count(10_000_000_000L));
        assertEquals(format.format(1_246), WechatDigestText.count(1246));
    }

    @Test public void relativeTimeMatchesTheAccountCenterSteps() {
        assertEquals("刚刚", WechatDigestText.ago(NOW, NOW.plusSeconds(119)));
        assertEquals("2 分钟前", WechatDigestText.ago(NOW, NOW.plusSeconds(120)));
        assertEquals("59 分钟前", WechatDigestText.ago(NOW, NOW.plusSeconds(3599)));
        assertEquals("1 小时前", WechatDigestText.ago(NOW, NOW.plusSeconds(3600)));
        assertEquals("1 天前", WechatDigestText.ago(NOW, NOW.plusSeconds(86400)));
        assertEquals("29 天前", WechatDigestText.ago(NOW, NOW.plusSeconds(30 * 86400L - 1)));
        Instant later = NOW.plusSeconds(30 * 86400L);
        assertEquals(DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault()).format(NOW), WechatDigestText.ago(NOW, later));
        assertEquals("刚刚", WechatDigestText.ago(NOW, NOW.minusSeconds(30)));
    }

    @Test public void stalenessStartsAfterTwoHours() throws Exception {
        WechatDigest.Doc d = WechatDigest.parseResponse(WechatDigest.exampleResponse(NOW));
        assertFalse(WechatDigestText.stale(d.generatedAt, d.generatedAt.plus(2, ChronoUnit.HOURS)));
        assertTrue(WechatDigestText.stale(d.generatedAt, d.generatedAt.plus(2, ChronoUnit.HOURS).plusSeconds(1)));
        assertFalse(WechatDigestText.stale(d.generatedAt, d.generatedAt.minusSeconds(5)));
        assertEquals("数据截至 10月22日 18:30 · 生成于 30 分钟前", WechatDigestText.header(d, NOW.plusSeconds(1800)));
        assertEquals("数据截至 10月22日 18:30 · 生成于 2 小时前", WechatDigestText.header(d, NOW.plus(2, ChronoUnit.HOURS)));
        assertEquals("数据截至 10月22日 18:30 · 生成于 3 小时前 · 数据可能不是最新", WechatDigestText.header(d, NOW.plus(3, ChronoUnit.HOURS)));
        assertEquals("数据截至 10月22日 18:30 · 生成于 刚刚", WechatDigestText.header(d, NOW.minusSeconds(10)));
    }

    @Test public void dailyAverageRoundsHalfUpAndDropsAZeroFraction() {
        assertEquals("0", WechatDigestText.dailyAverage(0));
        assertEquals("0.1", WechatDigestText.dailyAverage(1));
        assertEquals("0.4", WechatDigestText.dailyAverage(3));
        assertEquals("0.6", WechatDigestText.dailyAverage(4));
        assertEquals("0.9", WechatDigestText.dailyAverage(6));
        assertEquals("1", WechatDigestText.dailyAverage(7));
        assertEquals("1.1", WechatDigestText.dailyAverage(8));
        assertEquals("1.4", WechatDigestText.dailyAverage(10));
        assertEquals("1.6", WechatDigestText.dailyAverage(11));
        assertEquals("1.9", WechatDigestText.dailyAverage(13));
        assertEquals("300", WechatDigestText.dailyAverage(2100));
        assertEquals("178", WechatDigestText.dailyAverage(1246));
        assertEquals(WechatDigestText.count(1_428_571_428L) + ".6", WechatDigestText.dailyAverage(10_000_000_000L));
    }

    @Test public void typeSharesUseLargestRemainderAndKeepOneDecimal() {
        List<WechatDigestText.Share> shares = WechatDigestText.shares(new long[]{3000, 500, 200, 100, 200, 50, 255});
        int[] tenths = {697, 116, 47, 23, 46, 12, 59};
        String[] percents = {"69.7%", "11.6%", "4.7%", "2.3%", "4.6%", "1.2%", "5.9%"};
        int sum = 0;
        for (int i = 0; i < 7; i++) {
            assertEquals(WechatDigestText.TYPE_NAMES[i], shares.get(i).name);
            assertEquals(tenths[i], shares.get(i).tenths);
            assertEquals(percents[i], shares.get(i).percent);
            assertEquals(new long[]{3000, 500, 200, 100, 200, 50, 255}[i] / 4305d, shares.get(i).fraction, 1e-12);
            sum += shares.get(i).tenths;
        }
        assertEquals(1000, sum);
        List<WechatDigestText.Share> tie = WechatDigestText.shares(new long[]{1, 1, 1});
        assertEquals("the earlier index wins a tie", 334, tie.get(0).tenths);
        assertEquals(333, tie.get(1).tenths); assertEquals(333, tie.get(2).tenths);
        List<WechatDigestText.Share> zero = WechatDigestText.shares(new long[7]);
        int zeros = 0; for (WechatDigestText.Share s : zero) { assertEquals(0, s.tenths); assertEquals("0.0%", s.percent); assertEquals(0, s.fraction, 0); zeros += s.tenths; }
        assertEquals("a zero total is not forced to 100%", 0, zeros);
    }

    @Test public void barsNormalizeAgainstThePeak() {
        assertArrayEquals(new double[]{0, 0.5, 1}, WechatDigestText.normalize(new long[]{0, 5, 10}), 1e-9);
        assertArrayEquals(new double[]{0, 0}, WechatDigestText.normalize(new long[]{0, 0}), 0);
        assertArrayEquals(new double[]{1}, WechatDigestText.normalize(new long[]{4}), 0);
        assertEquals(0, WechatDigestText.normalize(new long[0]).length);
        assertEquals(0, WechatDigestText.normalize(null).length);
        assertEquals(2, WechatDigestText.peakIndex(new long[]{1, 4, 9, 9}));
        assertEquals(0, WechatDigestText.peakIndex(new long[]{5, 5}));
        assertEquals(-1, WechatDigestText.peakIndex(new long[0]));
        assertEquals(-1, WechatDigestText.peakIndex(null));
    }

    @Test public void labelsAndTheBoardCaption() {
        assertEquals("群", WechatDigestText.kindLabel("group"));
        assertEquals("私聊", WechatDigestText.kindLabel("private"));
        assertEquals("今天 · 近 7 天日均 178 · 周末闲聊", WechatDigestText.boardCaption(1246, "周末闲聊"));
        assertEquals("今天 · 近 7 天日均 178", WechatDigestText.boardCaption(1246, null));
        assertEquals("今天 · 近 7 天日均 178", WechatDigestText.boardCaption(1246, ""));
        assertEquals("群 · 发出 30 · 2 分钟前", WechatDigestText.conversationMeta("group", 30, NOW, NOW.plusSeconds(120)));
        assertEquals("9月23日", WechatDigestText.dayLabel("2026-09-23"));
        assertEquals("10月22日", WechatDigestText.dayLabel("2026-10-22"));
    }
}
