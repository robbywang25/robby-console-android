package wang.robby.console.android.auth;

import java.util.Set;

/** Only a well-formed OAuth invalid_grant response invalidates a refresh session. */
final class RefreshFailurePolicy {
    private static final Set<String> ALLOWED_CODES = Set.of(
            "invalid_grant", "invalid_client", "invalid_request", "invalid_target", "invalid_scope",
            "unauthorized_client", "unsupported_grant_type", "temporarily_unavailable", "server_error",
            "access_denied", "authorization_pending", "slow_down", "expired_token");
    static String safeCode(Object value) {
        return value instanceof String && ALLOWED_CODES.contains(value) ? (String) value : "unrecognized_error";
    }
    static boolean invalidatesSession(String phase, int status, Object value) {
        // RFC 6749 invalid_grant is an HTTP 400 error. Inconsistent 401/403/5xx
        // responses are server/configuration failures, never grounds to erase tokens.
        return "token".equals(phase) && status == 400 && "invalid_grant".equals(safeCode(value));
    }
    static String phase(String endpoint) {
        if ((AuthSession.ISSUER + "/.well-known/openid-configuration").equals(endpoint)) return "discovery";
        if ((AuthSession.ISSUER + "/api/oidc/token").equals(endpoint)) return "token";
        if ((AuthSession.ISSUER + "/api/oidc/device-authorization").equals(endpoint)) return "device-authorization";
        if ((AuthSession.ISSUER + "/api/oidc/revocation").equals(endpoint)) return "revocation";
        return "unknown";
    }
    static String diagnostic(String phase, int status, Object value) {
        int safeStatus = status >= 100 && status <= 599 ? status : 0;
        String safePhase = Set.of("discovery", "token", "device-authorization", "revocation").contains(phase == null ? "" : phase) ? phase : "unknown";
        return "phase=" + safePhase + " status=" + safeStatus + " error=" + safeCode(value);
    }
}
