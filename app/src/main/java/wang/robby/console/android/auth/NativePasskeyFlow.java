package wang.robby.console.android.auth;

import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/**
 * One native Passkey sign-in, run on a worker thread: challenge, system assertion, Authelia verification, consent and
 * the resumed authorization request, ending at the OAuth callback URL that {@link AuthCoordinator#callback} then
 * checks and exchanges with PKCE as before. No WebView, no browser, no credential export and no native token minting.
 * The Authelia session cookie lives only in this object and is dropped when the run ends.
 */
public final class NativePasskeyFlow {
    /** Blocking hooks into the system UI; both are called on the worker thread and wait for the person. */
    public interface Ui {
        /** Shows the system Passkey sheet for this WebAuthn request JSON and returns the authentication response JSON. */
        String assertion(String requestJson) throws Exception;
        /** Asks the person to allow God to access the workbench, like the consent page would. */
        boolean consent() throws Exception;
    }
    interface Transport { Response exchange(URI url, byte[] json, String cookie) throws Exception; }
    static final class Response {
        final int status; final String type, location; final List<String> cookies; final byte[] body;
        Response(int status, String type, String location, List<String> cookies, byte[] body) {
            this.status = status; this.type = type; this.location = location; this.cookies = cookies; this.body = body;
        }
    }
    /** A failure with a message for the person; {@code cancelled} failures are silent. */
    public static final class Failure extends Exception {
        public final boolean cancelled;
        Failure(String message, boolean cancelled) { super(message); this.cancelled = cancelled; }
    }
    static final String INVALID = "原生验证响应未通过校验，请重试。";
    static final String UNAVAILABLE = "认证服务暂时无法完成登录，请稍后再试。";
    static final String VERIFICATION = "此通行密钥尚未满足账号验证要求，请使用已登记并支持指纹或面容验证的通行密钥。";
    static final String REJECTED = "认证服务没有接受这次通行密钥验证。请确认用的是 God 账号登记过的通行密钥，然后重试。";

    private final Transport transport;
    private final Ui ui;
    private final String origin;
    private volatile boolean cancelled;
    private String cookie;

    public NativePasskeyFlow(Ui ui, String origin) { this(new Https(), ui, origin); }
    NativePasskeyFlow(Transport transport, Ui ui, String origin) { this.transport = transport; this.ui = ui; this.origin = origin; }

    public static Failure cancelled() { return new Failure(null, true); }
    public static Failure failure(String message) { return new Failure(message, false); }
    public void cancel() { cancelled = true; }

    /** Returns the OAuth callback URL for {@code authorizationUrl}; never requests the callback itself. */
    public String run(String authorizationUrl) throws Failure {
        String phase = "start";
        try {
            URI authorization = NativePasskey.authorization(authorizationUrl);
            phase = "challenge";
            NativePasskey.Challenge challenge = NativePasskey.challenge(json("/api/firstfactor/passkey", null, null));
            phase = "system";
            String response = ui.assertion(challenge.requestJson);
            check();
            phase = "assertion";
            JSONObject body = NativePasskey.assertion(response, challenge.challenge, origin);
            try { json("/api/firstfactor/passkey", null, body); }
            catch (Rejected rejected) { throw new Failure(REJECTED, false); }
            phase = "state";
            if (json("/api/state", null, null).optInt("authentication_level", -1) != 2) throw new Failure(VERIFICATION, false);
            phase = "authorize";
            URI first = redirect(authorization);
            if (NativePasskey.callback(first)) return first.toString();
            String flow = NativePasskey.flowId(first);
            phase = "consent";
            NativePasskey.consent(json("/api/oidc/consent", "flow_id=" + flow, null));
            if (!ui.consent()) throw cancelled();
            check();
            JSONObject accepted = json("/api/oidc/consent", null, new JSONObject().put("client_id", AuthSession.CLIENT_ID)
                    .put("flow_id", flow).put("consent", true).put("pre_configure", false).put("claims", new org.json.JSONArray()));
            Object target = accepted.opt("redirect_uri");
            OAuthGuards.require(target instanceof String);
            URI resume = new URI((String) target);
            NativePasskey.resume(resume, authorization, flow);
            phase = "resume";
            URI callback = redirect(resume);
            OAuthGuards.require(NativePasskey.callback(callback));
            return callback.toString();
        } catch (Failure failure) {
            if (!failure.cancelled) Log.w("GodAuth", "native passkey failed: " + phase);
            throw failure;
        } catch (Unavailable | IOException e) {
            Log.w("GodAuth", "native passkey unavailable: " + phase);
            throw new Failure(UNAVAILABLE, false);
        } catch (Exception e) {
            // Fixed phase name only; never the exception, a URL, a body or a cookie.
            Log.w("GodAuth", "native passkey rejected: " + phase);
            throw new Failure(INVALID, false);
        } finally { cookie = null; }
    }

