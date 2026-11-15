package wang.robby.console.android.auth;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONObject;

/** Exact routing policy for the domain transition; an issuer is never an arbitrary network destination. */
final class AuthIssuerPolicy {
    static final String PRIMARY = "https://auth.robby.wang";
    static final String LEGACY = "https://auth.god.robby.wang";
    private AuthIssuerPolicy() {}

    static String issuer(Object value) throws Exception {
        OAuthGuards.require(PRIMARY.equals(value) || LEGACY.equals(value));
        return (String) value;
    }

    static String origin(URI url) throws Exception {
        OAuthGuards.require("https".equals(url.getScheme()) && url.getRawUserInfo() == null
                && url.getPort() == -1 && url.getRawFragment() == null);
        return issuer("https://" + url.getRawAuthority());
    }

    /** Pre-migration pending requests were always issued by the legacy domain. */
    static String pendingIssuer(JSONObject pending) throws Exception {
        return pending.has("issuer") ? issuer(pending.opt("issuer")) : LEGACY;
    }

    /**
     * Old encrypted records did not store issuer separately. Their access token provides a bounded routing hint,
     * checked against an explicit stored issuer when present. This does not replace server-side JWT verification.
     */
    static String sessionIssuer(JSONObject session) throws Exception {
        String claimed = issuer(claims(session.getString("access_token")).opt("iss"));
        if (session.has("issuer")) OAuthGuards.require(claimed.equals(issuer(session.opt("issuer"))));
        return claimed;
    }

    static JSONObject claims(String access) throws TokenResponsePolicy.Invalid {
        try {
            TokenResponsePolicy.require(access != null && access.length() <= 32768, "jwt_format");
            String[] parts = access.split("\\.", -1);
            TokenResponsePolicy.require(parts.length == 3, "jwt_format");
            byte[][] decoded = new byte[3][];
            for (int i = 0; i < 3; i++) {
                TokenResponsePolicy.require(parts[i].matches("[A-Za-z0-9_-]+"), "jwt_format");
                decoded[i] = Base64.getUrlDecoder().decode(parts[i]);
                TokenResponsePolicy.require(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded[i]).equals(parts[i]), "jwt_format");
            }
            JSONObject header = new JSONObject(new String(decoded[0], StandardCharsets.UTF_8));
            TokenResponsePolicy.require("RS256".equals(header.opt("alg"))
                    && ("at+jwt".equals(header.opt("typ")) || "application/at+jwt".equals(header.opt("typ"))), "jwt_format");
            return new JSONObject(new String(decoded[1], StandardCharsets.UTF_8));
        } catch (Exception ignored) { throw new TokenResponsePolicy.Invalid("jwt_format"); }
    }
}
