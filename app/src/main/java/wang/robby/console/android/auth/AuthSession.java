package wang.robby.console.android.auth;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Base64;
import java.util.function.LongSupplier;
import javax.net.ssl.HttpsURLConnection;

/**
 * Public-client Authorization Code + PKCE session. All network methods must run on
 * a worker thread. Safe across Activity recreation and multiple helper instances
 * in the app's single Android process. Does not parse or trust ID-token claims.
 */
public final class AuthSession {
    public static final String ISSUER = "https://auth.god.robby.wang";
    public static final String CLIENT_ID = "robby-console-android";
    public static final String AUDIENCE = "https://god.robby.wang/api/mobile/v1";
    public static final String REDIRECT_URI = "https://god.robby.wang/mobile/android/callback";
    public static final String SCOPES = "openid offline_access god.mobile.read god.mobile.clipboard.read "
            + "god.mobile.travel.read god.mobile.travel.current.read god.mobile.analytics.read "
            + "god.mobile.sites.health.read god.mobile.domains.read god.mobile.okx.read "
            + "god.mobile.wechat.read god.mobile.prompts.read "
            + "god.mobile.travel.entries.write god.mobile.travel.people.write";
    private static final Object STATE_LOCK = new Object();
    private static final Object REFRESH_LOCK = new Object();
    private static long generation;
    private final EncryptedStore store;
    private volatile Discovery cachedDiscovery;

    public AuthSession(Context context) { store = new EncryptedStore(context.getApplicationContext()); }

    public String authorizationUrl() throws Exception {
        return authorizationUrl(beginAuthorization());
    }

    // Reserve synchronously before queueing network work, so logout also cancels
    // authorization tasks which have not started executing yet.
    long beginAuthorization() {
        synchronized (STATE_LOCK) { return ++generation; }
    }

    void cancelAuthorization() {
        synchronized (STATE_LOCK) {
            generation++;
            try {
                JSONObject saved = readOrReset();
                if (saved.has("pending")) { saved.remove("pending"); store.write(saved); }
            } catch (Exception ignored) { /* readOrReset has already invalidated unreadable storage. */ }
        }
    }

    Runnable cancelDeviceAuthorization(DeviceGrant grant) {
        synchronized (STATE_LOCK) {
            // Cancellation can race between the token's durable commit and the
            // coordinator's success notification. Remove only this flow's session.
            if (grant != null && grant.committed && generation == grant.reservation + 1) return clearLocal();
            cancelAuthorization();
            return () -> { };
        }
    }

    DeviceGrant startDeviceAuthorization(long expected) throws Exception {
        try {
            synchronized (STATE_LOCK) {
                OAuthGuards.require(generation == expected);
                JSONObject saved = readOrReset();
                if (saved.has("pending")) { saved.remove("pending"); store.write(saved); }
            }
            Discovery discovery = discovery();
            OAuthGuards.require(discovery.deviceAuthorization != null);
            JSONObject response = request(discovery.deviceAuthorization, OAuthRequests.deviceAuthorization(), false);
            String code = string(response, "device_code"), user = string(response, "user_code");
            OAuthGuards.require(OAuthGuards.safeToken(code, 4096) && user.matches("[A-Za-z0-9][A-Za-z0-9-]{3,63}"));
            String verification = DevicePollPolicy.verificationUri(string(response, "verification_uri"));
            int seconds = DevicePollPolicy.lifetime(response.opt("expires_in"));
            int interval = DevicePollPolicy.interval(response.has("interval") ? response.get("interval") : null);
            synchronized (STATE_LOCK) { OAuthGuards.require(generation == expected); }
            DeviceAuthorization info = new DeviceAuthorization(verification, user, System.currentTimeMillis() + seconds * 1000L, interval);
            return new DeviceGrant(info, code, expected, discovery, new DevicePollPolicy(seconds, interval, monotonicNow()));
        } catch (Exception ignored) { throw failure("无法准备设备登录，请检查网络或稍后重试。"); }
    }

