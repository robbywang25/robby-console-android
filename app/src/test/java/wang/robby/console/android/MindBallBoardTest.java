package wang.robby.console.android;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.time.LocalDate;
import java.util.*;

/** 心力球's 看板 sums a week of day files: minutes per day, 关键事项, and the 待办 still open, newest day first. */
public final class MindBallBoardTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);
    private static JSONObject day(long minutes, JSONArray items, String... done) throws Exception {
        JSONObject marks = new JSONObject(); for (String title : done) marks.put(MindBall.todoKey(new JSONObject().put("title", title)), 1L);
        JSONObject d = new JSONObject().put("segments", new JSONArray().put(new JSONObject().put("start", 0L).put("end", minutes * 60_000L))).put("done", marks);
        return items == null ? d : d.put("timeline", new JSONObject().put("items", items));
    }
    private static JSONObject item(String kind, String time, String title) throws Exception { return new JSONObject().put("kind", kind).put("time", time).put("title", title); }

    @Test public void weekCountsMinutesItemsAndOpenTodos() throws Exception {
        Map<String, JSONObject> byDay = new HashMap<>();
        byDay.put("2026-10-24", day(30, new JSONArray().put(item("event", "09:00", "晨会")).put(item("todo", "10:00", "审合同")).put(item("todo", "11:00", "回电话")), "回电话"));
        byDay.put("2026-10-22", day(90, new JSONArray().put(item("todo", "15:00", "发周报"))));
        byDay.put("2026-10-20", day(10, null));
        byDay.put("2026-10-10", day(500, new JSONArray().put(item("todo", "08:00", "太早了，不算"))));
        MindBallBoard.Week w = MindBallBoard.week(byDay, TODAY);
        assertEquals("2026-10-18", w.days.get(0)); assertEquals("2026-10-24", w.days.get(MindBallBoard.DAYS - 1));
        assertArrayEquals(new long[] { 0, 0, 10, 0, 90, 0, 30 }, w.minutes);
        assertEquals(130, w.totalMinutes()); assertEquals(3, w.recordedDays()); assertEquals(3, w.segments);
        assertEquals(1, w.events); assertEquals(3, w.todos); assertEquals(1, w.done);
        assertEquals("newest day first, timeline order within a day", List.of("审合同", "发周报"), titles(w.open));
        assertEquals("2026-10-22", w.open.get(1).day);
    }
    @Test public void emptyWeekIsAllZeros() {
        MindBallBoard.Week w = MindBallBoard.week(new HashMap<>(), TODAY);
        assertEquals(MindBallBoard.DAYS, w.days.size()); assertEquals(0, w.totalMinutes()); assertTrue(w.open.isEmpty());
    }
    @Test public void latestEventsSkipTodosAndStartFromTheEnd() throws Exception {
        JSONObject d = day(5, new JSONArray().put(item("event", "08:00", "一")).put(item("todo", "09:00", "待办")).put(item("event", "10:00", "二")).put(item("event", "11:00", "三")));
        List<String> out = new ArrayList<>(); for (JSONObject it : MindBallBoard.latestEvents(d, 2)) out.add(it.getString("title"));
        assertEquals(List.of("三", "二"), out);
        assertTrue(MindBallBoard.latestEvents(new JSONObject(), 3).isEmpty());
    }
    @Test public void durationReadsInHoursAndMinutes() {
        assertEquals("0 分钟", MindBallBoard.duration(0)); assertEquals("40 分钟", MindBallBoard.duration(40));
        assertEquals("2 小时", MindBallBoard.duration(120)); assertEquals("3 小时 25 分钟", MindBallBoard.duration(205));
    }
    private static List<String> titles(List<MindBallBoard.Todo> todos) { List<String> out = new ArrayList<>(); for (MindBallBoard.Todo t : todos) out.add(t.title); return out; }
}
