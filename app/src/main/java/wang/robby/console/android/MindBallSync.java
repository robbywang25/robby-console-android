package wang.robby.console.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;
import wang.robby.console.android.auth.AuthSession;

/**
 * 心力球's keys and service settings on every phone of the account: one document at {@link AccountCenter#APP_SECRETS}
 * (DeepSeek key, transcription key, transcription address and model, DeepSeek model). God's gateway seals it before it
 * is stored. Reading and saving need {@link AuthSession#PROFILE_WRITE}; without it the keys stay on this phone.
 * A save here is uploaded right away (or on the next sync when offline), and a newer save from another phone replaces
 * this phone's copy. The later save wins. Runs on {@link MindBall}'s worker, before anything is transcribed.
 */
final class MindBallSync {
    private MindBallSync() {}
    static final String SYNCED_AT = "syncedAt", DIRTY = "syncDirty", STATE = "syncState";
    static final String[] FIELDS = {"deepseekKey", "asrKey", "asrUrl", "asrModel", "model"};
    enum Step { UPLOAD, APPLY, NONE }

    /**
     * What to do with the server's copy. An unsent local change is uploaded; a document the server never had (or can no
     * longer read) is filled from this phone; a server copy this phone has not applied yet replaces the local one.
     */
    static Step decide(boolean dirty, boolean localHasAny, String syncedAt, String remoteAt, boolean remoteHas) {
        if (dirty) return Step.UPLOAD;
        if (!remoteHas) return localHasAny ? Step.UPLOAD : Step.NONE;
        return remoteAt != null && !remoteAt.equals(syncedAt) ? Step.APPLY : Step.NONE;
    }

    /** This phone's document; a field never set (so the default applies) is null. */
    static JSONObject local(Context c) throws JSONException {
        SharedPreferences p = MindBall.prefs(c);
        return new JSONObject().put("deepseekKey", orNull(MindBall.secret(c, MindBall.KEY_DEEPSEEK))).put("asrKey", orNull(MindBall.secret(c, MindBall.KEY_ASR)))
            .put("asrUrl", orNull(p.getString("asrUrl", null))).put("asrModel", orNull(p.getString("asrModel", null))).put("model", orNull(p.getString("model", null)));
    }
    static boolean hasAny(JSONObject doc) { for (String f : FIELDS) if (!doc.isNull(f)) return true; return false; }
    /** The server document shaped for this phone: exactly the five fields, each a non-empty string or null. */
    static JSONObject checked(JSONObject secrets) throws JSONException {
        if (secrets == null || secrets.length() != FIELDS.length) throw new JSONException("app secrets shape");
        JSONObject out = new JSONObject();
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

    /** One sync, on the worker thread. Never throws; the outcome is left in {@link #STATE}. */
    static void run(Context c) {
        if (PreviewMode.isBuild()) return;
        SharedPreferences p = MindBall.prefs(c);
        AuthSession auth;
        try { auth = new AuthSession(c); if (!auth.hasSession()) { state(p, "仅本机：登录后可在多台手机间同步"); return; } }
        catch (RuntimeException e) { state(p, "仅本机：登录后可在多台手机间同步"); return; }
        if (!auth.hasScope(AuthSession.PROFILE_WRITE)) { state(p, "仅本机：重新登录一次后可在多台手机间同步"); return; }
        try {
            MobileApi api = new MobileApi(auth);
            JSONObject remote = api.get(AccountCenter.APP_SECRETS), local = local(c);
            boolean remoteHas = !remote.isNull("secrets");
            Step step = decide(p.getBoolean(DIRTY, false), hasAny(local), p.getString(SYNCED_AT, null), remote.isNull("updatedAt") ? null : remote.optString("updatedAt", null), remoteHas);
            if (step == Step.UPLOAD) {
                JSONObject saved = api.post(AccountCenter.APP_SECRETS, local);
                p.edit().putString(SYNCED_AT, saved.optString("updatedAt", "")).putBoolean(DIRTY, false).apply();
            } else if (step == Step.APPLY) {
                apply(c, checked(remote.getJSONObject("secrets")));
                p.edit().putString(SYNCED_AT, remote.getString("updatedAt")).apply();
                MindBall.notifyChanged();
            }
            state(p, "已同步到账号");
        } catch (MobileApi.ApiError e) {
            Log.w(MindBall.TAG, "sync_refused " + e.status);
            state(p, p.getBoolean(DIRTY, false) ? "待同步：" + reason(e.status) : "同步失败：" + reason(e.status));
        } catch (Exception e) {
            Log.w(MindBall.TAG, "sync_failed");
            state(p, p.getBoolean(DIRTY, false) ? "待同步：网络不可用，稍后自动重试" : "同步失败：网络不可用，稍后自动重试");
        }
    }
    static String reason(int status) {
        return switch (status) { case 401 -> "登录已失效，请重新登录"; case 403 -> "需要重新登录一次"; case 404 -> "服务器还没有开通同步"; case 429 -> "请求较多，稍后自动重试"; default -> status >= 500 ? "服务暂不可用" : "请求被拒绝（HTTP " + status + "）"; };
    }
    private static void state(SharedPreferences p, String s) { p.edit().putString(STATE, s).apply(); }
    /** Writes another phone's document here without marking it as a change of this phone. */
    private static void apply(Context c, JSONObject doc) {
        MindBall.setSecret(c, MindBall.KEY_DEEPSEEK, str(doc, "deepseekKey"));
        MindBall.setSecret(c, MindBall.KEY_ASR, str(doc, "asrKey"));
        SharedPreferences.Editor e = MindBall.prefs(c).edit();
        for (String[] f : new String[][] {{"asrUrl", "asrUrl"}, {"asrModel", "asrModel"}, {"model", "model"}}) { String v = str(doc, f[0]); if (v == null) e.remove(f[1]); else e.putString(f[1], v); }
        e.apply();
    }
}