    boolean pollDeviceAuthorization(DeviceGrant grant) throws Exception {
        Token result = null;
        try {
            synchronized (STATE_LOCK) { OAuthGuards.require(generation == grant.reservation && grant.code != null); }
            OAuthGuards.require(grant.policy.delay(monotonicNow()) == 0);
            JSONObject response;
            try {
                response = request(grant.discovery.token, OAuthRequests.deviceToken(grant.code), false, true);
            } catch (DeviceOAuthFailure pending) {
                grant.policy.response(pending.code, monotonicNow());
                return false;
            } catch (SocketTimeoutException timeout) {
                grant.policy.timeout(monotonicNow());
                return false;
            }
            result = token(response);
            grant.policy.delay(monotonicNow()); // Reject success arriving after the device-code deadline.
            synchronized (STATE_LOCK) {
                OAuthGuards.require(generation == grant.reservation && grant.code != null);
                store.write(new JSONObject().put("session", result.json));
                generation++;
                grant.committed = true;
            }
            result = null;
            return true;
        } finally {
            if (result != null) revoke(grant.discovery, result.refresh, result.access);
        }
    }

    static long monotonicNow() { return SystemClock.elapsedRealtime(); }

    static final class DeviceGrant {
        final DeviceAuthorization info;
        final long reservation;
        final Discovery discovery;
        final DevicePollPolicy policy;
        private final LongSupplier clock;
        private volatile String code;
        private boolean committed;
        DeviceGrant(DeviceAuthorization info, String code, long reservation, Discovery discovery, DevicePollPolicy policy) {
            this(info, code, reservation, discovery, policy, AuthSession::monotonicNow);
        }
        DeviceGrant(DeviceAuthorization info, String code, long reservation, Discovery discovery, DevicePollPolicy policy, LongSupplier clock) {
            this.info = info; this.code = code; this.reservation = reservation; this.discovery = discovery; this.policy = policy;
            this.clock = clock;
        }
        long delay() throws Exception { return policy.delay(clock.getAsLong()); }
        void clear() { code = null; }
        @Override public String toString() { return "DeviceGrant{redacted}"; }
    }

    String authorizationUrl(long expected) throws Exception {
        try {
            synchronized (STATE_LOCK) { OAuthGuards.require(expected == generation); }
            Discovery discovery = discovery();
            String verifier = OAuthGuards.randomSecret(), state = OAuthGuards.randomSecret();
            JSONObject pending = new JSONObject().put("verifier", verifier).put("state", state)
                    .put("created_at", System.currentTimeMillis());
            synchronized (STATE_LOCK) {
                OAuthGuards.require(expected == generation);
                JSONObject saved = readOrReset();
                saved.put("pending", pending);
                store.write(saved);
            }
            return discovery.authorization + "?" + form(OAuthRequests.authorization(verifier, state));
        } catch (Exception ignored) { throw failure("无法开始登录，请检查网络后重试。"); }
    }

    public void handleCallback(Uri uri) throws Exception {
        Token result = null;
        Discovery discovery = null;
        try {
            final long expected;
            final String code, verifier;
            synchronized (STATE_LOCK) {
                JSONObject saved = readOrReset(), pending = saved.optJSONObject("pending");
                OAuthGuards.require(pending != null);
                code = OAuthGuards.callback(uri == null ? null : uri.toString(), REDIRECT_URI, ISSUER,
                        string(pending, "state"), pending.getLong("created_at"), System.currentTimeMillis());
                verifier = string(pending, "verifier");
                OAuthGuards.require(verifier.matches("[A-Za-z0-9_-]{43}"));
                // Durably consume before sending the code, including across process death.
                saved.remove("pending");
                store.write(saved);
                expected = ++generation;
            }
            discovery = discovery();
            result = token(request(discovery.token, OAuthRequests.code(code, verifier), false));
            synchronized (STATE_LOCK) {
                OAuthGuards.require(generation == expected);
                store.write(new JSONObject().put("session", result.json));
                // A refresh of the previous account may already be in flight.
                // It must not replace the newly exchanged session.
                generation++;
            }
            result = null; // Persisted: ownership transfers to the session.
        } catch (Exception ignored) {
            throw failure("登录未完成或已过期，请重新登录。");
        } finally {
            if (result != null) revoke(discovery, result.refresh, result.access);
        }
    }

    public String accessToken() throws Exception {
        return accessToken(false);
    }

