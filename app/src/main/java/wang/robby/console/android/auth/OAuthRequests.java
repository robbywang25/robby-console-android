package wang.robby.console.android.auth;

import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit audience wire contract for the deployed Authelia 4.39 native client. */
final class OAuthRequests {
    private OAuthRequests() {}
    static Map<String, String> authorization(String verifier, String state) throws Exception {
        return parameters("client_id", AuthSession.CLIENT_ID, "redirect_uri", AuthSession.REDIRECT_URI,
                "response_type", "code", "scope", AuthSession.SCOPES, "audience", AuthSession.AUDIENCE,
                "state", state, "code_challenge", OAuthGuards.challenge(verifier), "code_challenge_method", "S256");
    }
    static Map<String, String> code(String code, String verifier) {
        return parameters("grant_type", "authorization_code", "code", code, "code_verifier", verifier,
                "client_id", AuthSession.CLIENT_ID, "redirect_uri", AuthSession.REDIRECT_URI,
                "audience", AuthSession.AUDIENCE);
    }
    static Map<String, String> refresh(String refresh) {
        return parameters("grant_type", "refresh_token", "refresh_token", refresh,
                "client_id", AuthSession.CLIENT_ID, "audience", AuthSession.AUDIENCE);
    }
    static Map<String, String> deviceAuthorization() {
        return parameters("client_id", AuthSession.CLIENT_ID, "scope", AuthSession.SCOPES, "audience", AuthSession.AUDIENCE);
    }
    static Map<String, String> deviceToken(String deviceCode) {
        return parameters("grant_type", DevicePollPolicy.GRANT, "device_code", deviceCode,
                "client_id", AuthSession.CLIENT_ID, "audience", AuthSession.AUDIENCE);
    }
    private static Map<String, String> parameters(String... pairs) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i], pairs[i + 1]);
        return result;
    }
}
