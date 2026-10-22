package wang.robby.console.android;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Account settings document: what is uploaded, what is ignored, and how two phones meet in the middle. */
public final class PreferenceSyncTest {
    @Test public void everySyncKeyRoundTrips() throws Exception {
        Map<String, Object> local = sample();
        JSONObject doc = PreferenceSync.document(local);
        assertEquals(PreferenceSync.KEYS.size(), doc.length());
        Map<String, Object> back = PreferenceSync.read(doc);
        assertEquals(local.get("board"), back.get("board"));
        assertEquals(local.get("clocks"), back.get("clocks"));
        assertEquals(local.get("calendarDays"), back.get("calendarDays"));
        assertEquals(local.get("hiddenQuotaCards"), back.get("hiddenQuotaCards"));
        assertEquals(local.get("glassOn"), back.get("glassOn"));
        assertEquals(local.get("glassLevel"), back.get("glassLevel"));
        assertEquals(local.get("statusBar"), back.get("statusBar"));
        assertEquals(local.get("islandLeft"), back.get("islandLeft"));
        assertEquals(local.get("islandRight"), back.get("islandRight"));
        assertEquals(local.get("hiddenFeatures"), back.get("hiddenFeatures"));
        assertEquals(local.get("shownFeatures"), back.get("shownFeatures"));
        assertEquals(local.get("recentFeatures"), back.get("recentFeatures"));
        assertEquals(local.get("noticeKinds"), back.get("noticeKinds"));
        assertEquals(local.get("dailyLine"), back.get("dailyLine"));
        assertEquals(local.get("lowLine"), back.get("lowLine"));
        assertEquals(local.get("enhancedNotice"), back.get("enhancedNotice"));
        assertEquals(local.get("persistentNotice"), back.get("persistentNotice"));
        assertEquals(local.get("tiboNotice"), back.get("tiboNotice"));
        assertEquals(back, PreferenceSync.read(PreferenceSync.document(back)));
    }

    @Test public void unknownKeysAndValuesAreIgnored() throws Exception {
        JSONObject prefs = new JSONObject();
        prefs.put("board", new JSONArray().put("services").put("nope").put("tokens"));
        prefs.put("glassOn", "yes");
        prefs.put("glassLevel", 150);
        prefs.put("calendarDays", 9);
        prefs.put("statusBar", "nope");
        prefs.put("islandLeft", "left");
        prefs.put("islandRight", new JSONArray().put("time"));
        prefs.put("noticeKinds", new JSONArray().put("daily").put("nope"));
        prefs.put("dailyLine", 15);
        prefs.put("lowLine", true);
        prefs.put("clocks", new JSONArray().put(new JSONObject().put("name", "X").put("zone", "Not A Zone")).put(new JSONObject().put("name", "北京时间").put("zone", "Asia/Shanghai")));
        prefs.put("recentFeatures", new JSONArray().put(new JSONObject().put("id", "Bad Id").put("score", 1).put("at", 5)).put(new JSONObject().put("id", "clipboard").put("score", 1.5).put("at", 50)));
        prefs.put("futureWidget", true);
        prefs.put("signature", "keep-off");
        JSONObject deep = new JSONObject().put("a", new JSONObject().put("b", new JSONObject().put("c", new JSONObject().put("d", new JSONObject().put("e", 1)))));
        prefs.put("enhancedNotice", deep);
        Map<String, Object> got = PreferenceSync.read(prefs);
        assertEquals(List.of("services", "tokens"), got.get("board"));
        assertFalse(got.containsKey("glassOn"));
        assertFalse(got.containsKey("glassLevel"));
        assertFalse(got.containsKey("calendarDays"));
        assertFalse(got.containsKey("statusBar"));
        assertFalse(got.containsKey("islandLeft"));
        assertFalse(got.containsKey("islandRight"));
        assertEquals(List.of("daily"), got.get("noticeKinds"));
        assertFalse(got.containsKey("dailyLine"));
        assertFalse(got.containsKey("lowLine"));
        assertEquals(List.of("北京时间|Asia/Shanghai"), got.get("clocks"));
        List<PreferenceSync.Recent> recent = cast(got.get("recentFeatures"));
        assertEquals(1, recent.size());
        assertEquals("clipboard", recent.get(0).id);
        assertFalse(got.containsKey("futureWidget"));
        assertFalse(got.containsKey("signature"));
        assertFalse(got.containsKey("enhancedNotice"));
        assertFalse(PreferenceSync.document(got).has("futureWidget"));
        assertFalse(PreferenceSync.document(got).has("signature"));
    }

