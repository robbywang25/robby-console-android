package wang.robby.console.android.auth;

import static org.junit.Assert.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Synthetic storage/network tests: no Android keystore, real tokens, or live request. */
public final class AuthIssuerMigrationTest {
    static String b64(String s) { return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }
    static String jwt(String issuer) throws Exception {
        return b64("{\"alg\":\"RS256\",\"typ\":\"at+jwt\"}") + "." + b64(new JSONObject()
                .put("iss", issuer).put("client_id", AuthSession.CLIENT_ID).put("aud", AuthSession.AUDIENCE).toString()) + ".c3ludGhldGlj";
    }
    static JSONObject stored(String issuer, boolean explicit) throws Exception {
        JSONObject value = new JSONObject().put("access_token", jwt(issuer)).put("refresh_token", "synthetic-refresh")
                .put("scope", AuthSession.SCOPES).put("expires_at", System.currentTimeMillis() + 600_000);
        if (explicit) value.put("issuer", issuer);
        return value;
    }
    static final class Memory implements AuthSession.Store {
        JSONObject value = new JSONObject(); int clears;
        public JSONObject read() throws Exception { return new JSONObject(value.toString()); }
        public void write(JSONObject v) throws Exception { value = new JSONObject(v.toString()); }
        public void clear() { clears++; value = new JSONObject(); }
    }
    static final class Network implements AuthSession.Transport {
        final List<URI> requests = new ArrayList<>();
        String expected, responseIssuer, badTokenEndpoint;
        boolean offline;
        Network(String issuer) { expected = issuer; responseIssuer = issuer; }
        public JSONObject request(URI url, Map<String,String> form, boolean empty, boolean device) throws Exception {
            requests.add(url);
            assertEquals("tokens and codes must stay on their session's exact origin", expected, AuthIssuerPolicy.origin(url));
            if (offline) throw new java.io.IOException("synthetic offline");
            if (url.getPath().equals("/.well-known/openid-configuration")) {
                assertNull(form);
                return new JSONObject().put("issuer", expected).put("authorization_response_iss_parameter_supported", true)
                        .put("code_challenge_methods_supported", new JSONArray(List.of("S256")))
                        .put("response_types_supported", new JSONArray(List.of("code")))
                        .put("grant_types_supported", new JSONArray(List.of("authorization_code", "refresh_token")))
                        .put("token_endpoint_auth_methods_supported", new JSONArray(List.of("none")))
                        .put("authorization_endpoint", expected + "/api/oidc/authorization")
                        .put("token_endpoint", badTokenEndpoint == null ? expected + "/api/oidc/token" : badTokenEndpoint)
                        .put("revocation_endpoint", expected + "/api/oidc/revocation");
            }
            if (url.getPath().equals("/api/oidc/revocation")) { assertTrue(empty); return new JSONObject(); }
            assertEquals("/api/oidc/token", url.getPath());assertNotNull(form);
            return new JSONObject().put("access_token", jwt(responseIssuer)).put("refresh_token", "synthetic-rotated")
                    .put("token_type", "Bearer").put("scope", AuthSession.SCOPES).put("expires_in", 3600);
        }
    }

    @Test public void oldAndNewSessionsReadRefreshPersistAndRevokeOnlyAtTheirOwnOrigin() throws Exception {
        for (String issuer : List.of(AuthSession.LEGACY_ISSUER, AuthSession.ISSUER)) for (boolean explicit : List.of(false,true)) {
            Memory memory = new Memory();memory.value.put("session", stored(issuer, explicit));
            Network network = new Network(issuer);AuthSession session = new AuthSession(memory,network);
            assertTrue(session.hasSession());assertEquals(jwt(issuer),session.accessToken());assertTrue(network.requests.isEmpty());
            assertEquals(jwt(issuer), session.accessToken(true));
            assertEquals(issuer,memory.value.getJSONObject("session").getString("issuer"));assertEquals(0,memory.clears);
            session.clearLocal().run();assertEquals(1,memory.clears);
            assertEquals(2,network.requests.stream().filter(u->u.getPath().equals("/api/oidc/revocation")).count());
        }
    }

    @Test public void freshLoginDefaultsToCanonicalButRestoredLegacyPendingKeepsLegacyIssuer() throws Exception {
        Memory memory = new Memory();Network network = new Network(AuthSession.ISSUER);AuthSession session = new AuthSession(memory,network);
        String authorization = session.authorizationUrl();assertTrue(authorization.startsWith(AuthSession.ISSUER+"/api/oidc/authorization?"));
        assertEquals(AuthSession.ISSUER,memory.value.getJSONObject("pending").getString("issuer"));
        for (String issuer : List.of(AuthSession.LEGACY_ISSUER,AuthSession.ISSUER)) {
            Memory restored = new Memory();String state="synthetic-state",verifier="v".repeat(43);
            JSONObject pending = new JSONObject().put("state",state).put("verifier",verifier).put("created_at",System.currentTimeMillis());
            if (issuer.equals(AuthSession.ISSUER)) pending.put("issuer",issuer);
            restored.value.put("pending",pending);
            AuthSession active = new AuthSession(restored,new Network(issuer));
            active.handleCallbackValue(AuthSession.REDIRECT_URI+"?code=synthetic-code&state="+state+"&iss="+URLEncoder.encode(issuer,"UTF-8"));
            assertFalse(restored.value.has("pending"));assertEquals(issuer,restored.value.getJSONObject("session").getString("issuer"));
            assertTrue(active.hasSession());
        }
    }