    /** Internal native caller may explicitly test refresh without waiting for access-token expiry. */
    public String accessToken(boolean forceRefresh) throws Exception {
        // One refresh at a time, shared by all Activity/helper instances.
        synchronized (REFRESH_LOCK) {
            Token updated = null;
            Discovery discovery = null;
            long expected = -1;
            try {
                final JSONObject current;
                synchronized (STATE_LOCK) {
                    current = readOrReset().optJSONObject("session");
                    OAuthGuards.require(current != null);
                    validateStored(current);
                    if (!forceRefresh && current.getLong("expires_at") > System.currentTimeMillis() + 60_000L)
                        return string(current, "access_token");
                    expected = generation;
                }
                discovery = discovery();
                updated = token(request(discovery.token, OAuthRequests.refresh(string(current, "refresh_token")), false), current);
                String access = updated.access;
                synchronized (STATE_LOCK) {
                    OAuthGuards.require(expected == generation);
                    JSONObject saved = readOrReset();
                    saved.put("session", updated.json);
                    store.write(saved);
                }
                updated = null;
                return access;
            } catch (HttpFailure failure) {
                if (failure.invalidSession) {
                    synchronized (STATE_LOCK) {
                        if (generation == expected) { generation++; store.clear(); }
                    }
                    throw failure("登录已失效，请重新登录。");
                }
                throw failure("暂时无法刷新登录，会话已保留，请稍后重试。");
            } catch (Exception error) {
                // Only fixed categories; never log exception messages, URLs or credentials.
                String kind = error instanceof java.net.SocketTimeoutException ? "timeout"
                    : error instanceof java.net.UnknownHostException ? "dns"
                    : error instanceof javax.net.ssl.SSLException ? "tls"
                    : error instanceof android.os.NetworkOnMainThreadException ? "main_thread"
                    : error instanceof java.io.IOException ? "io" : "validation_or_storage";
                Log.w("GodAuth", "refresh_failure=" + kind);
                throw failure("无法读取有效登录，请重新登录或稍后重试。");
            } finally {
                if (updated != null) revoke(discovery, updated.refresh, updated.access);
            }
        }
    }

    /** Locally stored session presence only; does not claim server validity. */
    public boolean hasSession() {
        synchronized (STATE_LOCK) {
            try {
                JSONObject session = readOrReset().optJSONObject("session");
                if (session == null) return false;
                validateStored(session);
                return true;
            } catch (Exception ignored) { return false; }
        }
    }

    /** Clears local state immediately; server revocation is best effort on the calling worker. */
    public void logout() {
        clearLocal().run();
    }

    /** Synchronous local invalidation; returned job only performs best-effort remote revocation. */
    Runnable clearLocal() {
        String refresh = null, access = null;
        synchronized (STATE_LOCK) {
            generation++;
            try {
                JSONObject session = store.read().optJSONObject("session");
                if (session != null) { refresh = string(session, "refresh_token"); access = string(session, "access_token"); }
            } catch (Exception ignored) { /* Removal still proceeds if keystore is unavailable. */ }
            store.clear();
        }
        final String refreshToRevoke = refresh, accessToRevoke = access;
        return () -> {
            if (refreshToRevoke != null || accessToRevoke != null) {
                try { revoke(discovery(), refreshToRevoke, accessToRevoke); } catch (Exception ignored) { }
            }
        };
    }

