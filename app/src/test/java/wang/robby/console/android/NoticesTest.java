package wang.robby.console.android;

import static org.junit.Assert.*;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class NoticesTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long DAY = 86_400_000L, H = 3_600_000L;
    private static final long T0 = Instant.parse("2026-10-20T02:00:00Z").toEpochMilli(); // 10:00 in Shanghai
    private static String iso(long ms) { return Instant.ofEpochMilli(ms).toString(); }

    private static JSONObject window(String id, String label, double remaining, long starts, long resets) throws Exception {
        return new JSONObject().put("id", id).put("label", label).put("remainingPercent", remaining).put("unlimited", false).put("startsAt", iso(starts)).put("resetsAt", iso(resets));
    }
    private static JSONObject quotas(long now, String status, JSONObject... windows) throws Exception {
        JSONObject a = new JSONObject().put("id", "claude-1").put("provider", "Anthropic").put("product", "claude").put("label", "Claude")
            .put("profile", new JSONObject().put("name", "演示账号")).put("status", status).put("lastSuccessAt", iso(now)).put("windows", new JSONArray(Arrays.asList(windows)));
        return new JSONObject().put("accounts", new JSONArray().put(a));
    }
    private static Notices.Settings all(int daily, int low) { return new Notices.Settings(new HashSet<>(Notices.KINDS), daily, low); }
    private static JSONObject weekly(double r) throws Exception { return window("seven_day", "每周", r, T0 - 3 * DAY, T0 + 4 * DAY); }
    private static JSONObject fiveHour(double r, long resets) throws Exception { return window("five_hour", "5 小时", r, resets - 5 * H, resets); }

    @Test public void dailyConsumptionCrossesTheLineOnceADay() throws Exception {
        Notices.State st = new Notices.State(null); Notices.Settings s = all(30, 5);
        assertTrue(Notices.quotas(quotas(T0, "connected", weekly(90), fiveHour(80, T0 + H)), st, s, T0, ZONE).isEmpty());
        assertTrue(Notices.quotas(quotas(T0 + H, "connected", weekly(75), fiveHour(60, T0 + H)), st, s, T0 + H, ZONE).isEmpty());
        List<Notices.Notice> n = Notices.quotas(quotas(T0 + 2 * H, "connected", weekly(58), fiveHour(70, T0 + 6 * H)), st, s, T0 + 2 * H, ZONE);
        assertEquals(1, n.size()); assertEquals(Notices.DAILY, n.get(0).kind);
        assertEquals("Claude · 演示账号 今天已用掉 32%每周", n.get(0).title);
        assertTrue(Notices.quotas(quotas(T0 + 3 * H, "connected", weekly(40), fiveHour(70, T0 + 6 * H)), st, s, T0 + 3 * H, ZONE).isEmpty());
        // Tomorrow counts afresh from the first read.
        long tomorrow = T0 + DAY;
        assertTrue(Notices.quotas(quotas(tomorrow, "connected", window("seven_day", "每周", 38, T0 - 3 * DAY, T0 + 4 * DAY)), st, s, tomorrow, ZONE).isEmpty());
        assertEquals(1, Notices.quotas(quotas(tomorrow + H, "connected", window("seven_day", "每周", 5, T0 - 3 * DAY, T0 + 4 * DAY)), st, s, tomorrow + H, ZONE).stream().filter(x -> x.kind.equals(Notices.DAILY)).count());
    }

    @Test public void lowAndResetFireOncePerPeriodAndFiveHourRefillsStayQuiet() throws Exception {
        Notices.State st = new Notices.State(null); Notices.Settings s = all(90, 10);
        Notices.quotas(quotas(T0, "connected", weekly(40), fiveHour(30, T0 + H)), st, s, T0, ZONE);
        List<Notices.Notice> n = Notices.quotas(quotas(T0 + 600_000, "connected", weekly(39), fiveHour(8, T0 + H)), st, s, T0 + 600_000, ZONE);
        assertEquals(List.of("low"), kinds(n)); assertTrue(n.get(0).title.contains("5 小时只剩 8%"));
        assertTrue(Notices.quotas(quotas(T0 + 900_000, "connected", weekly(39), fiveHour(3, T0 + H)), st, s, T0 + 900_000, ZONE).isEmpty());
        // The 5-hour window refills: no reset notice for it.
        assertTrue(Notices.quotas(quotas(T0 + 2 * H, "connected", weekly(38), fiveHour(100, T0 + 6 * H)), st, s, T0 + 2 * H, ZONE).isEmpty());
        // The weekly one resets after being used: one reset notice.
        n = Notices.quotas(quotas(T0 + 3 * H, "connected", window("seven_day", "每周", 100, T0 + 3 * H, T0 + 10 * DAY), fiveHour(99, T0 + 6 * H)), st, s, T0 + 3 * H, ZONE);
        assertEquals(List.of("reset"), kinds(n)); assertEquals("Claude · 演示账号 每周已重置", n.get(0).title);
    }

    @Test public void accountTroubleIsReportedOnTheChangeOnly() throws Exception {
        Notices.State st = new Notices.State(null); Notices.Settings s = all(90, 5);
        Notices.quotas(quotas(T0, "connected", weekly(50)), st, s, T0, ZONE);
        assertEquals(List.of("account"), kinds(Notices.quotas(quotas(T0 + H, "needs_auth", weekly(50)), st, s, T0 + H, ZONE)));
        assertTrue(Notices.quotas(quotas(T0 + 2 * H, "needs_auth", weekly(50)), st, s, T0 + 2 * H, ZONE).isEmpty());
        // Switched off: nothing.
        Notices.State fresh = new Notices.State(null); Notices.Settings none = new Notices.Settings(Set.of(), 30, 10);
        Notices.quotas(quotas(T0, "connected", weekly(90)), fresh, none, T0, ZONE);
        assertTrue(Notices.quotas(quotas(T0 + H, "needs_auth", weekly(1)), fresh, none, T0 + H, ZONE).isEmpty());
    }

    @Test public void cardsExpiringWithinADayOnce() throws Exception {
        Notices.State st = new Notices.State(null); Notices.Settings s = all(90, 5);
        JSONObject q = quotas(T0, "connected", weekly(50));
        q.getJSONArray("accounts").getJSONObject(0).put("resetCredits", new JSONObject().put("cards", new JSONArray()
            .put(new JSONObject().put("id", "card-1").put("status", "available").put("expiresAt", iso(T0 + 5 * H)))
            .put(new JSONObject().put("id", "card-2").put("status", "available").put("expiresAt", iso(T0 + 3 * DAY)))
            .put(new JSONObject().put("id", "card-3").put("status", "used").put("expiresAt", iso(T0 + H)))));
        assertEquals(List.of("card"), kinds(Notices.quotas(q, st, s, T0, ZONE)));
        assertTrue(Notices.quotas(q, st, s, T0 + 600_000, ZONE).isEmpty());
    }

    @Test public void sitesNeedTwoMissesAndReportRecovery() throws Exception {
        Notices.State st = new Notices.State(null); Notices.Settings s = all(30, 10);
        assertTrue(Notices.sites(overview(true, "active"), st, s, T0).isEmpty());
        assertTrue(Notices.sites(overview(false, "active"), st, s, T0 + 1).isEmpty());
        List<Notices.Notice> n = Notices.sites(overview(false, "active"), st, s, T0 + 2);
        assertEquals(1, n.size()); assertEquals("演示实验站 离线", n.get(0).title); assertEquals("status", n.get(0).open);
        assertTrue(Notices.sites(overview(false, "active"), st, s, T0 + 3).isEmpty());
        assertEquals("演示实验站 已恢复", Notices.sites(overview(true, "active"), st, s, T0 + 4).get(0).title);
        Notices.State other = new Notices.State(null);
        for (int i = 0; i < 4; i++) assertTrue("excluded sites stay quiet", Notices.sites(overview(i == 0, "excluded"), other, s, T0 + i).isEmpty());
    }
    private static JSONObject overview(boolean online, String tracker) throws Exception {
        return new JSONObject().put("status", new JSONObject().put("sites", new JSONArray().put(new JSONObject().put("hostname", "lab.example.com").put("label", "演示实验站").put("tracker", tracker).put("online", online))));
    }
    private static List<String> kinds(List<Notices.Notice> n) { List<String> out = new ArrayList<>(); for (Notices.Notice x : n) out.add(x.kind); return out; }
}
