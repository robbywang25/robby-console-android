package wang.robby.console.android.auth;

import org.junit.Test;
import static org.junit.Assert.*;

public class RefreshFailurePolicyTest {
    @Test public void onlyHttp400InvalidGrantClearsSession() {
        assertTrue(RefreshFailurePolicy.invalidatesSession("token", 400, "invalid_grant"));
        for (int status : new int[] {0, 200, 302, 401, 403, 429, 500, 503})
            assertFalse(RefreshFailurePolicy.invalidatesSession("token", status, "invalid_grant"));
    }
    @Test public void configurationScopeResourceAndTransientErrorsAlwaysPreserveSession() {
        for (String code : new String[] {"invalid_client", "invalid_request", "invalid_target", "invalid_scope",
                "unauthorized_client", "unsupported_grant_type", "temporarily_unavailable", "server_error",
                "access_denied", "authorization_pending", "slow_down", "expired_token"})
            for (int status : new int[] {400, 401, 403, 429, 500, 503})
                assertFalse(RefreshFailurePolicy.invalidatesSession("token", status, code));
    }
    @Test public void missingMalformedAndUnknownErrorsNeverClearSession() {
        for (Object code : new Object[] {null, true, 1, new String[] {"invalid_grant"}, "INVALID_GRANT",
                " invalid_grant", "invalid_grant\n", "other", ""}) {
            assertFalse(RefreshFailurePolicy.invalidatesSession("token", 400, code));
            assertEquals("unrecognized_error", RefreshFailurePolicy.safeCode(code));
        }
    }
    @Test public void diagnosticsContainOnlyFixedShapeStatusAndAllowlistedProtocolCode() {
        assertEquals("phase=token status=400 error=invalid_client", RefreshFailurePolicy.diagnostic("token", 400, "invalid_client"));
        assertEquals("phase=token status=503 error=temporarily_unavailable", RefreshFailurePolicy.diagnostic("token", 503, "temporarily_unavailable"));
        for (String injection : new String[] {"synthetic-token", "https://private.example/?code=synthetic",
                "invalid_grant\nuser=synthetic", "{\"error_description\":\"synthetic\"}"})
            assertEquals("phase=token status=400 error=unrecognized_error", RefreshFailurePolicy.diagnostic("token", 400, injection));
        assertEquals("phase=token status=0 error=unrecognized_error", RefreshFailurePolicy.diagnostic("token", -1, null));
    }
    @Test public void discoveryOrOtherEndpointCannotInvalidateRefreshSessionEvenWithInvalidGrant() {
        for (String phase : new String[] {"discovery", "device-authorization", "revocation", "unknown", null})
            assertFalse(RefreshFailurePolicy.invalidatesSession(phase, 400, "invalid_grant"));
        assertEquals("discovery", RefreshFailurePolicy.phase(AuthSession.ISSUER + "/.well-known/openid-configuration"));
        assertEquals("token", RefreshFailurePolicy.phase(AuthSession.ISSUER + "/api/oidc/token"));
        assertEquals("device-authorization", RefreshFailurePolicy.phase(AuthSession.ISSUER + "/api/oidc/device-authorization"));
        assertEquals("revocation", RefreshFailurePolicy.phase(AuthSession.ISSUER + "/api/oidc/revocation"));
        assertEquals("unknown", RefreshFailurePolicy.phase(AuthSession.ISSUER + "/api/oidc/token?code=synthetic"));
        assertEquals("phase=unknown status=400 error=invalid_grant",
                RefreshFailurePolicy.diagnostic("https://example.test?code=synthetic", 400, "invalid_grant"));
    }
}
