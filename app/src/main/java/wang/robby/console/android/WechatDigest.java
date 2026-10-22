package wang.robby.console.android;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * wechat-digest v1. One mismatch — unknown field, shape, count, order, or a privacy leak — makes the whole
 * document unavailable. Nothing is guessed, truncated, or filled in. "Today" is the local date of
 * {@code generatedAt} in the document timezone, not the phone clock.
 */
final class WechatDigest {
    private WechatDigest() {}
    static final int MAX_BYTES = 262144;
    static final String UNAVAILABLE = "还没有微信汇总，等本机下一次同步";
    static final String INVALID = "微信汇总格式不对，已忽略";
    private static final long MAX_COUNT = 10_000_000_000L;
    private static final Pattern UTC = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z");
    static final String[] TYPE_KEYS = {"text", "image", "voice", "video", "link", "file", "other"};

    static final class Window {
        final long messages, sent, received, conversations;
        Window(long messages, long sent, long received, long conversations) {
            this.messages = messages; this.sent = sent; this.received = received; this.conversations = conversations;
        }
    }
    static final class Day {
        final String date; final long messages, sent, received;
        Day(String date, long messages, long sent, long received) {
            this.date = date; this.messages = messages; this.sent = sent; this.received = received;
        }
    }
    static final class Conversation {
        final String name, kind; final long messages7d, sent7d; final Instant lastAt;
        Conversation(String name, String kind, long messages7d, long sent7d, Instant lastAt) {
            this.name = name; this.kind = kind; this.messages7d = messages7d; this.sent7d = sent7d; this.lastAt = lastAt;
        }
    }
    static final class Domain {
        final String domain; final long count;
        Domain(String domain, long count) { this.domain = domain; this.count = count; }
    }
    /** A document that already passed every check. Lists are unmodifiable. */
    static final class Doc {
        final Instant generatedAt, archiveTime; final String timezone; final ZoneId zone;
        final long sourceConversations, sourceMessages;
        final Window today, last7d, last30d; final List<Day> daily; final long[] hourly, types;
        final List<Conversation> conversations; final long linkCount7d; final List<Domain> domains;
        final String reportTitle; final List<String> reportItems;
        Doc(Instant generatedAt, Instant archiveTime, String timezone, ZoneId zone, long sourceConversations, long sourceMessages,
            Window today, Window last7d, Window last30d, List<Day> daily, long[] hourly, long[] types,
            List<Conversation> conversations, long linkCount7d, List<Domain> domains, String reportTitle, List<String> reportItems) {
            this.generatedAt = generatedAt; this.archiveTime = archiveTime; this.timezone = timezone; this.zone = zone;
            this.sourceConversations = sourceConversations; this.sourceMessages = sourceMessages;
            this.today = today; this.last7d = last7d; this.last30d = last30d; this.daily = daily; this.hourly = hourly; this.types = types;
            this.conversations = conversations; this.linkCount7d = linkCount7d; this.domains = domains;
            this.reportTitle = reportTitle; this.reportItems = reportItems;
        }
    }

    /** The digest document itself, as a string. Oversized text is rejected before it is parsed. */
    static Doc parse(String raw) {
        try {
            if (raw == null || tooBig(raw)) return null;
            return document(new JSONObject(raw), false);
        } catch (RuntimeException | JSONException e) { return null; }
    }
    /** A digest object already in hand. Its compact serialization must also fit the byte cap. */
    static Doc parseDocument(JSONObject o) {
        try { return document(o, true); } catch (RuntimeException e) { return null; }
    }
    /** The mobile envelope: schemaVersion 1, checkedAt, and the digest. */
    static Doc parseResponse(JSONObject envelope) {
        try {
            if (!exact(envelope, "schemaVersion", "checkedAt", "digest")) return null;
            if (!version1(envelope.opt("schemaVersion")) || instant(envelope.opt("checkedAt")) == null) return null;
            Object digest = envelope.opt("digest");
            if (!(digest instanceof JSONObject)) return null;
            return document((JSONObject) digest, true);
        } catch (RuntimeException e) { return null; }
    }
    /** Copy for 503 only. Anything else keeps the caller's own message. */
    static String failure(int status, String code) {
        if (status != 503) return null;
        if ("wechat_digest_unavailable".equals(code)) return UNAVAILABLE;
        if ("wechat_digest_invalid".equals(code)) return INVALID;
        return null;
    }

