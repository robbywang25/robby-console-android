package wang.robby.console.android.auth;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

/** Each added write scope keeps earlier sessions signed in, but nothing wider or unknown is ever accepted. */
public final class AuthScopesTest {
    private static Set<String> set(String s) { return new HashSet<>(Arrays.asList(s.split(" "))); }
    @Test public void newSignInsAskForTheAvatarScopeAndOldSessionsStayValid() {
        assertTrue(set(AuthSession.PROFILE_SCOPES).contains(AuthSession.PROFILE_WRITE));
        assertFalse(set(AuthSession.PREVIOUS_SCOPES).contains(AuthSession.PROFILE_WRITE));
        Set<String> previous = set(AuthSession.PREVIOUS_SCOPES); previous.add(AuthSession.PROFILE_WRITE);
        assertEquals("the avatar set is the old one plus exactly the avatar scope", set(AuthSession.PROFILE_SCOPES), previous);
        assertTrue(AuthSession.acceptedScope(AuthSession.SCOPES));
        assertTrue(AuthSession.acceptedScope(AuthSession.PROFILE_SCOPES));
        assertTrue(AuthSession.acceptedScope(AuthSession.PREVIOUS_SCOPES));
    }
    @Test public void newSignInsAskForTheAccountScopeOnTopOfTheAvatarSet() {
        Set<String> profile = set(AuthSession.PROFILE_SCOPES); assertFalse(profile.contains(AuthSession.ACCOUNT_WRITE));
        profile.add(AuthSession.ACCOUNT_WRITE);
        assertEquals("the new set is the avatar set plus exactly the account scope", set(AuthSession.SCOPES), profile);
        assertEquals("god.mobile.account.write", AuthSession.ACCOUNT_WRITE);
    }
    @Test public void anythingElseIsInvalid() {
        assertFalse(AuthSession.acceptedScope(null));
        assertFalse(AuthSession.acceptedScope("openid"));
        assertFalse(AuthSession.acceptedScope(AuthSession.SCOPES + " god.mobile.admin"));
        assertFalse(AuthSession.acceptedScope(AuthSession.PREVIOUS_SCOPES.replace(" god.mobile.tokens.read", "")));
        assertFalse("the account scope alone on the oldest set is not a set any version asked for",
            AuthSession.acceptedScope(AuthSession.PREVIOUS_SCOPES + " " + AuthSession.ACCOUNT_WRITE));
    }
    @Test public void googleSignInIsReadOnlyAndNeverAnAutheliaSet() {
        Set<String> federated = set(AuthSession.FEDERATED_SCOPES);
        assertTrue(federated.contains("god.mobile.read") && federated.contains("god.mobile.tokens.read"));
        for (String scope : federated) assertTrue(scope, scope.endsWith(".read"));
        assertFalse(federated.contains(AuthSession.ACCOUNT_WRITE)); assertFalse(federated.contains(AuthSession.PROFILE_WRITE));
        assertFalse("a federated set is not accepted as an Authelia session", AuthSession.acceptedScope(AuthSession.FEDERATED_SCOPES));
        assertTrue(set(AuthSession.SCOPES).containsAll(federated));
        assertTrue(AuthSession.FEDERATED_START.startsWith("https://god.robby.wang/session/app/"));
    }
}
