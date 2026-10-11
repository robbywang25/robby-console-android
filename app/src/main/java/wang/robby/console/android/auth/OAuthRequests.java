package wang.robby.console.android.auth;

import java.util.LinkedHashMap;
import java.util.Map;

/** RFC 8707 resource wire contract shared with the released iOS client. */
final class OAuthRequests {
    private OAuthRequests() {}
    static Map<String, String> authorization(String verifier, String state) throws Exception {
        return parameters("client_id", AuthSession.CLIENT_ID, "redirect_uri", AuthSession.REDIRECT_URI,
                "response_type", "code", "scope", AuthSession.SCOPES, "resource", AuthSession.AUDIENCE,
                "state", state, "code_challenge", OAuthGuards.challenge(verifier), "code_challenge_method", "S256");
    }
    static Map<String, String> code(String code, String verifier) {
        return parameters("grant_type", "authorization_code", "code", code, "code_verifier", verifier,
                "client_id", AuthSession.CLIENT_ID, "redirect_uri", AuthSession.REDIRECT_URI,
                "resource", AuthSession.AUDIENCE);
    }
    static Map<String, String> refresh(String refresh) {
        return parameters("grant_type", "refresh_token", "refresh_token", refresh,
                "client_id", AuthSession.CLIENT_ID, "resource", AuthSession.AUDIENCE);
    }
    private static Map<String, String> parameters(String... pairs) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i], pairs[i + 1]);
        return result;
    }
}
