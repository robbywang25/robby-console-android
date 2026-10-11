package wang.robby.console.android.auth;

import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class OAuthRequestsTest {
    @Test public void allFlowsUseExplicitAudienceWithoutResourceOrClientSecret() throws Exception {
        for (Map<String, String> request : List.of(OAuthRequests.authorization("verifier", "state"),
                OAuthRequests.code("code", "verifier"), OAuthRequests.refresh("refresh"),
                OAuthRequests.deviceAuthorization(), OAuthRequests.deviceToken("synthetic-device-code"))) {
            assertEquals("https://god.robby.wang/api/mobile/v1", request.get("audience"));
            assertFalse(request.containsKey("resource"));
            assertFalse(request.containsKey("client_secret"));
            assertEquals("robby-console-android", request.get("client_id"));
        }
    }
    @Test public void authorizationAndRefreshMatchIosContract() throws Exception {
        Map<String, String> authorization = OAuthRequests.authorization("verifier", "state");
        assertEquals("S256", authorization.get("code_challenge_method"));
        assertEquals(OAuthGuards.challenge("verifier"), authorization.get("code_challenge"));
        assertEquals("state", authorization.get("state"));
        assertEquals(AuthSession.REDIRECT_URI, authorization.get("redirect_uri"));
        assertFalse(authorization.containsKey("code_verifier"));
        Map<String, String> code = OAuthRequests.code("code", "verifier");
        assertEquals("authorization_code", code.get("grant_type"));
        assertEquals("verifier", code.get("code_verifier"));
        assertEquals("refresh_token", OAuthRequests.refresh("refresh").get("grant_type"));
        assertFalse(OAuthRequests.refresh("refresh").containsKey("scope"));
    }
}
