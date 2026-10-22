package wang.robby.console.android;

import static org.junit.Assert.*;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** The home screen widgets get figures only: no email, account id, site or conversation name, and stale readings say so. */
public final class WidgetSnapshotTest {
    private static final long NOW = Instant.parse("2026-10-22T04:00:00Z").toEpochMilli(), H = 3600_000L;
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    private static JSONObject account(String id, String product, double left, long resetAt) throws Exception {
        JSONObject w = new JSONObject().put("id", "w-" + id).put("label", "每周").put("remainingPercent", left).put("resetsAt", Instant.ofEpochMilli(resetAt).toString());
        return new JSONObject().put("id", id).put("product", product).put("status", "connected").put("lastSuccessAt", Instant.ofEpochMilli(NOW - 60_000L).toString())
            .put("profile", new JSONObject().put("email", id + "@example.com")).put("windows", new JSONArray().put(w));
    }
    private static JSONObject quotas() throws Exception {
        JSONArray accounts = new JSONArray();
        double[] left = {90, 15, 60, 40, 75, 5, 50};
        for (int i = 0; i < left.length; i++) accounts.put(account("codex-" + i, "codex", left[i], NOW + (i + 1) * H));
        accounts.put(account("chatgpt-chat", "chatgpt-chat", 1, NOW + H));
        return new JSONObject().put("accounts", accounts).put("additionalAccounts", new JSONArray().put(account("today-global", "todayai", 30, NOW + 30 * H)));
    }

    @Test public void tokensKeepSixLeastLeftFirstWithoutEmailsOrHiddenCards() throws Exception {
        JSONObject snap = WidgetSnapshot.tokens(quotas(), Set.of("codex-5"));
        JSONArray rows = snap.getJSONArray("rows");
        assertEquals(6, rows.length());
        assertEquals("the hidden card is left out and the least left comes first", 15, rows.getJSONObject(0).getLong("percent"));
        for (int i = 1; i < rows.length(); i++) assertTrue(rows.getJSONObject(i - 1).getLong("percent") <= rows.getJSONObject(i).getLong("percent"));
        assertFalse("no email, local part or account id", snap.toString().contains("@") || snap.toString().contains("example") || snap.toString().contains("codex-"));
        assertTrue(WidgetSnapshot.low(rows.getJSONObject(0)));
        assertFalse(WidgetSnapshot.low(rows.getJSONObject(5)));
        String line = WidgetSnapshot.tokenLine(rows.getJSONObject(0), BEIJING, NOW);
        assertTrue(line, line.contains("15%") && line.endsWith(" 重置"));
    }
    @Test public void calendarListsTheNextFiveWithoutAccounts() throws Exception {
        JSONArray rows = WidgetSnapshot.calendar(quotas(), BEIJING, NOW).getJSONArray("rows");
        assertEquals(5, rows.length());
        for (int i = 1; i < rows.length(); i++) assertTrue(rows.getJSONObject(i - 1).getLong("at") <= rows.getJSONObject(i).getLong("at"));
        assertFalse(rows.toString().contains("@") || rows.toString().contains("example"));
        assertEquals("13:00 · Codex 每周", WidgetSnapshot.calendarLine(rows.getJSONObject(0), BEIJING, NOW));
    }
    @Test public void statusCountsSitesAndIssuesOnly() throws Exception {
        JSONObject sum = new JSONObject().put("publicOnline", 6).put("publicKnown", 7).put("publicTotal", 7).put("privateOnline", 9).put("privateKnown", 10).put("privateTotal", 10)
            .put("upstreamOperational", 1).put("upstreamTotal", 2);
        JSONObject s = WidgetSnapshot.status(new JSONObject().put("status", new JSONObject().put("summary", sum).put("sites", new JSONArray().put(new JSONObject().put("label", "博客")))));
        assertEquals(15, s.getInt("online")); assertEquals(17, s.getInt("total")); assertEquals(3, s.getInt("issues"));
        assertFalse("no site names", s.toString().contains("博客"));
        assertNull(WidgetSnapshot.status(new JSONObject()));
    }
    @Test public void wechatKeepsTodayAndTheWeeklyAverageOnly() throws Exception {
        JSONObject envelope = WechatDigest.exampleResponse(Instant.ofEpochMilli(NOW));
        JSONObject s = WidgetSnapshot.wechat(envelope);
        WechatDigest.Doc d = WechatDigest.parseResponse(envelope);
        assertEquals(d.today.messages, s.getLong("today"));
        assertEquals(Math.round(d.last7d.messages / 7.0), s.getLong("average7d"));
        for (WechatDigest.Conversation c : d.conversations) assertFalse("no conversation name", s.toString().contains(c.name));
        assertNull(WidgetSnapshot.wechat(new JSONObject().put("schemaVersion", 1)));
    }
    @Test public void readingsOlderThanTwoHoursAskToOpenGod() {
        assertFalse(WidgetSnapshot.stale(NOW - H, NOW));
        assertTrue(WidgetSnapshot.stale(NOW - 2 * H - 1, NOW));
        assertTrue("never read", WidgetSnapshot.stale(0, NOW));
        assertTrue("a clock moved back", WidgetSnapshot.stale(NOW + 2 * H, NOW));
        assertEquals("打开 God 更新", WidgetSnapshot.footer(NOW - 3 * H, NOW, BEIJING));
        assertEquals("11:30 更新", WidgetSnapshot.footer(NOW - 30 * 60_000L, NOW, BEIJING));
        assertEquals("明天 09:00", WidgetSnapshot.when(Instant.parse("2026-10-23T01:00:00Z").toEpochMilli(), BEIJING, NOW));
        assertEquals("10/25 09:00", WidgetSnapshot.when(Instant.parse("2026-10-25T01:00:00Z").toEpochMilli(), BEIJING, NOW));
    }
    @Test public void clocksAreTheFirstThreeWithPstByDefault() {
        List<Dashboard.Clock> d = WidgetSnapshot.clocks(null);
        assertEquals(3, d.size()); assertEquals("Etc/GMT+8", d.get(2).zone);
        String four = "东京|Asia/Tokyo\n伦敦|Europe/London\n纽约|America/New_York\n悉尼|Australia/Sydney";
        assertEquals(3, WidgetSnapshot.clocks(four).size()); assertEquals("东京", WidgetSnapshot.clocks(four).get(0).name);
        assertTrue(WidgetSnapshot.clocks("").isEmpty());
        assertEquals(Collections.emptyList(), WidgetSnapshot.clocks(""));
    }
    /** The manifest's widgets with account figures stay off the lock screen; only the clock and God 小组件 may go there. */
    @Test public void privateWidgetsAreHomeScreenOnly() throws Exception {
        java.io.File dir = new java.io.File("src/main/res/xml");
        for (String name : new String[]{"token_widget_info", "calendar_widget_info", "status_widget_info", "wechat_widget_info"}) {
            String xml = new String(java.nio.file.Files.readAllBytes(new java.io.File(dir, name + ".xml").toPath()), "UTF-8");
            assertTrue(name, xml.contains("android:widgetCategory=\"home_screen\"")); assertTrue(name, xml.contains("android:updatePeriodMillis=\"0\""));
        }
        String clock = new String(java.nio.file.Files.readAllBytes(new java.io.File(dir, "clock_widget_info.xml").toPath()), "UTF-8");
        assertTrue(clock.contains("home_screen|keyguard"));
    }
}
