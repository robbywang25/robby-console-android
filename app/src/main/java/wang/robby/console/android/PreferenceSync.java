package wang.robby.console.android;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Account-wide app settings. The document is what GET/POST /account/preferences stores; this class decides which
 * keys belong in it, how two copies merge, and when a change is uploaded. Pure Java: the activity only feeds it
 * values and a transport. Device-only state (session, cutout, quota caches, reminder bookkeeping, signature, avatar)
 * is never written into the document.
 */
final class PreferenceSync {
    private PreferenceSync() {}
    static final int SCHEMA = 1, MAX_BYTES = 16384;
    static final long QUIET_MS = 3_000L, POLL_MS = 60_000L;
    /**
     * Reserved document key: when each setting was last changed, {key: epoch ms}. Two phones merge key by key and
     * the later change wins, so a phone's old unsent edit never overwrites a newer one made on another phone.
     */
    static final String EDITED = "editedAt";
    /** Keys the account document may carry, in write order. Names avoid signature, avatar, token, secret, password. */
    static final List<String> KEYS = List.of(
        "board", "clocks", "calendarDays", "hiddenQuotaCards",
        "glassOn", "glassLevel", "statusBar", "islandLeft", "islandRight",
        "hiddenFeatures", "shownFeatures", "recentFeatures",
        "noticeKinds", "dailyLine", "lowLine", "creditsLine", "marketDays", "marketLine", "enhancedNotice", "persistentNotice", "tiboNotice");
    /**
     * Stored on the phone, never uploaded. Listed so a test can hand every one of them in and watch the document
     * stay clear of them: session, cutout geometry, 常亮展示, read caches, reminder runtime, signature and avatar.
     */
    static final List<String> LOCAL_ONLY = List.of(
        "accessToken", "refreshToken", "idToken", "session", "deviceCode",
        "signature", "avatar", "avatarEtag", "avatarPending",
        "tokenBoardRows", "upcoming", "savedAt",
        "cutoutLeft", "cutoutTop", "cutoutRight", "cutoutBottom",
        "holeLeft", "holeTop", "holeRight", "holeBottom",
        "islandLeftPx", "islandTopPx", "islandRightPx", "islandBottomPx",
        "screenWidth", "statusHeight", "alwaysOn", "alwaysOnSaver",
        "state", "pending", "seen", "lastCheckAt", "lastError", "primed",
        "creditBase", "creditFired", "marketReadAt",
        "enabledAt", "round", "pendingTest", "more",
        "hideStatusBar", "prefSyncVersion", "prefSyncUpdatedAt", "prefSyncBase", "prefSyncDirty", "prefSyncPending",
        "prefSyncAskNotify", "prefSyncEdited");

