package wang.robby.console.android;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public final class MarketChangeTest {
    @Test public void upIsRedArrowUpDownIsGreenArrowDown() throws Exception {
        JSONObject n = new JSONObject().put("up", 1.234).put("down", -5.2821).put("flat", 0.001).put("text", "1.2");
        assertEquals("▲ 1.23%", MarketChange.text(n, "up")); assertEquals(1, MarketChange.direction(n, "up"));
        assertEquals("▼ 5.28%", MarketChange.text(n, "down")); assertEquals(-1, MarketChange.direction(n, "down"));
        assertEquals("0.00%", MarketChange.text(n, "flat")); assertEquals(0, MarketChange.direction(n, "flat"));
        assertNull(MarketChange.text(n, "missing")); assertNull(MarketChange.text(n, "text")); assertEquals(0, MarketChange.direction(n, "text"));
        assertEquals("涨跌 上涨 1.23% · 今年以来 下跌 5.28%", MarketChange.speakable("涨跌 ▲ 1.23% · 今年以来 ▼ 5.28%"));
    }
}
