package wang.robby.console.android;

import android.content.Context;
import android.content.ContextWrapper;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Synthetic day files only; never uses a phone, keys, recordings or a model service. */
public final class MindBallStorageTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Context context() {
        return new ContextWrapper(null) {
            @Override public File getNoBackupFilesDir() { return temp.getRoot(); }
        };
    }
    private static void append(Context c, long start, long end, String text) throws Exception {
        MindBall.append(c, round(), "2026-10-28", start, end, text);
    }
    private static int round() throws Exception {
        Field epoch = MindBall.class.getDeclaredField("epoch"); epoch.setAccessible(true); return epoch.getInt(null);
    }
    private File dayFile(Context c) { return new File(MindBall.dir(c), "2026-10-28.json"); }
    private void writeDay(Context c, JSONObject value) throws Exception { Files.write(dayFile(c).toPath(), value.toString().getBytes(StandardCharsets.UTF_8)); }
    private static JSONObject timeline() throws Exception { return new JSONObject().put("items", new JSONArray()).put("summary", "synthetic summary"); }
    @Test public void retryAfterSavedTranscriptDoesNotDuplicateRecording() throws Exception {
        Context c = context();
        append(c, 1000, 601000, "synthetic original");
        append(c, 1000, 601000, "synthetic retried transcription");
        JSONArray segs = MindBall.day(c, "2026-10-28").getJSONArray("segments");
        assertEquals("a retry of the same recorded interval must not inflate minutes", 1, segs.length());
        assertEquals("synthetic original", segs.getJSONObject(0).getString("text"));
        assertEquals(10, MindBall.minutes(segs));
    }
    @Test public void lateOlderTranscriptMustBeEligibleForSummary() throws Exception {
        Context c = context();
        append(c, 10000, 20000, "synthetic latest recording");
        JSONObject day = MindBall.day(c, "2026-10-28");
        day.put("summarizedThrough", 20000).put("summarizedAt", 30000).put("summarizedSegmentCount", day.getJSONArray("segments").length());
        Files.write(new File(MindBall.dir(c), "2026-10-28.json").toPath(), day.toString().getBytes(StandardCharsets.UTF_8));
        append(c, 1000, 2000, "synthetic delayed older recording");
        day = MindBall.day(c, "2026-10-28");
        assertTrue("a late older transcript is new content even below the timestamp watermark",
            MindBall.summaryDue(true, false, day, 40000));
    }
    @Test public void unreadableExistingDayMustNotBeReplacedByAnEmptyDay() throws Exception {
        Context c = context();
        File original = new File(MindBall.dir(c), "2026-10-28.json");
        String broken = "{\"segments\":[{\"text\":\"synthetic incomplete file";
        Files.write(original.toPath(), broken.getBytes(StandardCharsets.UTF_8));
        try { append(c, 1000, 2000, "synthetic new text"); fail("unreadable existing data must stop the write"); }
        catch (IOException | org.json.JSONException expected) { }
        assertEquals("preserve every original byte for recovery", broken, new String(Files.readAllBytes(original.toPath()), StandardCharsets.UTF_8));
    }
    @Test public void legacyDayKeepsEveryExistingFieldAndGetsCheckpointOnlyOnAppend() throws Exception {
        Context c = context();
        JSONObject legacy = new JSONObject("{\"segments\":[{\"start\":10000,\"end\":20000,\"text\":\"synthetic original\",\"extra\":7}],"
            + "\"done\":{\"old-key\":12345},\"timeline\":{\"items\":[{\"kind\":\"todo\",\"title\":\"synthetic todo\"}]},"
            + "\"summarizedThrough\":20000,\"summarizedAt\":30000,\"custom\":{\"keep\":[1,2,3]}}");
        writeDay(c, legacy);
        byte[] before = Files.readAllBytes(dayFile(c).toPath());
        assertEquals(0, MindBall.pendingSummarySegments(MindBall.day(c, "2026-10-28")));
        assertArrayEquals("reading never migrates or rewrites a legacy file", before, Files.readAllBytes(dayFile(c).toPath()));
        append(c, 1000, 2000, "synthetic delayed text");
        JSONObject updated = MindBall.day(c, "2026-10-28");
        assertEquals(legacy.getJSONArray("segments").getJSONObject(0).toString(), updated.getJSONArray("segments").getJSONObject(0).toString());
        for (String key : new String[] {"done", "timeline", "custom"}) assertEquals(legacy.getJSONObject(key).toString(), updated.getJSONObject(key).toString());
        assertEquals(30000, updated.getLong("summarizedAt"));
        assertEquals(20000, updated.getLong("summarizedThrough"));
        assertEquals(1, MindBall.pendingSummarySegments(updated));
        assertFalse("today still observes the hourly cadence", MindBall.summaryDue(false, true, updated, 40000));
        assertTrue(MindBall.summaryDue(true, true, updated, 40000));
        assertTrue(MindBall.summaryDue(false, false, updated, 40000));
        byte[] afterAppend = Files.readAllBytes(dayFile(c).toPath());
        append(c, 1000, 2000, "different retry text");
        assertArrayEquals("a duplicate does not even rewrite the day", afterAppend, Files.readAllBytes(dayFile(c).toPath()));
    }
    @Test public void summaryCommitPreservesCompletionAndOnlyMarksItsOwnSnapshot() throws Exception {
        Context c = context(); append(c, 10000, 20000, "synthetic newer text");
        JSONObject source = MindBall.day(c, "2026-10-28");
        // These writes happen while a model response for source is outstanding.
        MindBall.setDone(c, "2026-10-28", "synthetic-key", true);
        append(c, 1000, 2000, "synthetic late text");
        assertTrue(MindBall.saveSummary(c, round(), "2026-10-28", source, timeline(), 40000));
        JSONObject saved = MindBall.day(c, "2026-10-28");
        assertTrue(saved.getJSONObject("done").has("synthetic-key"));
        assertEquals(2, saved.getJSONArray("segments").length());
        assertEquals(1, saved.getInt("summarizedSegmentCount"));
        assertEquals(1, MindBall.pendingSummarySegments(saved));
        MindBall.setDone(c, "2026-10-28", "synthetic-key", false);
        assertTrue(MindBall.saveSummary(c, round(), "2026-10-28", saved, timeline(), 50000));
        saved = MindBall.day(c, "2026-10-28");
        assertFalse(saved.getJSONObject("done").has("synthetic-key"));
        assertEquals(0, MindBall.pendingSummarySegments(saved));
        assertFalse("already summarized content is not sent again on force", MindBall.summaryDue(true, true, saved, 60000));
    }
    @Test public void failedTemporaryWriteKeepsOriginalBytes() throws Exception {
        Context c = context(); append(c, 1000, 2000, "synthetic original");
        byte[] before = Files.readAllBytes(dayFile(c).toPath());
        assertTrue(new File(MindBall.dir(c), "2026-10-28.json.tmp").mkdir());
        try { append(c, 3000, 4000, "synthetic new"); fail("a directory prevents the temp-file write"); }
        catch (MindBall.StorageError expected) { assertEquals("本机记录无法读写，已保留原文件", MindBall.reason(expected)); }
        assertArrayEquals(before, Files.readAllBytes(dayFile(c).toPath()));
    }
    @Test public void wrongShapeIsPreservedOnAppendDoneAndSummaryWrites() throws Exception {
        Context c = context();
        for (String broken : new String[] {"{\"segments\":{}}", "{\"done\":[]}", "{\"timeline\":7}"}) {
            Files.write(dayFile(c).toPath(), broken.getBytes(StandardCharsets.UTF_8));
            try { append(c, 1000, 2000, "synthetic new"); fail("invalid shape"); } catch (MindBall.StorageError expected) { }
            MindBall.setDone(c, "2026-10-28", "synthetic-key", true);
            try { MindBall.saveSummary(c, round(), "2026-10-28", new JSONObject(), timeline(), 40000); fail("invalid shape"); }
            catch (MindBall.StorageError expected) { }
            assertEquals(broken, new String(Files.readAllBytes(dayFile(c).toPath()), StandardCharsets.UTF_8));
        }
    }
    @Test public void clearAllRejectsLateTranscriptAndSummaryButAcceptsNextRound() throws Exception {
        Context c = context(); append(c, 1000, 2000, "synthetic original");
        int previous = round(); JSONObject source = MindBall.day(c, "2026-10-28");
        File audio = MindBall.segmentFile(c, 3000, 4000); Files.write(audio.toPath(), new byte[] {1, 2, 3});
        MindBall.clearAll(c);
        assertFalse(audio.exists()); assertFalse(dayFile(c).exists());
        assertFalse(MindBall.append(c, previous, "2026-10-28", 3000, 4000, "synthetic late text"));
        assertFalse(MindBall.saveSummary(c, previous, "2026-10-28", source, timeline(), 50000));
        assertFalse("no cleared document is resurrected", dayFile(c).exists());
        append(c, 5000, 6000, "synthetic next round");
        assertEquals(1, MindBall.day(c, "2026-10-28").getJSONArray("segments").length());
    }

    @Test public void legacyPendingTailAndLateOlderAppendAreBothCounted() throws Exception {
        Context c = context();
        writeDay(c, new JSONObject("{\"segments\":[{\"start\":10000,\"end\":20000,\"text\":\"synthetic summarized\"},"
            + "{\"start\":30000,\"end\":40000,\"text\":\"synthetic pending tail\"}],\"summarizedThrough\":20000}"));
        assertEquals(1, MindBall.pendingSummarySegments(MindBall.day(c, "2026-10-28")));
        append(c, 1000, 2000, "synthetic older append");
        JSONObject day = MindBall.day(c, "2026-10-28");
        assertEquals(2, MindBall.pendingSummarySegments(day));
        assertEquals(1, day.getInt("summarizedSegmentCount"));
    }

}
