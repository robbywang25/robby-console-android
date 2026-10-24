package wang.robby.console.android;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;
import java.util.*;

/**
 * 心力球's 看板: the last {@link #DAYS} days at a glance, from the day files on this phone. Minutes recorded per day,
 * how many 关键事项 and 待办 DeepSeek found, and the 待办 still open across those days. Pure Java for the JVM tests.
 */
final class MindBallBoard {
    private MindBallBoard() {}
    static final int DAYS = 7;

    static final class Todo {
        final String day, key, time, title, due;
        Todo(String day, String key, String time, String title, String due) { this.day = day; this.key = key; this.time = time; this.title = title; this.due = due; }
    }
    static final class Week {
        /** Oldest first, today last; {@code minutes[i]} belongs to {@code days[i]}. */
        final List<String> days = new ArrayList<>(); final long[] minutes = new long[DAYS];
        int segments, events, todos, done;
        /** Open 待办, newest day first and in the timeline's order within a day. */
        final List<Todo> open = new ArrayList<>();
        long totalMinutes() { long m = 0; for (long v : minutes) m += v; return m; }
        int recordedDays() { int n = 0; for (long v : minutes) if (v > 0) n++; return n; }
    }

    /** {@code byDay} maps "yyyy-MM-dd" to that day's file; days missing from it count as nothing recorded. */
    static Week week(Map<String, JSONObject> byDay, LocalDate today) {
        Week w = new Week();
        for (int i = DAYS - 1; i >= 0; i--) w.days.add(today.minusDays(i).toString());
        for (int i = 0; i < DAYS; i++) {
            JSONObject d = byDay.get(w.days.get(i)); if (d == null) continue;
            JSONArray segs = d.optJSONArray("segments"); w.minutes[i] = MindBall.minutes(segs); w.segments += segs == null ? 0 : segs.length();
        }
        for (int i = DAYS - 1; i >= 0; i--) {
            String day = w.days.get(i); JSONObject d = byDay.get(day); if (d == null) continue;
            JSONObject tl = d.optJSONObject("timeline"); JSONArray items = tl == null ? null : tl.optJSONArray("items"); if (items == null) continue;
            JSONObject marks = d.optJSONObject("done"); if (marks == null) marks = new JSONObject();
            for (int k = 0; k < items.length(); k++) {
                JSONObject it = items.optJSONObject(k); if (it == null) continue;
                if (!"todo".equals(it.optString("kind"))) { w.events++; continue; }
                w.todos++; String key = MindBall.todoKey(it);
                if (marks.has(key)) w.done++; else w.open.add(new Todo(day, key, it.optString("time"), it.optString("title"), it.optString("due")));
            }
        }
        return w;
    }
    /** "3 小时 25 分钟", "40 分钟", "0 分钟". */
    static String duration(long minutes) { return minutes >= 60 ? minutes / 60 + " 小时" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + " 分钟") : minutes + " 分钟"; }
    /** The last {@code n} 关键事项 of a day, latest first; the board shows what happened most recently. */
    static List<JSONObject> latestEvents(JSONObject day, int n) {
        JSONObject tl = day.optJSONObject("timeline"); JSONArray items = tl == null ? null : tl.optJSONArray("items"); List<JSONObject> out = new ArrayList<>();
        if (items != null) for (int i = items.length() - 1; i >= 0 && out.size() < n; i--) { JSONObject it = items.optJSONObject(i); if (it != null && !"todo".equals(it.optString("kind"))) out.add(it); }
        return out;
    }
}