    @Test public void localOnlyKeysStayOffTheDocument() throws Exception {
        Map<String, Object> raw = new LinkedHashMap<>(sample());
        for (String key : PreferenceSync.LOCAL_ONLY) raw.put(key, "LEAK-" + key);
        JSONObject doc = PreferenceSync.document(raw);
        String text = doc.toString();
        assertFalse(text.contains("LEAK-"));
        for (String key : PreferenceSync.LOCAL_ONLY) {
            assertFalse(key, doc.has(key));
            assertFalse(key, text.contains("\"" + key + "\""));
        }
        for (String key : keys(doc)) assertFalse(key, PreferenceSync.forbidden(key));
        assertTrue("a card id may say tokens; the key must not", text.contains("tokens"));
        assertTrue(text.contains("secrets"));
        Map<String, Object> ui = new LinkedHashMap<>();
        ui.put("dashboard", "services,tokens");
        ui.put("tokenBoardRows", "LEAK-rows");
        ui.put("signature", "LEAK-signature");
        ui.put("avatarEtag", "LEAK-avatar");
        ui.put("hideStatusBar", true);
        ui.put("cutoutLeft", 12);
        ui.put("islandLeftPx", 0);
        ui.put("screenWidth", 1080);
        ui.put("prefSyncBase", "LEAK-base");
        Map<String, Object> notices = new LinkedHashMap<>();
        notices.put("on", Set.of("daily"));
        notices.put("state", "LEAK-state");
        notices.put("pending", "LEAK-pending");
        notices.put("lastCheckAt", 9L);
        notices.put("lastError", "LEAK-error");
        notices.put("primed", true);
        Map<String, Object> tibo = new LinkedHashMap<>();
        tibo.put("enabled", true);
        tibo.put("seen", Set.of("LEAK-seen"));
        tibo.put("pending", "LEAK-alarm");
        tibo.put("lastCheckAt", 9L);
        tibo.put("enabledAt", 9L);
        tibo.put("round", 2);
        JSONObject collected = PreferenceSync.document(PreferenceSync.collect(ui, notices, tibo));
        String uploaded = collected.toString();
        assertFalse(uploaded.contains("LEAK-"));
        assertEquals("hideUp", collected.getString("statusBar"));
        assertFalse(collected.has("hideStatusBar"));
        assertFalse(collected.has("tokenBoardRows"));
        assertEquals(AccountCenter.PREFERENCES, MobileApi.checkedPath(AccountCenter.PREFERENCES));
        assertTrue(AccountCenter.readRoute(AccountCenter.PREFERENCES));
        assertTrue(AccountCenter.writeRoute(AccountCenter.PREFERENCES));
    }

    @Test public void tooManyRecentRowsDropTheOldest() throws Exception {
        List<PreferenceSync.Recent> rows = new ArrayList<>();
        for (int i = 0; i < 2000; i++) rows.add(new PreferenceSync.Recent(String.format("feature-%04d", i), 1.25, 1_700_000_000_000L + i));
        Map<String, Object> local = new LinkedHashMap<>();
        local.put("recentFeatures", rows);
        local.put("board", List.of("services"));
        JSONObject doc = PreferenceSync.document(local);
        JSONObject body = PreferenceSync.requestBody(Integer.MAX_VALUE, doc);
        assertTrue(doc.toString().getBytes(StandardCharsets.UTF_8).length <= PreferenceSync.MAX_BYTES);
        assertTrue(body.toString().getBytes(StandardCharsets.UTF_8).length <= PreferenceSync.MAX_BYTES);
        JSONArray kept = doc.getJSONArray("recentFeatures");
        assertTrue(kept.length() < rows.size());
        assertTrue(kept.length() > 0);
        long oldest = Long.MAX_VALUE, newest = 0;
        for (int i = 0; i < kept.length(); i++) {
            long at = kept.getJSONObject(i).getLong("at");
            oldest = Math.min(oldest, at); newest = Math.max(newest, at);
        }
        assertTrue(oldest > 1_700_000_000_000L);
        assertEquals(1_700_000_000_000L + 1999, newest);
        assertEquals("services", doc.getJSONArray("board").getString(0));
    }

