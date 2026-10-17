package wang.robby.console.android;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

/** Tibo 重置提醒 rings once per new announcement and never for history, updates or someone else's posts. */
public final class TiboAlertTest {
    private static final long NOW = Instant.parse("2026-10-18T05:00:00Z").toEpochMilli(), H = 3600_000L;
    private static JSONObject alert(String id, String kind, long at, long deadline) throws Exception {
        return TiboChallengeTest.post(id, 13, kind, "标题 " + id, Instant.ofEpochMilli(at).toString(), deadline > 0 ? Instant.ofEpochMilli(deadline).toString() : null);
    }
    private static List<TiboAlert> parse(JSONObject... items) throws Exception {
        JSONArray list = new JSONArray(); for (JSONObject i : items) list.put(i);
        return TiboAlert.parse(new JSONObject().put("schemaVersion", 1).put("alerts", list));
    }
    @Test public void onlyIntentPostsOfTibosOwnAccountAreRead() throws Exception {
        List<TiboAlert> list = parse(alert("2111600000000000001", "reset-scheduled", NOW - H, NOW + H), alert("2111600000000000002", "update", NOW, 0), alert("2111600000000000003", "reset-completed", NOW, 0),
            alert("2111600000000000004", "cards-announced", NOW, 0).put("sourceUrl", "https://x.com/someone/status/2111600000000000004"),
            alert("2111600000000000005", "cards-announced", NOW, 0).put("sourceUrl", "https://x.com/thsottiaux/status/2111600000000000006"), alert("2111600000000000007", "cards-scheduled", NOW, 0));
        assertEquals(List.of("2111600000000000001", "2111600000000000007"), list.stream().map(a -> a.id).toList());
        assertTrue(TiboAlert.parse(new JSONObject().put("schemaVersion", 2).put("alerts", new JSONArray().put(alert("2111600000000000001", "reset-scheduled", NOW, 0)))).isEmpty());
    }
    @Test public void ringsOncePerNewPostAndNeverForHistory() throws Exception {
        long enabled = NOW - 2 * H;
        List<TiboAlert> list = parse(alert("2111600000000000001", "reset-announced", NOW - 10 * 60_000L, 0), alert("2111600000000000002", "reset-scheduled", NOW - 3 * H, NOW + H));
        assertTrue("the first check only learns what is there", TiboAlert.due(list, Set.of(), false, enabled, NOW).isEmpty());
        assertEquals("published before switching on", List.of("2111600000000000001"), TiboAlert.due(list, Set.of(), true, enabled, NOW).stream().map(a -> a.id).toList());
        assertTrue(TiboAlert.due(list, Set.of("2111600000000000001"), true, enabled, NOW).isEmpty());
        List<TiboAlert> late = parse(alert("2111600000000000003", "cards-scheduled", NOW - 30 * H, NOW + H), alert("2111600000000000004", "reset-announced", NOW - 30 * H, 0));
        assertEquals("a day-old post rings only while its time is ahead", List.of("2111600000000000003"), TiboAlert.due(late, Set.of(), true, NOW - 40 * H, NOW).stream().map(a -> a.id).toList());
    }
    @Test public void alarmTextSaysWhatAndWhen() throws Exception {
        TiboAlert a = TiboAlert.from(alert("2111600000000000001", "reset-scheduled", NOW - H, NOW + 2 * H + 5 * 60_000L));
        assertEquals("Tibo 定好了额度重置时间", a.heading());
        assertEquals("10/18 15:05 · 2 小时 5 分钟后", a.when(ZoneId.of("Asia/Shanghai"), NOW));
        TiboAlert c = TiboAlert.from(alert("2111600000000000002", "cards-announced", NOW, 0).put("conditional", true));
        assertEquals("Tibo 宣布发放重置卡（有条件）", c.heading()); assertEquals("时间未公布", c.when(ZoneId.of("Asia/Shanghai"), NOW));
        TiboAlert round = TiboAlert.from(a.json()); assertEquals(a.id, round.id); assertEquals(a.deadline, round.deadline);
    }
}