    private JSONObject readOrReset() throws Exception {
        try { return store.read(); }
        catch (Exception ignored) { store.clear(); throw failure("本机登录存储不可用，请重新登录。"); }
    }
    private static void validateStored(JSONObject token) throws Exception {
        OAuthGuards.require(OAuthGuards.safeToken(string(token, "access_token"), 32768)
                && OAuthGuards.safeToken(string(token, "refresh_token"), 32768)
                && SCOPES.equals(string(token, "scope")) && token.getLong("expires_at") > 0);
    }
    private static Token token(JSONObject body) throws Exception {
        return token(body, null);
    }
    private static Token token(JSONObject body, JSONObject previous) throws Exception {
        try {
            String access = TokenResponsePolicy.field(body.has("access_token"), body.opt("access_token"), null, "access_token");
            TokenResponsePolicy.require(OAuthGuards.safeToken(access, 32768), "access_token");
            String refresh = TokenResponsePolicy.field(body.has("refresh_token"), body.opt("refresh_token"),
                    previous == null ? null : string(previous, "refresh_token"), "refresh_token");
            TokenResponsePolicy.require(OAuthGuards.safeToken(refresh, 32768), "refresh_token");
            TokenResponsePolicy.require(body.opt("token_type") instanceof String && "Bearer".equalsIgnoreCase((String) body.opt("token_type")), "token_type");
            String scope = TokenResponsePolicy.field(body.has("scope"), body.opt("scope"),
                    previous == null ? null : string(previous, "scope"), "scope");
            TokenResponsePolicy.require(OAuthGuards.exactScopes(scope, SCOPES), "scope");
            Object expiry = body.opt("expires_in");
            TokenResponsePolicy.require(expiry instanceof Integer || expiry instanceof Long, "expires_in");
            long seconds = ((Number) expiry).longValue();
            TokenResponsePolicy.require(seconds >= 60 && seconds <= 86400, "expires_in");
            validateTokenClaims(access);
            JSONObject saved = new JSONObject().put("access_token", access).put("refresh_token", refresh)
                    .put("scope", SCOPES).put("expires_at", System.currentTimeMillis() + seconds * 1000L);
            return new Token(saved, access, refresh);
        } catch (TokenResponsePolicy.Invalid failure) {
            Log.w("GodAuth", TokenResponsePolicy.diagnostic(failure.reason));
            throw failure("登录响应校验失败，请重新登录或稍后重试。");
        } catch (Exception ignored) {
            Log.w("GodAuth", TokenResponsePolicy.diagnostic(null));
            throw failure("登录响应校验失败，请重新登录或稍后重试。");
        }
    }
    private static void validateTokenClaims(String access) throws TokenResponsePolicy.Invalid {
        final JSONObject claims;
        try {
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
            claims = new JSONObject(new String(decoded[1], StandardCharsets.UTF_8));
        } catch (Exception ignored) { throw new TokenResponsePolicy.Invalid("jwt_format"); }
        Object audience = claims.opt("aud");
        if (audience instanceof JSONArray) {
            JSONArray array = (JSONArray) audience;
            ArrayList<Object> values = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) values.add(array.opt(i));
            audience = values;
        }
        TokenResponsePolicy.claims(claims.opt("iss"), claims.opt("client_id"), audience);
    }
    private Discovery discovery() throws Exception {
        Discovery cached = cachedDiscovery;
        if (cached != null && System.currentTimeMillis() >= cached.created
                && System.currentTimeMillis() - cached.created < 600_000L) return cached;
        JSONObject body = request(URI.create(ISSUER + "/.well-known/openid-configuration"), null, false);
        OAuthGuards.require(ISSUER.equals(string(body, "issuer"))
                && Boolean.TRUE.equals(body.opt("authorization_response_iss_parameter_supported"))
                && contains(body, "code_challenge_methods_supported", "S256")
                && contains(body, "response_types_supported", "code")
                && contains(body, "grant_types_supported", "authorization_code")
                && contains(body, "grant_types_supported", "refresh_token")
                && contains(body, "token_endpoint_auth_methods_supported", "none"));
        URI authorization = OAuthGuards.endpoint(string(body, "authorization_endpoint"), ISSUER);
        URI token = OAuthGuards.endpoint(string(body, "token_endpoint"), ISSUER);
        URI revocation = body.has("revocation_endpoint")
                ? OAuthGuards.endpoint(string(body, "revocation_endpoint"), ISSUER) : null;
        URI device = null;
        if (body.has("device_authorization_endpoint") && contains(body, "grant_types_supported", DevicePollPolicy.GRANT)) {
            device = OAuthGuards.endpoint(string(body, "device_authorization_endpoint"), ISSUER);
            OAuthGuards.require(device.toString().equals(ISSUER + "/api/oidc/device-authorization"));
        }
        cachedDiscovery = new Discovery(authorization, token, revocation, device);
        return cachedDiscovery;
    }
    private static boolean contains(JSONObject body, String key, String value) throws Exception {
        JSONArray array = body.getJSONArray(key);
        for (int i = 0; i < array.length(); i++) if (value.equals(array.opt(i))) return true;
        return false;
    }
    private static String string(JSONObject body, String key) throws Exception {
        Object value = body.opt(key);
        OAuthGuards.require(value instanceof String);
        return (String) value;
    }
    private static void revoke(Discovery discovery, String refresh, String access) {
        if (discovery == null || discovery.revocation == null) return;
        for (String hint : new String[] {"refresh_token", "access_token"}) {
            String token = "refresh_token".equals(hint) ? refresh : access;
            if (token == null) continue;
            try { request(discovery.revocation, parameters("client_id", CLIENT_ID, "token", token,
                    "token_type_hint", hint), true); } catch (Exception ignored) { }
        }
    }
    private static JSONObject request(URI endpoint, Map<String, String> form, boolean emptyResponse) throws Exception {
        return request(endpoint, form, emptyResponse, false);
    }
    private static JSONObject request(URI endpoint, Map<String, String> form, boolean emptyResponse, boolean deviceErrors) throws Exception {
        OAuthGuards.endpoint(endpoint.toString(), ISSUER);
        HttpsURLConnection connection = (HttpsURLConnection) endpoint.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Cache-Control", "no-store");
        try {
            if (form != null) {
                byte[] bytes = form(form).getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            boolean deviceError = deviceErrors && status == 400;
            if ((status < 200 || status >= 300) && !deviceError) {
                String phase = RefreshFailurePolicy.phase(endpoint.toString());
                String protocolError = protocolError(connection);
                // Fixed phase enum, numeric status and allowlisted code only.
                // Never pass an exception, URL, body, token or user identifier.
                Log.w("GodAuth", RefreshFailurePolicy.diagnostic(phase, status, protocolError));
                boolean refresh = form != null && "refresh_token".equals(form.get("grant_type"));
                // Discovery and every non-refresh request can never erase a session.
                throw new HttpFailure(refresh && RefreshFailurePolicy.invalidatesSession(phase, status, protocolError));
            }
            if (emptyResponse) return new JSONObject();
            String contentType = connection.getContentType();
            OAuthGuards.require(contentType != null && contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json"));
            OAuthGuards.require(connection.getContentLengthLong() <= 131072L);
            try (InputStream stream = deviceError ? connection.getErrorStream() : connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                OAuthGuards.require(stream != null);
                byte[] buffer = new byte[4096]; int count;
                while ((count = stream.read(buffer)) != -1) {
                    OAuthGuards.require(output.size() + count <= 131072);
                    output.write(buffer, 0, count);
                }
                JSONObject body = new JSONObject(output.toString("UTF-8"));
                if (deviceError) {
                    OAuthGuards.require(!body.has("access_token") && !body.has("refresh_token"));
                    String code = string(body, "error");
                    if (!"authorization_pending".equals(code) && !"slow_down".equals(code))
                        Log.w("GodAuth", RefreshFailurePolicy.diagnostic(RefreshFailurePolicy.phase(endpoint.toString()), status, code));
                    throw new DeviceOAuthFailure(code);
                }
                if (deviceErrors) OAuthGuards.require(status == 200 && !body.has("error"));
                return body;
            }
        } finally { connection.disconnect(); }
    }
    private static String protocolError(HttpsURLConnection connection) {
        try {
            String type = connection.getContentType();
            if (type == null || !"application/json".equalsIgnoreCase(type.split(";", 2)[0].trim())
                    || connection.getContentLengthLong() > 16384) return "unrecognized_error";
            try (InputStream stream = connection.getErrorStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (stream == null) return "unrecognized_error";
                byte[] buffer = new byte[2048]; int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (output.size() + count > 16384) return "unrecognized_error";
                    output.write(buffer, 0, count);
                }
                org.json.JSONTokener parser = new org.json.JSONTokener(output.toString("UTF-8"));
                Object value = parser.nextValue();
                if (!(value instanceof JSONObject) || parser.nextClean() != 0) return "unrecognized_error";
                JSONObject body = (JSONObject) value;
                if (body.has("access_token") || body.has("refresh_token")) return "unrecognized_error";
                return RefreshFailurePolicy.safeCode(body.opt("error"));
            }
        } catch (Exception ignored) { return "unrecognized_error"; }
    }
    private static Map<String, String> parameters(String... pairs) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i], pairs[i + 1]);
        return result;
    }
    private static String form(Map<String, String> parameters) throws Exception {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            if (result.length() > 0) result.append('&');
            result.append(URLEncoder.encode(entry.getKey(), "UTF-8")).append('=')
                    .append(URLEncoder.encode(entry.getValue(), "UTF-8"));
        }
        return result.toString();
    }
    private static Exception failure(String message) { return new Exception(message); }
    private static final class HttpFailure extends Exception {
        final boolean invalidSession;
        HttpFailure(boolean invalidSession) { super("身份服务请求失败。"); this.invalidSession = invalidSession; }
    }
    private static final class DeviceOAuthFailure extends Exception {
        final String code;
        DeviceOAuthFailure(String code) {
            super("设备授权尚未完成。");
            this.code = ("authorization_pending".equals(code) || "slow_down".equals(code)
                    || "access_denied".equals(code) || "expired_token".equals(code)) ? code : "unsupported_error";
        }
    }
    private static final class Discovery {
        final URI authorization, token, revocation, deviceAuthorization;
        final long created = System.currentTimeMillis();
        Discovery(URI authorization, URI token, URI revocation, URI deviceAuthorization) {
            this.authorization = authorization; this.token = token; this.revocation = revocation; this.deviceAuthorization = deviceAuthorization;
        }
    }
    private static final class Token {
        final JSONObject json;
        final String access, refresh;
        Token(JSONObject json, String access, String refresh) { this.json = json; this.access = access; this.refresh = refresh; }
    }
}