    @Test public void newerServerReplacesLocalAndVersionZeroUploads() throws Exception {
        Manual time = new Manual();
        Net net = new Net();
        PreferenceSync.Sync sync = ready(time, net);
        sync.restore(1, "2026-10-22T00:00:00Z", new JSONObject(), Set.of(), false, Map.of("board", List.of("services"), "glassOn", true));
        sync.setReady(true, true);
        JSONObject remote = new JSONObject().put("board", new JSONArray().put("clocks")).put("glassOn", false);
        net.nextGet = PreferenceSync.Response.ok(4, "2026-10-22T02:00:00Z", remote);
        sync.foreground();
        assertEquals(1, net.gets);
        assertEquals(0, net.puts);
        assertEquals(List.of("clocks"), sync.local().get("board"));
        assertEquals(false, sync.local().get("glassOn"));
        assertEquals(4, sync.version());
        assertFalse(sync.pending());

        PreferenceSync.Sync first = ready(time, net);
        first.restore(0, null, new JSONObject(), Set.of(), false, Map.of("board", List.of("services")));
        first.setReady(true, true);
        net.nextGet = PreferenceSync.Response.ok(0, null, new JSONObject());
        net.nextPut = PreferenceSync.Response.ok(1, "2026-10-22T03:00:00Z", new JSONObject().put("board", new JSONArray().put("services")));
        first.foreground();
        assertEquals(1, net.puts);
        assertEquals(0, net.lastExpected);
        assertEquals("services", net.lastPrefs.getJSONArray("board").getString(0));
        assertEquals(1, first.version());
        assertFalse(first.pending());

        net.puts = 0;
        PreferenceSync.Sync empty = ready(time, net);
        empty.restore(0, null, new JSONObject(), Set.of(), false, Map.of());
        empty.setReady(true, true);
        net.nextGet = PreferenceSync.Response.ok(0, null, new JSONObject());
        empty.foreground();
        assertEquals(0, net.puts);
    }