    static final class Recent {
        final String id; final double score; final long at;
        Recent(String id, double score, long at) { this.id = id; this.score = score; this.at = at; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Recent r)) return false;
            return id.equals(r.id) && at == r.at && Math.abs(score - r.score) < 0.00005;
        }
        @Override public int hashCode() { return Objects.hash(id, at); }
    }
    static final class Response {
        final int status, version; final String updatedAt; final JSONObject preferences;
        Response(int status, int version, String updatedAt, JSONObject preferences) {
            this.status = status; this.version = version; this.updatedAt = updatedAt; this.preferences = preferences;
        }
        static Response ok(int version, String updatedAt, JSONObject preferences) { return new Response(200, version, updatedAt, preferences == null ? new JSONObject() : preferences); }
        static Response fail(int status) { return new Response(status, 0, null, null); }
    }
    /** GET/POST body shape. A missing or foreign document is a failure, so the phone keeps what it has. */
    static Response parse(JSONObject body) {
        if (body == null || body.optInt("schemaVersion", -1) != SCHEMA || !(body.opt("version") instanceof Number n)) return Response.fail(0);
        if (n.doubleValue() != n.longValue() || n.longValue() < 0 || n.longValue() > Integer.MAX_VALUE) return Response.fail(0);
        if (body.has("preferences") && !body.isNull("preferences") && !(body.opt("preferences") instanceof JSONObject)) return Response.fail(0);
        Object at = body.opt("updatedAt");
        JSONObject prefs = body.optJSONObject("preferences");
        return Response.ok((int) n.longValue(), at instanceof String ? (String) at : null, prefs == null ? new JSONObject() : prefs);
    }
    /** 409 body: {"error":"version_conflict","current":<GET shape>}. */
    static Response conflict(JSONObject errorBody) {
        Response current = parse(errorBody == null ? null : errorBody.optJSONObject("current"));
        return current.status == 200 ? new Response(409, current.version, current.updatedAt, current.preferences) : Response.fail(409);
    }
    static JSONObject requestBody(int expectedVersion, JSONObject preferences) {
        JSONObject body = new JSONObject();
        put(body, "expectedVersion", expectedVersion);
        put(body, "preferences", preferences == null ? new JSONObject() : preferences);
        return body;
    }
    static boolean forbidden(String key) {
        String s = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return s.contains("signature") || s.contains("avatar") || s.contains("token") || s.contains("secret") || s.contains("password");
    }

    /** Upload document for a bag of values. Keys other than {@link #KEYS} are dropped, including every {@link #LOCAL_ONLY} key. */
    static JSONObject document(Map<String, ?> values) {
        JSONObject prefs = new JSONObject();
        for (String key : KEYS) {
            if (values == null || !values.containsKey(key) || values.get(key) == null) continue;
            Object n = normalize(key, values.get(key));
            if (n != null) put(prefs, key, toJson(key, n));
        }
        return fit(prefs);
    }
    /** {@link #document} plus the {@link #EDITED} times of the keys it carries. */
    static JSONObject document(Map<String, ?> values, Map<String, Long> edited) {
        JSONObject prefs = document(values);
        JSONObject times = new JSONObject();
        for (String key : KEYS) { Long at = edited == null ? null : edited.get(key); if (prefs.has(key) && at != null && at > 0) put(times, key, at); }
        if (times.length() > 0) put(prefs, EDITED, times);
        return fit(prefs);
    }
    /** The {@link #EDITED} times of a server document; unknown keys and values that are not positive whole numbers are left out. */
    static Map<String, Long> editedTimes(JSONObject prefs) {
        Map<String, Long> out = new LinkedHashMap<>();
        JSONObject times = prefs == null ? null : prefs.optJSONObject(EDITED);
        if (times == null) return out;
        for (String key : KEYS) {
            Object v = times.opt(key);
            if (v instanceof Number n && n.doubleValue() == n.longValue() && n.longValue() > 0) out.put(key, n.longValue());
        }
        return out;
    }
    static long epochMillis(String iso) {
        if (iso == null) return 0;
        try { return java.time.Instant.parse(iso).toEpochMilli(); } catch (RuntimeException e) { return 0; }
    }
    /** Recognized keys from a server document. Unknown keys and values of the wrong shape are left out. */
    static Map<String, Object> read(JSONObject prefs) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (prefs == null) return out;
        for (String key : KEYS) {
            if (!prefs.has(key) || prefs.isNull(key)) continue;
            Object raw = prefs.opt(key);
            if (depth(raw) > 4 || forbiddenTree(raw)) continue;
            Object n = normalize(key, raw);
            if (n != null) out.put(key, n);
        }
        return out;
    }
    /**
     * Sync fields out of the three preference files. {@code ui} is god-ui, {@code notices} god-notices, {@code tibo}
     * tibo-watch. Anything else in those maps (rows cache, signature, seen, cutout) is ignored.
     */
    static Map<String, Object> collect(Map<String, ?> ui, Map<String, ?> notices, Map<String, ?> tibo) {
        Map<String, Object> raw = new LinkedHashMap<>();
        if (ui != null) {
            copy(raw, "board", ui, "dashboard");
            copy(raw, "clocks", ui, Dashboard.CLOCKS);
            copy(raw, "calendarDays", ui, Dashboard.CALENDAR_DAYS);
            copy(raw, "hiddenQuotaCards", ui, Dashboard.HIDDEN_TOKENS);
            copy(raw, "glassOn", ui, ConsoleTheme.GLASS_ON);
            copy(raw, "glassLevel", ui, ConsoleTheme.GLASS_LEVEL);
            copy(raw, "statusBar", ui, StatusBarStyle.KEY);
            if (!ui.containsKey(StatusBarStyle.KEY) && Boolean.TRUE.equals(ui.get(StatusBarStyle.LEGACY))) raw.put("statusBar", StatusBarStyle.HIDE_UP);
            copy(raw, "islandLeft", ui, StatusBarStyle.LEFT_KEY);
            copy(raw, "islandRight", ui, StatusBarStyle.RIGHT_KEY);
            copy(raw, "hiddenFeatures", ui, FeatureVisibility.HIDDEN);
            copy(raw, "shownFeatures", ui, FeatureVisibility.SHOWN);
            copy(raw, "recentFeatures", ui, "featureUsage");
        }
        if (notices != null) {
            copy(raw, "noticeKinds", notices, "on");
            copy(raw, "dailyLine", notices, "daily");
            copy(raw, "lowLine", notices, "low");
            copy(raw, "creditsLine", notices, "creditsLine");
            copy(raw, "marketDays", notices, "marketDays");
            copy(raw, "marketLine", notices, "marketLine");
            copy(raw, "enhancedNotice", notices, "enhanced");
            copy(raw, "persistentNotice", notices, "persistent");
        }
        if (tibo != null) copy(raw, "tiboNotice", tibo, "enabled");
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            Object n = normalize(e.getKey(), e.getValue());
            if (n != null) out.put(e.getKey(), n);
        }
        return out;
    }
    /**
     * 「设置已同步到账号 · 刚刚」when this login can store settings; 「设置从账号读取 · 本机修改不上传」when it can only
     * pull (a Google login, or a session from before the profile write scope); 「设置仅保存在本机」when signed out.
     */
    static String statusLine(boolean onAccount, boolean canWrite, String updatedAt, long nowMs) {
        if (!onAccount) return "设置仅保存在本机";
        if (!canWrite) return "设置从账号读取 · 本机修改不上传";
        String when = updatedAt == null || updatedAt.isEmpty() ? "尚未同步" : AccountCenter.ago(updatedAt, java.time.Instant.ofEpochMilli(nowMs));
        return "设置已同步到账号 · " + when;
    }
    /** Second line under {@link #statusLine}: how a read-only login gets its changes onto the account. */
    static String statusDetail(boolean onAccount, boolean canWrite, boolean federated) {
        if (!onAccount || canWrite) return "";
        return federated ? "Google 登录只读，用 Passkey 登录后同步" : "重新登录后，本机修改会同步到账号";
    }
    /**
     * True when a server document turns on a reminder that was off on this phone: a new kind in {@code noticeKinds},
     * or {@code tiboNotice} going from off to on. {@code before} is {@link #collect} from just before the write. The
     * phone that flipped the switch asked for notification permission; this one has not, so the activity must.
     */
    static boolean turnsOnReminder(Map<String, ?> before, Map<String, ?> changed) {
        if (changed == null) return false;
        if (Boolean.TRUE.equals(changed.get("tiboNotice")) && !Boolean.TRUE.equals(before == null ? null : before.get("tiboNotice"))) return true;
        if (!(changed.get("noticeKinds") instanceof Collection<?> kinds)) return false;
        Object had = before == null ? null : before.get("noticeKinds");
        for (Object kind : kinds) if (!(had instanceof Collection<?> old) || !old.contains(kind)) return true;
        return false;
    }

    interface Clock { long millis(); }
    interface Scheduler { void schedule(Runnable task, long delayMs); void cancel(Runnable task); }
    interface Runner { void execute(Runnable task); }
    interface Transport { Response get() throws Exception; Response put(int expectedVersion, JSONObject preferences) throws Exception; }
    static final class Snapshot {
        final int version; final String updatedAt, baseJson; final Set<String> dirty; final boolean pending; final Map<String, Long> edited;
        Snapshot(int version, String updatedAt, String baseJson, Set<String> dirty, boolean pending, Map<String, Long> edited) {
            this.version = version; this.updatedAt = updatedAt; this.baseJson = baseJson; this.dirty = dirty; this.pending = pending; this.edited = edited;
        }
    }
    interface Listener { void applied(Map<String, Object> changed); void persisted(Snapshot snap); }

    /**
     * One phone's copy. Edits wait {@link #QUIET_MS} and share a single in-flight request. A 409 is retried once,
     * server document first, with this phone's dirty keys on top. 429 and network failures keep the local values
     * and the pending flag for the next foreground or edit.
     */
    static final class Sync {
        private final Clock clock; private final Scheduler scheduler; private final Runner runner; private final Transport transport; private final Listener listener;
        private final Runnable fire = this::onFire;
        private final Map<String, Object> local = new LinkedHashMap<>();
        private final Set<String> dirty = new LinkedHashSet<>();
        /** When each key's value on this phone was set: by an edit here, or by the server copy it was taken from. */
        private final Map<String, Long> edited = new LinkedHashMap<>();
        private JSONObject base = new JSONObject();
        private int knownVersion; private String updatedAt;
        private boolean pending, online, canWrite, inFlight, followUp, pullQueued;
        private long dueAt;
        /** Bumped on sign-out so a response already in flight cannot write the old account's cursor back. */
        private int epoch;

        Sync(Clock clock, Scheduler scheduler, Runner runner, Transport transport, Listener listener) {
            this.clock = clock; this.scheduler = scheduler; this.runner = runner; this.transport = transport; this.listener = listener;
        }
        synchronized void restore(int version, String updatedAt, JSONObject base, Set<String> dirtyKeys, boolean pendingFlag, Map<String, ?> collected) {
            restore(version, updatedAt, base, dirtyKeys, pendingFlag, collected, Map.of());
        }
        /** A dirty key saved without a time (before 0.33.1) counts as older than any server copy. */
        synchronized void restore(int version, String updatedAt, JSONObject base, Set<String> dirtyKeys, boolean pendingFlag, Map<String, ?> collected, Map<String, Long> editedTimes) {
            knownVersion = Math.max(0, version); this.updatedAt = updatedAt == null || updatedAt.isEmpty() ? null : updatedAt;
            this.base = base == null ? new JSONObject() : document(read(base));
            dirty.clear(); if (dirtyKeys != null) for (String k : dirtyKeys) if (KEYS.contains(k)) dirty.add(k);
            local.clear(); if (collected != null) for (String key : KEYS) if (collected.containsKey(key) && collected.get(key) != null) {
                Object n = normalize(key, collected.get(key)); if (n != null) local.put(key, n);
            }
            edited.clear(); if (editedTimes != null) for (Map.Entry<String, Long> e : editedTimes.entrySet()) if (KEYS.contains(e.getKey()) && e.getValue() != null && e.getValue() > 0) edited.put(e.getKey(), e.getValue());
            pending = pendingFlag || !dirty.isEmpty();
        }
        synchronized void setReady(boolean online, boolean canWrite) {
            this.online = online; this.canWrite = canWrite;
            if (!online || !canWrite) scheduler.cancel(fire);
        }
        /**
         * Logout. The sync cursor goes back to "this phone has not synced": version 0, empty base, nothing dirty,
         * nothing pending. Board, appearance and the other local values stay, so the next account can pull over them.
         */
        synchronized void signOut() {
            epoch++;
            knownVersion = 0; updatedAt = null; base = new JSONObject();
            dirty.clear(); edited.clear(); pending = false; followUp = false; pullQueued = false;
            scheduler.cancel(fire);
            persist();
        }
        synchronized void edit(String key, Object value) {
            if (!KEYS.contains(key)) return;
            if (value == null) { if (!local.containsKey(key)) return; local.remove(key); }
            else { Object n = normalize(key, value); if (n == null || Objects.equals(local.get(key), n)) return; local.put(key, n); }
            dirty.add(key); edited.put(key, Math.max(clock.millis(), 1L)); pending = true;
            if (online && canWrite) { if (inFlight) followUp = true; else arm(); }
            persist();
        }
        void foreground() {
            int ticket;
            synchronized (this) {
                if (!online) return;
                if (inFlight) { pullQueued = true; return; }
                inFlight = true; scheduler.cancel(fire); ticket = epoch;
            }
            runner.execute(() -> get(ticket));
        }
        synchronized int version() { return knownVersion; }
        synchronized boolean pending() { return pending; }
        synchronized String updatedAt() { return updatedAt; }
        synchronized Map<String, Object> local() { return new LinkedHashMap<>(local); }
        synchronized Set<String> dirty() { return new LinkedHashSet<>(dirty); }
        synchronized Map<String, Long> edited() { return new LinkedHashMap<>(edited); }

        private void arm() { dueAt = clock.millis() + QUIET_MS; scheduler.cancel(fire); scheduler.schedule(fire, QUIET_MS); }
        private void onFire() {
            int expect, ticket; JSONObject doc;
            synchronized (this) {
                if (!online || !canWrite || inFlight || !pending) return;
                long now = clock.millis();
                // The scheduler waits in real time; the injected clock may have jumped backwards. Reschedule
                // from the new clock instead of dropping the upload.
                if (now < dueAt) {
                    long wait = dueAt - now;
                    if (wait > QUIET_MS) arm(); else scheduler.schedule(fire, wait);
                    return;
                }
                inFlight = true; followUp = false; ticket = epoch; expect = knownVersion; doc = uploadDocument();
            }
            runner.execute(() -> put(ticket, expect, doc, false));
        }
        private void get(int ticket) {
            Response r; try { r = transport.get(); } catch (Exception e) { r = Response.fail(0); }
            Job job; synchronized (this) { job = ticket == epoch ? finishGet(r) : abandoned(); }
            dispatch(job);
        }
        private void put(int ticket, int expect, JSONObject doc, boolean retried) {
            Response r; try { r = transport.put(expect, doc); } catch (Exception e) { r = Response.fail(0); }
            Job job; synchronized (this) { job = ticket == epoch ? finishPut(r, doc, retried) : abandoned(); }
            dispatch(job);
        }
        private Job finishGet(Response r) {
            if (r.status != 200 || r.preferences == null) return endFlight(null);
            if (r.version == 0) {
                if (!local.isEmpty()) pending = true;
                if (!local.isEmpty() && canWrite) return held(Job.put(epoch, 0, uploadDocument(), false, null));
                return endFlight(null);
            }
            if (r.version > knownVersion) {
                Map<String, Object> changed = applyRemote(r.preferences, r.updatedAt);
                knownVersion = r.version; updatedAt = r.updatedAt; base = document(read(r.preferences));
                pending = !dirty.isEmpty();
                if (pending && canWrite) return held(Job.put(epoch, knownVersion, uploadDocument(), false, changed));
                return endFlight(changed);
            }
            if (pending && canWrite) return held(Job.put(epoch, knownVersion, uploadDocument(), false, null));
            return endFlight(null);
        }
        private Job finishPut(Response r, JSONObject sent, boolean retried) {
            if (r.status == 200 && r.preferences != null) {
                Map<String, Object> changed = applyRemote(r.preferences, r.updatedAt);
                knownVersion = r.version; updatedAt = r.updatedAt; base = document(read(r.preferences));
                adoptTrim(sent, changed); retainDirty(sent); pending = !dirty.isEmpty();
                return endFlight(changed);
            }
            if (r.status == 409 && r.preferences != null && !retried) {
                Map<String, Object> changed = applyRemote(r.preferences, r.updatedAt);
                knownVersion = r.version; if (r.updatedAt != null) updatedAt = r.updatedAt;
                base = document(read(r.preferences)); pending = true;
                return held(Job.put(epoch, knownVersion, conflictDocument(), true, changed));
            }
            pending = true;
            return endFlight(null);
        }
        /** The in-flight request belonged to a cursor that sign-out already replaced. */
        private Job abandoned() {
            inFlight = false;
            if (pullQueued && online) { pullQueued = false; inFlight = true; persist(); return Job.get(epoch, null); }
            persist();
            return Job.done(null);
        }
        /** A follow-up request keeps the in-flight slot; the snapshot is saved before it starts. */
        private Job held(Job job) { persist(); return job; }
        private Job endFlight(Map<String, Object> changed) {
            inFlight = false;
            if (pullQueued && online) { pullQueued = false; inFlight = true; persist(); return Job.get(epoch, changed); }
            if (followUp && pending && online && canWrite) { followUp = false; arm(); }
            else followUp = false;
            persist();
            return Job.done(changed);
        }
        private void persist() { if (listener != null) listener.persisted(snapshot()); }
        private void dispatch(Job job) {
            if (listener != null && job.changed != null && !job.changed.isEmpty()) listener.applied(job.changed);
            if (job.kind == Job.PUT) runner.execute(() -> put(job.ticket, job.expect, job.doc, job.retried));
            else if (job.kind == Job.GET) runner.execute(() -> get(job.ticket));
        }
        /** Ordinary upload: last server base, then every value stored on this phone. */
        private JSONObject uploadDocument() {
            Map<String, Object> merged = new LinkedHashMap<>(read(base));
            merged.putAll(local);
            for (String key : dirty) if (!local.containsKey(key)) merged.remove(key);
            return document(merged, edited);
        }
        /** 409 retry: server document, plus only the keys changed here since the last sync. */
        private JSONObject conflictDocument() {
            Map<String, Object> merged = new LinkedHashMap<>(read(base));
            for (String key : dirty) {
                if (local.containsKey(key)) merged.put(key, local.get(key));
                else merged.remove(key);
            }
            return document(merged, edited);
        }
        /** A trimmed recent-use list is the value this phone keeps, so the upload counts as synced. */
        private void adoptTrim(JSONObject sent, Map<String, Object> changed) {
            Object have = local.get("recentFeatures");
            if (!(have instanceof List<?> rows) || rows.isEmpty()) return;
            Object kept = read(sent).get("recentFeatures");
            if (Objects.equals(have, kept)) return;
            int keptSize = kept instanceof List<?> list ? list.size() : 0;
            if (keptSize >= rows.size()) return;
            if (kept == null) local.remove("recentFeatures"); else local.put("recentFeatures", kept);
            changed.put("recentFeatures", kept == null ? List.of() : kept);
        }
        /**
         * Takes the server copy of each key unless this phone changed that key later and has not uploaded it yet.
         * A key's server time is its {@link #EDITED} entry, or the document's updatedAt for documents without one.
         */
        private Map<String, Object> applyRemote(JSONObject prefs, String remoteUpdatedAt) {
            Map<String, Object> changed = new LinkedHashMap<>();
            Map<String, Long> times = editedTimes(prefs);
            long fallback = epochMillis(remoteUpdatedAt);
            for (Map.Entry<String, Object> e : read(prefs).entrySet()) {
                String key = e.getKey();
                long remote = times.getOrDefault(key, fallback);
                if (dirty.contains(key) && edited.getOrDefault(key, 0L) > remote) continue;
                dirty.remove(key);
                if (remote > 0) edited.put(key, remote);
                if (Objects.equals(local.get(key), e.getValue())) continue;
                local.put(key, e.getValue()); changed.put(key, e.getValue());
            }
            return changed;
        }
        private void retainDirty(JSONObject sent) {
            Map<String, Object> was = read(sent); Set<String> keep = new LinkedHashSet<>();
            for (String key : dirty) if (!Objects.equals(local.get(key), was.get(key))) keep.add(key);
            dirty.clear(); dirty.addAll(keep);
        }
        private Snapshot snapshot() {
            return new Snapshot(knownVersion, updatedAt, base.toString(), new LinkedHashSet<>(dirty), pending, new LinkedHashMap<>(edited));
        }
    }

    /** Pulls every {@link #POLL_MS} while the app is in front, so a change made on another phone arrives without leaving the app. */
    static final class Poller {
        private final Scheduler scheduler; private final Runnable pull;
        private final Runnable tick = this::tick;
        private boolean running;
        Poller(Scheduler scheduler, Runnable pull) { this.scheduler = scheduler; this.pull = pull; }
        void start() { if (running) return; running = true; scheduler.schedule(tick, POLL_MS); }
        void stop() { running = false; scheduler.cancel(tick); }
        boolean running() { return running; }
        private void tick() { if (!running) return; pull.run(); scheduler.schedule(tick, POLL_MS); }
    }

    private static final class Job {
        static final int DONE = 0, PUT = 1, GET = 2;
        final int kind, ticket, expect; final JSONObject doc; final boolean retried; final Map<String, Object> changed;
        private Job(int kind, int ticket, int expect, JSONObject doc, boolean retried, Map<String, Object> changed) {
            this.kind = kind; this.ticket = ticket; this.expect = expect; this.doc = doc; this.retried = retried; this.changed = changed;
        }
        static Job done(Map<String, Object> changed) { return new Job(DONE, 0, 0, null, false, changed); }
        static Job get(int ticket, Map<String, Object> changed) { return new Job(GET, ticket, 0, null, false, changed); }
        static Job put(int ticket, int expect, JSONObject doc, boolean retried, Map<String, Object> changed) { return new Job(PUT, ticket, expect, doc, retried, changed); }
    }

    private static void copy(Map<String, Object> raw, String key, Map<String, ?> from, String name) { if (from.containsKey(name)) raw.put(key, from.get(name)); }
    private static void put(JSONObject o, String key, Object value) {
        if (forbidden(key)) throw new IllegalStateException("refusing " + key);
        try { o.put(key, value); } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
    }
    private static int depth(Object v) {
        int m = 0;
        if (v instanceof JSONObject o) { m = 1; for (Iterator<String> it = o.keys(); it.hasNext(); ) m = Math.max(m, 1 + depth(o.opt(it.next()))); }
        else if (v instanceof JSONArray a) { m = 1; for (int i = 0; i < a.length(); i++) m = Math.max(m, 1 + depth(a.opt(i))); }
        return m;
    }
    private static boolean forbiddenTree(Object v) {
        if (v instanceof JSONObject o) { for (Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); if (forbidden(k) || forbiddenTree(o.opt(k))) return true; } }
        else if (v instanceof JSONArray a) { for (int i = 0; i < a.length(); i++) if (forbiddenTree(a.opt(i))) return true; }
        return false;
    }
    private static Object normalize(String key, Object raw) {
        if (raw == null) return null;
        return switch (key) {
            case "board" -> cards(raw);
            case "clocks" -> clocks(raw);
            case "calendarDays" -> choice(raw, Dashboard.CALENDAR_RANGES);
            case "hiddenQuotaCards" -> ids(raw, "[a-z0-9-]{1,32}", 64);
            case "glassOn", "enhancedNotice", "persistentNotice", "tiboNotice" -> flag(raw);
            case "glassLevel" -> range(raw, 0, 100);
            case "statusBar" -> token(raw, StatusBarStyle.ALL);
            case "islandLeft", "islandRight" -> token(raw, StatusBarStyle.ITEMS);
            case "hiddenFeatures", "shownFeatures" -> ids(raw, "[a-z0-9-]{1,40}", 100);
            case "recentFeatures" -> recent(raw);
            case "noticeKinds" -> kinds(raw);
            case "dailyLine" -> choice(raw, boxes(Notices.DAILY_CHOICES));
            case "lowLine" -> choice(raw, boxes(Notices.LOW_CHOICES));
            case "creditsLine" -> choice(raw, boxes(Notices.CREDIT_CHOICES));
            case "marketDays" -> choice(raw, boxes(Notices.MARKET_DAY_CHOICES));
            case "marketLine" -> choice(raw, boxes(Notices.MARKET_LINE_CHOICES));
            default -> null;
        };
    }
    private static Object toJson(String key, Object normalized) {
        return switch (key) {
            case "clocks" -> clockJson((List<?>) normalized);
            case "recentFeatures" -> recentJson((List<?>) normalized);
            case "board", "hiddenQuotaCards", "hiddenFeatures", "shownFeatures", "noticeKinds" -> new JSONArray((Collection<?>) normalized);
            default -> normalized;
        };
    }
    private static List<String> cards(Object raw) {
        String joined = joined(raw);
        if (joined == null) return null;
        return Dashboard.parse(joined);
    }
    private static List<String> clocks(Object raw) {
        List<Dashboard.Clock> out = new ArrayList<>();
        if (raw instanceof String s) out.addAll(Dashboard.clocks(s));
        else if (raw instanceof JSONArray a) { for (int i = 0; i < a.length() && out.size() < Dashboard.MAX_CLOCKS; i++) addClock(out, a.opt(i)); }
        else if (raw instanceof Collection<?> c) { for (Object item : c) { if (out.size() >= Dashboard.MAX_CLOCKS) break; addClock(out, item); } }
        else return null;
        List<String> lines = new ArrayList<>();
        for (Dashboard.Clock c : out) lines.add(c.name + "|" + c.zone);
        return lines;
    }
    private static void addClock(List<Dashboard.Clock> out, Object item) {
        String name = null, zone = null;
        if (item instanceof String s) { int bar = s.lastIndexOf('|'); if (bar <= 0) return; name = s.substring(0, bar); zone = s.substring(bar + 1).trim(); }
        else if (item instanceof Dashboard.Clock c) { name = c.name; zone = c.zone; }
        else if (item instanceof JSONObject o) { if (forbiddenTree(o)) return; name = o.optString("name", ""); zone = o.optString("zone", ""); }
        else return;
        name = Dashboard.clockName(name);
        if (name != null && Dashboard.validZone(zone)) out.add(new Dashboard.Clock(name, zone));
    }
    private static JSONArray clockJson(List<?> lines) {
        JSONArray a = new JSONArray();
        for (Object line : lines) {
            String s = String.valueOf(line); int bar = s.lastIndexOf('|'); if (bar <= 0) continue;
            JSONObject o = new JSONObject(); put(o, "name", s.substring(0, bar)); put(o, "zone", s.substring(bar + 1)); a.put(o);
        }
        return a;
    }
    private static List<Recent> recent(Object raw) {
        List<Recent> out = new ArrayList<>();
        if (raw instanceof String s) { for (Map.Entry<String, FeatureUsage.Entry> e : FeatureUsage.parse(s).entrySet()) out.add(new Recent(e.getKey(), round4(e.getValue().score), e.getValue().last)); }
        else if (raw instanceof JSONArray a) { for (int i = 0; i < a.length(); i++) addRecent(out, a.opt(i)); }
        else if (raw instanceof Collection<?> c) { for (Object item : c) addRecent(out, item); }
        else return null;
        return out;
    }
    private static void addRecent(List<Recent> out, Object item) {
        if (item instanceof Recent r) { if (r.id.matches("[a-z0-9-]{1,40}") && r.score > 0 && r.score < 1e6 && r.at > 0) out.add(new Recent(r.id, round4(r.score), r.at)); return; }
        if (!(item instanceof JSONObject o) || forbiddenTree(o)) return;
        String id = o.optString("id", ""); Object score = o.opt("score"), at = o.opt("at");
        if (!id.matches("[a-z0-9-]{1,40}") || !(score instanceof Number sn) || !(at instanceof Number an)) return;
        double sc = sn.doubleValue(); long when = an.longValue();
        if (sc > 0 && sc < 1e6 && sc == sn.doubleValue() && when > 0 && an.doubleValue() == when) out.add(new Recent(id, round4(sc), when));
    }
    private static JSONArray recentJson(List<?> items) {
        JSONArray a = new JSONArray();
        for (Object item : items) {
            Recent r = (Recent) item; JSONObject o = new JSONObject();
            put(o, "id", r.id); put(o, "score", r.score); put(o, "at", r.at); a.put(o);
        }
        return a;
    }
    private static double round4(double score) { return Math.round(score * 10_000d) / 10_000d; }
    private static List<String> ids(Object raw, String pattern, int max) {
        List<String> out = new ArrayList<>();
        if (raw instanceof String s) { for (String id : s.split(",")) addId(out, id.trim(), pattern, max); }
        else if (raw instanceof JSONArray a) { for (int i = 0; i < a.length(); i++) addId(out, String.valueOf(a.opt(i)), pattern, max); }
        else if (raw instanceof Collection<?> c) { for (Object item : c) addId(out, String.valueOf(item), pattern, max); }
        else return null;
        return out;
    }
    private static void addId(List<String> out, String id, String pattern, int max) { if (out.size() < max && id.matches(pattern) && !out.contains(id)) out.add(id); }
    private static List<String> kinds(Object raw) {
        List<String> ids = ids(raw, "[a-z0-9-]{1,40}", 20);
        if (ids == null) return null;
        List<String> out = new ArrayList<>();
        for (String kind : Notices.KINDS) if (ids.contains(kind)) out.add(kind);
        return out;
    }
    private static Boolean flag(Object raw) { return raw instanceof Boolean b ? b : null; }
    private static Integer range(Object raw, int min, int max) {
        Integer n = whole(raw); return n != null && n >= min && n <= max ? n : null;
    }
    private static Integer choice(Object raw, List<Integer> allowed) {
        Integer n = whole(raw); return n != null && allowed.contains(n) ? n : null;
    }
    private static Integer whole(Object raw) {
        if (raw instanceof String s) { try { raw = Integer.valueOf(s.trim()); } catch (NumberFormatException e) { return null; } }
        if (!(raw instanceof Number n) || n.doubleValue() != n.longValue()) return null;
        long v = n.longValue(); return v < Integer.MIN_VALUE || v > Integer.MAX_VALUE ? null : (int) v;
    }
    private static String token(Object raw, List<String> allowed) {
        if (!(raw instanceof String s)) return null;
        return allowed.contains(s) ? s : null;
    }
    private static List<Integer> boxes(int[] values) { List<Integer> out = new ArrayList<>(); for (int v : values) out.add(v); return out; }
    private static String joined(Object raw) {
        if (raw instanceof String s) return s;
        if (raw instanceof JSONArray a) { List<String> parts = new ArrayList<>(); for (int i = 0; i < a.length(); i++) parts.add(String.valueOf(a.opt(i))); return String.join(",", parts); }
        if (raw instanceof Collection<?> c) { List<String> parts = new ArrayList<>(); for (Object item : c) parts.add(String.valueOf(item)); return String.join(",", parts); }
        return null;
    }
    private static boolean fits(JSONObject prefs) {
        return requestBody(Integer.MAX_VALUE, prefs).toString().getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }
    /** Drops the oldest recent-use rows until the POST body fits the server's byte cap. */
    private static JSONObject fit(JSONObject prefs) {
        if (fits(prefs)) return prefs;
        JSONArray recent = prefs.optJSONArray("recentFeatures");
        if (recent == null) return prefs;
        List<JSONObject> items = new ArrayList<>();
        for (int i = 0; i < recent.length(); i++) { JSONObject o = recent.optJSONObject(i); if (o != null) items.add(o); }
        items.sort(Comparator.comparingLong(o -> o.optLong("at", Long.MAX_VALUE)));
        int lo = 0, hi = items.size(), best = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (fits(withRecent(prefs, items.subList(items.size() - mid, items.size())))) { best = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        return withRecent(prefs, items.subList(items.size() - best, items.size()));
    }
    private static JSONObject withRecent(JSONObject prefs, List<JSONObject> kept) {
        JSONObject copy; try { copy = new JSONObject(prefs.toString()); } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
        if (kept.isEmpty()) copy.remove("recentFeatures");
        else { JSONArray a = new JSONArray(); for (JSONObject item : kept) a.put(item); put(copy, "recentFeatures", a); }
        return copy;
    }
}
