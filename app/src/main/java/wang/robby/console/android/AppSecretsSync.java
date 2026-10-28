package wang.robby.console.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;
import wang.robby.console.android.auth.AuthSession;

/**
 * The account's sealed settings on every phone of the account: one document at {@link AccountCenter#APP_SECRETS} with
 * 心力球's keys and service settings (DeepSeek key, transcription key, transcription address and model, DeepSeek model)
 * and the 米家 lamp (name, LAN address, token). God's gateway seals it before it is stored. Reading and saving need
 * {@link AuthSession#PROFILE_WRITE}; without it everything stays on this phone. A save here is uploaded right away (or on
 * the next sync when offline), and a newer save from another phone replaces this phone's copy. The later save wins.
 * Runs on {@link MindBall}'s worker, so two syncs never overlap: before a transcription round when the last sync is half
 * an hour old, on opening God while recording or with a lamp set up, and on opening either page. The bookkeeping stays
 * in 心力球's preferences, where it was before the lamp joined.
 */
final class AppSecretsSync {
    private AppSecretsSync() {}
    static final String SYNCED_AT = "syncedAt", DIRTY = "syncDirty", STATE = "syncState", LIGHT_STATE = "syncLightState";
    static final String[] FIELDS = {"deepseekKey", "asrKey", "asrUrl", "asrModel", "model"};
    static final String LIGHT = "mijiaLight";
    /** The lamp is only read and written on version 2; a gateway older than it answers 404 there. */
    static final String V2 = AccountCenter.APP_SECRETS + "?version=2";
    /**
     * How old the last sync may be before one is asked for again: a transcription round, opening God while recording, and
     * opening the 心力球 page. A change saved here, and the settings row, sync at once.
     */
    static final long ROUND_AGE = 30 * 60_000L, RESUME_AGE = 10 * 60_000L, PAGE_AGE = 60_000L;
    /** When the last sync began ({@link SystemClock#elapsedRealtime}); -1 before the first in this process. */
    private static volatile long lastRun = -1;
    enum Step { UPLOAD, APPLY, MERGE, NONE }

    static boolean due(boolean dirty, long lastRun, long now, long maxAge) { return dirty || lastRun < 0 || now - lastRun >= maxAge; }
    /** {@link #run} unless this phone has nothing to send and synced within {@code maxAge}; true when it ran. */
    static boolean runIfDue(Context c, long maxAge) {
        if (!due(MindBall.prefs(c).getBoolean(DIRTY, false), lastRun, SystemClock.elapsedRealtime(), maxAge)) return false;
        run(c); return true;
    }

    /**
     * What to do with the server's copy. An unsent local change is uploaded; a document the server never had (or can no
     * longer read) is filled from this phone; a server copy this phone has not applied yet replaces the local one. A
     * document no phone has saved a lamp into yet takes the lamp set up here: sent as it is, or after applying the
     * account's newer keys first ({@link Step#MERGE}), so a lamp set up before syncing existed reaches the account too.
     */
    static Step decide(boolean dirty, boolean localHasAny, String syncedAt, String remoteAt, boolean remoteHas, boolean remoteKnowsLight, boolean localHasLight) {
        if (dirty) return Step.UPLOAD;
        if (!remoteHas) return localHasAny ? Step.UPLOAD : Step.NONE;
        boolean newer = remoteAt != null && !remoteAt.equals(syncedAt);
        if (!remoteKnowsLight && localHasLight) return newer ? Step.MERGE : Step.UPLOAD;
        return newer ? Step.APPLY : Step.NONE;
    }