    @Test public void conflictKeepsLocalEditsAndRateLimitOrOfflineDoesNotDropThem() throws Exception {
        Manual time = new Manual();
        Net net = new Net();
        PreferenceSync.Sync sync = ready(time, net);
        JSONObject base = new JSONObject().put("board", new JSONArray().put("services")).put("glassLevel", 10);
        sync.restore(1, "2026-10-22T00:00:00Z", base, Set.of(), false, Map.of("board", List.of("services"), "glassLevel", 10));
        sync.setReady(true, true);
        sync.edit("glassLevel", 80);
        JSONObject current = new JSONObject().put("schemaVersion", 1).put("version", 4).put("updatedAt", "2026-10-22T04:00:00Z")
            .put("preferences", new JSONObject().put("board", new JSONArray().put("clocks")).put("glassLevel", 1).put("islandLeft", "date"));
        net.nextPut = PreferenceSync.conflict(new JSONObject().put("error", "version_conflict").put("current", current));
        net.afterPut = PreferenceSync.Response.ok(5, "2026-10-22T04:00:01Z", new JSONObject().put("board", new JSONArray().put("clocks")).put("glassLevel", 80).put("islandLeft", "date"));
        time.advance(PreferenceSync.QUIET_MS);
        assertEquals(2, net.puts);
        assertEquals(4, net.lastExpected);
        assertEquals(80, net.lastPrefs.getInt("glassLevel"));
        assertEquals("clocks", net.lastPrefs.getJSONArray("board").getString(0));
        assertEquals("date", net.lastPrefs.getString("islandLeft"));
        assertEquals(80, ((Number) sync.local().get("glassLevel")).intValue());
        assertEquals(List.of("clocks"), sync.local().get("board"));
        assertEquals("date", sync.local().get("islandLeft"));
        assertEquals(5, sync.version());
        assertFalse(sync.pending());

        PreferenceSync.Sync limited = ready(time, net);
        limited.restore(2, "2026-10-22T00:00:00Z", new JSONObject(), Set.of(), false, Map.of("board", List.of("services")));
        limited.setReady(true, true);
        limited.edit("board", List.of("clocks"));
        net.puts = 0;
        net.nextPut = PreferenceSync.Response.fail(429);
        net.afterPut = null;
        time.advance(PreferenceSync.QUIET_MS);
        assertEquals(1, net.puts);
        assertEquals(List.of("clocks"), limited.local().get("board"));
        assertTrue(limited.pending());
        time.advance(60_000);
        assertEquals(1, net.puts);

        net.nextGet = PreferenceSync.Response.fail(0);
        limited.foreground();
        assertEquals(1, net.puts);
        assertEquals(List.of("clocks"), limited.local().get("board"));
        assertTrue(limited.pending());

        PreferenceSync.Sync offline = ready(time, net);
        offline.restore(2, "2026-10-22T00:00:00Z", new JSONObject(), Set.of(), false, Map.of("glassOn", true));
        offline.setReady(true, true);
        offline.edit("glassOn", false);
        net.failPut = true;
        net.puts = 0;
        time.advance(PreferenceSync.QUIET_MS);
        assertEquals(1, net.puts);
        assertEquals(false, offline.local().get("glassOn"));
        assertTrue(offline.pending());
        time.advance(60_000);
        assertEquals(1, net.puts);
    }

    @Test public void tenEditsSendOnePostAndAFlightStaysSingle() throws Exception {
        Manual time = new Manual();
        Net net = new Net();
        PreferenceSync.Sync sync = ready(time, net);
        sync.restore(3, "2026-10-22T00:00:00Z", new JSONObject(), Set.of(), false, Map.of("glassLevel", 10));
        sync.setReady(true, true);
        net.onFirstPut = () -> { for (int i = 0; i < 5; i++) sync.edit("glassOn", i % 2 == 0); };
        for (int i = 0; i < 10; i++) { sync.edit("glassLevel", 10 + i); time.advance(100); }
        assertEquals(0, net.puts);
        time.advance(PreferenceSync.QUIET_MS);
        assertEquals(1, net.puts);
        assertEquals(19, net.lastPrefs.getInt("glassLevel"));
        assertEquals(1, net.puts);
        time.advance(PreferenceSync.QUIET_MS);
        assertEquals(2, net.puts);
        assertTrue(net.lastPrefs.has("glassOn"));
    }

