package wang.robby.console.android.auth;

import java.util.List;
import java.util.Set;

/** Reject-only consistency checks. These never replace resource-server JWT signature validation. */
final class TokenResponsePolicy {
    static final class Invalid extends Exception {
        final String reason;
        Invalid(String reason) { super("登录响应校验失败。"); this.reason = reason; }
    }
    static void require(boolean valid, String reason) throws Invalid { if (!valid) throw new Invalid(reason); }
    static String field(boolean present, Object returned, String previous, String reason) throws Invalid {
        if (!present && previous != null) return previous;
        require(returned instanceof String && !((String) returned).isEmpty(), reason);
        return (String) returned;
    }
    static void claims(Object issuer, Object client, Object audience) throws Invalid { claims(AuthSession.ISSUER, issuer, client, audience); }
    static void claims(String expectedIssuer, Object issuer, Object client, Object audience) throws Invalid {
        require(expectedIssuer.equals(issuer), "issuer_mismatch");
        require(AuthSession.CLIENT_ID.equals(client), "client_mismatch");
        boolean valid = AuthSession.AUDIENCE.equals(audience);
        if (audience instanceof List<?>) {
            List<?> values = (List<?>) audience;
            valid = !values.isEmpty() && values.size() <= 8 && values.contains(AuthSession.AUDIENCE);
            for (Object value : values) valid &= value instanceof String && OAuthGuards.safeToken((String) value, 512);
        }
        require(valid, "audience_mismatch");
    }
    static String diagnostic(String reason) {
        Set<String> safe = Set.of("access_token", "refresh_token", "token_type", "scope", "expires_in",
                "jwt_format", "issuer_mismatch", "client_mismatch", "audience_mismatch");
        return "phase=token status=200 error=token_validation_failed reason="
                + (reason != null && safe.contains(reason) ? reason : "token_fields");
    }
}
