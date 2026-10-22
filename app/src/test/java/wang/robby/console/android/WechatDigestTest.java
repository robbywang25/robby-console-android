package wang.robby.console.android;

import static org.junit.Assert.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** The digest is all or nothing: a legal sample keeps every field, and each listed defect drops the whole document. */
public final class WechatDigestTest {
    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");

    private static JSONObject sample() throws Exception { return new JSONObject(WechatDigest.example(NOW).toString()); }
    private static void reject(String why, JSONObject o) { assertNull(why, WechatDigest.parse(o.toString())); }
    private static String chars(char unit, int n) { StringBuilder b = new StringBuilder(); for (int i = 0; i < n; i++) b.append(unit); return b.toString(); }

    @Test public void parsesEveryFieldOfALegalSample() throws Exception {
        WechatDigest.Doc d = WechatDigest.parse(WechatDigest.example(NOW).toString());
        WechatDigest.Doc again = WechatDigest.parseResponse(WechatDigest.exampleResponse(NOW));
        assertNotNull(d); assertNotNull(again);
        for (WechatDigest.Doc doc : new WechatDigest.Doc[]{d, again}) {
            assertEquals(NOW, doc.generatedAt);
            assertEquals(NOW.minusSeconds(5400), doc.archiveTime);
            assertEquals("Asia/Shanghai", doc.timezone);
            assertEquals(200, doc.sourceConversations); assertEquals(50000, doc.sourceMessages);
            assertEquals(187, doc.today.messages); assertEquals(39, doc.today.sent); assertEquals(148, doc.today.received); assertEquals(12, doc.today.conversations);
            assertEquals(1246, doc.last7d.messages); assertEquals(252, doc.last7d.sent); assertEquals(994, doc.last7d.received); assertEquals(40, doc.last7d.conversations);
            assertEquals(4305, doc.last30d.messages); assertEquals(735, doc.last30d.sent); assertEquals(3570, doc.last30d.received); assertEquals(90, doc.last30d.conversations);
            assertEquals(30, doc.daily.size());
            assertEquals("2026-09-23", doc.daily.get(0).date); assertEquals(100, doc.daily.get(0).messages); assertEquals(10, doc.daily.get(0).sent); assertEquals(90, doc.daily.get(0).received);
            assertEquals("2026-10-22", doc.daily.get(29).date); assertEquals(187, doc.daily.get(29).messages);
            assertEquals(24, doc.hourly.length); assertEquals(180, doc.hourly[0]); assertEquals(180, doc.hourly[8]); assertEquals(179, doc.hourly[9]); assertEquals(179, doc.hourly[23]);
            assertArrayEquals(new long[]{3000, 500, 200, 100, 200, 50, 255}, doc.types);
            assertEquals(3, doc.conversations.size());
            assertEquals("周末闲聊", doc.conversations.get(0).name); assertEquals("group", doc.conversations.get(0).kind);
            assertEquals(420, doc.conversations.get(0).messages7d); assertEquals(30, doc.conversations.get(0).sent7d);
            assertEquals(NOW.minusSeconds(120), doc.conversations.get(0).lastAt);
            assertEquals("产品讨论组", doc.conversations.get(1).name); assertEquals(280, doc.conversations.get(1).messages7d);
            assertEquals("林晓", doc.conversations.get(2).name); assertEquals("private", doc.conversations.get(2).kind); assertEquals(96, doc.conversations.get(2).messages7d); assertEquals(40, doc.conversations.get(2).sent7d);
            assertEquals(27, doc.linkCount7d); assertEquals(3, doc.domains.size());
            assertEquals("github.com", doc.domains.get(0).domain); assertEquals(12, doc.domains.get(0).count);
            assertEquals("example.com", doc.domains.get(1).domain); assertEquals(8, doc.domains.get(1).count);
            assertEquals("docs.example.com", doc.domains.get(2).domain); assertEquals(4, doc.domains.get(2).count);
            assertEquals("微信日报 · 10月22日", doc.reportTitle);
            assertEquals(java.util.List.of("今天 187 条消息，发出 39、收到 148。", "近 7 天 1246 条，日均 178。", "最活跃的是周末闲聊，近 7 天 420 条。"), doc.reportItems);
        }
        JSONObject renamed = sample();
        renamed.getJSONArray("topConversations").getJSONObject(0).put("name", "改过的名字");
        WechatDigest.Doc renamedDoc = WechatDigest.parse(renamed.toString());
        assertNotNull(renamedDoc); assertEquals("改过的名字", renamedDoc.conversations.get(0).name);
        assertNull(WechatDigest.parse(null)); assertNull(WechatDigest.parse("")); assertNull(WechatDigest.parse("{"));
        assertNull(WechatDigest.parseResponse(null)); assertNull(WechatDigest.parseDocument(null));
    }

