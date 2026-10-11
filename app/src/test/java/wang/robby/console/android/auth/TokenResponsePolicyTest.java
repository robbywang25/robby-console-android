package wang.robby.console.android.auth;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class TokenResponsePolicyTest {
    @Test public void expectedIssuerClientAndAudienceStringOrArrayAreConsistent() throws Exception {
        TokenResponsePolicy.claims(AuthSession.ISSUER, AuthSession.CLIENT_ID, AuthSession.AUDIENCE);
        TokenResponsePolicy.claims(AuthSession.ISSUER, AuthSession.CLIENT_ID, List.of(AuthSession.AUDIENCE));
    }
    @Test public void wrongIssuerOrClientRejectedWithoutEchoingClaims() {
        TokenResponsePolicy.Invalid issuer = assertThrows(TokenResponsePolicy.Invalid.class,
                () -> TokenResponsePolicy.claims("https://wrong.example", AuthSession.CLIENT_ID, AuthSession.AUDIENCE));
        assertEquals("issuer_mismatch", issuer.reason); assertFalse(issuer.getMessage().contains("wrong.example"));
        for (Object client : new Object[] {null, 1, "robby-console-ios", "other"}) {
            TokenResponsePolicy.Invalid error = assertThrows(TokenResponsePolicy.Invalid.class,
                    () -> TokenResponsePolicy.claims(AuthSession.ISSUER, client, AuthSession.AUDIENCE));
            assertEquals("client_mismatch", error.reason);
        }
    }
    @Test public void emptyWrongOrMalformedAudienceRejected() {
        for (Object audience : new Object[] {null, "", "https://wrong.example", List.of(), List.of("other"),
                Arrays.asList(AuthSession.AUDIENCE, null), List.of(AuthSession.AUDIENCE, 1),
                List.of(AuthSession.AUDIENCE, "has whitespace"), java.util.Collections.nCopies(9, AuthSession.AUDIENCE)}) {
            TokenResponsePolicy.Invalid error = assertThrows(TokenResponsePolicy.Invalid.class,
                    () -> TokenResponsePolicy.claims(AuthSession.ISSUER, AuthSession.CLIENT_ID, audience));
            assertEquals("audience_mismatch", error.reason);
        }
    }
    @Test public void omittedRefreshFieldsInheritExactPriorValuesOnly() throws Exception {
        assertEquals("old-refresh", TokenResponsePolicy.field(false, null, "old-refresh", "refresh_token"));
        assertEquals(AuthSession.SCOPES, TokenResponsePolicy.field(false, null, AuthSession.SCOPES, "scope"));
        assertEquals("rotated-refresh", TokenResponsePolicy.field(true, "rotated-refresh", "old-refresh", "refresh_token"));
        assertThrows(TokenResponsePolicy.Invalid.class, () -> TokenResponsePolicy.field(false, null, null, "refresh_token"));
        assertThrows(TokenResponsePolicy.Invalid.class, () -> TokenResponsePolicy.field(false, null, null, "scope"));
    }
    @Test public void explicitNullEmptyOrWrongTypeNeverFallsBackToOldGrant() {
        for (Object returned : new Object[] {null, "", 1, true, List.of("openid")}) {
            assertThrows(TokenResponsePolicy.Invalid.class, () -> TokenResponsePolicy.field(true, returned, "old-refresh", "refresh_token"));
            assertThrows(TokenResponsePolicy.Invalid.class, () -> TokenResponsePolicy.field(true, returned, AuthSession.SCOPES, "scope"));
        }
    }
    @Test public void returnedScopeCannotExpandOrSilentlyInheritWhenPresent() throws Exception {
        String expanded = TokenResponsePolicy.field(true, AuthSession.SCOPES + " unrequested.write", AuthSession.SCOPES, "scope");
        assertFalse(OAuthGuards.exactScopes(expanded, AuthSession.SCOPES));
        String narrowed = TokenResponsePolicy.field(true, "openid", AuthSession.SCOPES, "scope");
        assertFalse(OAuthGuards.exactScopes(narrowed, AuthSession.SCOPES));
    }
    @Test public void successResponseDiagnosticsNeverContainServerValues() {
        assertEquals("phase=token status=200 error=token_validation_failed reason=audience_mismatch", TokenResponsePolicy.diagnostic("audience_mismatch"));
        assertEquals("phase=token status=200 error=token_validation_failed reason=token_fields", TokenResponsePolicy.diagnostic("https://wrong.example?token=synthetic"));
    }
}