    @Test public void signOutResetsTheCursorAndKeepsLocalSettings() throws Exception {
        Manual time = new Manual();
        Net net = new Net();
        PreferenceSync.Snapshot[] seen = new PreferenceSync.Snapshot[1];
        PreferenceSync.Sync sync = new PreferenceSync.Sync(time, time, time, net, new PreferenceSync.Listener() {
            public void applied(Map<String, Object> changed) {}
            public void persisted(PreferenceSync.Snapshot snap) { seen[0] = snap; }
        });
        JSONObject base = new JSONObject().put("board", new JSONArray().put("services")).put("glassLevel", 10);
        sync.restore(4, "2026-10-22T00:00:00Z", base, Set.of("glassLevel"), true, Map.of("board", List.of("services"), "glassLevel", 40));
        sync.setReady(true, true);
        sync.signOut();
        assertEquals(0, sync.version());
        assertNull(sync.updatedAt());
        assertFalse(sync.pending());
        assertTrue(sync.dirty().isEmpty());
        assertEquals(List.of("services"), sync.local().get("board"));
        assertEquals(40, ((Number) sync.local().get("glassLevel")).intValue());
        assertEquals(0, seen[0].version);
        assertEquals("{}", seen[0].baseJson);
        assertTrue(seen[0].dirty.isEmpty());
        assertFalse(seen[0].pending);
        assertNull(seen[0].updatedAt);
        assertEquals(0, net.puts);

        sync.setReady(true, true);
        sync.edit("glassLevel", 70);
        net.nextPut = PreferenceSync.Response.ok(9, "2026-10-22T08:00:00Z", new JSONObject().put("glassLevel", 1).put("board", new JSONArray().put("clocks")));
        net.onFirstPut = sync::signOut;
        time.advance(PreferenceSync.QUIET_MS);
        assertEquals(1, net.puts);
        assertEquals(0, sync.version());
        assertFalse(sync.pending());
        assertEquals(70, ((Number) sync.local().get("glassLevel")).intValue());
        assertEquals(List.of("services"), sync.local().get("board"));
    }

    @Test public void trimmedRecentUploadIsAdoptedAndNotPending() throws Exception {
        List<PreferenceSync.Recent> rows = new ArrayList<>();
        for (int i = 0; i < 2000; i++) rows.add(new PreferenceSync.Recent(String.format("feature-%04d", i), 1.25, 1_700_000_000_000L + i));
        Manual time = new Manual();
        Net net = new Net();
        List<Map<String, Object>> applied = new ArrayList<>();
        PreferenceSync.Sync sync = new PreferenceSync.Sync(time, time, time, net, new PreferenceSync.Listener() {
            public void applied(Map<String, Object> changed) { applied.add(new LinkedHashMap<>(changed)); }
            public void persisted(PreferenceSync.Snapshot snap) {}
        });
        sync.restore(1, "2026-10-22T00:00:00Z", new JSONObject(), Set.of(), false, Map.of("board", List.of("services")));
        sync.setReady(true, true);
        net.echo = true; net.echoVersion = 2; net.echoAt = "2026-10-22T05:00:00Z";
        sync.edit("recentFeatures", rows);
        time.advance(PreferenceSync.QUIET_MS);
        assertEquals(1, net.puts);
        int kept = net.lastPrefs.getJSONArray("recentFeatures").length();
        assertTrue(kept > 0 && kept < rows.size());
        assertEquals(kept, ((List<?>) sync.local().get("recentFeatures")).size());
        assertFalse(sync.pending());
        assertTrue(sync.dirty().isEmpty());
        assertEquals(2, sync.version());
        boolean wrote = false;
        for (Map<String, Object> change : applied) if (change.containsKey("recentFeatures")) {
            assertEquals(kept, ((List<?>) change.get("recentFeatures")).size());
            wrote = true;
        }
        assertTrue(wrote);
        time.advance(PreferenceSync.QUIET_MS);
        assertEquals(1, net.puts);
    }

    @Test public void conflictOverlaysOnlyDirtyKeys() throws Exception {
        Manual time = new Manual();
        Net net = new Net();
        PreferenceSync.Sync sync = ready(time, net);
        JSONObject base = new JSONObject().put("board", new JSONArray().put("services")).put("glassLevel", 10);
        sync.restore(1, "2026-10-22T00:00:00Z", base, Set.of(), false, Map.of(
            "board", List.of("services"), "glassLevel", 10, "clocks", List.of("北京时间|Asia/Shanghai")));
        sync.setReady(true, true);
        sync.edit("glassLevel", 80);
        JSONObject current = new JSONObject().put("schemaVersion", 1).put("version", 4).put("updatedAt", "2026-10-22T04:00:00Z")
            .put("preferences", new JSONObject().put("board", new JSONArray().put("clocks")).put("glassLevel", 1).put("islandLeft", "date"));
        net.nextPut = PreferenceSync.conflict(new JSONObject().put("error", "version_conflict").put("current", current));
        net.afterPut = PreferenceSync.Response.ok(5, "2026-10-22T04:00:01Z", new JSONObject().put("board", new JSONArray().put("clocks")).put("glassLevel", 80).put("islandLeft", "date"));
        time.advance(PreferenceSync.QUIET_MS);
        assertEquals(2, net.puts);
        assertTrue(net.sent.get(0).has("clocks"));
        assertFalse(net.lastPrefs.has("clocks"));
        assertEquals("clocks", net.lastPrefs.getJSONArray("board").getString(0));
        assertEquals(80, net.lastPrefs.getInt("glassLevel"));
        assertEquals("date", net.lastPrefs.getString("islandLeft"));
    }

