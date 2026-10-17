package wang.robby.console.android;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.time.*;
import java.util.List;

/** 重置日历 places only real, zoned reset instants; it never invents a date or calls an old reading current. */
public final class QuotaCalendarTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final long NOW = OffsetDateTime.parse("2026-10-18T10:00:00+08:00").toInstant().toEpochMilli(), H = 3600_000L;
    private static String iso(long at) { return Instant.ofEpochMilli(at).toString(); }
    private static JSONObject account(String id, String product, String status, long lastSuccess) throws Exception {
        return new JSONObject().put("id", id).put("product", product).put("provider", "演示").put("label", product).put("status", status)
            .put("profile", new JSONObject().put("email", id + "@example.com")).put("lastSuccessAt", lastSuccess > 0 ? iso(lastSuccess) : JSONObject.NULL).put("windows", new JSONArray());
    }
    private static JSONObject window(String id, String label, String group, Object resetsAt) throws Exception {
        return new JSONObject().put("id", id).put("label", label).put("quotaGroup", group == null ? JSONObject.NULL : group).put("resetsAt", resetsAt);
    }

    @Test public void upcomingWindowsAndElapsedHistoryLandOnLocalDays() throws Exception {
        JSONObject codex = account("codex-1", "codex", "connected", NOW - 30_000);
        codex.getJSONArray("windows").put(window("primary", "5 小时", "default", iso(NOW + 15 * H))).put(window("r", "Reserve", "reserve", iso(NOW + 2 * H)));
        JSONObject claude = account("claude-1", "claude", "connected", NOW - 30_000);
        claude.getJSONArray("windows").put(window("five_hour", "5 小时", null, iso(NOW + 3 * H)));
        claude.put("resetHistory", new JSONArray().put(window("five_hour", "5 小时", null, iso(NOW - 5 * H))).put(window("five_hour", "5 小时", null, iso(NOW - 5 * H))).put(window("future", "未来", null, iso(NOW + 9 * H))));
        QuotaCalendar.Model m = QuotaCalendar.build(List.of(codex, claude), SHANGHAI, NOW);
        assertEquals(LocalDate.of(2026, 10, 18), m.today);
        assertEquals("reserve stays out, a repeated or future history entry adds nothing", 3, m.events.size());
        QuotaCalendar.Event first = m.events.get(0);
        assertEquals(QuotaCalendar.EXPIRED, first.status); assertEquals(LocalDate.of(2026, 10, 18), first.day); assertEquals("已过期", QuotaCalendar.statusLabel(first));
        QuotaCalendar.Event codexReset = m.events.get(2);
        assertEquals("01:00 the next day in Shanghai", LocalDate.of(2026, 10, 19), codexReset.day);
        assertEquals("默认额度 5 小时", codexReset.label); assertEquals("5 小时", codexReset.shortLabel()); assertEquals(QuotaCalendar.SCHEDULED, codexReset.status);
        assertEquals("本月 3 项重置", QuotaCalendar.monthSummary(m, YearMonth.of(2026, 10)));
        assertEquals("本月暂无已知重置日期", QuotaCalendar.monthSummary(m, YearMonth.of(2026, 11)));
    }
    @Test public void oldReadingsAreUnverifiedAndMissingDatesAreCounted() throws Exception {
        JSONObject stale = account("codex-2", "codex", "connected", NOW - 10 * 60_000);
        stale.getJSONArray("windows").put(window("weekly", "每周", "default", iso(NOW + 30 * H))).put(window("dateless", "无日期", "default", "2026-10-20")).put(window("zoneless", "无时区", "default", "2026-10-20T10:00:00"));
        JSONObject empty = account("grok", "grok", "needs_auth", 0);
        JSONObject unset = account("claude-2", "claude", "not_configured", 0);
        QuotaCalendar.Model m = QuotaCalendar.build(List.of(stale, empty, unset), SHANGHAI, NOW);
        assertEquals(1, m.events.size()); assertEquals(QuotaCalendar.STALE, m.events.get(0).status); assertEquals("待核实", QuotaCalendar.statusLabel(m.events.get(0)));
        assertEquals("本月 1 项重置，1 项待核实", QuotaCalendar.monthSummary(m, YearMonth.of(2026, 10)));
        assertEquals("accounts not set up stay off the calendar", 2, m.accounts);
        assertEquals("1 个账号、2 项额度未提供日期", QuotaCalendar.missing(m));
    }
    @Test public void gridIsSixMondayFirstWeeks() {
        List<LocalDate> grid = QuotaCalendar.grid(YearMonth.of(2026, 10));
        assertEquals(42, grid.size()); assertEquals(LocalDate.of(2026, 9, 28), grid.get(0)); assertEquals(DayOfWeek.MONDAY, grid.get(0).getDayOfWeek());
        assertTrue(grid.contains(LocalDate.of(2026, 10, 31)));
    }
}
