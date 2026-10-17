package wang.robby.console.android;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Tibo 28 天挑战 as tokens.robby.wang renders it (tibo.mjs): day markers, briefs, the selected day's progress and the
 * official reset countdown. Only verified posts from Tibo's own account reach the phone; they are shown as plain text.
 */
final class TiboChallenge {
    private TiboChallenge() {}
    static final Pattern OFFICIAL = Pattern.compile("https://x\\.com/thsottiaux/status/\\d{15,22}");
    static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles");
    static final Set<String> INTENT = Set.of("reset-announced", "reset-scheduled", "cards-announced", "cards-scheduled");
    private static final Map<String, String> KIND = Map.of("improvement", "产品更新", "reset-completed", "重置已确认", "reset-announced", "重置预告", "reset-scheduled", "重置预告",
        "cards-announced", "发卡公告", "cards-scheduled", "发卡预告", "cards-completed", "发卡已确认", "update", "官方动态");

    static final class Post {
        final String id, kind, title, excerpt, url; final int day; final long published, deadline; final boolean conditional;
        Post(JSONObject e) {
            id = e.optString("id"); kind = e.optString("kind"); title = text(e.opt("title")); excerpt = text(e.opt("excerpt"));
            url = official(e.opt("sourceUrl")); day = e.opt("day") instanceof Integer ? (Integer) e.opt("day") : 0;
            published = QuotaCalendar.instant(e.opt("publishedAt")); deadline = QuotaCalendar.instant(e.opt("deadline")); conditional = Boolean.TRUE.equals(e.opt("conditional"));
        }
        boolean cards() { return kind.startsWith("cards"); }
        String kindLabel() { return TiboChallenge.kindLabel(kind); }
    }
    static final class Item { final String title, summary, kind, url; Item(String title, String summary, String kind, String url) { this.title = title; this.summary = summary; this.kind = kind; this.url = url; } }
    static final class Summary { final int day; final String preview; final long updated; final List<Item> items; Summary(int day, String preview, long updated, List<Item> items) { this.day = day; this.preview = preview; this.updated = updated; this.items = items; } }
    static final class Cell { final int day; final LocalDate date; final String marker, brief; final boolean today; Cell(int day, LocalDate date, String marker, String brief, boolean today) { this.day = day; this.date = date; this.marker = marker; this.brief = brief; this.today = today; } }