    @Test public void unknownFieldsAreUnavailable() throws Exception {
        JSONObject top = sample(); top.put("extra", 1); reject("top", top);
        JSONObject nested = sample(); nested.getJSONObject("summary").put("year", 1); reject("summary", nested);
        JSONObject day = sample(); day.getJSONArray("daily").getJSONObject(0).put("note", "x"); reject("day", day);
        JSONObject type = sample(); type.getJSONObject("types").put("sticker", 0); reject("type", type);
        JSONObject missing = sample(); missing.getJSONObject("types").remove("other"); reject("missing type", missing);
    }

    @Test public void shapeAndCountMismatchesAreUnavailable() throws Exception {
        JSONObject shortDaily = sample(); shortDaily.getJSONArray("daily").remove(0); reject("daily length", shortDaily);
        JSONObject shortHour = sample(); shortHour.getJSONArray("hourly").remove(0); reject("hourly length", shortHour);
        JSONObject today = sample(); today.getJSONObject("summary").getJSONObject("today").put("sent", 40); reject("today sum", today);
        JSONObject row = sample(); row.getJSONArray("daily").getJSONObject(0).put("received", 91); reject("day sum", row);
        JSONObject negative = sample(); negative.getJSONArray("hourly").put(0, -1); reject("negative hour", negative);
        JSONObject fraction = sample(); fraction.getJSONArray("hourly").put(0, 1.5); reject("double hour", fraction);
        JSONObject huge = sample(); huge.getJSONObject("source").put("messages", 10_000_000_001L); reject("above 10^10", huge);
        JSONObject kind = sample(); kind.getJSONArray("topConversations").getJSONObject(0).put("kind", "channel"); reject("kind", kind);
        JSONObject order = sample(); order.getJSONArray("topConversations").getJSONObject(0).put("messages7d", 100); reject("unsorted", order);
        JSONObject gap = sample(); gap.getJSONArray("daily").getJSONObject(1).put("date", "2026-09-25"); reject("date gap", gap);
        JSONObject nextDay = sample(); nextDay.put("generatedAt", "2026-10-23T12:00:00Z"); reject("daily does not end today", nextDay);
        JSONObject version = sample(); version.put("schemaVersion", 2); reject("schema 2", version);
        JSONObject decimal = sample(); decimal.put("schemaVersion", Double.valueOf(1.0)); assertNull("schema double object", WechatDigest.parseDocument(decimal));
        assertNull("schema double text", WechatDigest.parse(sample().toString().replaceFirst("\"schemaVersion\":1,", "\"schemaVersion\":1.0,")));
        JSONObject text = sample(); text.put("schemaVersion", "1"); reject("schema string", text);
        JSONObject hourSum = sample(); hourSum.getJSONArray("hourly").put(0, 0); reject("hourly sum", hourSum);
        JSONObject typeSum = sample(); typeSum.getJSONObject("types").put("text", 3001); reject("type sum", typeSum);
        JSONObject people = sample(); people.getJSONObject("summary").getJSONObject("today").put("conversations", 0); reject("messages need a conversation", people);
        JSONObject sent = sample(); sent.getJSONArray("topConversations").getJSONObject(2).put("sent7d", 97); reject("sent above messages", sent);
        JSONObject offset = sample(); offset.getJSONArray("topConversations").getJSONObject(0).put("lastAt", "2026-10-22T11:58:00+08:00"); reject("offset", offset);
        JSONObject future = sample(); future.getJSONArray("topConversations").getJSONObject(0).put("lastAt", NOW.plusSeconds(60).toString()); reject("lastAt after generated", future);
        JSONObject old = sample(); old.getJSONArray("topConversations").getJSONObject(0).put("lastAt", "2026-10-14T16:00:00Z"); reject("lastAt before the 7 day window", old);
        JSONObject archive = sample(); archive.getJSONObject("source").put("archiveTime", NOW.plusSeconds(1).toString()); reject("archive after generated", archive);
        JSONObject domainSum = sample(); domainSum.getJSONObject("links").getJSONArray("topDomains").getJSONObject(1).put("count", 100); reject("domain above count7d", domainSum);
    }

