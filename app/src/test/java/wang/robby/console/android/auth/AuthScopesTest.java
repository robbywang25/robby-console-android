package wang.robby.console.android.auth;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

/** Adding the avatar scope keeps earlier sessions signed in, but never accepts anything wider or unknown. */
public final class AuthScopesTest {
    private static Set<String> set(String s) { return new HashSet<>(Arrays.asList(s.split(" "))); }
    @Test public void newSignInsAskForTheAvatarScopeAndOldSessionsStayValid() {
        assertTrue(set(AuthSession.SCOPES).contains(AuthSession.PROFILE_WRITE));
        assertFalse(set(AuthSession.PREVIOUS_SCOPES).contains(AuthSession.PROFILE_WRITE));
        Set<String> previous = set(AuthSession.PREVIOUS_SCOPES); previous.add(AuthSession.PROFILE_WRITE);
        assertEquals("the new set is the old one plus exactly the avatar scope", set(AuthSession.SCOPES), previous);
        assertTrue(AuthSession.acceptedScope(AuthSession.SCOPES));
        assertTrue(AuthSession.acceptedScope(AuthSession.PREVIOUS_SCOPES));
    }
    @Test public void anythingElseIsInvalid() {
        assertFalse(AuthSession.acceptedScope(null));
        assertFalse(AuthSession.acceptedScope("openid"));
        assertFalse(AuthSession.acceptedScope(AuthSession.SCOPES + " god.mobile.admin"));
        assertFalse(AuthSession.acceptedScope(AuthSession.PREVIOUS_SCOPES.replace(" god.mobile.tokens.read", "")));
    }
}
