package wang.robby.console.android.auth;

import org.junit.Test;
import static org.junit.Assert.*;

public class DevicePollPolicyTest {
    @Test public void missingIntervalDefaultsToFiveAndCannotBeReduced() throws Exception {
        assertEquals(5, DevicePollPolicy.interval(null));
        assertEquals(5, DevicePollPolicy.interval(1));
        assertEquals(8, DevicePollPolicy.interval(8));
        for (Object value : new Object[] {0, -1, 901, "5", 5.0})
            assertThrows(Exception.class, () -> DevicePollPolicy.interval(value));
    }
    @Test public void expiryMustBeBoundedInteger() throws Exception {
        assertEquals(900, DevicePollPolicy.lifetime(900));
        for (Object value : new Object[] {null, 0, -1, 901, Long.MAX_VALUE, "60", 60.0})
            assertThrows(Exception.class, () -> DevicePollPolicy.lifetime(value));
    }
    @Test public void pendingWaitsFullIntervalAndSlowDownAddsFivePermanently() throws Exception {
        DevicePollPolicy policy = new DevicePollPolicy(900, 5, 1000);
        assertEquals(5000, policy.delay(1000)); assertEquals(1, policy.delay(5999));
        assertEquals(0, policy.delay(6000));
        policy.response("authorization_pending", 6000); assertEquals(5000, policy.delay(6000));
        policy.response("slow_down", 11000); assertEquals(10000, policy.delay(11000));
        policy.response("authorization_pending", 21000); assertEquals(10000, policy.delay(21000));
        policy.response("slow_down", 31000); assertEquals(15000, policy.delay(31000));
    }
    @Test public void timeoutDoublesIntervalAndDeadlineStopsEveryOutcome() throws Exception {
        DevicePollPolicy policy = new DevicePollPolicy(60, 5, 1000);
        policy.timeout(6000); assertEquals(10000, policy.delay(6000));
        policy.timeout(16000); assertEquals(20000, policy.delay(16000));
        assertThrows(Exception.class, () -> policy.delay(61000));
        assertThrows(Exception.class, () -> policy.response("authorization_pending", 61000));
        assertThrows(Exception.class, () -> policy.timeout(61000));
    }
    @Test public void denialExpiryAndUnknownErrorsNeverContinuePolling() throws Exception {
        for (String error : new String[] {"access_denied", "expired_token", "invalid_client", "server_error", ""}) {
            DevicePollPolicy policy = new DevicePollPolicy(60, 5, 1000);
            assertThrows(Exception.class, () -> policy.response(error, 6000));
        }
    }
    @Test public void verificationUriCannotContainCodeOrAlternativeOriginPath() throws Exception {
        String expected = AuthSession.ISSUER + "/consent/openid/device-authorization";
        assertEquals(expected, DevicePollPolicy.verificationUri(expected));
        for (String value : new String[] {expected + "?user_code=test", expected + "#test", expected + "/",
                expected.replace("https:", "http:"), expected.replace("auth.god.robby.wang", "evil.example"), AuthSession.ISSUER + "/other"})
            assertThrows(Exception.class, () -> DevicePollPolicy.verificationUri(value));
    }
    @Test public void deviceRequestsUseResourceAndNeverPutCodeIntoVerificationUrl() {
        assertEquals(AuthSession.AUDIENCE, OAuthRequests.deviceAuthorization().get("resource"));
        assertEquals(AuthSession.SCOPES, OAuthRequests.deviceAuthorization().get("scope"));
        assertEquals(DevicePollPolicy.GRANT, OAuthRequests.deviceToken("synthetic-code").get("grant_type"));
        assertEquals(AuthSession.AUDIENCE, OAuthRequests.deviceToken("synthetic-code").get("resource"));
        assertFalse(OAuthRequests.deviceToken("synthetic-code").containsKey("client_secret"));
        DeviceAuthorization info = new DeviceAuthorization("https://auth.example/verify", "TEST-CODE", 1000, 5);
        assertFalse(info.toString().contains(info.userCode));
    }
}
