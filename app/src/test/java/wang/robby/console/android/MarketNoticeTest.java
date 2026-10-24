package wang.robby.console.android;

import static org.junit.Assert.*;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** 行情波动提醒: the quote against the last N days' closes, once per move and direction. */
public final class MarketNoticeTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long DAY = 86_400_000L, MIN = 60_000L;
    private static final long T0 = Instant.parse("2026-10-20T02:00:00Z").toEpochMilli(); // 10:00 on 10-20 in Shanghai
    /** Daily closes at 14:00Z (22:00 in Shanghai) for 10-13 … 10-19; the last price is the newest. */
    private static JSONObject market(double quote, double... closes) throws Exception {
        JSONArray points = new JSONArray();
        for (int i = 0; i < closes.length; i++) points.put(new JSONObject().put("timestamp", Instant.parse("2026-10-19T14:00:00Z").minusMillis((closes.length - 1 - i) * DAY).toString()).put("price", closes[i]));
        JSONObject series = new JSONObject().put("symbol", "COIN-A").put("label", "合成币 A").put("currency", "USD").put("points", points);
        JSONArray watch = new JSONArray(); if (quote > 0) watch.put(new JSONObject().put("symbol", "COIN-A").put("label", "合成币 A").put("price", quote).put("currency", "USD"));
        return new JSONObject().put("watchlist", watch).put("history", new JSONObject().put("periodDays", 7).put("staleSymbols", new JSONArray()).put("unavailableSymbols", new JSONArray()).put("series", new JSONArray().put(series)));
    }
    private static Notices.Settings on(int days, int line) { return new Notices.Settings(Set.of(Notices.MARKET), 30, 10, 50, days, line); }

    @Test public void aRiseFromTheWindowLowNotifiesOnce() throws Exception {
        Notices.State st = new Notices.State(null); Notices.Settings s = on(3, 5);
        List<Notices.Notice> n = Notices.market(market(106, 90, 95, 99, 103, 100, 101, 104), st, s, T0, ZONE);
        assertEquals(1, n.size());
        assertEquals(Notices.MARKET, n.get(0).kind);
        assertEquals("market", n.get(0).open);
        assertEquals("合成币 A 3 天内上涨 6.0%", n.get(0).title);
        assertEquals("从 3 天内低点 100 涨到 106 USD，超过你设的 5% 提醒线。", n.get(0).text);
        assertTrue("the same move is not repeated", Notices.market(market(106.5, 90, 95, 99, 103, 100, 101, 104), st, s, T0 + 10 * MIN, ZONE).isEmpty());
        // Another full line on top notifies again; easing below half the line re-arms the direction.
        assertEquals("合成币 A 3 天内上涨 11.0%", Notices.market(market(111, 90, 95, 99, 103, 100, 101, 104), st, s, T0 + 20 * MIN, ZONE).get(0).title);
        assertTrue(Notices.market(market(102, 90, 95, 99, 103, 100, 101, 104), st, s, T0 + 30 * MIN, ZONE).isEmpty());
        assertEquals(1, Notices.market(market(105, 90, 95, 99, 103, 100, 101, 104), st, s, T0 + 40 * MIN, ZONE).size());
    }

    @Test public void aFallFromTheWindowHighNotifies() throws Exception {
        List<Notices.Notice> n = Notices.market(market(100, 100, 100, 100, 100, 110, 104, 102), new Notices.State(null), on(3, 5), T0, ZONE);
        assertEquals(1, n.size());
        assertEquals("合成币 A 3 天内下跌 9.1%", n.get(0).title);
        assertEquals("从 3 天内高点 110 跌到 100 USD，超过你设的 5% 提醒线。", n.get(0).text);
    }

    @Test public void theRangeDecidesWhichClosesCount() throws Exception {
        // The 90 close is on 10-16: outside 3 days, inside 7.
        JSONObject m = market(97, 100, 100, 100, 90, 96, 96, 96);
        assertTrue(Notices.market(m, new Notices.State(null), on(3, 5), T0, ZONE).isEmpty());
        assertEquals("合成币 A 7 天内上涨 7.8%", Notices.market(m, new Notices.State(null), on(7, 5), T0, ZONE).get(0).title);
        // One day back is yesterday's close against the quote now.
        assertEquals("合成币 A 1 天内下跌 3.1%", Notices.market(m.put("watchlist", new JSONArray().put(new JSONObject().put("symbol", "COIN-A").put("label", "合成币 A").put("price", 93))), new Notices.State(null), on(1, 3), T0, ZONE).get(0).title);
    }

    @Test public void bothWaysSayTheLargerOnceAndMarkBoth() throws Exception {
        Notices.State st = new Notices.State(null);
        List<Notices.Notice> n = Notices.market(market(110, 100, 100, 100, 100, 100, 120, 115), st, on(3, 5), T0, ZONE);
        assertEquals(1, n.size());
        assertEquals("合成币 A 3 天内上涨 10.0%", n.get(0).title);
        assertTrue("the fall is not said a read later", Notices.market(market(110, 100, 100, 100, 100, 100, 120, 115), st, on(3, 5), T0 + 10 * MIN, ZONE).isEmpty());
    }

    @Test public void offMissingAndUnavailableStayQuiet() throws Exception {
        assertTrue(Notices.market(market(120, 100, 100, 100), new Notices.State(null), new Notices.Settings(Set.of(Notices.SITES), 30, 10), T0, ZONE).isEmpty());
        assertTrue(Notices.market(new JSONObject(), new Notices.State(null), on(3, 5), T0, ZONE).isEmpty());
        JSONObject gone = market(120, 100, 100, 100); gone.getJSONObject("history").put("unavailableSymbols", new JSONArray().put("COIN-A"));
        assertTrue(Notices.market(gone, new Notices.State(null), on(3, 5), T0, ZONE).isEmpty());
        // Without a quote the newest close stands for now.
        assertEquals("合成币 A 3 天内上涨 8.0%", Notices.market(market(0, 100, 100, 100, 100, 100, 100, 108), new Notices.State(null), on(3, 5), T0, ZONE).get(0).title);
    }

    @Test public void stateKeepsOnlySymbolsAndNumbers() throws Exception {
        Notices.State st = new Notices.State(null);
        Notices.market(market(106, 100, 100, 100, 100, 100, 100, 100), st, on(3, 5), T0, ZONE);
        JSONObject e = st.json.getJSONObject("market").getJSONObject("COIN-A");
        assertEquals(Set.of("seenAt", "up"), keys(e));
        assertFalse(st.json.toString().contains("合成币"));
        // Two weeks unseen and the entry goes.
        Notices.market(new JSONObject().put("history", new JSONObject().put("series", new JSONArray())), st, on(3, 5), T0 + 15 * DAY, ZONE);
        assertFalse(st.json.getJSONObject("market").has("COIN-A"));
    }

    @Test public void choicesAndWording() {
        assertEquals(3, Notices.choice(9, Notices.MARKET_DAY_CHOICES, Notices.MARKET_DAYS_DEFAULT));
        assertEquals(7, Notices.choice(7, Notices.MARKET_DAY_CHOICES, Notices.MARKET_DAYS_DEFAULT));
        assertEquals(5, Notices.choice(4, Notices.MARKET_LINE_CHOICES, Notices.MARKET_LINE_DEFAULT));
        assertEquals("84,830.46", Notices.money(84830.456));
        assertEquals("0.0123", Notices.money(0.012345));
        assertEquals("行情波动提醒", Notices.label(Notices.MARKET));
        assertEquals("任一关注资产 3 天内涨跌超过 5% 时提醒", Notices.detail(Notices.MARKET, on(3, 5)));
        assertTrue(Notices.KINDS.contains(Notices.MARKET));
        assertFalse(Notices.QUOTA_KINDS.contains(Notices.MARKET));
        assertFalse(Notices.QUOTA_KINDS.contains(Notices.SITES));
    }
    private static Set<String> keys(JSONObject o) { Set<String> out = new HashSet<>(); for (Iterator<String> it = o.keys(); it.hasNext(); ) out.add(it.next()); return out; }
}