    static final class Model {
        final int currentDay, days, recorded; final LocalDate start; final List<Post> events, relevant; final List<Summary> summaries; final Post upcoming, latest;
        final String status, summaryStatus, sourceNote, upcomingState; final long lastSuccess, summaryLastSuccess; final int interval;
        Model(JSONObject p) {
            JSONObject c = p.optJSONObject("challenge");
            int total = c != null && c.opt("days") instanceof Integer ? Math.max(1, Math.min(28, c.optInt("days"))) : 28;
            days = total; currentDay = c != null && c.opt("currentDay") instanceof Integer ? Math.max(1, Math.min(total, c.optInt("currentDay"))) : 1;
            LocalDate s; try { s = LocalDate.parse(c == null ? "" : c.optString("startDate")); } catch (RuntimeException e) { s = LocalDate.of(2026, 10, 5); } start = s;
            List<Post> all = new ArrayList<>(); JSONArray list = p.optJSONArray("events");
            if (list != null) for (int i = 0; i < list.length(); i++) { JSONObject e = list.optJSONObject(i); if (e == null) continue; Post post = new Post(e); if (post.url != null && !post.title.isEmpty() && post.published > 0) all.add(post); }
            all.sort((a, b) -> Long.compare(b.published, a.published)); events = all;
            List<Summary> sums = new ArrayList<>(); JSONArray daily = p.optJSONArray("dailySummaries");
            if (daily != null) for (int i = 0; i < daily.length(); i++) { JSONObject d = daily.optJSONObject(i); if (d == null || !(d.opt("day") instanceof Integer)) continue; int day = d.optInt("day"); if (day < 1 || day > currentDay) continue;
                List<Item> items = new ArrayList<>(); JSONArray raw = d.optJSONArray("items");
                if (raw != null) for (int k = 0; k < raw.length(); k++) { JSONObject it = raw.optJSONObject(k); if (it == null) continue; String url = official(it.opt("sourceUrl")), title = text(it.opt("title")), summary = text(it.opt("summary")); if (url != null && !title.isEmpty() && !summary.isEmpty()) items.add(new Item(title, summary, it.optString("kind"), url)); }
                if (!items.isEmpty()) sums.add(new Summary(day, text(d.opt("preview")), QuotaCalendar.instant(d.opt("updatedAt")), items)); }
            summaries = sums;
            Set<String> digest = new HashSet<>(); JSONArray dailyIds = p.optJSONArray("dailySummaries");
            if (dailyIds != null) for (int i = 0; i < dailyIds.length(); i++) { JSONObject d = dailyIds.optJSONObject(i); JSONArray items = d == null ? null : d.optJSONArray("items"); if (items != null) for (int k = 0; k < items.length(); k++) { JSONObject it = items.optJSONObject(k); if (it != null && it.opt("id") instanceof String) digest.add(it.optString("id")); } }
            List<Post> rel = new ArrayList<>(); for (Post e : all) if ((!"update".equals(e.kind) || digest.contains(e.id)) && e.day >= 1 && e.day <= 28) rel.add(e); relevant = rel;
            Set<Integer> recordedDays = new HashSet<>(); for (Post e : rel) recordedDays.add(e.day); for (Summary d : sums) recordedDays.add(d.day); recorded = recordedDays.size();
            Post last = null; for (Post e : rel) if (e.kind.equals("reset-completed") || e.kind.equals("cards-completed")) { last = e; break; } latest = last;
            JSONObject up = p.optJSONObject("upcoming"); Post next = up == null ? null : new Post(up);
            upcoming = next != null && next.url != null && INTENT.contains(next.kind) ? next : null; upcomingState = up == null ? null : up.optString("state");
            status = p.optString("status"); summaryStatus = p.has("summaryStatus") && !p.isNull("summaryStatus") ? p.optString("summaryStatus") : null; sourceNote = text(p.opt("sourceNote"));
            lastSuccess = QuotaCalendar.instant(p.opt("lastSuccessAt")); summaryLastSuccess = QuotaCalendar.instant(p.opt("summaryLastSuccessAt"));
            interval = p.opt("intervalSeconds") instanceof Integer ? p.optInt("intervalSeconds") : 300;
        }
        Summary summary(int day) { for (Summary s : summaries) if (s.day == day) return s; return null; }
        List<Post> on(int day) { List<Post> out = new ArrayList<>(); for (Post e : relevant) if (e.day == day) out.add(e); out.sort(Comparator.comparingLong(e -> e.published)); return out; }
        /** The day the page opens on: today when it has a record, else the latest recorded day, else today. */
        int defaultDay() {
            Set<Integer> recordedDays = new HashSet<>(); for (Post e : relevant) recordedDays.add(e.day); for (Summary d : summaries) recordedDays.add(d.day);
            return recordedDays.contains(currentDay) ? currentDay : recordedDays.isEmpty() ? currentDay : Collections.max(recordedDays);
        }
        List<Cell> cells() {
            List<Cell> out = new ArrayList<>();
            for (int day = 1; day <= 28; day++) {
                List<Post> items = on(day); Summary auto = day <= currentDay ? summary(day) : null;
                boolean reset = false, cards = false; for (Post e : items) { reset |= e.kind.equals("reset-completed"); cards |= e.kind.equals("cards-completed"); }
                String marker = reset ? "已重置" : cards ? "已发卡" : !items.isEmpty() || auto != null ? "有更新" : day == currentDay ? "今天" : "待公布";
                String brief = "";
                if (day <= currentDay) {
                    if (auto != null) brief = !auto.preview.isEmpty() ? auto.preview : auto.items.get(0).title;
                    else { for (Post e : items) if (e.kind.equals("improvement")) { brief = e.title; break; } if (brief.isEmpty() && !items.isEmpty()) brief = items.get(0).title; }
                }
                out.add(new Cell(day, start.plusDays(day - 1), marker, brief, day == currentDay));
            }
            return out;
        }
        /** 自动同步正常 only while both the post monitor and the daily summaries are current. */
        boolean syncing() { return "connected".equals(status) && (summaryStatus == null || "connected".equals(summaryStatus)); }
        /** A connected result older than three monitor intervals is not current, whatever it says. */
        boolean current(long now) { return "connected".equals(status) && lastSuccess > 0 && now - lastSuccess <= 3L * interval * 1000L + 60_000L; }
    }

    static String kindLabel(String kind) { return KIND.getOrDefault(kind, "官方动态"); }
    /** HH:MM:SS to the deadline, as the web's resetCountdown. */
    static String countdown(long deadline, long now) {
        if (deadline <= 0) return "时间未确认";
        long seconds = (long) Math.ceil((deadline - now) / 1000.0); if (seconds <= 0) return "预告时间已到 · 待确认";
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }
    private static final DateTimeFormatter TIBO_CLOCK = DateTimeFormatter.ofPattern("MM/dd HH:mm:ss zzz", Locale.US);
    static String tiboClock(long now) { return TIBO_CLOCK.format(Instant.ofEpochMilli(now).atZone(PACIFIC)); }
    static String format(long at, ZoneId zone) { return at <= 0 ? "尚未同步" : DateTimeFormatter.ofPattern("MM/dd HH:mm").format(Instant.ofEpochMilli(at).atZone(zone)); }

    private static final Pattern MENTIONS = Pattern.compile("重置|发卡"), DONE = Pattern.compile("已.{0,8}(?:完成|发放|到账|到所有)|重置.{0,8}完成"), CARD = Pattern.compile("卡|发卡");
    /** A summary that says a reset or card grant happened is flagged until an original post confirms it, as on the web. */
    static String verificationNote(Item item, int day, Model m) {
        String words = item.title + " " + item.summary;
        if (!MENTIONS.matcher(words).find() || !DONE.matcher(words).find()) return null;
        String complete = CARD.matcher(words).find() ? "cards-completed" : "reset-completed";
        if (complete.equals(item.kind)) return null;
        for (Post e : m.events) if (e.day == day && e.kind.equals(complete) && !e.conditional) return null;
        return "原帖尚未明确确认完成，暂不按已重置或已发卡处理。";
    }
    static String official(Object url) { return url instanceof String && OFFICIAL.matcher((String) url).matches() ? (String) url : null; }
    private static String text(Object v) { return v instanceof String ? ((String) v).replaceAll("[\\u0000-\\u001f\\u007f-\\u009f]", " ").trim() : ""; }
}
