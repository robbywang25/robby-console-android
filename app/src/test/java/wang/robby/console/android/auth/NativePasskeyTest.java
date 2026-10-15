package wang.robby.console.android.auth;

import static org.junit.Assert.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** The native Passkey sign-in accepts only Authelia's exact ceremony for this app, as the iOS client does. */
public final class NativePasskeyTest {
    static final String ORIGIN = "android:apk-key-hash:g9Yk4ObUlZissgYreTrsad3vxPRQmmpCmQQEZhs9pWk";
    static final String CHALLENGE = b64("a challenge of 32 random bytes!!".getBytes(StandardCharsets.UTF_8));
    static final String FLOW = "3f2b8c1e-4d5a-4b6c-8e7f-9a0b1c2d3e4f";
    static final String AUTHORIZE = "https://auth.god.robby.wang/api/oidc/authorization?client_id=robby-console-android"
            + "&redirect_uri=https%3A%2F%2Fgod.robby.wang%2Fmobile%2Fandroid%2Fcallback&response_type=code"
            + "&scope=openid+offline_access&state=s1&code_challenge=c1&code_challenge_method=S256";

    static String b64(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    static JSONObject challengeData(String rpId, String verification, String challenge) throws Exception {
        return new JSONObject().put("publicKey", new JSONObject().put("challenge", challenge).put("rpId", rpId)
                .put("userVerification", verification).put("timeout", 60000).put("allowCredentials", new JSONArray()));
    }
    /** What Credential Manager returns: authenticatorData = rpIdHash ‖ flags ‖ counter; clientDataJSON with origin. */
    static String response(String rpId, int flags, String type, String challenge, String origin, String user) throws Exception {
        byte[] data = new byte[37];
        System.arraycopy(MessageDigest.getInstance("SHA-256").digest(rpId.getBytes(StandardCharsets.UTF_8)), 0, data, 0, 32);
        data[32] = (byte) flags;
        String client = new JSONObject().put("type", type).put("challenge", challenge).put("origin", origin).toString();
        JSONObject inner = new JSONObject().put("clientDataJSON", b64(client.getBytes(StandardCharsets.UTF_8)))
                .put("authenticatorData", b64(data)).put("signature", b64(new byte[70]));
        if (user != null) inner.put("userHandle", user);
        return new JSONObject().put("id", "Y3JlZC0x").put("rawId", "Y3JlZC0x").put("type", "public-key")
                .put("authenticatorAttachment", "platform").put("response", inner).put("clientExtensionResults", new JSONObject()).toString();
    }
    static String valid() throws Exception { return response("auth.god.robby.wang", 0x05, "webauthn.get", CHALLENGE, ORIGIN, "dXNlcg"); }
    static void rejects(ThrowingRunnable check) { try { check.run(); fail("accepted"); } catch (Exception expected) { } }
    interface ThrowingRunnable { void run() throws Exception; }

    @Test public void serverOriginIsTheReleaseCertificate() throws Exception {
        // Authelia's webauthn.experimental_opaque_origins holds exactly this value for the release signing certificate.
        byte[] digest = new byte[32]; String hex = "83d624e0e6d49598acb2062b793aec69ddefc4f4509a6a42990404661b3da569";
        for (int i = 0; i < 32; i++) digest[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        assertEquals(ORIGIN, "android:apk-key-hash:" + b64(digest));
        byte[] certificate = "certificate".getBytes(StandardCharsets.UTF_8);
        assertEquals("android:apk-key-hash:" + b64(MessageDigest.getInstance("SHA-256").digest(certificate)), NativePasskey.origin(certificate));
    }

    @Test public void challengeBecomesASystemRequestOnlyForThisRelyingParty() throws Exception {
        NativePasskey.Challenge c = NativePasskey.challenge(challengeData("auth.god.robby.wang", "required", CHALLENGE));
        JSONObject request = new JSONObject(c.requestJson);
        assertEquals(CHALLENGE, c.challenge);
        assertEquals("auth.god.robby.wang", request.getString("rpId"));
        assertEquals("required", request.getString("userVerification"));
        assertEquals(0, request.getJSONArray("allowCredentials").length());
        rejects(() -> NativePasskey.challenge(challengeData("god.robby.wang", "required", CHALLENGE)));
        rejects(() -> NativePasskey.challenge(challengeData("auth.god.robby.wang", "preferred", CHALLENGE)));
        rejects(() -> NativePasskey.challenge(challengeData("auth.god.robby.wang", "required", b64(new byte[15]))));
        rejects(() -> NativePasskey.challenge(challengeData("auth.god.robby.wang", "required", CHALLENGE + "=")));
        rejects(() -> NativePasskey.challenge(new JSONObject()));
    }

    @Test public void assertionMustAnswerThisChallengeFromThisAppVerified() throws Exception {
        JSONObject body = NativePasskey.assertion(valid(), CHALLENGE, ORIGIN);
        assertFalse(body.getBoolean("keepMeLoggedIn"));
        assertEquals("GET", body.getString("requestMethod"));
        JSONObject credential = body.getJSONObject("response");
        assertEquals("Y3JlZC0x", credential.getString("rawId"));
        assertEquals("dXNlcg", credential.getJSONObject("response").getString("userHandle"));
        assertEquals("platform", credential.getString("authenticatorAttachment"));
        rejects(() -> NativePasskey.assertion(valid(), CHALLENGE, "android:apk-key-hash:other"));
        rejects(() -> NativePasskey.assertion(valid(), b64(new byte[32]), ORIGIN));
        rejects(() -> NativePasskey.assertion(response("auth.god.robby.wang", 0x05, "webauthn.create", CHALLENGE, ORIGIN, "dXNlcg"), CHALLENGE, ORIGIN));
        rejects(() -> NativePasskey.assertion(response("auth.god.robby.wang", 0x01, "webauthn.get", CHALLENGE, ORIGIN, "dXNlcg"), CHALLENGE, ORIGIN));
        rejects(() -> NativePasskey.assertion(response("evil.example", 0x05, "webauthn.get", CHALLENGE, ORIGIN, "dXNlcg"), CHALLENGE, ORIGIN));
        rejects(() -> NativePasskey.assertion(response("auth.god.robby.wang", 0x05, "webauthn.get", CHALLENGE, ORIGIN, null), CHALLENGE, ORIGIN));
        rejects(() -> NativePasskey.assertion(new JSONObject(valid()).put("rawId", "b3RoZXI").toString(), CHALLENGE, ORIGIN));
    }

    @Test public void consentRedirectCarriesOnlyTheFlow() throws Exception {
        assertEquals(FLOW, NativePasskey.flowId(URI.create("https://auth.god.robby.wang/consent/openid/decision?flow=openid_connect&flow_id=" + FLOW)));
        rejects(() -> NativePasskey.flowId(URI.create("https://evil.example/consent/openid/decision?flow=openid_connect&flow_id=" + FLOW)));
        rejects(() -> NativePasskey.flowId(URI.create("https://auth.god.robby.wang/consent/openid/decision?flow=openid_connect&flow_id=" + FLOW + "&x=1")));
        rejects(() -> NativePasskey.flowId(URI.create("https://auth.god.robby.wang/consent/openid/decision?flow=openid_connect&flow_id=not-a-uuid")));
        rejects(() -> NativePasskey.flowId(URI.create("https://auth.god.robby.wang/?flow=openid_connect&flow_id=" + FLOW)));
    }

    static JSONObject consent() throws Exception {
        return new JSONObject().put("client_id", AuthSession.CLIENT_ID).put("scopes", new JSONArray(List.of(AuthSession.SCOPES.split(" "))))
                .put("audience", new JSONArray(List.of(AuthSession.AUDIENCE))).put("resource", JSONObject.NULL)
                .put("require_login", false).put("claims", new JSONArray()).put("essential_claims", JSONObject.NULL);
    }

    @Test public void consentMustBeExactlyThisAppsRequest() throws Exception {
        NativePasskey.consent(consent());
        NativePasskey.consent(consent().put("audience", JSONObject.NULL).put("resource", new JSONArray(List.of(AuthSession.AUDIENCE))));
        rejects(() -> NativePasskey.consent(consent().put("client_id", "robby-console")));
        rejects(() -> NativePasskey.consent(consent().put("require_login", true)));
        rejects(() -> NativePasskey.consent(consent().put("scopes", new JSONArray(List.of("openid", "offline_access")))));
        rejects(() -> NativePasskey.consent(consent().put("scopes", consent().getJSONArray("scopes").put("god.admin"))));
        rejects(() -> NativePasskey.consent(consent().put("audience", new JSONArray(List.of("https://god.robby.wang/api/admin")))));
        rejects(() -> NativePasskey.consent(consent().put("audience", JSONObject.NULL)));
        rejects(() -> NativePasskey.consent(consent().put("claims", new JSONArray(List.of("email")))));
    }

    @Test public void resumeAddsOnlyTheConsentId() throws Exception {
        URI original = URI.create(AUTHORIZE);
        NativePasskey.resume(URI.create(AUTHORIZE + "&consent_id=" + FLOW), original, FLOW);
        // Authelia re-encodes the form; %20 and + are the same space.
        NativePasskey.resume(URI.create(AUTHORIZE.replace("openid+offline_access", "openid%20offline_access") + "&consent_id=" + FLOW), original, FLOW);
        rejects(() -> NativePasskey.resume(URI.create(AUTHORIZE.replace("state=s1", "state=s2") + "&consent_id=" + FLOW), original, FLOW));
        rejects(() -> NativePasskey.resume(URI.create(AUTHORIZE + "&consent_id=" + FLOW + "&prompt=none"), original, FLOW));
        rejects(() -> NativePasskey.resume(URI.create(AUTHORIZE + "&consent_id=" + FLOW.replace('3', '4')), original, FLOW));
        rejects(() -> NativePasskey.resume(URI.create(AUTHORIZE.replace("/api/oidc/authorization", "/api/oidc/other") + "&consent_id=" + FLOW), original, FLOW));
    }

    @Test public void onlyTheSecureSessionCookieIsKept() throws Exception {
        String ok = "authelia_robby_session=abc123; Domain=robby.wang; Path=/; Secure; HttpOnly; SameSite=Lax";
        assertEquals("authelia_robby_session=abc123", NativePasskey.cookie(List.of(ok), null));
        assertEquals("prior", NativePasskey.cookie(List.of("other=1; Secure; HttpOnly"), "prior"));
        rejects(() -> NativePasskey.cookie(List.of("authelia_robby_session=abc; Domain=robby.wang; HttpOnly"), null));
        rejects(() -> NativePasskey.cookie(List.of("authelia_robby_session=abc; Domain=robby.wang; Secure"), null));
        rejects(() -> NativePasskey.cookie(List.of("authelia_robby_session=abc; Domain=evil.example; Secure; HttpOnly"), null));
    }

    @Test public void callbackIsOnlyTheAndroidRedirect() {
        assertTrue(NativePasskey.callback(URI.create("https://god.robby.wang/mobile/android/callback?code=c&state=s")));
        assertFalse(NativePasskey.callback(URI.create("https://god.robby.wang/mobile/oauth/callback?code=c")));
        assertFalse(NativePasskey.callback(URI.create("https://evil.example/mobile/android/callback?code=c")));
        assertFalse(NativePasskey.callback(URI.create("http://god.robby.wang/mobile/android/callback?code=c")));
    }
}