    @Test public void namesKindsDomainsAndReportBoundsAreUnavailable() throws Exception {
        JSONObject name = sample(); name.getJSONArray("topConversations").getJSONObject(0).put("name", chars('名', 33)); reject("33 字", name);
        JSONObject blank = sample(); blank.getJSONArray("topConversations").getJSONObject(0).put("name", " 林晓"); reject("padded name", blank);
        JSONObject emptyName = sample(); emptyName.getJSONArray("topConversations").getJSONObject(0).put("name", ""); reject("empty name", emptyName);
        JSONObject upper = sample(); upper.getJSONObject("links").getJSONArray("topDomains").getJSONObject(0).put("domain", "GitHub.com"); reject("uppercase domain", upper);
        JSONObject www = sample(); www.getJSONObject("links").getJSONArray("topDomains").getJSONObject(0).put("domain", "www.github.com"); reject("www", www);
        JSONObject space = sample(); space.getJSONObject("links").getJSONArray("topDomains").getJSONObject(0).put("domain", "bad domain"); reject("bad domain", space);
        JSONObject longDomain = sample(); longDomain.getJSONObject("links").getJSONArray("topDomains").getJSONObject(0).put("domain", chars('a', 254)); reject("254 domain", longDomain);
        JSONObject many = sample(); JSONArray extra = many.getJSONObject("report").getJSONArray("items"); while (extra.length() < 9) extra.put("补充一条。"); reject("9 items", many);
        JSONObject longItem = sample(); longItem.getJSONObject("report").getJSONArray("items").put(0, chars('字', 81)); reject("81 字", longItem);
        JSONObject noItems = sample(); noItems.getJSONObject("report").put("items", new JSONArray()); reject("empty items", noItems);
        JSONObject longTitle = sample(); longTitle.getJSONObject("report").put("title", chars('题', 41)); reject("41 字 title", longTitle);
        JSONObject emptyTitle = sample(); emptyTitle.getJSONObject("report").put("title", ""); reject("empty title", emptyTitle);
        JSONObject twentyOne = sample(); twentyOne.put("topConversations", conversations(21)); reject("21 conversations", twentyOne);
        JSONObject eleven = sample();
        JSONArray domains = new JSONArray(); for (int i = 0; i < 11; i++) domains.put(new JSONObject().put("domain", "d" + i + ".example.com").put("count", 11 - i));
        eleven.getJSONObject("links").put("count7d", 100).put("topDomains", domains); reject("11 domains", eleven);
    }

    @Test public void boundsThatMatchTheSpecAreKept() throws Exception {
        JSONObject name = sample(); String thirtyTwo = chars('名', 32);
        name.getJSONArray("topConversations").getJSONObject(0).put("name", thirtyTwo);
        assertEquals(thirtyTwo, WechatDigest.parse(name.toString()).conversations.get(0).name);
        JSONObject title = sample(); String forty = chars('题', 40);
        title.getJSONObject("report").put("title", forty);
        assertEquals(forty, WechatDigest.parse(title.toString()).reportTitle);
        JSONObject item = sample(); String eighty = chars('字', 80);
        item.getJSONObject("report").getJSONArray("items").put(0, eighty);
        assertEquals(eighty, WechatDigest.parse(item.toString()).reportItems.get(0));
        JSONObject eight = sample(); JSONArray eightItems = eight.getJSONObject("report").getJSONArray("items"); while (eightItems.length() < 8) eightItems.put("补充一条。");
        assertEquals(8, WechatDigest.parse(eight.toString()).reportItems.size());
        JSONObject cap = sample(); cap.getJSONObject("source").put("messages", 10_000_000_000L);
        assertEquals(10_000_000_000L, WechatDigest.parse(cap.toString()).sourceMessages);
        JSONObject tie = sample(); tie.getJSONArray("topConversations").getJSONObject(1).put("messages7d", 420);
        assertNotNull("equal messages7d may tie", WechatDigest.parse(tie.toString()));
        JSONObject twenty = sample(); twenty.put("topConversations", conversations(20));
        assertEquals(20, WechatDigest.parse(twenty.toString()).conversations.size());
        JSONObject ten = sample();
        JSONArray domains = new JSONArray(); for (int i = 0; i < 10; i++) domains.put(new JSONObject().put("domain", "d" + i + ".example.com").put("count", 10 - i));
        ten.getJSONObject("links").put("count7d", 100).put("topDomains", domains);
        assertEquals(10, WechatDigest.parse(ten.toString()).domains.size());
        JSONObject zeroDomain = sample(); zeroDomain.getJSONObject("links").getJSONArray("topDomains").getJSONObject(2).put("count", 0);
        assertEquals(0, WechatDigest.parse(zeroDomain.toString()).domains.get(2).count);
        JSONObject edge = sample(); edge.getJSONArray("topConversations").getJSONObject(2).put("lastAt", "2026-10-15T16:00:00Z");
        assertNotNull("the first day of the 7 day window is inside it", WechatDigest.parse(edge.toString()));
        JSONObject none = sample(); none.put("topConversations", new JSONArray());
        assertTrue(WechatDigest.parse(none.toString()).conversations.isEmpty());
    }

