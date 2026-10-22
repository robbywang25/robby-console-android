package wang.robby.console.android;

import static org.junit.Assert.*;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import wang.robby.console.android.MindBallSync.Step;

/** 心力球's keys across phones: when this phone uploads, applies the account's copy, or leaves it, and what it accepts. */
public final class MindBallSyncTest {
    private static final String AT = "2026-10-23T01:00:00Z", LATER = "2026-10-23T02:00:00Z";

    @Test public void anUnsentChangeHereIsUploadedFirst() {
        assertEquals(Step.UPLOAD, MindBallSync.decide(true, true, AT, LATER, true));
        assertEquals("a key cleared here is a change too", Step.UPLOAD, MindBallSync.decide(true, false, AT, LATER, true));
    }
    @Test public void anEmptyAccountIsFilledFromThePhoneThatHasKeys() {
        assertEquals(Step.UPLOAD, MindBallSync.decide(false, true, null, null, false));
        assertEquals("nothing on either side: nothing to do", Step.NONE, MindBallSync.decide(false, false, null, null, false));
        assertEquals("a copy the server can no longer read is replaced", Step.UPLOAD, MindBallSync.decide(false, true, AT, null, false));
    }
    @Test public void anotherPhonesNewerSaveReplacesThisCopy() {
        assertEquals("a new phone takes the account's keys", Step.APPLY, MindBallSync.decide(false, false, null, AT, true));
        assertEquals("even over keys typed here earlier and already sent", Step.APPLY, MindBallSync.decide(false, true, AT, LATER, true));
        assertEquals(Step.NONE, MindBallSync.decide(false, true, LATER, LATER, true));
    }
    @Test public void theAccountsDocumentIsCheckedBeforeItIsWrittenHere() throws Exception {
        JSONObject ok = new JSONObject().put("deepseekKey", "sk-a").put("asrKey", JSONObject.NULL).put("asrUrl", "https://api.siliconflow.cn/v1").put("asrModel", JSONObject.NULL).put("model", "deepseek-flash");
        assertEquals("sk-a", MindBallSync.checked(ok).getString("deepseekKey"));
        assertTrue(MindBallSync.checked(ok).isNull("asrKey"));
        for (JSONObject bad : new JSONObject[] {
                new JSONObject(ok.toString()).put("extra", 1), new JSONObject(ok.toString()).put("deepseekKey", ""), new JSONObject(ok.toString()).put("asrKey", 3),
                new JSONObject(ok.toString()).put("asrUrl", "http://plain.example/v1"), new JSONObject().put("deepseekKey", "sk-a") }) {
            try { MindBallSync.checked(bad); fail("accepted " + bad); } catch (JSONException expected) {}
        }
        try { MindBallSync.checked(null); fail("accepted null"); } catch (JSONException expected) {}
    }
    @Test public void aDocumentWithOnlyDefaultsHasNothingToUpload() throws Exception {
        JSONObject empty = new JSONObject();
        for (String f : MindBallSync.FIELDS) empty.put(f, JSONObject.NULL);
        assertFalse(MindBallSync.hasAny(empty));
        assertTrue(MindBallSync.hasAny(empty.put("model", "deepseek-v4-pro")));
    }
    @Test public void onlyABusyGatewayIsRetriedAndOnlyOnce() {
        assertEquals(3_000L, MindBallSync.retryDelay(429, 0));
        assertEquals(-1L, MindBallSync.retryDelay(429, 1));
        for (int status : new int[] {401, 403, 404, 500, 503}) assertEquals(-1L, MindBallSync.retryDelay(status, 0));
    }
    @Test public void theRouteIsRegisteredForReadingAndSaving() {
        assertTrue(AccountCenter.readRoute(AccountCenter.APP_SECRETS));
        assertTrue(AccountCenter.writeRoute(AccountCenter.APP_SECRETS));
        assertEquals("account/app-secrets", MobileApi.checkedPath(AccountCenter.APP_SECRETS));
        assertEquals("服务器还没有开通同步", MindBallSync.reason(404));
        assertEquals("需要重新登录一次", MindBallSync.reason(403));
    }
}