    private void check() throws Failure { if (cancelled) throw cancelled(); }

    /** Authelia's {"status":"OK","data":{…}} envelope from one of the three allowed JSON endpoints. */
    private JSONObject json(String path, String query, JSONObject body) throws Exception {
        check();
        OAuthGuards.require(NativePasskey.PATHS.contains(path));
        URI url = new URI(AuthSession.ISSUER + path + (query == null ? "" : "?" + query));
        byte[] payload = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
        OAuthGuards.require(payload == null || payload.length <= 32768);
        Response response = send(url, payload);
        boolean verification = body != null && "/api/firstfactor/passkey".equals(path);
        if (verification && (response.status == 401 || response.status == 403)) throw new Rejected();
        OAuthGuards.require(response.status == 200 && response.type != null
                && response.type.split(";", 2)[0].trim().equalsIgnoreCase("application/json"));
        JSONObject envelope = new JSONObject(new String(response.body, StandardCharsets.UTF_8));
        if (verification && "KO".equals(envelope.opt("status"))) throw new Rejected();
        OAuthGuards.require("OK".equals(envelope.opt("status")));
        JSONObject data = envelope.optJSONObject("data");
        if (data != null) return data;
        // The completed assertion may return a status-only body; the state check that follows still requires level 2.
        OAuthGuards.require(verification);
        return new JSONObject();
    }

    /** Requests an authorization URL and returns where Authelia redirects; the redirect is validated, never followed. */
    private URI redirect(URI url) throws Exception {
        OAuthGuards.require(NativePasskey.sameIssuer(url) && NativePasskey.AUTHORIZATION_PATH.equals(url.getRawPath()));
        Response response = send(url, null);
        OAuthGuards.require(response.status == 302 || response.status == 303);
        return NativePasskey.location(url, response.location);
    }

    private Response send(URI url, byte[] body) throws Exception {
        check();
        OAuthGuards.require(NativePasskey.sameIssuer(url));
        Response response = transport.exchange(url, body, cookie);
        check();
        if (response.status == 408 || response.status == 429 || response.status >= 500) throw new Unavailable();
        cookie = NativePasskey.cookie(response.cookies, cookie);
        return response;
    }

    private static final class Unavailable extends Exception {}
    private static final class Rejected extends Exception {}

    /** HTTPS to the issuer only: no redirects followed, no cache, no shared cookie store, bounded bodies. */
    static final class Https implements Transport {
        @Override public Response exchange(URI url, byte[] json, String cookie) throws Exception {
            HttpsURLConnection connection = (HttpsURLConnection) url.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-store");
            if (cookie != null) connection.setRequestProperty("Cookie", cookie);
            try {
                if (json != null) {
                    connection.setRequestMethod("POST"); connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json");
                    connection.setFixedLengthStreamingMode(json.length);
                    try (OutputStream output = connection.getOutputStream()) { output.write(json); }
                }
                int status = connection.getResponseCode();
                List<String> cookies = new ArrayList<>();
                for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet())
                    if (header.getKey() != null && header.getKey().equalsIgnoreCase("Set-Cookie")) cookies.addAll(header.getValue());
                OAuthGuards.require(connection.getContentLengthLong() <= 65536L);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                    if (stream != null) {
                        byte[] buffer = new byte[4096]; int count;
                        while ((count = stream.read(buffer)) != -1) {
                            OAuthGuards.require(output.size() + count <= 65536);
                            output.write(buffer, 0, count);
                        }
                    }
                }
                return new Response(status, connection.getContentType(), connection.getHeaderField("Location"), cookies, output.toByteArray());
            } finally { connection.disconnect(); }
        }
    }
}
