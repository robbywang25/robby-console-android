package wang.robby.console.android;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** 心力球's pure parts: what is sent to DeepSeek and the transcription service, and what is kept from the reply. */
public final class MindBallTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static long at(String hm) { return java.time.LocalDateTime.parse("2026-10-22T" + hm).atZone(SHANGHAI).toInstant().toEpochMilli(); }

    @Test public void timelineKeepsWellFormedItemsSortedByTime() throws Exception {
        JSONObject t = MindBall.parseTimeline("```json\n{\"summary\":\"  今天   开了两个会 \",\"items\":["
            + "{\"time\":\"14:05\",\"kind\":\"todo\",\"title\":\"发报价单\",\"due\":\"2026-10-23 18:00\"},"
            + "{\"time\":\"9:30\",\"kind\":\"event\",\"title\":\"晨会\",\"detail\":\"定了范围\"},"
            + "{\"time\":\"25:00\",\"kind\":\"meeting\",\"title\":\"时间不对\",\"due\":\"明天\"},"
            + "{\"time\":\"10:00\",\"kind\":\"todo\",\"title\":\"  \"}, 7]}\n```");
        JSONArray items = t.getJSONArray("items");
        assertEquals(3, items.length());
        assertEquals("09:30", items.getJSONObject(0).getString("time"));
        assertEquals("14:05", items.getJSONObject(1).getString("time"));
        assertEquals("todo", items.getJSONObject(1).getString("kind"));
        assertEquals("2026-10-23 18:00", items.getJSONObject(1).getString("due"));
        assertEquals("an unknown kind is an event, a bad time sorts last", "event", items.getJSONObject(2).getString("kind"));
        assertEquals("", items.getJSONObject(2).getString("time"));
        assertEquals("relative dates the model failed to resolve are dropped", "", items.getJSONObject(2).getString("due"));
        assertEquals("今天 开了两个会", t.getString("summary"));
    }
    @Test public void emptyOrOverlongRepliesStayBounded() throws Exception {
        assertEquals(0, MindBall.parseTimeline("{}").getJSONArray("items").length());
        StringBuilder many = new StringBuilder("{\"items\":["); for (int i = 0; i < 300; i++) many.append(i == 0 ? "" : ",").append("{\"time\":\"08:00\",\"title\":\"").append("长".repeat(200)).append("\"}");
        JSONArray items = MindBall.parseTimeline(many.append("]}").toString()).getJSONArray("items");
        assertEquals(200, items.length());
        assertEquals(81, items.getJSONObject(0).getString("title").length());
    }
    @Test(expected = org.json.JSONException.class) public void nonJsonReplyIsAnError() throws Exception { MindBall.parseTimeline("好的，以下是时间线"); }

    @Test public void transcriptNamesTheDateAndEachSegmentsTimes() throws Exception {
        JSONArray segs = new JSONArray().put(new JSONObject().put("start", at("09:40")).put("end", at("09:50")).put("text", "第一段\n换行"))
            .put(new JSONObject().put("start", at("10:00")).put("end", at("10:10")).put("text", "第二段"));
        String text = MindBall.transcript("2026-10-22", segs, SHANGHAI);
        assertTrue(text.startsWith("录音日期：2026-10-22（星期四）\n"));
        assertTrue(text.contains("[09:40–09:50] 第一段 换行\n[10:00–10:10] 第二段"));
    }
    @Test public void longDaysSendTheLatestSegments() throws Exception {
        JSONArray segs = new JSONArray(); String block = "字".repeat(100_000);
        for (int i = 0; i < 4; i++) segs.put(new JSONObject().put("start", at("1" + i + ":00")).put("end", at("1" + i + ":10")).put("text", block));
        String text = MindBall.transcript("2026-10-22", segs, SHANGHAI);
        assertTrue(text.length() <= MindBall.MAX_DAY_CHARS + 100);
        assertTrue(text.contains("（只附上最近的 2 段）"));
        assertTrue(text.contains("[13:00–13:10]")); assertFalse(text.contains("[10:00–10:10]"));
    }
    @Test public void todoMarksSurviveRewording() throws Exception {
        assertEquals(MindBall.todoKey(new JSONObject().put("title", "发报价单给王总。")), MindBall.todoKey(new JSONObject().put("title", "发报价单 给王总")));
        assertNotEquals(MindBall.todoKey(new JSONObject().put("title", "发报价单")), MindBall.todoKey(new JSONObject().put("title", "看合同")));
    }
    @Test public void multipartCarriesModelThenFile() {
        String head = new String(MindBall.multipartHead("B", "FunAudioLLM/SenseVoiceSmall", "1-2.m4a"), StandardCharsets.UTF_8);
        assertEquals("--B\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nFunAudioLLM/SenseVoiceSmall\r\n--B\r\nContent-Disposition: form-data; name=\"file\"; filename=\"1-2.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n", head);
    }
    @Test public void failuresAreWordedWithoutDetails() {
        assertEquals("API Key 无效或没有权限", MindBall.reason(new MindBall.HttpError(401)));
        assertEquals("账户余额不足", MindBall.reason(new MindBall.HttpError(402)));
        assertEquals("服务暂不可用（HTTP 503）", MindBall.reason(new MindBall.HttpError(503)));
        assertEquals("网络不可用，稍后自动重试", MindBall.reason(new java.io.IOException("boom")));
    }
    @Test public void liveLineSaysHowFarTheSegmentIs() {
        assertEquals("这一段已录 不到 1 分钟 · 还没听到说话声", MindBall.liveLine(40_000, 0));
        assertEquals("这一段已录 3 分钟 · 听到说话 5 秒 · 点「立即整理」可现在转写", MindBall.liveLine(200_000, 5));
        assertEquals("这一段已录 7 分钟 · 听到说话 1 分 5 秒 · 满 10 分钟或点「立即整理」就转写", MindBall.liveLine(7 * 60_000 + 59_000, 65));
        assertEquals("麦克风被通话或其他录音占用，一分钟后再试", MindBall.liveLine(-1, 0));
    }
    @Test public void aPastDaysTailIsSummarizedOnTheNextRound() {
        assertEquals("yesterday is looked at although nothing of it was transcribed this round", java.util.List.of("2026-10-23", "2026-10-22", "2026-10-21", "2026-10-20"),
            MindBall.summaryDays(java.util.List.of(), java.util.List.of("2026-10-19", "2026-10-22", "2026-10-20", "2026-10-21"), "2026-10-23"));
        assertEquals("an older day just transcribed into is looked at too", java.util.List.of("2026-10-23", "2026-10-22", "2026-10-21", "2026-10-15"),
            MindBall.summaryDays(java.util.List.of("2026-10-15", "2026-10-23"), java.util.List.of("2026-10-23", "2026-10-22", "2026-10-21", "2026-10-15"), "2026-10-23"));
        assertEquals(java.util.List.of("2026-10-23"), MindBall.summaryDays(java.util.List.of(), java.util.List.of(), "2026-10-23"));
    }
    @Test public void onlyTodayWaitsForTheHourlySummary() {
        long now = at("14:00"), half = now - 30 * 60_000L, hour = now - 61 * 60_000L;
        assertFalse(MindBall.summaryDue(false, true, now, now - 1, half, now));
        assertTrue(MindBall.summaryDue(false, true, now, now - 1, hour, now));
        assertTrue("立即整理", MindBall.summaryDue(true, true, now, now - 1, half, now));
        assertTrue("a past day with new text is summarized at once", MindBall.summaryDue(false, false, now, now - 1, now - 60_000L, now));
        assertFalse("nothing new: not even 立即整理 asks again", MindBall.summaryDue(true, false, now, now, half, now));
    }
    @Test public void onlyAStatusAboutTheFileSkipsIt() {
        for (int status : new int[] {400, 413, 415, 422}) assertTrue(String.valueOf(status), MindBall.fileRefused(status));
        for (int status : new int[] {401, 402, 403, 404, 408, 429, 500, 503}) assertFalse(String.valueOf(status), MindBall.fileRefused(status));
    }
    @Test public void settingsShowHostAndModelName() {
        assertEquals("api.siliconflow.cn", MindBallPage.host("https://api.siliconflow.cn/v1"));
        assertEquals("SenseVoiceSmall", MindBallPage.shortModel("FunAudioLLM/SenseVoiceSmall"));
    }
}