    /**
     * This phone's document; a field never set (so the default applies) is null. With {@code withLight} it carries the
     * lamp too: null when none is set up here, and the account's copy ({@code remoteLight}, absent when it has none) when
     * this phone's Keystore can no longer open the saved token, so an unreadable token never removes the lamp elsewhere.
     */
    static JSONObject local(Context c, boolean withLight, Object remoteLight) throws JSONException {
        SharedPreferences p = MindBall.prefs(c);
        JSONObject doc = new JSONObject().put("deepseekKey", orNull(MindBall.secret(c, MindBall.KEY_DEEPSEEK))).put("asrKey", orNull(MindBall.secret(c, MindBall.KEY_ASR)))
            .put("asrUrl", orNull(p.getString("asrUrl", null))).put("asrModel", orNull(p.getString("asrModel", null))).put("model", orNull(p.getString("model", null)));
        if (!withLight) return doc;
        if (!MiioLight.configured(c)) return doc.put(LIGHT, JSONObject.NULL);
        String token = MiioLight.tokenHex(c);
        return doc.put(LIGHT, token == null ? remoteLight == null ? JSONObject.NULL : remoteLight : light(MiioLight.name(c), MiioLight.address(c), token));
    }
    static JSONObject light(String name, String address, String token) throws JSONException { return new JSONObject().put("name", name).put("address", address).put("token", token); }
    static boolean hasAny(JSONObject doc) { for (String f : FIELDS) if (!doc.isNull(f)) return true; return doc.has(LIGHT) && !doc.isNull(LIGHT); }
    /**
     * The server document shaped for this phone: the five fields, each a non-empty string or null, and the lamp when the
     * document has one: null, or a name of up to 40 characters, a private IPv4 address and a 32-hex token.
     */
    static JSONObject checked(JSONObject secrets) throws JSONException {
        boolean light = secrets != null && secrets.has(LIGHT);
        if (secrets == null || secrets.length() != FIELDS.length + (light ? 1 : 0)) throw new JSONException("app secrets shape");
        JSONObject out = new JSONObject();
        if (light) {
            Object v = secrets.get(LIGHT);
            if (v == JSONObject.NULL) out.put(LIGHT, v);
            else {
                if (!(v instanceof JSONObject) || ((JSONObject) v).length() != 3) throw new JSONException("app secrets lamp");
                JSONObject l = (JSONObject) v; String name = l.optString("name", ""), address = l.optString("address", ""), token = l.optString("token", "");
                if (!(l.opt("name") instanceof String) || name.trim().isEmpty() || name.codePointCount(0, name.length()) > 40 || !MiioLight.validAddress(address) || !token.matches("[0-9a-f]{32}"))
                    throw new JSONException("app secrets lamp");
                out.put(LIGHT, light(name, address, token));
            }
        }
        for (String f : FIELDS) {
            if (!secrets.has(f)) throw new JSONException("app secrets shape");
            Object v = secrets.get(f);
            if (v != JSONObject.NULL && !(v instanceof String && !((String) v).isEmpty())) throw new JSONException("app secrets shape");
            out.put(f, v);
        }
        if (!out.isNull("asrUrl") && !out.getString("asrUrl").startsWith("https://")) throw new JSONException("app secrets address");
        return out;
    }
    private static Object orNull(String s) { return s == null || s.trim().isEmpty() ? JSONObject.NULL : s.trim(); }
    private static String str(JSONObject doc, String f) { return doc.isNull(f) ? null : doc.optString(f, null); }

    /** A key or setting saved on this phone: uploaded on the next sync, which the caller starts. */
    static void changed(Context c) { MindBall.prefs(c).edit().putBoolean(DIRTY, true).apply(); }

    /** "已同步到账号", "仅本机：…", "待同步", or a failure, for the settings card. */
    static String state(Context c) { return MindBall.prefs(c).getString(STATE, ""); }
    /** The same for the lamp, which also stays on this phone while the gateway is older than version 2. */
    static String lightState(Context c) { return MindBall.prefs(c).getString(LIGHT_STATE, ""); }

