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

    @Test public void codexCreditDropsCrossTheLineOncePerAccountPerDay() throws Exception {
        Notices.State st = new Notices.State(null);
        Notices.Settings s = new Notices.Settings(new HashSet<>(Notices.KINDS), 30, 10, 50);
        long evening = T0 + 13 * H, morning = T0 + 15 * H; // 23:00, then 01:00 the next local day
        assertTrue(Notices.quotas(credits(T0, 200, 200, 500, null), st, s, T0, ZONE).isEmpty());
        // A crosses 50. B has only dropped 40, so it waits. Claude and the unlimited account stay quiet.
        List<Notices.Notice> n = Notices.quotas(credits(T0 + H, 140, 160, 100, 0), st, s, T0 + H, ZONE);
        assertEquals(List.of(Notices.CREDITS), kinds(n));
        assertEquals("OpenAI · a@example.com 今天已用掉 60 点积分", n.get(0).title);
        assertTrue(n.get(0).text.contains("50 点"));
        // A already rang today. B tops up: the rise is not consumption, and 300 is the new baseline.
        assertTrue(Notices.quotas(credits(T0 + 2 * H, 100, 300, 0, null), st, s, T0 + 2 * H, ZONE).isEmpty());
        JSONObject b = st.json.getJSONObject("credits").getJSONObject("codex-b");
        assertEquals(0, b.getDouble("used"), 0.001);
        assertEquals(300, b.getDouble("base"), 0.001);
        assertTrue(Notices.quotas(credits(T0 + 3 * H, 90, 260, 0, null), st, s, T0 + 3 * H, ZONE).isEmpty());
        n = Notices.quotas(credits(T0 + 4 * H, 80, 240, 0, null), st, s, T0 + 4 * H, ZONE);
        assertEquals(1, n.size());
        assertEquals("OpenAI · b@example.com 今天已用掉 60 点积分", n.get(0).title);
        assertTrue("A does not ring a second time the same day", Notices.quotas(credits(evening, 10, 200, 0, null), st, s, evening, ZONE).isEmpty());
        // The local day rolls over. The first read is the new baseline, even when the balance is unchanged.
        assertTrue(Notices.quotas(credits(morning, 10, 200, 0, null), st, s, morning, ZONE).isEmpty());
        n = Notices.quotas(credits(morning + H, 10, 140, 0, null), st, s, morning + H, ZONE);
        assertEquals(List.of(Notices.CREDITS), kinds(n));
        assertEquals("codex-b", n.get(0).key.split(":")[1]);
        // No line, no notice. A missing balance and an unlimited balance never count.
        Notices.State off = new Notices.State(null);
        Notices.Settings none = new Notices.Settings(Set.of(Notices.DAILY), 30, 10, 50);
        assertTrue(Notices.quotas(credits(T0, 200, 200, 500, 500), off, none, T0, ZONE).isEmpty());
        assertTrue(Notices.quotas(credits(T0 + H, 0, 0, 0, 0), off, none, T0 + H, ZONE).isEmpty());
    }
    private static JSONObject credits(long now, int aLeft, int bLeft, Integer unlimitedLeft, Integer claudeLeft) throws Exception {
        JSONArray accounts = new JSONArray()
            .put(codex("codex-a", "a@example.com", now, aLeft, false))
            .put(codex("codex-b", "b@example.com", now, bLeft, false))
            .put(codex("codex-unlimited", "u@example.com", now, unlimitedLeft, true))
            .put(codex("codex-blank", "n@example.com", now, null, false));
        if (claudeLeft != null) accounts.put(new JSONObject().put("id", "claude-1").put("provider", "Anthropic").put("product", "claude").put("label", "Claude")
            .put("profile", new JSONObject().put("name", "演示账号")).put("status", "connected").put("lastSuccessAt", iso(now))
            .put("credits", new JSONObject().put("remaining", claudeLeft).put("unlimited", false))
            .put("windows", new JSONArray().put(window("seven_day", "每周", 80, T0 - 3 * DAY, T0 + 4 * DAY))));
        return new JSONObject().put("accounts", accounts);
    }
    /** {@code remaining} null omits credits. {@code unlimited} true never reminds, whatever the number does. */
    private static JSONObject codex(String id, String email, long now, Integer remaining, boolean unlimited) throws Exception {
        JSONObject a = new JSONObject().put("id", id).put("provider", "OpenAI").put("product", "codex").put("label", "ChatGPT Codex")
            .put("profile", new JSONObject().put("email", email)).put("status", "connected").put("lastSuccessAt", iso(now))
            .put("windows", new JSONArray().put(window("weekly", "每周", 80, T0 - 3 * DAY, T0 + 4 * DAY)));
        if (unlimited) a.put("credits", new JSONObject().put("unlimited", true).put("remaining", remaining == null ? 0 : remaining));
        else if (remaining != null) a.put("credits", new JSONObject().put("unlimited", false).put("remaining", remaining));
        return a;
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
