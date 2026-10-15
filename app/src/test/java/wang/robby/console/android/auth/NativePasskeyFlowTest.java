package wang.robby.console.android.auth;

import static org.junit.Assert.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

/** The whole native ceremony against a scripted Authelia: what is sent, in which order, and how each failure ends. */
public final class NativePasskeyFlowTest {
    static final String CALLBACK = "https://god.robby.wang/mobile/android/callback?code=abc&iss=https%3A%2F%2Fauth.god.robby.wang&state=s1";
    static final String SET_COOKIE = "authelia_robby_session=sess1; Domain=robby.wang; Path=/; Secure; HttpOnly";

    /** A scripted Authelia; each field changes one answer. */
    static final class Server implements NativePasskeyFlow.Transport {
        final List<String> log = new ArrayList<>();
        int verifyStatus = 200, level = 2, challengeStatus = 200;
        boolean consentFirst = true;
        String consentLocation = "https://auth.god.robby.wang/consent/openid/decision?flow=openid_connect&flow_id=" + NativePasskeyTest.FLOW;
        JSONObject assertion;

        @Override public NativePasskeyFlow.Response exchange(URI url, byte[] json, String cookie) throws Exception {
            String path = url.getRawPath(), method = json == null ? "GET" : "POST";
            log.add(method + " " + path + (cookie == null ? "" : " +cookie"));
            if (path.equals("/api/firstfactor/passkey") && json == null) {
                if (challengeStatus != 200) return new NativePasskeyFlow.Response(challengeStatus, "text/html", null, List.of(), new byte[0]);
                return ok(NativePasskeyTest.challengeData("auth.god.robby.wang", "required", NativePasskeyTest.CHALLENGE), List.of(SET_COOKIE));
            }
            if (path.equals("/api/firstfactor/passkey")) {
                assertion = new JSONObject(new String(json, StandardCharsets.UTF_8));
                if (verifyStatus != 200) return new NativePasskeyFlow.Response(verifyStatus, "application/json", null, List.of(),
                        "{\"status\":\"KO\",\"message\":\"Authentication failed.\"}".getBytes(StandardCharsets.UTF_8));
                return new NativePasskeyFlow.Response(200, "application/json; charset=utf-8", null, List.of(), "{\"status\":\"OK\"}".getBytes(StandardCharsets.UTF_8));
            }
            if (path.equals("/api/state")) return ok(new JSONObject().put("authentication_level", level), List.of());
            if (path.equals("/api/oidc/authorization")) {
                boolean resumed = url.getRawQuery().contains("consent_id=");
                String location = resumed || !consentFirst ? CALLBACK : consentLocation;
                return new NativePasskeyFlow.Response(302, "text/html", location, List.of(), new byte[0]);
            }
            if (path.equals("/api/oidc/consent") && json == null) return ok(NativePasskeyTest.consent(), List.of());
            if (path.equals("/api/oidc/consent"))
                return ok(new JSONObject().put("redirect_uri", NativePasskeyTest.AUTHORIZE + "&consent_id=" + NativePasskeyTest.FLOW), List.of());
            throw new AssertionError("unexpected " + path);
        }
        static NativePasskeyFlow.Response ok(JSONObject data, List<String> cookies) throws Exception {
            byte[] body = new JSONObject().put("status", "OK").put("data", data).toString().getBytes(StandardCharsets.UTF_8);
            return new NativePasskeyFlow.Response(200, "application/json; charset=utf-8", null, cookies, body);
        }
    }

    static final class Person implements NativePasskeyFlow.Ui {
        boolean allow = true, cancelSheet; String request;
        @Override public String assertion(String requestJson) throws Exception {
            request = requestJson;
            if (cancelSheet) throw NativePasskeyFlow.cancelled();
            return NativePasskeyTest.valid();
        }
        @Override public boolean consent() { return allow; }
    }

    static NativePasskeyFlow.Failure fails(Server server, Person person) {
        try { new NativePasskeyFlow(server, person, NativePasskeyTest.ORIGIN).run(NativePasskeyTest.AUTHORIZE); fail("signed in"); return null; }
        catch (NativePasskeyFlow.Failure failure) { return failure; }
    }

    @Test public void signsInWithoutAnyWebPage() throws Exception {
        Server server = new Server(); Person person = new Person();
        String callback = new NativePasskeyFlow(server, person, NativePasskeyTest.ORIGIN).run(NativePasskeyTest.AUTHORIZE);
        assertEquals(CALLBACK, callback);
        assertEquals(List.of("GET /api/firstfactor/passkey", "POST /api/firstfactor/passkey +cookie", "GET /api/state +cookie",
                "GET /api/oidc/authorization +cookie", "GET /api/oidc/consent +cookie", "POST /api/oidc/consent +cookie",
                "GET /api/oidc/authorization +cookie"), server.log);
        assertEquals("auth.god.robby.wang", new JSONObject(person.request).getString("rpId"));
        assertEquals("Y3JlZC0x", server.assertion.getJSONObject("response").getString("id"));
        // The callback itself is never requested: the coordinator validates and exchanges it with PKCE.
        assertFalse(server.log.stream().anyMatch(line -> line.contains("/mobile/android/callback")));
    }

    @Test public void rememberedConsentGoesStraightToTheCallback() throws Exception {
        Server server = new Server(); server.consentFirst = false;
        assertEquals(CALLBACK, new NativePasskeyFlow(server, new Person(), NativePasskeyTest.ORIGIN).run(NativePasskeyTest.AUTHORIZE));
        assertFalse(server.log.stream().anyMatch(line -> line.contains("/api/oidc/consent")));
    }

    @Test public void cancellingIsSilentAndSendsNothingMore() {
        Server server = new Server(); Person person = new Person(); person.cancelSheet = true;
        assertTrue(fails(server, person).cancelled);
        assertEquals(List.of("GET /api/firstfactor/passkey"), server.log);
        Server declined = new Server(); Person no = new Person(); no.allow = false;
        assertTrue(fails(declined, no).cancelled);
        assertFalse(declined.log.contains("POST /api/oidc/consent +cookie"));
    }

    @Test public void eachFailureExplainsItself() {
        Server down = new Server(); down.challengeStatus = 503;
        assertEquals(NativePasskeyFlow.UNAVAILABLE, fails(down, new Person()).getMessage());
        Server rejected = new Server(); rejected.verifyStatus = 401;
        assertEquals(NativePasskeyFlow.REJECTED, fails(rejected, new Person()).getMessage());
        Server weak = new Server(); weak.level = 1;
        assertEquals(NativePasskeyFlow.VERIFICATION, fails(weak, new Person()).getMessage());
        Server elsewhere = new Server(); elsewhere.consentLocation = "https://evil.example/consent/openid/decision?flow=openid_connect&flow_id=" + NativePasskeyTest.FLOW;
        assertEquals(NativePasskeyFlow.INVALID, fails(elsewhere, new Person()).getMessage());
        assertFalse(elsewhere.log.stream().anyMatch(line -> line.contains("evil")));
    }

    @Test public void onlyTheIssuersAuthorizationEndpointIsAccepted() {
        try { new NativePasskeyFlow(new Server(), new Person(), NativePasskeyTest.ORIGIN).run("https://evil.example/api/oidc/authorization?x=1"); fail(); }
        catch (NativePasskeyFlow.Failure failure) { assertEquals(NativePasskeyFlow.INVALID, failure.getMessage()); }
    }
}