    @Test public void conflictNearTheCapParsesAndRetriesOnce() throws Exception {
        JSONObject prefs = new JSONObject();
        JSONArray rows = new JSONArray();
        prefs.put("board", new JSONArray().put("services"));
        prefs.put("glassLevel", 1);
        prefs.put("recentFeatures", rows);
        JSONObject error = null;
        for (int i = 0; i < 4000; i++) {
            rows.put(new JSONObject().put("id", String.format("feature-%04d", i)).put("score", 1.25).put("at", 1_700_000_000_000L + i));
            JSONObject current = new JSONObject().put("schemaVersion", 1).put("version", 6).put("updatedAt", "2026-10-22T06:00:00Z").put("preferences", prefs);
            JSONObject wrapped = new JSONObject().put("error", "version_conflict").put("current", current);
            int size = wrapped.toString().getBytes(StandardCharsets.UTF_8).length;
            if (size > 16384) {
                assertTrue(size <= 24576);
                assertTrue(size <= MobileApi.responseLimit(409, AccountCenter.PREFERENCES));
                error = wrapped;
                break;
            }
        }
        assertNotNull(error);
        PreferenceSync.Response conflict = PreferenceSync.conflict(error);
        assertEquals(409, conflict.status);
        assertEquals(6, conflict.version);
        assertTrue(conflict.preferences.getJSONArray("recentFeatures").length() > 0);
        Manual time = new Manual();
        Net net = new Net();
        PreferenceSync.Sync sync = ready(time, net);
        sync.restore(1, "2026-10-22T00:00:00Z", new JSONObject().put("glassLevel", 10), Set.of(), false, Map.of("glassLevel", 10));
        sync.setReady(true, true);
        sync.edit("glassLevel", 80);
        net.nextPut = conflict;
        net.afterPut = PreferenceSync.Response.ok(7, "2026-10-22T06:00:01Z", new JSONObject().put("glassLevel", 80).put("board", new JSONArray().put("services")));
        time.advance(PreferenceSync.QUIET_MS);
        assertEquals(2, net.puts);
        assertEquals(6, net.lastExpected);
        assertEquals(80, net.lastPrefs.getInt("glassLevel"));
        assertFalse(sync.pending());
    }

    @Test public void clockRollbackReschedulesTheUpload() throws Exception {
        Manual time = new Manual();
        time.now = 10_000;
        Net net = new Net();
        PreferenceSync.Sync sync = ready(time, net);
        sync.restore(3, "2026-10-22T00:00:00Z", new JSONObject(), Set.of(), false, Map.of("glassLevel", 10));
        sync.setReady(true, true);
        net.nextPut = PreferenceSync.Response.ok(4, "2026-10-22T01:00:00Z", new JSONObject().put("glassLevel", 20));
        sync.edit("glassLevel", 20);
        assertEquals(0, net.puts);
        time.now = 0;
        time.fire();
        assertEquals(0, net.puts);
        assertTrue(time.armed);
        time.advance(PreferenceSync.QUIET_MS);
        assertEquals(1, net.puts);
        assertEquals(20, net.lastPrefs.getInt("glassLevel"));
    }