    private static Doc document(JSONObject o, boolean checkSize) {
        if (o == null) return null;
        if (checkSize && tooBig(o.toString())) return null;
        if (!exact(o, "schemaVersion", "generatedAt", "timezone", "source", "summary", "daily", "hourly", "types", "topConversations", "links", "report")) return null;
        if (!version1(o.opt("schemaVersion"))) return null;
        Instant generatedAt = instant(o.opt("generatedAt"));
        String timezone = asString(o.opt("timezone"));
        ZoneId zone = zone(timezone);
        if (generatedAt == null || zone == null) return null;
        JSONObject source = o.optJSONObject("source");
        if (!exact(source, "archiveTime", "conversations", "messages")) return null;
        Instant archiveTime = instant(source.opt("archiveTime"));
        Long sourceConversations = count(source.opt("conversations")), sourceMessages = count(source.opt("messages"));
        if (archiveTime == null || sourceConversations == null || sourceMessages == null || archiveTime.isAfter(generatedAt)) return null;
        JSONObject summary = o.optJSONObject("summary");
        if (!exact(summary, "today", "last7d", "last30d")) return null;
        Window today = window(summary.optJSONObject("today")), last7d = window(summary.optJSONObject("last7d")), last30d = window(summary.optJSONObject("last30d"));
        if (today == null || last7d == null || last30d == null || !nested(today, last7d) || !nested(last7d, last30d)) return null;
        if (sourceMessages < last30d.messages || sourceConversations < last30d.conversations) return null;
        LocalDate day = generatedAt.atZone(zone).toLocalDate();
        List<Day> daily = daily(o.optJSONArray("daily"), day, today, last7d, last30d);
        long[] hourly = hourly(o.optJSONArray("hourly"), last30d.messages);
        long[] types = types(o.optJSONObject("types"), last30d.messages);
        List<Conversation> conversations = conversations(o.optJSONArray("topConversations"), generatedAt, zone, day, last7d);
        JSONObject links = o.optJSONObject("links");
        if (daily == null || hourly == null || types == null || conversations == null || !exact(links, "count7d", "topDomains")) return null;
        Long linkCount = count(links.opt("count7d"));
        List<Domain> domains = domains(links.optJSONArray("topDomains"), linkCount);
        JSONObject report = o.optJSONObject("report");
        if (linkCount == null || domains == null || !exact(report, "title", "items")) return null;
        String title = asString(report.opt("title"));
        List<String> items = items(report.optJSONArray("items"));
        if (title == null || !words(title, 1, 40) || items == null) return null;
        return new Doc(generatedAt, archiveTime, timezone, zone, sourceConversations, sourceMessages, today, last7d, last30d, daily, hourly, types, conversations, linkCount, domains, title, items);
    }

