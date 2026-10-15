package wang.robby.console.android.auth;

import java.net.HttpCookie;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Wire policy for the native Passkey sign-in, the Android counterpart of iOS NativePasskeyAuthentication. The app talks
 * to the deployed Authelia JSON ceremony and consent endpoints only; credential verification and token issuance stay
 * with Authelia. Pure Java (org.json, java.net) so every check runs in JVM tests.
 */
final class NativePasskey {
    private NativePasskey() {}
    static final String RP_ID = URI.create(AuthSession.ISSUER).getHost();
    static final String COOKIE = "authelia_robby_session";
    static final String AUTHORIZATION_PATH = "/api/oidc/authorization";
    static final Set<String> PATHS = Set.of("/api/firstfactor/passkey", "/api/state", "/api/oidc/consent");
    private static final Set<String> COOKIE_DOMAINS = Set.of(RP_ID, "." + RP_ID, "god.robby.wang", ".god.robby.wang", "robby.wang", ".robby.wang");

    /** A challenge Authelia issued, and the Credential Manager request built only from its validated parts. */
    static final class Challenge {
        final String challenge, requestJson;
        Challenge(String challenge, String requestJson) { this.challenge = challenge; this.requestJson = requestJson; }
    }

    static Challenge challenge(JSONObject data) throws Exception {
        JSONObject key = data.optJSONObject("publicKey");
        OAuthGuards.require(key != null);
        String challenge = text(key, "challenge"), rpId = text(key, "rpId"), verification = text(key, "userVerification");
        byte[] bytes = decode(challenge, 512);
        OAuthGuards.require(RP_ID.equals(rpId) && "required".equals(verification) && bytes != null && bytes.length >= 16);
        JSONArray allowed = key.optJSONArray("allowCredentials"), credentials = new JSONArray();
        if (allowed != null) {
            OAuthGuards.require(allowed.length() <= 64);
            for (int i = 0; i < allowed.length(); i++) {
                JSONObject item = allowed.optJSONObject(i);
                OAuthGuards.require(item != null && "public-key".equals(text(item, "type")) && decode(text(item, "id"), 1024) != null);
                credentials.put(new JSONObject().put("type", "public-key").put("id", text(item, "id")));
            }
        }
        long timeout = Math.max(10_000L, Math.min(300_000L, key.optLong("timeout", 60_000L)));
        JSONObject request = new JSONObject().put("challenge", challenge).put("rpId", RP_ID).put("timeout", timeout)
                .put("userVerification", "required").put("allowCredentials", credentials);
        return new Challenge(challenge, request.toString());
    }

    /**
     * Credential Manager's authentication response to the body Authelia's passkey endpoint expects. The assertion must
     * answer this challenge, from this app's signing origin, for this relying party, with the person present and verified.
     */
    static JSONObject assertion(String responseJson, String challenge, String origin) throws Exception {
        OAuthGuards.require(responseJson != null && responseJson.length() <= 32768 && origin != null);
        JSONObject in = new JSONObject(responseJson), response = in.optJSONObject("response");
        OAuthGuards.require(response != null);
        String id = text(in, "id"), raw = in.has("rawId") ? text(in, "rawId") : id;
        String clientData = text(response, "clientDataJSON"), authenticator = text(response, "authenticatorData");
        String signature = text(response, "signature"), user = text(response, "userHandle");
        OAuthGuards.require("public-key".equals(text(in, "type")) && id != null && id.equals(raw) && decode(id, 1024) != null);
        byte[] client = decode(clientData, 4096), data = decode(authenticator, 4096);
        OAuthGuards.require(client != null && data != null && data.length >= 37 && decode(signature, 1024) != null && decode(user, 128) != null);
        JSONObject collected = new JSONObject(new String(client, StandardCharsets.UTF_8));
        OAuthGuards.require("webauthn.get".equals(text(collected, "type")) && challenge.equals(text(collected, "challenge"))
                && origin.equals(text(collected, "origin")));
        byte[] rpHash = MessageDigest.getInstance("SHA-256").digest(RP_ID.getBytes(StandardCharsets.UTF_8));
        // authenticatorData: rpIdHash (32 bytes), then flags; 0x01 user present, 0x04 user verified.
        OAuthGuards.require(MessageDigest.isEqual(Arrays.copyOf(data, 32), rpHash) && (data[32] & 0x05) == 0x05);
        JSONObject credential = new JSONObject().put("id", id).put("rawId", id).put("type", "public-key")
                .put("response", new JSONObject().put("authenticatorData", authenticator).put("clientDataJSON", clientData)
                        .put("signature", signature).put("userHandle", user))
                .put("clientExtensionResults", new JSONObject())
                .put("authenticatorAttachment", "cross-platform".equals(text(in, "authenticatorAttachment")) ? "cross-platform" : "platform");
        return new JSONObject().put("response", credential).put("keepMeLoggedIn", false).put("targetURL", "").put("requestMethod", "GET");
    }