    @Test public void uploadAcceptedWithNoKeyChangeStillPersistsTheStamp() throws Exception {
        Manual time = new Manual();
        Net net = new Net();
        List<PreferenceSync.Snapshot> snaps = new ArrayList<>();
        List<Map<String, Object>> applied = new ArrayList<>();
        PreferenceSync.Sync sync = new PreferenceSync.Sync(time, time, time, net, new PreferenceSync.Listener() {
            public void applied(Map<String, Object> changed) { applied.add(new LinkedHashMap<>(changed)); }
            public void persisted(PreferenceSync.Snapshot snap) { snaps.add(snap); }
        });
        sync.restore(3, "2026-10-22T00:00:00Z", new JSONObject().put("glassLevel", 10), Set.of(), false, Map.of("glassLevel", 10));
        sync.setReady(true, true);
        net.nextPut = PreferenceSync.Response.ok(4, "2026-10-22T03:00:00Z", new JSONObject().put("glassLevel", 20));
        sync.edit("glassLevel", 20);
        snaps.clear();
        applied.clear();
        time.advance(PreferenceSync.QUIET_MS);
        assertEquals(1, net.puts);
        assertTrue(applied.isEmpty());
        assertFalse(sync.pending());
        assertTrue(sync.dirty().isEmpty());
        PreferenceSync.Snapshot last = snaps.get(snaps.size() - 1);
        assertEquals(4, last.version);
        assertEquals("2026-10-22T03:00:00Z", last.updatedAt);
        assertFalse(last.pending);
    }

    @Test public void islandSidesTravelAndCutoutDoesNot() throws Exception {
        Map<String, Object> ui = new LinkedHashMap<>();
        ui.put(StatusBarStyle.LEFT_KEY, "date");
        ui.put(StatusBarStyle.RIGHT_KEY, "weekday");
        ui.put(StatusBarStyle.KEY, StatusBarStyle.ISLAND);
        ui.put("cutoutLeft", 84); ui.put("cutoutTop", 0); ui.put("cutoutRight", 168); ui.put("cutoutBottom", 160);
        ui.put("holeLeft", 100); ui.put("holeTop", 20); ui.put("holeRight", 180); ui.put("holeBottom", 100);
        ui.put("islandLeftPx", 0); ui.put("islandTopPx", 11); ui.put("islandRightPx", 300); ui.put("islandBottomPx", 80);
        ui.put("screenWidth", 1080); ui.put("statusHeight", 120);
        JSONObject doc = PreferenceSync.document(PreferenceSync.collect(ui, Map.of(), Map.of()));
        for (String key : keys(doc)) {
            String s = key.toLowerCase(java.util.Locale.ROOT);
            assertFalse(key, s.contains("cutout") || s.contains("hole") || s.contains("px") || s.contains("screen") || s.contains("coord"));
        }
        assertEquals("date", doc.getString("islandLeft"));
        assertEquals("weekday", doc.getString("islandRight"));
        assertEquals(StatusBarStyle.ISLAND, doc.getString("statusBar"));
        Map<String, Object> applied = PreferenceSync.read(doc);
        assertEquals("date", applied.get("islandLeft"));
        assertEquals("weekday", applied.get("islandRight"));
        int[] find = StatusBarStyle.island(1080, StatusBarStyle.hole(new int[]{84, 0, 168, 160}, null), 8);
        int[] pixel = StatusBarStyle.island(1080, new int[]{500, 40, 580, 120}, 8);
        assertFalse(java.util.Arrays.equals(find, pixel));
        assertEquals(applied.get("islandLeft"), PreferenceSync.read(doc).get("islandLeft"));
        assertEquals(applied.get("islandRight"), PreferenceSync.read(doc).get("islandRight"));
    }

    @Test public void statusLineNamesTheAccountOrThePhone() {
        long now = 1_779_000_000_000L;
        assertEquals("设置仅保存在本机", PreferenceSync.statusLine(false, "2026-10-22T00:00:00Z", now));
        assertEquals("设置仅保存在本机", PreferenceSync.statusLine(false, null, now));
        assertEquals("设置已同步到账号 · 尚未同步", PreferenceSync.statusLine(true, null, now));
        assertTrue(PreferenceSync.statusLine(true, "2026-10-22T00:00:00Z", java.time.Instant.parse("2026-10-22T00:00:30Z").toEpochMilli()).endsWith("刚刚"));
    }

