package wang.robby.console.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.ref.WeakReference;
import wang.robby.console.android.auth.AuthSession;

/**
 * The activity's side of {@link PreferenceSync}: read the three preference files, push and pull through
 * {@link MobileApi}, and write a server document back. Preview builds and signed-out phones never call the network.
 */
final class PreferenceSyncHub {
    private PreferenceSyncHub() {}
    private static final Object LOCK = new Object();
    private static PreferenceSync.Sync sync;
    private static WeakReference<MainActivity> ui = new WeakReference<>(null);
    private static Handler main;
    private static ExecutorService worker;
    /** Writes of a server document. Touched only while holding {@link #LOCK}. */
    private static final AtomicInteger applying = new AtomicInteger();
    /** Keys noted while a server document was being written; replayed from the preference files afterwards. */
    private static final Set<String> skipped = new LinkedHashSet<>();
    private static PreferenceSync.Poller poller;

    static void attach(MainActivity activity) { ui = new WeakReference<>(activity); }
    /** While the app is in front, pull every minute so changes made on another phone show up here. */
    static void startPolling(Context context) {
        if (PreviewMode.isBuild()) return;
        Context app = context.getApplicationContext();
        PreferenceSync.Poller p;
        synchronized (LOCK) {
            if (main == null) main = new Handler(Looper.getMainLooper());
            if (poller == null) poller = new PreferenceSync.Poller(new PreferenceSync.Scheduler() {
                public void schedule(Runnable task, long delayMs) { main.postDelayed(task, delayMs); }
                public void cancel(Runnable task) { main.removeCallbacks(task); }
            }, () -> foreground(app));
            p = poller;
        }
        p.start();
    }
    static void stopPolling() { PreferenceSync.Poller p; synchronized (LOCK) { p = poller; } if (p != null) p.stop(); }
    /** A local change to one sync key. A change during a server write is kept and applied when that write ends. */
    static void note(Context context, String key) {
        if (PreviewMode.isBuild()) return;
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (applying.get() > 0) { skipped.add(key); return; }
            PreferenceSync.Sync engine = engine(app);
            boolean logged = logged(app);
            engine.setReady(logged, logged && writer(app));
            engine.edit(key, current(app).get(key));
        }
    }
    /** Login, cold start and return to the foreground: pull, and upload when the account has nothing yet. */
    static void foreground(Context context) {
        if (PreviewMode.isBuild()) return;
        Context app = context.getApplicationContext();
        PreferenceSync.Sync engine = engine(app);
        boolean logged = logged(app);
        if (!logged) { engine.setReady(false, false); engine.signOut(); return; }
        engine.setReady(true, writer(app));
        engine.foreground();
    }
    /** False for a read-only login (Google, or a session older than the profile write scope): pulls, never uploads. */
    static boolean canWrite(Context context) { return writer(context.getApplicationContext()); }
    /**
     * True once after a server document switched on a reminder that was off here. The flag stays in god-ui until an
     * activity is in front to ask for notification permission, then this clears it.
     */
    static boolean takeNotifyAsk(Context context) {
        SharedPreferences ui = context.getApplicationContext().getSharedPreferences("god-ui", Context.MODE_PRIVATE);
        if (!ui.getBoolean(ASK_NOTIFY, false)) return false;
        ui.edit().remove(ASK_NOTIFY).apply();
        return true;
    }
    private static final String ASK_NOTIFY = "prefSyncAskNotify";
    static String updatedAt(Context context) {
        String value = context.getApplicationContext().getSharedPreferences("god-ui", Context.MODE_PRIVATE).getString("prefSyncUpdatedAt", null);
        return value == null || value.isEmpty() ? null : value;
    }

    private static PreferenceSync.Sync engine(Context app) {
        synchronized (LOCK) {
            if (sync != null) return sync;
            if (main == null) main = new Handler(Looper.getMainLooper());
            if (worker == null) worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "pref-sync"); t.setDaemon(true); return t; });
            sync = new PreferenceSync.Sync(System::currentTimeMillis, new PreferenceSync.Scheduler() {
                public void schedule(Runnable task, long delayMs) { main.postDelayed(task, delayMs); }
                public void cancel(Runnable task) { main.removeCallbacks(task); }
            }, worker::execute, new Api(app), new Store(app));
            SharedPreferences ui = app.getSharedPreferences("god-ui", Context.MODE_PRIVATE);
            JSONObject base = new JSONObject();
            try { base = new JSONObject(ui.getString("prefSyncBase", "{}")); } catch (org.json.JSONException e) { base = new JSONObject(); }
            Set<String> dirty = new LinkedHashSet<>();
            for (String key : ui.getString("prefSyncDirty", "").split(",")) if (!key.isEmpty()) dirty.add(key);
            sync.restore(ui.getInt("prefSyncVersion", 0), ui.getString("prefSyncUpdatedAt", null), base, dirty, ui.getBoolean("prefSyncPending", false), current(app), edited(ui.getString("prefSyncEdited", "{}")));
            return sync;
        }
    }
    private static boolean logged(Context app) { try { return new AuthSession(app).hasSession(); } catch (RuntimeException e) { return false; } }
    private static boolean writer(Context app) { try { return new AuthSession(app).hasScope(AuthSession.PROFILE_WRITE); } catch (RuntimeException e) { return false; } }
    private static Map<String, Object> current(Context app) {
        return PreferenceSync.collect(app.getSharedPreferences("god-ui", Context.MODE_PRIVATE).getAll(), NoticeCenter.prefs(app).getAll(), TiboWatch.prefs(app).getAll());
    }

    private static final class Api implements PreferenceSync.Transport {
        private final Context app;
        Api(Context app) { this.app = app; }
        public PreferenceSync.Response get() throws Exception { return call(null, 0); }
        public PreferenceSync.Response put(int expectedVersion, JSONObject preferences) throws Exception { return call(preferences, expectedVersion); }
        private PreferenceSync.Response call(JSONObject preferences, int expectedVersion) throws Exception {
            AuthSession auth = new AuthSession(app);
            if (!auth.hasSession()) return PreferenceSync.Response.fail(401);
            if (preferences != null && !auth.hasScope(AuthSession.PROFILE_WRITE)) return PreferenceSync.Response.fail(403);
            MobileApi api = new MobileApi(auth);
            try {
                JSONObject body = preferences == null ? api.get(AccountCenter.PREFERENCES) : api.post(AccountCenter.PREFERENCES, PreferenceSync.requestBody(expectedVersion, preferences));
                return PreferenceSync.parse(body);
            } catch (MobileApi.ApiError error) { return error.status == 409 ? PreferenceSync.conflict(error.body) : PreferenceSync.Response.fail(error.status); }
        }
    }
    private static final class Store implements PreferenceSync.Listener {
        private final Context app;
        private int seenVersion; private String seenAt;
        Store(Context app) {
            this.app = app;
            SharedPreferences saved = app.getSharedPreferences("god-ui", Context.MODE_PRIVATE);
            seenVersion = saved.getInt("prefSyncVersion", 0);
            seenAt = saved.getString("prefSyncUpdatedAt", "");
            if (seenAt == null) seenAt = "";
        }
        public void applied(Map<String, Object> changed) {
            synchronized (LOCK) { applying.incrementAndGet(); }
            try { write(app, changed); }
            finally {
                List<String> replay;
                synchronized (LOCK) {
                    applying.decrementAndGet();
                    replay = new ArrayList<>(skipped);
                    skipped.clear();
                }
                if (!replay.isEmpty()) replay(app, replay);
            }
            main.post(() -> { MainActivity activity = ui.get(); if (activity != null) activity.onPreferencesApplied(); });
        }
        public void persisted(PreferenceSync.Snapshot snap) {
            String at = snap.updatedAt == null ? "" : snap.updatedAt;
            boolean moved = snap.version != seenVersion || !at.equals(seenAt);
            seenVersion = snap.version; seenAt = at;
            app.getSharedPreferences("god-ui", Context.MODE_PRIVATE).edit()
                .putInt("prefSyncVersion", snap.version)
                .putString("prefSyncUpdatedAt", at)
                .putString("prefSyncBase", snap.baseJson)
                .putString("prefSyncDirty", String.join(",", snap.dirty))
                .putBoolean("prefSyncPending", snap.pending)
                .putString("prefSyncEdited", new JSONObject(snap.edited).toString()).apply();
            // A sync that changed no setting still moves the "synced at" line.
            if (moved && main != null) main.post(() -> { MainActivity activity = ui.get(); if (activity != null) activity.onPreferencesApplied(); });
        }
    }

    /** Re-reads keys the person changed while a server document was landing, and sends those values on. */
    private static void replay(Context app, List<String> keys) {
        synchronized (LOCK) {
            if (sync == null) return;
            boolean logged = logged(app);
            sync.setReady(logged, logged && writer(app));
            for (String key : keys) sync.edit(key, current(app).get(key));
        }
    }

    /** A key the person changed while this write was in progress, or already edited locally since the pull. */
    private static boolean blocked(String key) {
        return skipped.contains(key) || (sync != null && sync.dirty().contains(key));
    }
    /** Writes server values into the preference files and restarts the reminder service when a switch moved. */
    private static void write(Context c, Map<String, Object> changed) {
        // Sampled before the lock so an edit that landed in the preference files during this write is visible.
        Map<String, Object> before = current(c);
        boolean tiboInChange = changed.containsKey("tiboNotice");
        boolean uiTouch = false, noticeTouch = false;
        synchronized (LOCK) {
            List<Map.Entry<String, Object>> plan = new ArrayList<>();
            Map<String, Object> now = current(c);
            for (Map.Entry<String, Object> e : changed.entrySet()) if (!skip(e.getKey(), before, now)) plan.add(e);
            // One more look: the person can write preferences without this lock, so drop anything that moved.
            now = current(c);
            SharedPreferences.Editor ui = c.getSharedPreferences("god-ui", Context.MODE_PRIVATE).edit();
            SharedPreferences.Editor notices = NoticeCenter.prefs(c).edit();
            for (Map.Entry<String, Object> e : plan) {
                if (skip(e.getKey(), before, now)) continue;
                switch (e.getKey()) {
                    case "board" -> { ui.putString("dashboard", String.join(",", strings(e.getValue()))); uiTouch = true; }
                    case "clocks" -> { ui.putString(Dashboard.CLOCKS, String.join("\n", strings(e.getValue()))); uiTouch = true; }
                    case "calendarDays" -> { ui.putString(Dashboard.CALENDAR_DAYS, String.valueOf(e.getValue())); uiTouch = true; }
                    case "hiddenQuotaCards" -> { ui.putString(Dashboard.HIDDEN_TOKENS, String.join(",", strings(e.getValue()))); uiTouch = true; }
                    case "glassOn" -> { ui.putBoolean(ConsoleTheme.GLASS_ON, (Boolean) e.getValue()); uiTouch = true; }
                    case "glassLevel" -> { ui.putInt(ConsoleTheme.GLASS_LEVEL, ((Number) e.getValue()).intValue()); uiTouch = true; }
                    case "statusBar" -> { ui.putString(StatusBarStyle.KEY, (String) e.getValue()).remove(StatusBarStyle.LEGACY); uiTouch = true; }
                    case "islandLeft" -> { ui.putString(StatusBarStyle.LEFT_KEY, (String) e.getValue()); uiTouch = true; }
                    case "islandRight" -> { ui.putString(StatusBarStyle.RIGHT_KEY, (String) e.getValue()); uiTouch = true; }
                    case "hiddenFeatures" -> { ui.putString(FeatureVisibility.HIDDEN, String.join(",", strings(e.getValue()))); uiTouch = true; }
                    case "shownFeatures" -> { ui.putString(FeatureVisibility.SHOWN, String.join(",", strings(e.getValue()))); uiTouch = true; }
                    case "recentFeatures" -> { ui.putString("featureUsage", usage(e.getValue())); uiTouch = true; }
                    case "noticeKinds" -> { notices.putStringSet("on", new HashSet<>(strings(e.getValue()))).putBoolean("primed", false); noticeTouch = true; }
                    case "dailyLine" -> { notices.putInt("daily", ((Number) e.getValue()).intValue()); noticeTouch = true; }
                    case "lowLine" -> { notices.putInt("low", ((Number) e.getValue()).intValue()); noticeTouch = true; }
                    case "creditsLine" -> { notices.putInt("creditsLine", ((Number) e.getValue()).intValue()); noticeTouch = true; }
                    case "marketDays" -> { notices.putInt("marketDays", ((Number) e.getValue()).intValue()); noticeTouch = true; }
                    case "marketLine" -> { notices.putInt("marketLine", ((Number) e.getValue()).intValue()); noticeTouch = true; }
                    default -> { }
                }
            }
            if (uiTouch) ui.apply();
            if (noticeTouch) notices.apply();
            now = current(c);
            if (changed.containsKey("enhancedNotice") && !skip("enhancedNotice", before, now)) NoticeCenter.setMode(c, "enhanced", (Boolean) changed.get("enhancedNotice"));
            if (changed.containsKey("persistentNotice") && !skip("persistentNotice", before, now)) NoticeCenter.setMode(c, "persistent", (Boolean) changed.get("persistentNotice"));
            if (tiboInChange && !skip("tiboNotice", before, now)) {
                boolean on = (Boolean) changed.get("tiboNotice");
                if (on != TiboWatch.enabled(c)) { if (on) TiboWatch.enable(c); else TiboWatch.disable(c); }
            }
        }
        if (!tiboInChange && noticeTouch) {
            if (TiboWatch.active(c)) TiboWatch.start(c, true); else TiboWatch.stop(c);
        }
        // Another phone asked for notification permission when it turned this on; this phone still has to.
        if (PreferenceSync.turnsOnReminder(before, current(c)))
            c.getSharedPreferences("god-ui", Context.MODE_PRIVATE).edit().putBoolean(ASK_NOTIFY, true).apply();
    }
    /**
     * True when this key must not be overwritten: the person already edited it ({@link #skipped} or a dirty sync
     * value), or the preference files changed between the start of this write and now. A drifted key is replayed
     * from the files once the write ends.
     */
    private static boolean skip(String key, Map<String, Object> before, Map<String, Object> now) {
        if (!Objects.equals(before.get(key), now.get(key))) { skipped.add(key); return true; }
        return blocked(key);
    }
    private static Map<String, Long> edited(String json) {
        Map<String, Long> out = new LinkedHashMap<>();
        try {
            JSONObject o = new JSONObject(json == null ? "{}" : json);
            for (String key : PreferenceSync.KEYS) { Object v = o.opt(key); if (v instanceof Number n && n.longValue() > 0) out.put(key, n.longValue()); }
        } catch (org.json.JSONException e) { return out; }
        return out;
    }
    private static List<String> strings(Object value) {
        List<String> out = new java.util.ArrayList<>();
        for (Object item : (List<?>) value) out.add(String.valueOf(item));
        return out;
    }
    private static String usage(Object value) {
        Map<String, FeatureUsage.Entry> usage = new LinkedHashMap<>();
        for (Object item : (List<?>) value) {
            PreferenceSync.Recent recent = (PreferenceSync.Recent) item;
            usage.put(recent.id, new FeatureUsage.Entry(recent.score, recent.at));
        }
        return FeatureUsage.serialize(usage);
    }
}
