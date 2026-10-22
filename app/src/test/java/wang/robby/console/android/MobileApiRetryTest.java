package wang.robby.console.android;

import org.junit.Test;
import static org.junit.Assert.*;

/** Only a rejected bearer earns the single forced-refresh retry; every other failure surfaces unchanged. */
public final class MobileApiRetryTest {
    @Test public void unauthorizedReadRetriesOnceWithFreshToken() {
        assertTrue(MobileApi.retryWithFreshToken(new MobileApi.ApiError(401, "invalid_token", "会话已失效，请重新登录")));
    }
    @Test public void permissionRateLimitAndServerErrorsAreNotRetried() {
        for (int status : new int[] {400, 403, 404, 409, 429, 500, 503})
            assertFalse(MobileApi.retryWithFreshToken(new MobileApi.ApiError(status, "http_error", "x")));
    }
    @Test public void preferenceConflictCanCarryTheCurrentDocument() {
        assertEquals(24576, MobileApi.responseLimit(409, AccountCenter.PREFERENCES));
        assertTrue(MobileApi.responseLimit(409, AccountCenter.PREFERENCES) >= 16384 + 8192);
        assertEquals(16384, MobileApi.responseLimit(409, AccountCenter.SIGNATURE));
        assertEquals(16384, MobileApi.responseLimit(400, AccountCenter.PREFERENCES));
        assertEquals(524288, MobileApi.responseLimit(200, AccountCenter.PREFERENCES));
        assertEquals(2097152, MobileApi.responseLimit(200, "travel-ledger/current/snapshot"));
    }
}