    @Test public void refreshNeverAcceptsTheOtherIssuerOrFallsBackAfterLegacyFailure() throws Exception {
        for (boolean offline : List.of(false,true)) {
            Memory memory = new Memory();memory.value.put("session",stored(AuthSession.LEGACY_ISSUER,false));String original=memory.value.toString();
            Network network = new Network(AuthSession.LEGACY_ISSUER);network.offline=offline;network.responseIssuer=AuthSession.ISSUER;
            AuthSession session=new AuthSession(memory,network);
            assertThrows(Exception.class,()->session.accessToken(true));assertEquals(original,memory.value.toString());assertEquals(0,memory.clears);
            assertTrue(network.requests.stream().allMatch(u->"auth.god.robby.wang".equals(u.getHost())));
        }
    }

    @Test public void discoveryCannotSendAnOldRefreshTokenToTheNewOrForeignIssuer() throws Exception {
        for(String tokenEndpoint:List.of(AuthSession.ISSUER+"/api/oidc/token","https://evil.example/token")){
            Memory memory=new Memory();memory.value.put("session",stored(AuthSession.LEGACY_ISSUER,false));
            Network network=new Network(AuthSession.LEGACY_ISSUER);network.badTokenEndpoint=tokenEndpoint;
            assertThrows(Exception.class,()->new AuthSession(memory,network).accessToken(true));
            assertEquals(1,network.requests.size());assertEquals(0,memory.clears);assertTrue(memory.value.has("session"));
        }
    }

    @Test public void mismatchedOrHostileStoredIssuerNeverMakesNetworkRequestsOrErasesSession() throws Exception {
        for(Object issuer:List.of(AuthSession.ISSUER,"https://evil.example",AuthSession.LEGACY_ISSUER+"/",42)){
            Memory memory=new Memory();memory.value.put("session",stored(AuthSession.LEGACY_ISSUER,false).put("issuer",issuer));
            Network network=new Network(AuthSession.LEGACY_ISSUER);AuthSession session=new AuthSession(memory,network);
            assertFalse(session.hasSession());assertThrows(Exception.class,()->session.accessToken(true));assertEquals(0,memory.clears);assertTrue(network.requests.isEmpty());
        }
        for(String issuer:List.of("https://evil.example","http://auth.robby.wang","https://auth.robby.wang:443","https://auth.robby.wang.evil.example",AuthSession.FEDERATED_ISSUER)){
            assertThrows(Exception.class,()->AuthIssuerPolicy.sessionIssuer(stored(issuer,false)));
        }
    }

    @Test public void callbackIssuerIsPinnedToPendingRequestBeforeAnyCodeExchange() throws Exception {
        Memory memory=new Memory();memory.value.put("pending",new JSONObject().put("state","s1").put("verifier","v".repeat(43)).put("created_at",System.currentTimeMillis()));
        Network network=new Network(AuthSession.LEGACY_ISSUER);
        assertThrows(Exception.class,()->new AuthSession(memory,network).handleCallbackValue(AuthSession.REDIRECT_URI+"?code=abc&state=s1&iss="+URLEncoder.encode(AuthSession.ISSUER,"UTF-8")));
        assertTrue(network.requests.isEmpty());assertTrue(memory.value.has("pending"));assertEquals(0,memory.clears);
    }

    @Test public void federatedSessionsKeepTheirIndependentTokenAndRevocationEndpoints() throws Exception {
        Memory memory=new Memory();memory.value.put("session",stored(AuthSession.FEDERATED_ISSUER,false).put("kind","federated").put("scope",AuthSession.FEDERATED_SCOPES));
        List<String> endpoints=new ArrayList<>();AuthSession session=new AuthSession(memory,(url,form,empty,device)->{
            endpoints.add(url.toString());assertTrue(url.toString().equals(AuthSession.FEDERATED_TOKEN)||url.toString().equals(AuthSession.FEDERATED_REVOKE));
            return empty?new JSONObject():new JSONObject().put("access_token",jwt(AuthSession.FEDERATED_ISSUER)).put("refresh_token","federated-rotated")
                    .put("token_type","Bearer").put("scope",AuthSession.FEDERATED_SCOPES).put("expires_in",3600);
        });
        assertTrue(session.hasSession());assertTrue(session.isFederated());session.accessToken(true);assertFalse(memory.value.getJSONObject("session").has("issuer"));
        session.clearLocal().run();assertEquals(List.of(AuthSession.FEDERATED_TOKEN,AuthSession.FEDERATED_REVOKE),endpoints);
    }
}