    /** One sync, on the worker thread. Never throws; the outcome is left in {@link #STATE}. */
    static void run(Context c) {
        if (PreviewMode.isBuild()) return;
        lastRun = SystemClock.elapsedRealtime();
        SharedPreferences p = MindBall.prefs(c);
        AuthSession auth;
        try { auth = new AuthSession(c); if (!auth.hasSession()) { state(p, "仅本机：登录后可在多台手机间同步"); return; } }
        catch (RuntimeException e) { state(p, "仅本机：登录后可在多台手机间同步"); return; }
        if (!auth.hasScope(AuthSession.PROFILE_WRITE)) { state(p, "仅本机：重新登录一次后可在多台手机间同步"); return; }
        try {
            try { once(c, p, new MobileApi(auth)); }
            catch (MobileApi.ApiError e) {
                long wait = retryDelay(e.status, 0); if (wait < 0) throw e;
                // Opening God sends the account's other reads at the same moment and the gateway takes two at a time.
                Thread.sleep(wait); once(c, p, new MobileApi(auth));
            }
            state(p, "已同步到账号");
            if (!lightSynced) p.edit().putString(LIGHT_STATE, "仅本机：服务器还没有开通灯的同步").apply();
        } catch (MobileApi.ApiError e) {
            Log.w(MindBall.TAG, "sync_refused " + e.status);
            state(p, p.getBoolean(DIRTY, false) ? "待同步：" + reason(e.status) : "同步失败：" + reason(e.status));
        } catch (Exception e) {
            Log.w(MindBall.TAG, "sync_failed");
            state(p, p.getBoolean(DIRTY, false) ? "待同步：网络不可用，稍后自动重试" : "同步失败：网络不可用，稍后自动重试");
        }
    }
    /** Whether the last {@link #once} could carry the lamp; false while the gateway is older than version 2. */
    private static volatile boolean lightSynced = true;
    /** One read, then the upload, apply or both that it calls for. */
    private static void once(Context c, SharedPreferences p, MobileApi api) throws Exception {
        String path = V2; JSONObject remote;
        try { remote = api.get(V2); }
        catch (MobileApi.ApiError e) { if (e.status != 404) throw e; path = AccountCenter.APP_SECRETS; remote = api.get(path); }
        boolean v2 = path.equals(V2); lightSynced = v2;
        JSONObject secrets = null;
        // A copy this phone cannot accept is treated like one the server cannot read: this phone's copy replaces it.
        if (!remote.isNull("secrets")) try { secrets = checked(remote.getJSONObject("secrets")); } catch (JSONException e) { Log.w(MindBall.TAG, "sync_shape"); }
        boolean remoteHas = secrets != null;
        Object remoteLight = secrets != null && secrets.has(LIGHT) ? secrets.get(LIGHT) : null;
        JSONObject local = local(c, v2, remoteLight);
        // An older gateway neither keeps nor returns the lamp: the keys sync as before and the lamp waits on this phone.
        boolean knows = !v2 || secrets != null && secrets.has(LIGHT), localLight = v2 && !local.isNull(LIGHT);
        Step step = decide(p.getBoolean(DIRTY, false), hasAny(local), p.getString(SYNCED_AT, null), remote.isNull("updatedAt") ? null : remote.optString("updatedAt", null), remoteHas, knows, localLight);
        if (step == Step.APPLY || step == Step.MERGE) {
            apply(c, secrets);
            p.edit().putString(SYNCED_AT, remote.getString("updatedAt")).apply();
            MindBall.notifyChanged();
        }
        if (step == Step.UPLOAD || step == Step.MERGE) {
            // Until some phone has saved a lamp, a phone without one saves just the keys: a null here would read as
            // "removed" on the phone whose lamp has not been sent yet.
            boolean keysOnly = v2 && !knows && !localLight;
            JSONObject body = step == Step.MERGE ? local(c, true, remoteLight) : keysOnly ? local(c, false, null) : local;
            JSONObject saved = api.post(keysOnly ? AccountCenter.APP_SECRETS : path, body);
            p.edit().putString(SYNCED_AT, saved.optString("updatedAt", "")).putBoolean(DIRTY, false).apply();
        }
    }
    /** Milliseconds to wait before the one retry: only a busy gateway (429) is retried, a few seconds later. -1: give up. */
    static long retryDelay(int status, int attempt) { return status == 429 && attempt == 0 ? 3_000L : -1; }
    static String reason(int status) {
        return switch (status) { case 401 -> "登录已失效，请重新登录"; case 403 -> "需要重新登录一次"; case 404 -> "服务器还没有开通同步"; case 429 -> "请求较多，稍后自动重试"; default -> status >= 500 ? "服务暂不可用" : "请求被拒绝（HTTP " + status + "）"; };
    }
    private static void state(SharedPreferences p, String s) { p.edit().putString(STATE, s).putString(LIGHT_STATE, s).apply(); }
    /** Writes another phone's document here without marking it as a change of this phone. */
    private static void apply(Context c, JSONObject doc) throws JSONException {
        if (doc.has(LIGHT)) {
            boolean changed;
            if (doc.isNull(LIGHT)) { changed = MiioLight.configured(c); if (changed) MiioLight.forget(c); }
            else {
                JSONObject l = doc.getJSONObject(LIGHT); String name = l.getString("name"), address = l.getString("address"), token = l.getString("token");
                changed = !MiioLight.configured(c) || !name.equals(MiioLight.name(c)) || !address.equals(MiioLight.address(c)) || !token.equals(MiioLight.tokenHex(c));
                if (changed) MiioLight.configure(c, address, name, token);
            }
            if (changed) MiioLightPage.syncedFromAccount();
        }
        MindBall.setSecret(c, MindBall.KEY_DEEPSEEK, str(doc, "deepseekKey"));
        MindBall.setSecret(c, MindBall.KEY_ASR, str(doc, "asrKey"));
        SharedPreferences.Editor e = MindBall.prefs(c).edit();
        for (String[] f : new String[][] {{"asrUrl", "asrUrl"}, {"asrModel", "asrModel"}, {"model", "model"}}) { String v = str(doc, f[0]); if (v == null) e.remove(f[1]); else e.putString(f[1], v); }
        e.apply();
    }
}