    @Test public void privacyLeaksAreUnavailable() throws Exception {
        JSONObject wxid = sample(); wxid.getJSONArray("topConversations").getJSONObject(0).put("name", "Wxid_abc"); reject("wxid", wxid);
        JSONObject platform = sample(); platform.getJSONArray("topConversations").getJSONObject(0).put("name", "platform_id"); reject("platform_id", platform);
        JSONObject owner = sample(); owner.getJSONObject("report").getJSONArray("items").put(0, "含有 owner_id。"); reject("owner_id", owner);
        JSONObject mail = sample(); mail.getJSONObject("report").put("title", "写给 a@b.cn"); reject("email", mail);
        JSONObject phone = sample(); phone.getJSONObject("report").getJSONArray("items").put(0, "联系 13800138000。"); reject("phone", phone);
        JSONObject control = sample(); control.getJSONArray("topConversations").getJSONObject(0).put("name", "周末\u0007闲聊"); reject("control", control);
        JSONObject link = sample(); link.getJSONObject("report").getJSONArray("items").put(0, "见 https://example.com"); reject("url", link);
        JSONObject domain = sample(); domain.getJSONObject("links").getJSONArray("topDomains").getJSONObject(0).put("domain", "user@example.com"); reject("email domain", domain);
    }

    @Test public void envelopeMustMatch() throws Exception {
        assertNotNull(WechatDigest.parseResponse(new JSONObject(WechatDigest.exampleResponse(NOW).toString())));
        JSONObject extra = new JSONObject(WechatDigest.exampleResponse(NOW).toString()); extra.put("note", 1);
        assertNull(WechatDigest.parseResponse(extra));
        JSONObject offset = new JSONObject(WechatDigest.exampleResponse(NOW).toString()); offset.put("checkedAt", "2026-10-22T20:00:00+08:00");
        assertNull(WechatDigest.parseResponse(offset));
        JSONObject empty = new JSONObject(WechatDigest.exampleResponse(NOW).toString()); empty.put("digest", JSONObject.NULL);
        assertNull(WechatDigest.parseResponse(empty));
        JSONObject inner = new JSONObject(WechatDigest.exampleResponse(NOW).toString()); inner.getJSONObject("digest").put("extra", 1);
        assertNull(WechatDigest.parseResponse(inner));
        JSONObject array = new JSONObject(WechatDigest.exampleResponse(NOW).toString()); array.put("digest", new JSONArray());
        assertNull(WechatDigest.parseResponse(array));
    }

    @Test public void oversizedDocumentIsUnavailable() {
        StringBuilder spaces = new StringBuilder(); for (int i = 0; i < WechatDigest.MAX_BYTES + 1; i++) spaces.append(' ');
        assertNull(WechatDigest.parse(spaces.toString()));
        StringBuilder han = new StringBuilder(); for (int i = 0; i < 90000; i++) han.append('微');
        assertTrue(han.length() <= WechatDigest.MAX_BYTES);
        assertNull(WechatDigest.parse(han.toString()));
    }

    @Test public void failureMessages() {
        assertEquals(WechatDigest.UNAVAILABLE, WechatDigest.failure(503, "wechat_digest_unavailable"));
        assertEquals(WechatDigest.INVALID, WechatDigest.failure(503, "wechat_digest_invalid"));
        assertNull(WechatDigest.failure(500, "wechat_digest_unavailable"));
        assertNull(WechatDigest.failure(503, "http_error"));
        assertNull(WechatDigest.failure(200, "wechat_digest_invalid"));
    }

    private static JSONArray conversations(int n) throws Exception {
        JSONArray a = new JSONArray();
        for (int i = 0; i < n; i++) a.put(new JSONObject().put("name", "会话" + i).put("kind", i % 2 == 0 ? "group" : "private").put("messages7d", n - i).put("sent7d", 1).put("lastAt", NOW.toString()));
        return a;
    }
}
