package wang.robby.console.android;

import static org.junit.Assert.*;
import java.time.Instant;
import java.time.ZoneId;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class TokenForecastTest {
    private static final long NOW = Instant.parse("2026-11-27T08:00:00Z").toEpochMilli();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test public void consumesServerInsightsAndConvenienceFields() throws Exception {
        JSONObject a = new JSONObject()
            .put("id", "codex-1").put("product", "codex").put("status", "connected")
            .put("lastSuccessAt", Instant.ofEpochMilli(NOW - 30_000L).toString())
            .put("burnRatePerHour", 5.5)
            .put("etaMinutesLeft", 90)
            .put("etaAt", Instant.ofEpochMilli(NOW + 90 * 60_000L).toString())
            .put("etaBeforeReset", true)
            .put("windows", new JSONArray().put(new JSONObject().put("id", "w").put("remainingPercent", 40)
                .put("resetsAt", Instant.ofEpochMilli(NOW + 5 * 3600_000L).toString())));
        TokenForecast.Result r = TokenForecast.analyze(a, NOW);
        assertEquals("server", r.source);
        assertEquals(5.5, r.burnPerHour, 1e-6);
        assertEquals(Long.valueOf(90), r.minutesLeft);
        assertEquals(Boolean.TRUE, r.beforeReset);
        String line = TokenForecast.summaryLine(a, ZONE, NOW);
        assertNotNull(line);
        assertTrue(line, line.contains("早于重置"));
        assertTrue(line, line.contains("5.5%/h"));
    }

    @Test public void linearFallbackEstimatesDepletionFromTrendPoints() throws Exception {
        JSONArray pts = new JSONArray();
        for (int i = 0; i <= 6; i++) {
            pts.put(new JSONObject()
                .put("at", Instant.ofEpochMilli(NOW - (6 - i) * 600_000L).toString())
                .put("remaining", 80.0 - i * 4.0));
        }
        JSONObject a = new JSONObject()
            .put("id", "claude-1").put("product", "claude").put("status", "connected")
            .put("lastSuccessAt", Instant.ofEpochMilli(NOW - 10_000L).toString())
            .put("windows", new JSONArray().put(new JSONObject().put("id", "five_hour").put("remainingPercent", 56)
                .put("resetsAt", Instant.ofEpochMilli(NOW + 3 * 3600_000L).toString())))
            .put("usageTrend", new JSONObject().put("metric", "percent").put("balancePoints", pts));
        TokenForecast.Result r = TokenForecast.analyze(a, NOW);
        assertEquals("fallback", r.source);
        assertEquals(24.0, r.burnPerHour, 0.2);
        assertEquals(Long.valueOf(140L), r.minutesLeft);
        assertEquals(Boolean.TRUE, r.beforeReset);
        assertNotNull(TokenForecast.summaryLine(a, ZONE, NOW));
    }

    @Test public void staleAccountSuppressesForecastLine() throws Exception {
        JSONObject a = new JSONObject()
            .put("id", "codex-1").put("product", "codex").put("status", "connected")
            .put("lastSuccessAt", Instant.ofEpochMilli(NOW - 300_000L).toString())
            .put("burnRatePerHour", 10)
            .put("etaMinutesLeft", 30)
            .put("etaAt", Instant.ofEpochMilli(NOW + 1800_000L).toString());
        assertNull(TokenForecast.summaryLine(a, ZONE, NOW));
    }
}
