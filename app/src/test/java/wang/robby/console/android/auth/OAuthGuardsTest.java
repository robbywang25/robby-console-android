package wang.robby.console.android.auth;

import org.junit.Test;
import static org.junit.Assert.*;

public class OAuthGuardsTest {
    private static final String ISSUER = "https://auth.god.robby.wang";
    private static final String REDIRECT = "https://god.robby.wang/mobile/android/callback";
    private static final String QUERY = "?code=valid-code&state=expected&iss=https%3A%2F%2Fauth.god.robby.wang";
    private void reject(String input) {
        assertThrows(Exception.class, () -> OAuthGuards.callback(input, REDIRECT, ISSUER, "expected", 1000, 2000));
    }
    @Test public void rfc7636ChallengeVector() throws Exception {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                OAuthGuards.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }
    @Test public void secretsAreIndependentAndHighEntropyShape() {
        String first = OAuthGuards.randomSecret(), second = OAuthGuards.randomSecret();
        assertTrue(first.matches("[A-Za-z0-9_-]{43}"));
        assertNotEquals(first, second);
    }
    @Test public void acceptsExactCallback() throws Exception {
        assertEquals("valid-code", OAuthGuards.callback(REDIRECT + QUERY, REDIRECT, ISSUER, "expected", 1000, 2000));
    }
    @Test public void rejectsRedirectConfusion() {
        reject("http://god.robby.wang/mobile/android/callback" + QUERY);
        reject("https://god.robby.wang.evil.test/mobile/android/callback" + QUERY);
        reject("https://user@god.robby.wang/mobile/android/callback" + QUERY);
        reject("https://god.robby.wang:443/mobile/android/callback" + QUERY);
        reject(REDIRECT + "/" + QUERY);
        reject("https://god.robby.wang/mobile/android/%63allback" + QUERY);
        reject(REDIRECT + QUERY + "#fragment");
    }
    @Test public void rejectsDuplicateAndMissingState() {
        reject(REDIRECT + QUERY + "&state=expected");
        reject(REDIRECT + QUERY + "&%73tate=expected");
        reject(REDIRECT + QUERY.replace("&state=expected", ""));
        reject(REDIRECT + QUERY.replace("state=expected", "state=wrong"));
    }
    @Test public void requiresExactIssuerOnce() {
        reject(REDIRECT + QUERY + "&iss=https%3A%2F%2Fauth.god.robby.wang");
        reject(REDIRECT + QUERY.replace("auth.god.robby.wang", "evil.test"));
        reject(REDIRECT + "?code=valid-code&state=expected");
    }
    @Test public void rejectsErrorsAndMalformedCode() {
        reject(REDIRECT + QUERY + "&error=access_denied");
        reject(REDIRECT + QUERY.replace("code=valid-code", "error=access_denied"));
        reject(REDIRECT + QUERY.replace("code=valid-code", "code="));
        reject(REDIRECT + QUERY.replace("code=valid-code", "code=line%0Abreak"));
        reject(REDIRECT + QUERY + "&code=other");
    }
    @Test public void boundsPendingLifetimeAndRejectsClockRollback() throws Exception {
        assertThrows(Exception.class, () -> OAuthGuards.callback(REDIRECT + QUERY, REDIRECT, ISSUER, "expected", 1000, 601001));
        assertThrows(Exception.class, () -> OAuthGuards.callback(REDIRECT + QUERY, REDIRECT, ISSUER, "expected", 1000, 999));
        assertEquals("valid-code", OAuthGuards.callback(REDIRECT + QUERY, REDIRECT, ISSUER, "expected", 1000, 601000));
    }
    @Test public void rejectsUnsafeEndpointOrigins() throws Exception {
        assertEquals(ISSUER + "/token", OAuthGuards.endpoint(ISSUER + "/token", ISSUER).toString());
        for (String url : new String[] {"http://auth.god.robby.wang/token", ISSUER + ":444/token",
                "https://auth.god.robby.wang.evil.test/token", "https://user@auth.god.robby.wang/token",
                ISSUER + "/token?x=1", ISSUER + "/token#fragment"}) {
            assertThrows(Exception.class, () -> OAuthGuards.endpoint(url, ISSUER));
        }
    }
    @Test public void scopeValidationRejectsExpansionOmissionAndDuplicates() {
        assertTrue(OAuthGuards.exactScopes("read openid", "openid read"));
        assertFalse(OAuthGuards.exactScopes("openid read write", "openid read"));
        assertFalse(OAuthGuards.exactScopes("openid", "openid read"));
        assertFalse(OAuthGuards.exactScopes("openid read read", "openid read"));
        assertFalse(OAuthGuards.exactScopes("openid  read", "openid read"));
        assertFalse(OAuthGuards.exactScopes(null, "openid read"));
    }
    @Test public void tokenHeaderSafetyRejectsWhitespaceControlsAndExcessSize() {
        assertTrue(OAuthGuards.safeToken("abc.DEF-123_~+/=", 100));
        for (String value : new String[] {"", "abc def", "a\r\nb", "a\tb", "中文", "abc\u007f"})
            assertFalse(OAuthGuards.safeToken(value, 100));
        assertFalse(OAuthGuards.safeToken("12345", 4));
        assertFalse(OAuthGuards.safeToken(null, 100));
    }
    @Test public void tokenResponseRequiresTypeScopeRefreshAndBoundedIntegerLifetime() throws Exception {
        assertEquals(3600, OAuthGuards.tokenLifetime("access", "refresh", "Bearer", "openid read", 3600, "openid read"));
        for (Object expiry : new Object[] {null, "3600", 3600.0, -1, 0, 59, 86401, Long.MAX_VALUE})
            assertThrows(Exception.class, () -> OAuthGuards.tokenLifetime("access", "refresh", "Bearer", "openid read", expiry, "openid read"));
        assertThrows(Exception.class, () -> OAuthGuards.tokenLifetime("access", "refresh", "MAC", "openid read", 3600, "openid read"));
        assertThrows(Exception.class, () -> OAuthGuards.tokenLifetime("access", null, "Bearer", "openid read", 3600, "openid read"));
        assertThrows(Exception.class, () -> OAuthGuards.tokenLifetime("access", "refresh", "Bearer", null, 3600, "openid read"));
        assertThrows(Exception.class, () -> OAuthGuards.tokenLifetime("access", "refresh", null, "openid read", 3600, "openid read"));
    }
}
