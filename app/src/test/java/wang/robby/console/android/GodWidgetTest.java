package wang.robby.console.android;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;
import java.time.Instant;

/** The widget shows only Tibo's public announcement, and never an old reading as a live countdown. */
public final class GodWidgetTest {
    private static final long NOW = Instant.parse("2026-10-18T12:00:00Z").toEpochMilli(), H = 3600_000L;
    private static String post(String kind, long deadline) throws Exception {
        return TiboChallengeTest.post("2111600000000000013", 13, kind, "预告", "2026-10-18T11:00:00Z", deadline > 0 ? Instant.ofEpochMilli(deadline).toString() : null).put("state", "scheduled").toString();
    }
    @Test public void countdownOnlyForAFreshAnnouncementWithATime() throws Exception {
        GodWidget.Status s = GodWidget.status(post("reset-scheduled", NOW + 2 * H), NOW - 60_000L, NOW);
        assertEquals("Tibo 重置倒计时", s.line); assertEquals(NOW + 2 * H, s.deadline);
        assertEquals("Tibo 发卡倒计时", GodWidget.status(post("cards-scheduled", NOW + H), NOW, NOW).line);
        assertEquals("Tibo 预告时间已到 · 待确认", GodWidget.status(post("reset-scheduled", NOW - 1), NOW, NOW).line);
        assertEquals("Tibo 已预告重置 · 时间未公布", GodWidget.status(post("reset-announced", 0), NOW, NOW).line);
    }
    @Test public void nothingKnownOrStaleSaysOpenGod() throws Exception {
        assertEquals("打开 God", GodWidget.status(null, 0, NOW).line);
        assertEquals("a reading older than two hours is not shown", "打开 God", GodWidget.status(post("reset-scheduled", NOW + 5 * H), NOW - 3 * H, NOW).line);
        assertEquals(0, GodWidget.status(post("reset-scheduled", NOW + 5 * H), NOW - 3 * H, NOW).deadline);
        assertEquals("暂无 Tibo 重置预告", GodWidget.status("", NOW, NOW).line);
        assertEquals("a non-intent post is not an announcement", "暂无 Tibo 重置预告", GodWidget.status(post("update", 0), NOW, NOW).line);
        JSONObject foreign = new JSONObject(post("reset-scheduled", NOW + H)).put("sourceUrl", "https://x.com/someone/status/2111600000000000013");
        assertEquals("暂无 Tibo 重置预告", GodWidget.status(foreign.toString(), NOW, NOW).line);
        assertEquals("打开 God", GodWidget.status("{not json", NOW, NOW).line);
    }
}