    private static Map<String, Object> sample() {
        Map<String, Object> local = new LinkedHashMap<>();
        local.put("board", List.of("services", "clocks", "tokens"));
        local.put("clocks", List.of("Tibo|America/Los_Angeles", "北京时间|Asia/Shanghai"));
        local.put("calendarDays", 14);
        local.put("hiddenQuotaCards", List.of("codex-1", "today-cn"));
        local.put("glassOn", false);
        local.put("glassLevel", 80);
        local.put("statusBar", StatusBarStyle.ISLAND);
        local.put("islandLeft", "date");
        local.put("islandRight", "weekday");
        local.put("hiddenFeatures", List.of("umami", "secrets"));
        local.put("shownFeatures", List.of("posthog"));
        local.put("recentFeatures", List.of(new PreferenceSync.Recent("tokens", 2.5, 1_700_000_000_000L), new PreferenceSync.Recent("clipboard", 1.25, 1_700_000_100_000L)));
        local.put("noticeKinds", List.of("daily", "low", "sites"));
        local.put("dailyLine", 40);
        local.put("lowLine", 5);
        local.put("enhancedNotice", true);
        local.put("persistentNotice", true);
        local.put("tiboNotice", true);
        return local;
    }
    private static PreferenceSync.Sync ready(Manual time, Net net) { return new PreferenceSync.Sync(time, time, time, net, null); }
    @SuppressWarnings("unchecked")
    private static <T> T cast(Object v) { return (T) v; }
    private static List<String> keys(JSONObject o) throws Exception {
        List<String> out = new ArrayList<>();
        collectKeys(o, out);
        return out;
    }
    private static void collectKeys(Object v, List<String> out) throws Exception {
        if (v instanceof JSONObject o) { for (Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); out.add(k); collectKeys(o.get(k), out); } }
        else if (v instanceof JSONArray a) { for (int i = 0; i < a.length(); i++) collectKeys(a.get(i), out); }
    }

    /** Clock, scheduler and inline runner sharing one timeline. A task runs only once its delay has elapsed. */
    private static final class Manual implements PreferenceSync.Clock, PreferenceSync.Scheduler, PreferenceSync.Runner {
        long now; Runnable task; long due; boolean armed;
        public long millis() { return now; }
        public void schedule(Runnable task, long delayMs) { this.task = task; due = now + delayMs; armed = true; }
        public void cancel(Runnable task) { if (this.task == task) armed = false; }
        public void execute(Runnable task) { task.run(); }
        void advance(long ms) {
            now += ms;
            if (armed && now >= due) { armed = false; Runnable run = task; run.run(); }
        }
        /** Runs the scheduled task even when the injected clock has moved backwards. */
        void fire() {
            if (!armed) return;
            armed = false;
            Runnable run = task;
            run.run();
        }
    }
    private static final class Net implements PreferenceSync.Transport {
        int gets, puts, lastExpected, echoVersion; boolean failPut, echo;
        String echoAt;
        JSONObject lastPrefs;
        final List<JSONObject> sent = new ArrayList<>();
        PreferenceSync.Response nextGet = PreferenceSync.Response.fail(0), nextPut = PreferenceSync.Response.fail(500), afterPut;
        Runnable onFirstPut;
        public PreferenceSync.Response get() { gets++; return nextGet; }
        public PreferenceSync.Response put(int expectedVersion, JSONObject preferences) throws Exception {
            puts++; lastExpected = expectedVersion; lastPrefs = preferences; sent.add(preferences);
            if (failPut) throw new java.io.IOException("offline");
            if (onFirstPut != null && puts == 1) onFirstPut.run();
            PreferenceSync.Response r = echo ? PreferenceSync.Response.ok(echoVersion, echoAt, preferences) : nextPut;
            if (afterPut != null && puts > 1) r = afterPut;
            return r;
        }
    }
}
