package wang.robby.console.android;

import static org.junit.Assert.*;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import wang.robby.console.android.AppSecretsSync.Step;

/** 心力球's keys and the 米家 lamp across phones: when this phone uploads, applies the account's copy, or leaves it, and what it accepts. */
public final class AppSecretsSyncTest {
    private static final String AT = "2026-10-23T01:00:00Z", LATER = "2026-10-23T02:00:00Z";

    @Test public void anUnsentChangeHereIsUploadedFirst() {
        assertEquals(Step.UPLOAD, AppSecretsSync.decide(true, true, AT, LATER, true, true, false));
        assertEquals("a key cleared here is a change too", Step.UPLOAD, AppSecretsSync.decide(true, false, AT, LATER, true, true, false));
    }
    @Test public void anEmptyAccountIsFilledFromThePhoneThatHasKeys() {
        assertEquals(Step.UPLOAD, AppSecretsSync.decide(false, true, null, null, false, true, false));
        assertEquals("nothing on either side: nothing to do", Step.NONE, AppSecretsSync.decide(false, false, null, null, false, true, false));
        assertEquals("a copy the server can no longer read is replaced", Step.UPLOAD, AppSecretsSync.decide(false, true, AT, null, false, true, false));
    }
    @Test public void anotherPhonesNewerSaveReplacesThisCopy() {
        assertEquals("a new phone takes the account's keys", Step.APPLY, AppSecretsSync.decide(false, false, null, AT, true, true, false));
        assertEquals("even over keys typed here earlier and already sent", Step.APPLY, AppSecretsSync.decide(false, true, AT, LATER, true, true, false));
        assertEquals(Step.NONE, AppSecretsSync.decide(false, true, LATER, LATER, true, true, false));
    }
    @Test public void theAccountsDocumentIsCheckedBeforeItIsWrittenHere() throws Exception {
        JSONObject ok = new JSONObject().put("deepseekKey", "sk-a").put("asrKey", JSONObject.NULL).put("asrUrl", "https://api.siliconflow.cn/v1").put("asrModel", JSONObject.NULL).put("model", "deepseek-flash");
        assertEquals("sk-a", AppSecretsSync.checked(ok).getString("deepseekKey"));
        assertTrue(AppSecretsSync.checked(ok).isNull("asrKey"));
        for (JSONObject bad : new JSONObject[] {
                new JSONObject(ok.toString()).put("extra", 1), new JSONObject(ok.toString()).put("deepseekKey", ""), new JSONObject(ok.toString()).put("asrKey", 3),
                new JSONObject(ok.toString()).put("asrUrl", "http://plain.example/v1"), new JSONObject().put("deepseekKey", "sk-a") }) {
            try { AppSecretsSync.checked(bad); fail("accepted " + bad); } catch (JSONException expected) {}
        }
        try { AppSecretsSync.checked(null); fail("accepted null"); } catch (JSONException expected) {}
    }
    @Test public void aLampSetUpBeforeSyncingReachesTheAccount() {
        assertEquals("the account has keys but no lamp yet", Step.UPLOAD, AppSecretsSync.decide(false, true, AT, AT, true, false, true));
        assertEquals("another phone saved newer keys meanwhile: take them, then send the lamp", Step.MERGE, AppSecretsSync.decide(false, true, AT, LATER, true, false, true));
        assertEquals("no lamp here either: nothing to send", Step.NONE, AppSecretsSync.decide(false, true, AT, AT, true, false, false));
        assertEquals("once the account knows the lamp, the later save wins as for the keys", Step.APPLY, AppSecretsSync.decide(false, true, AT, LATER, true, true, true));
        assertEquals(Step.NONE, AppSecretsSync.decide(false, true, LATER, LATER, true, true, true));
    }
    @Test public void theLampInTheAccountsDocumentIsCheckedToo() throws Exception {
        JSONObject keys = new JSONObject();
        for (String f : AppSecretsSync.FIELDS) keys.put(f, JSONObject.NULL);
        JSONObject lamp = AppSecretsSync.light("台灯", "192.168.1.15", "00112233445566778899aabbccddeeff");
        JSONObject ok = new JSONObject(keys.toString()).put(AppSecretsSync.LIGHT, lamp);
        assertEquals("192.168.1.15", AppSecretsSync.checked(ok).getJSONObject(AppSecretsSync.LIGHT).getString("address"));
        assertTrue("removed on another phone", AppSecretsSync.checked(new JSONObject(keys.toString()).put(AppSecretsSync.LIGHT, JSONObject.NULL)).isNull(AppSecretsSync.LIGHT));
        assertFalse("an older document has no lamp at all", AppSecretsSync.checked(keys).has(AppSecretsSync.LIGHT));
        assertTrue(AppSecretsSync.hasAny(ok)); assertFalse(AppSecretsSync.hasAny(new JSONObject(keys.toString()).put(AppSecretsSync.LIGHT, JSONObject.NULL)));
        for (JSONObject bad : new JSONObject[] {
                new JSONObject(lamp.toString()).put("address", "8.8.8.8"), new JSONObject(lamp.toString()).put("token", "0011"), new JSONObject(lamp.toString()).put("token", "00112233445566778899AABBCCDDEEFF"),
                new JSONObject(lamp.toString()).put("name", " "), new JSONObject(lamp.toString()).put("name", 7), new JSONObject(lamp.toString()).put("model", "yeelink.light.lamp22") }) {
            try { AppSecretsSync.checked(new JSONObject(keys.toString()).put(AppSecretsSync.LIGHT, bad)); fail("accepted " + bad); } catch (JSONException expected) {}
        }
        try { AppSecretsSync.checked(new JSONObject(keys.toString()).put(AppSecretsSync.LIGHT, "192.168.1.15")); fail("accepted a bare address"); } catch (JSONException expected) {}
        assertEquals("account/app-secrets?version=2", MobileApi.checkedPath(AppSecretsSync.V2));
    }
    @Test public void aDocumentWithOnlyDefaultsHasNothingToUpload() throws Exception {
        JSONObject empty = new JSONObject();
        for (String f : AppSecretsSync.FIELDS) empty.put(f, JSONObject.NULL);
        assertFalse(AppSecretsSync.hasAny(empty));
        assertTrue(AppSecretsSync.hasAny(empty.put("model", "deepseek-v4-pro")));
    }
    @Test public void onlyABusyGatewayIsRetriedAndOnlyOnce() {
        assertEquals(3_000L, AppSecretsSync.retryDelay(429, 0));
        assertEquals(-1L, AppSecretsSync.retryDelay(429, 1));
        for (int status : new int[] {401, 403, 404, 500, 503}) assertEquals(-1L, AppSecretsSync.retryDelay(status, 0));
    }
    @Test public void theAccountIsAskedOnlyNowAndThen() {
        long now = 5_000_000L;
        assertTrue("the first sync in a process", AppSecretsSync.due(false, -1, now, AppSecretsSync.ROUND_AGE));
        assertFalse(AppSecretsSync.due(false, now - AppSecretsSync.ROUND_AGE + 1, now, AppSecretsSync.ROUND_AGE));
        assertTrue(AppSecretsSync.due(false, now - AppSecretsSync.ROUND_AGE, now, AppSecretsSync.ROUND_AGE));
        assertTrue("a change saved here goes at once", AppSecretsSync.due(true, now - 1, now, AppSecretsSync.ROUND_AGE));
        assertTrue("the settings row", AppSecretsSync.due(false, now, now, 0));
        assertTrue(AppSecretsSync.PAGE_AGE < AppSecretsSync.RESUME_AGE && AppSecretsSync.RESUME_AGE < AppSecretsSync.ROUND_AGE);
    }
    @Test public void theRouteIsRegisteredForReadingAndSaving() {
        assertTrue(AccountCenter.readRoute(AccountCenter.APP_SECRETS));
        assertTrue(AccountCenter.writeRoute(AccountCenter.APP_SECRETS));
        assertEquals("account/app-secrets", MobileApi.checkedPath(AccountCenter.APP_SECRETS));
        assertEquals("服务器还没有开通同步", AppSecretsSync.reason(404));
        assertEquals("需要重新登录一次", AppSecretsSync.reason(403));
    }
}
