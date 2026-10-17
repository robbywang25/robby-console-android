package wang.robby.console.android;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.time.Instant;
import java.util.List;

/** 28 天挑战 follows the web's markers and never treats a summary's wording as a confirmed reset. */
public final class TiboChallengeTest {
    private static final long NOW = Instant.parse("2026-10-18T05:00:00Z").toEpochMilli();
    static JSONObject post(String id, int day, String kind, String title, String at, String deadline) throws Exception {
        return new JSONObject().put("id", id).put("day", day).put("kind", kind).put("title", title).put("publishedAt", at).put("deadline", deadline == null ? JSONObject.NULL : deadline)
            .put("conditional", false).put("excerpt", "excerpt").put("sourceUrl", "https://x.com/thsottiaux/status/" + id);
    }
    static JSONObject payload() throws Exception {
        JSONArray events = new JSONArray()
            .put(post("2111600000000000013", 13, "reset-scheduled", "今晚重置", "2026-10-18T04:00:00Z", "2026-10-18T07:00:00Z"))
            .put(post("2111600000000000012", 13, "update", "闲聊", "2026-10-18T03:00:00Z", null))
            .put(post("2111600000000000031", 3, "cards-completed", "重置卡已发放", "2026-10-08T20:00:00Z", null))
            .put(post("2111600000000000021", 2, "reset-completed", "额度重置已完成", "2026-10-07T20:00:00Z", null))
            .put(post("2111600000000000020", 2, "improvement", "Day 2 产品更新", "2026-10-07T18:00:00Z", null))
            .put(post("2111600000000000099", 5, "improvement", "冒名帖子", "2026-10-09T18:00:00Z", null).put("sourceUrl", "https://x.com/someone/status/2111600000000000099"));
        JSONArray daily = new JSONArray()
            .put(new JSONObject().put("day", 4).put("preview", "即时纠偏").put("updatedAt", "2026-10-09T10:00:00Z").put("items", new JSONArray().put(new JSONObject().put("id", "2111600000000000040").put("title", "Day 4 重置已完成").put("summary", "社区反馈称重置已完成。").put("kind", "update").put("sourceUrl", "https://x.com/thsottiaux/status/2111600000000000040"))))
            .put(new JSONObject().put("day", 20).put("preview", "未来").put("items", new JSONArray().put(new JSONObject().put("title", "未来").put("summary", "不该出现").put("sourceUrl", "https://x.com/thsottiaux/status/2111600000000000200"))));
        return new JSONObject().put("schemaVersion", 1).put("status", "connected").put("lastSuccessAt", "2026-10-18T04:58:00Z").put("intervalSeconds", 300).put("sourceNote", "note")
            .put("challenge", new JSONObject().put("startDate", "2026-10-05").put("days", 28).put("timeZone", "America/Los_Angeles").put("currentDay", 13))
            .put("upcoming", post("2111600000000000013", 13, "reset-scheduled", "今晚重置", "2026-10-18T04:00:00Z", "2026-10-18T07:00:00Z").put("state", "scheduled"))
            .put("events", events).put("dailySummaries", daily);
    }

    @Test public void markersBriefsAndCountsFollowTheWeb() throws Exception {
        TiboChallenge.Model m = new TiboChallenge.Model(payload());
        List<TiboChallenge.Cell> cells = m.cells();
        assertEquals(28, cells.size());
        assertEquals("已重置", cells.get(1).marker); assertEquals("Day 2 产品更新", cells.get(1).brief); assertEquals(java.time.LocalDate.of(2026, 10, 6), cells.get(1).date);
        assertEquals("已发卡", cells.get(2).marker);
        assertEquals("有更新", cells.get(3).marker); assertEquals("即时纠偏", cells.get(3).brief);
        assertEquals("a foreign account's post is not a record", "待公布", cells.get(4).marker);
        assertEquals("有更新", cells.get(12).marker); assertTrue(cells.get(12).today);
        assertEquals("待公布", cells.get(19).marker); assertEquals("a future day's summary is ignored", "", cells.get(19).brief);
        assertEquals("days 2, 3, 4 and 13", 4, m.recorded);
        assertEquals(13, m.defaultDay());
        assertEquals("the newest confirmation, not the first in the list", "重置卡已发放", m.latest.title);
        assertNotNull(m.upcoming); assertTrue(m.current(NOW)); assertTrue(m.syncing());
        assertEquals("updates stay out of a day unless a digest names them", 1, m.on(13).size());
    }
    @Test public void summaryClaimsNeedAConfirmingPost() throws Exception {
        TiboChallenge.Model m = new TiboChallenge.Model(payload());
        TiboChallenge.Item claim = m.summary(4).items.get(0);
        assertEquals("原帖尚未明确确认完成，暂不按已重置或已发卡处理。", TiboChallenge.verificationNote(claim, 4, m));
        TiboChallenge.Item confirmed = new TiboChallenge.Item("Day 2 重置已完成", "已完成", "update", "https://x.com/thsottiaux/status/2111600000000000021");
        assertNull("day 2 has a reset-completed post", TiboChallenge.verificationNote(confirmed, 2, m));
        assertNull(TiboChallenge.verificationNote(new TiboChallenge.Item("产品更新", "新功能上线", "update", "u"), 4, m));
    }
    @Test public void countdownAndClock() {
        assertEquals("02:00:00", TiboChallenge.countdown(NOW + 7_200_000L, NOW));
        assertEquals("00:00:01", TiboChallenge.countdown(NOW + 1, NOW));
        assertEquals("预告时间已到 · 待确认", TiboChallenge.countdown(NOW, NOW));
        assertEquals("时间未确认", TiboChallenge.countdown(0, NOW));
        assertEquals("10/17 22:00:00 PDT", TiboChallenge.tiboClock(NOW));
        assertNull(TiboChallenge.official("https://x.com/thsottiaux/status/1"));
        assertNull(TiboChallenge.official("https://x.com/thsottiaux/status/2111600000000000013?x=1"));
    }
    @Test public void anOldConnectedResultIsNotCurrent() throws Exception {
        JSONObject p = payload().put("lastSuccessAt", "2026-10-18T04:00:00Z");
        assertFalse(new TiboChallenge.Model(p).current(NOW));
    }
}
