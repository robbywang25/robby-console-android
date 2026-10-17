package wang.robby.console.android;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

/**
 * The 重置日历 of tokens.robby.wang (quota-calendar.mjs): every shown account's reset instants by local day. Upcoming
 * resets come from the current windows, elapsed ones from resetHistory. Reserve windows stay out, as on the web.
 */
final class QuotaCalendar {
    private QuotaCalendar() {}
    static final long FRESH_MS = 120_000L;
    static final String SCHEDULED = "scheduled", STALE = "stale", EXPIRED = "expired";
    private static final Map<String, String> GROUPS = Map.of("default", "默认额度", "reserve", "Reserve", "code-review", "代码审查", "additional", "附加额度");

    static final class Event {
        final String account, platform, label, product, status; final long at; final LocalDate day;
        Event(String account, String platform, String label, String product, String status, long at, LocalDate day) {
            this.account = account; this.platform = platform; this.label = label; this.product = product; this.status = status; this.at = at; this.day = day;
        }
        /** The window label without the 默认额度 prefix, as the web's inline cell text. */
        String shortLabel() { String s = label.replaceFirst("^默认额度[ ·]*", ""); return s.isEmpty() ? label : s; }
    }
    static final class Model {
        final List<Event> events; final LocalDate today; final ZoneId zone; final int accounts, missingAccounts, missingWindows;
        Model(List<Event> events, LocalDate today, ZoneId zone, int accounts, int missingAccounts, int missingWindows) {
            this.events = events; this.today = today; this.zone = zone; this.accounts = accounts; this.missingAccounts = missingAccounts; this.missingWindows = missingWindows;
        }
        List<Event> on(LocalDate day) { List<Event> out = new ArrayList<>(); for (Event e : events) if (e.day.equals(day)) out.add(e); return out; }
        List<Event> in(YearMonth month) { List<Event> out = new ArrayList<>(); for (Event e : events) if (YearMonth.from(e.day).equals(month)) out.add(e); return out; }
    }

    static Model build(List<JSONObject> accounts, ZoneId zone, long now) {
        List<Event> events = new ArrayList<>(); int shown = 0, missingAccounts = 0, missingWindows = 0;
        Set<String> seenAccounts = new HashSet<>();
        for (JSONObject a : accounts) {
            if (!TokensModel.shownOnPage(a) || !seenAccounts.add(a.optString("id") + "\u0000" + a.optString("product"))) continue;
            shown++;
            JSONArray windows = a.optJSONArray("windows"), history = a.optJSONArray("resetHistory");
            if (windows == null || windows.length() == 0) missingAccounts++;
            boolean current = "connected".equals(a.optString("status")) && fresh(a.opt("lastSuccessAt"), now);
            List<JSONObject> all = new ArrayList<>();
            if (windows != null) for (int i = 0; i < Math.min(32, windows.length()); i++) all.add(windows.optJSONObject(i));
            if (history != null) for (int i = 0; i < Math.min(10000, history.length()); i++) { JSONObject h = history.optJSONObject(i); long at = instant(h == null ? null : h.opt("resetsAt")); if (at > 0 && at <= now) all.add(h); }
            Set<String> signatures = new HashSet<>();
            for (JSONObject w : all) {
                if (w == null) { missingWindows++; continue; }
                String group = w.opt("quotaGroup") instanceof String ? (String) w.opt("quotaGroup") : "";
                if ("reserve".equals(group)) continue;
                long at = instant(w.opt("resetsAt"));
                String label = clean(w.opt("label")), model = clean(w.opt("model")), id = clean(w.opt("id"));
                if (!signatures.add((id.isEmpty() ? label : id) + "\u0000" + group + "\u0000" + model + "\u0000" + at)) continue;
                if (at <= 0) { missingWindows++; continue; }
                String groupName = GROUPS.getOrDefault(group, "");
                String full = groupName.isEmpty() || groupName.equals(label) ? label : label.isEmpty() ? groupName : groupName + " " + label;
                events.add(new Event(TokensModel.displayName(a), TokensModel.productName(a), full.isEmpty() ? "额度" : full, a.optString("product"),
                    at <= now ? EXPIRED : current ? SCHEDULED : STALE, at, Instant.ofEpochMilli(at).atZone(zone).toLocalDate()));
            }
        }
        events.sort(Comparator.<Event>comparingLong(e -> e.at).thenComparing(e -> e.account).thenComparing(e -> e.label));
        return new Model(events, Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), zone, shown, missingAccounts, missingWindows);
    }

    /** 本月 N 项重置，M 项待核实, as the web's month summary. */
    static String monthSummary(Model m, YearMonth month) {
        List<Event> in = m.in(month); int pending = 0; for (Event e : in) if (STALE.equals(e.status)) pending++;
        return in.isEmpty() ? "本月暂无已知重置日期" : "本月 " + in.size() + " 项重置" + (pending > 0 ? "，" + pending + " 项待核实" : "");
    }
    static String missing(Model m) {
        List<String> parts = new ArrayList<>();
        if (m.missingAccounts > 0) parts.add(m.missingAccounts + " 个账号"); if (m.missingWindows > 0) parts.add(m.missingWindows + " 项额度");
        return parts.isEmpty() ? null : String.join("、", parts) + "未提供日期";
    }
    /** Six Monday-first weeks covering the month, as the web grid. */
    static List<LocalDate> grid(YearMonth month) {
        LocalDate first = month.atDay(1), start = first.minusDays(first.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue());
        List<LocalDate> out = new ArrayList<>(42); for (int i = 0; i < 42; i++) out.add(start.plusDays(i)); return out;
    }
    static String statusLabel(Event e) { return EXPIRED.equals(e.status) ? "已过期" : STALE.equals(e.status) ? "待核实" : null; }

    private static boolean fresh(Object value, long now) { long at = instant(value); return at > 0 && at <= now && now - at <= FRESH_MS; }
    /** An unambiguous instant with a zone or offset; a date-only or zone-free value is no reset time. */
    static long instant(Object value) {
        if (!(value instanceof String) || !((String) value).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:\\d{2})")) return 0;
        try { return java.time.OffsetDateTime.parse((String) value).toInstant().toEpochMilli(); } catch (RuntimeException e) { return 0; }
    }
    private static String clean(Object v) { return v instanceof String ? ((String) v).replaceAll("[\\u0000-\\u001f\\u007f-\\u009f]", " ").trim() : ""; }
}