    private static Window window(JSONObject o) {
        if (!exact(o, "messages", "sent", "received", "conversations")) return null;
        Long messages = count(o.opt("messages")), sent = count(o.opt("sent")), received = count(o.opt("received")), conversations = count(o.opt("conversations"));
        if (messages == null || sent == null || received == null || conversations == null || sent + received != messages) return null;
        if (messages == 0 ? conversations != 0 : conversations < 1) return null;
        return new Window(messages, sent, received, conversations);
    }
    private static boolean nested(Window inner, Window outer) {
        return inner.messages <= outer.messages && inner.sent <= outer.sent && inner.received <= outer.received && inner.conversations <= outer.conversations;
    }
    private static List<Day> daily(JSONArray a, LocalDate today, Window todayW, Window last7, Window last30) {
        if (a == null || a.length() != 30) return null;
        List<Day> out = new ArrayList<>();
        long m30 = 0, s30 = 0, r30 = 0, m7 = 0, s7 = 0, r7 = 0;
        LocalDate expect = today.minusDays(29);
        for (int i = 0; i < 30; i++) {
            JSONObject row = a.optJSONObject(i);
            if (!exact(row, "date", "messages", "sent", "received")) return null;
            String date = asString(row.opt("date"));
            Long messages = count(row.opt("messages")), sent = count(row.opt("sent")), received = count(row.opt("received"));
            if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}") || messages == null || sent == null || received == null || sent + received != messages) return null;
            LocalDate parsed; try { parsed = LocalDate.parse(date); } catch (RuntimeException e) { return null; }
            if (!parsed.equals(expect)) return null;
            expect = expect.plusDays(1);
            out.add(new Day(date, messages, sent, received));
            m30 += messages; s30 += sent; r30 += received;
            if (i >= 23) { m7 += messages; s7 += sent; r7 += received; }
        }
        Day last = out.get(29);
        if (m30 != last30.messages || s30 != last30.sent || r30 != last30.received) return null;
        if (m7 != last7.messages || s7 != last7.sent || r7 != last7.received) return null;
        if (last.messages != todayW.messages || last.sent != todayW.sent || last.received != todayW.received) return null;
        return List.copyOf(out);
    }
    private static long[] hourly(JSONArray a, long messages) {
        if (a == null || a.length() != 24) return null;
        long[] out = new long[24]; long sum = 0;
        for (int i = 0; i < 24; i++) { Long n = count(a.opt(i)); if (n == null) return null; out[i] = n; sum += n; }
        return sum == messages ? out : null;
    }
    private static long[] types(JSONObject o, long messages) {
        if (!exact(o, TYPE_KEYS)) return null;
        long[] out = new long[TYPE_KEYS.length]; long sum = 0;
        for (int i = 0; i < TYPE_KEYS.length; i++) { Long n = count(o.opt(TYPE_KEYS[i])); if (n == null) return null; out[i] = n; sum += n; }
        return sum == messages ? out : null;
    }
    private static List<Conversation> conversations(JSONArray a, Instant generatedAt, ZoneId zone, LocalDate today, Window last7) {
        if (a == null || a.length() > 20) return null;
        List<Conversation> out = new ArrayList<>();
        long prev = Long.MAX_VALUE, sumM = 0, sumS = 0; LocalDate earliest = today.minusDays(6);
        for (int i = 0; i < a.length(); i++) {
            JSONObject row = a.optJSONObject(i);
            if (!exact(row, "name", "kind", "messages7d", "sent7d", "lastAt")) return null;
            String name = asString(row.opt("name")), kind = asString(row.opt("kind"));
            Long messages = count(row.opt("messages7d")), sent = count(row.opt("sent7d"));
            Instant lastAt = instant(row.opt("lastAt"));
            if (name == null || !nameOk(name) || kind == null || messages == null || sent == null || lastAt == null) return null;
            if (!kind.equals("private") && !kind.equals("group")) return null;
            if (messages < 1 || sent > messages || messages > prev || lastAt.isAfter(generatedAt)) return null;
            if (lastAt.atZone(zone).toLocalDate().isBefore(earliest)) return null;
            prev = messages; sumM += messages; sumS += sent;
            out.add(new Conversation(name, kind, messages, sent, lastAt));
        }
        if (sumM > last7.messages || sumS > last7.sent) return null;
        return List.copyOf(out);
    }
    private static List<Domain> domains(JSONArray a, Long count7d) {
        if (a == null || count7d == null || a.length() > 10) return null;
        List<Domain> out = new ArrayList<>(); long prev = Long.MAX_VALUE, sum = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject row = a.optJSONObject(i);
            if (!exact(row, "domain", "count")) return null;
            String domain = asString(row.opt("domain")); Long n = count(row.opt("count"));
            if (domain == null || n == null || !domainOk(domain) || n > prev || n > count7d) return null;
            prev = n; sum += n; out.add(new Domain(domain, n));
        }
        return sum > count7d ? null : List.copyOf(out);
    }
    private static List<String> items(JSONArray a) {
        if (a == null || a.length() < 1 || a.length() > 8) return null;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            String s = asString(a.opt(i));
            if (s == null || !words(s, 1, 80)) return null;
            out.add(s);
        }
        return List.copyOf(out);
    }

    private static boolean exact(JSONObject o, String... keys) {
        if (o == null || o.length() != keys.length) return false;
        for (String key : keys) if (!o.has(key) || o.isNull(key)) return false;
        return true;
    }
    private static boolean version1(Object v) {
        if (v instanceof Integer || v instanceof Long) return ((Number) v).longValue() == 1L;
        return false;
    }
    /** Integers only, 0 through 10^10. Doubles, strings and bigger integers are rejected. */
    private static Long count(Object v) {
        if (!(v instanceof Integer) && !(v instanceof Long)) return null;
        long n = ((Number) v).longValue();
        return n < 0 || n > MAX_COUNT ? null : n;
    }
    private static String asString(Object v) { return v instanceof String s ? s : null; }
    private static Instant instant(Object v) {
        String s = asString(v);
        if (s == null || !UTC.matcher(s).matches()) return null;
        try { return Instant.parse(s); } catch (RuntimeException e) { return null; }
    }
    private static ZoneId zone(String s) {
        if (s == null || s.length() < 1 || s.length() > 64 || !s.matches("[A-Za-z0-9_+-]+(/[A-Za-z0-9_+-]+){0,2}")) return null;
        try { return ZoneId.of(s); } catch (RuntimeException e) { return null; }
    }
    private static boolean nameOk(String s) {
        return !s.isEmpty() && s.equals(s.trim()) && s.codePointCount(0, s.length()) <= 32 && !leaked(s);
    }
    private static boolean words(String s, int min, int max) {
        int n = s.codePointCount(0, s.length());
        return n >= min && n <= max && !leaked(s);
    }
    private static boolean domainOk(String s) {
        return s.matches("[a-z0-9.-]{1,253}") && !s.startsWith("www.") && !leaked(s);
    }
    /** Display text must not carry an id, a phone, an email, a URL, or a control character. */
    private static boolean leaked(String s) {
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            if (Character.getType(cp) == Character.CONTROL || cp == 0x2028 || cp == 0x2029) return true;
            i += Character.charCount(cp);
        }
        if (s.toLowerCase(java.util.Locale.ROOT).contains("wxid_")) return true;
        if (s.contains("platform_id") || s.contains("owner_id") || s.contains("://")) return true;
        if (s.matches("(?s).*[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}.*")) return true;
        return s.matches("(?s).*(^|[^0-9])1[3-9]\\d{9}([^0-9]|$).*");
    }
    /** UTF-8 size. A string longer than the cap in Java chars is already too big. */
    private static boolean tooBig(String s) {
        if (s.length() > MAX_BYTES) return true;
        int bytes = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            bytes += cp <= 0x7F ? 1 : cp <= 0x7FF ? 2 : cp <= 0xFFFF ? 3 : 4;
            if (bytes > MAX_BYTES) return true;
        }
        return false;
    }

    /**
     * One complete, internally consistent digest with made-up names. Shared by unit tests and the debug preview
     * so the two cannot drift. Days are local dates of {@code now} in Asia/Shanghai.
     */
    static JSONObject example(Instant now) throws JSONException {
        Instant stamp = now.truncatedTo(ChronoUnit.SECONDS);
        ZoneId sh = ZoneId.of("Asia/Shanghai");
        LocalDate today = stamp.atZone(sh).toLocalDate();
        JSONArray daily = new JSONArray();
        long m30 = 0, s30 = 0, r30 = 0, m7 = 0, s7 = 0, r7 = 0, todayM = 0, todayS = 0, todayR = 0;
        for (int i = 0; i < 30; i++) {
            long messages = 100L + 3 * i, sent = 10L + i, received = 90L + 2 * i;
            m30 += messages; s30 += sent; r30 += received;
            if (i >= 23) { m7 += messages; s7 += sent; r7 += received; }
            if (i == 29) { todayM = messages; todayS = sent; todayR = received; }
            daily.put(new JSONObject().put("date", today.minusDays(29L - i).toString()).put("messages", messages).put("sent", sent).put("received", received));
        }
        JSONArray hourly = new JSONArray();
        for (int h = 0; h < 24; h++) hourly.put(h <= 8 ? 180L : 179L);
        long topMessages = 420;
        return new JSONObject()
            .put("schemaVersion", 1)
            .put("generatedAt", stamp.toString())
            .put("timezone", "Asia/Shanghai")
            .put("source", new JSONObject().put("archiveTime", stamp.minusSeconds(5400).toString()).put("conversations", 200).put("messages", 50000))
            .put("summary", new JSONObject()
                .put("today", new JSONObject().put("messages", todayM).put("sent", todayS).put("received", todayR).put("conversations", 12))
                .put("last7d", new JSONObject().put("messages", m7).put("sent", s7).put("received", r7).put("conversations", 40))
                .put("last30d", new JSONObject().put("messages", m30).put("sent", s30).put("received", r30).put("conversations", 90)))
            .put("daily", daily)
            .put("hourly", hourly)
            .put("types", new JSONObject().put("text", 3000).put("image", 500).put("voice", 200).put("video", 100).put("link", 200).put("file", 50).put("other", 255))
            .put("topConversations", new JSONArray()
                .put(conversation("周末闲聊", "group", topMessages, 30, stamp.minusSeconds(120)))
                .put(conversation("产品讨论组", "group", 280, 18, stamp.minusSeconds(3600)))
                .put(conversation("林晓", "private", 96, 40, stamp.minusSeconds(7200))))
            .put("links", new JSONObject().put("count7d", 27).put("topDomains", new JSONArray()
                .put(new JSONObject().put("domain", "github.com").put("count", 12))
                .put(new JSONObject().put("domain", "example.com").put("count", 8))
                .put(new JSONObject().put("domain", "docs.example.com").put("count", 4))))
            .put("report", new JSONObject()
                .put("title", "微信日报 · " + today.getMonthValue() + "月" + today.getDayOfMonth() + "日")
                .put("items", new JSONArray()
                    .put("今天 " + todayM + " 条消息，发出 " + todayS + "、收到 " + todayR + "。")
                    .put("近 7 天 " + m7 + " 条，日均 " + WechatDigestText.dailyAverage(m7) + "。")
                    .put("最活跃的是周末闲聊，近 7 天 " + topMessages + " 条。")));
    }
    private static JSONObject conversation(String name, String kind, long messages, long sent, Instant lastAt) throws JSONException {
        return new JSONObject().put("name", name).put("kind", kind).put("messages7d", messages).put("sent7d", sent).put("lastAt", lastAt.toString());
    }
    /** Envelope around {@link #example}. {@code checkedAt} is {@code now} truncated to a second. */
    static JSONObject exampleResponse(Instant now) throws JSONException {
        Instant stamp = now.truncatedTo(ChronoUnit.SECONDS);
        return new JSONObject().put("schemaVersion", 1).put("checkedAt", stamp.toString()).put("digest", example(stamp));
    }
}