    /** The native client's origin as Credential Manager reports it: base64url SHA-256 of the signing certificate. */
    static String origin(byte[] certificate) throws Exception {
        return "android:apk-key-hash:" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(certificate));
    }

    static String flowId(URI location) throws Exception {
        OAuthGuards.require(sameIssuer(location) && "/consent/openid/decision".equals(location.getRawPath()));
        Map<String, String> query = query(location);
        OAuthGuards.require(query != null && query.size() == 2 && "openid_connect".equals(query.get("flow")));
        String id = query.get("flow_id");
        OAuthGuards.require(id != null && id.length() == 36 && UUID.fromString(id).toString().equals(id.toLowerCase(Locale.ROOT)));
        return id;
    }

    /** The consent Authelia asks for must be exactly this app's request: no other client, scope, audience or claim. */
    static void consent(JSONObject data) throws Exception {
        OAuthGuards.require(AuthSession.CLIENT_ID.equals(text(data, "client_id")) && !data.optBoolean("require_login", true));
        List<String> scopes = strings(data.opt("scopes")), audience = strings(data.opt("audience")), resource = strings(data.opt("resource"));
        List<String> claims = strings(data.opt("claims")), essential = strings(data.opt("essential_claims"));
        OAuthGuards.require(scopes != null && audience != null && resource != null && claims != null && essential != null);
        OAuthGuards.require(OAuthGuards.exactScopes(String.join(" ", scopes), AuthSession.SCOPES) && claims.isEmpty() && essential.isEmpty());
        Set<String> targets = new HashSet<>(audience); targets.addAll(resource);
        OAuthGuards.require(new HashSet<>(audience).size() == audience.size() && new HashSet<>(resource).size() == resource.size()
                && targets.equals(Set.of(AuthSession.AUDIENCE)));
    }

    /** After consent Authelia resumes the original authorization request, adding only the consent id. */
    static void resume(URI resume, URI original, String flowId) throws Exception {
        OAuthGuards.require(sameIssuer(resume) && original.getRawPath().equals(resume.getRawPath()));
        Map<String, String> before = query(original), after = query(resume);
        OAuthGuards.require(before != null && after != null && after.size() == before.size() + 1 && flowId.equals(after.get("consent_id")));
        for (Map.Entry<String, String> item : before.entrySet()) OAuthGuards.require(item.getValue().equals(after.get(item.getKey())));
    }

    static URI authorization(String url) throws Exception {
        URI uri = new URI(url);
        OAuthGuards.require(sameIssuer(uri) && AUTHORIZATION_PATH.equals(uri.getRawPath()) && query(uri) != null);
        return uri;
    }

    static boolean callback(URI uri) {
        URI expected = URI.create(AuthSession.REDIRECT_URI);
        return "https".equals(uri.getScheme()) && expected.getRawAuthority().equals(uri.getRawAuthority())
                && expected.getRawPath().equals(uri.getRawPath()) && uri.getRawUserInfo() == null && uri.getRawFragment() == null;
    }

    /** A redirect target; relative locations resolve against the request. Never followed blindly. */
    static URI location(URI request, String location) throws Exception {
        OAuthGuards.require(location != null && !location.isEmpty() && location.length() <= 16384);
        return request.resolve(new URI(location));
    }

    /** Keeps only Authelia's session cookie, and only when it is Secure, HttpOnly and scoped to the expected domain. */
    static String cookie(List<String> headers, String current) throws Exception {
        if (headers == null) return current;
        for (String header : headers) {
            for (HttpCookie cookie : HttpCookie.parse(header)) {
                if (!COOKIE.equals(cookie.getName())) continue;
                String domain = cookie.getDomain() == null ? RP_ID : cookie.getDomain().toLowerCase(Locale.ROOT);
                OAuthGuards.require(cookie.getSecure() && cookie.isHttpOnly() && COOKIE_DOMAINS.contains(domain)
                        && cookie.getValue().length() <= 8192 && OAuthGuards.safeToken(cookie.getValue(), 8192));
                current = COOKIE + "=" + cookie.getValue();
            }
        }
        return current;
    }

    static boolean sameIssuer(URI uri) {
        URI issuer = URI.create(AuthSession.ISSUER);
        return "https".equals(uri.getScheme()) && issuer.getHost().equals(uri.getHost()) && uri.getPort() == issuer.getPort()
                && uri.getRawUserInfo() == null && uri.getRawFragment() == null;
    }

    /** Form-decoded query, as Authelia re-encodes it; null for a missing query or a repeated name. */
    static Map<String, String> query(URI uri) throws Exception {
        if (uri.getRawQuery() == null || uri.getRawQuery().isEmpty()) return null;
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : uri.getRawQuery().split("&", -1)) {
            String[] pair = part.split("=", 2);
            if (pair.length != 2) return null;
            String key = URLDecoder.decode(pair[0], "UTF-8"), value = URLDecoder.decode(pair[1], "UTF-8");
            if (out.put(key, value) != null) return null;
        }
        return out;
    }

    static byte[] decode(String value, int maximum) {
        if (value == null || value.isEmpty() || value.length() > maximum * 2 || !value.matches("[A-Za-z0-9_-]+")) return null;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(value);
            boolean canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value);
            return bytes.length <= maximum && canonical ? bytes : null;
        } catch (IllegalArgumentException e) { return null; }
    }

    /** A JSON string member, or null when absent, null or not a string (Android's optString would print "null"). */
    private static String text(JSONObject object, String name) {
        Object value = object.opt(name);
        return value instanceof String ? (String) value : null;
    }
    /** A JSON array of strings; JSON null or absence is an empty list, anything else malformed is null. */
    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value == null || JSONObject.NULL.equals(value)) return out;
        if (!(value instanceof JSONArray)) return null;
        JSONArray array = (JSONArray) value;
        for (int i = 0; i < array.length(); i++) { Object item = array.opt(i); if (!(item instanceof String)) return null; out.add((String) item); }
        return out;
    }
}
