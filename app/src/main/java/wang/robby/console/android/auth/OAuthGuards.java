package wang.robby.console.android.auth;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

/** Pure Java security policy; deliberately independent of Android URI leniency. */
public final class OAuthGuards {
    private OAuthGuards() {}
    public static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static String challenge(String verifier) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }
    static void require(boolean condition) throws Exception {
        if (!condition) throw new Exception("登录信息校验失败，请重新登录。");
    }
    public static URI endpoint(String input, String issuer) throws Exception {
        URI uri = new URI(input), origin = new URI(issuer);
        require("https".equals(uri.getScheme()) && origin.getHost().equals(uri.getHost())
                && uri.getPort() == origin.getPort() && uri.getRawUserInfo() == null
                && uri.getRawFragment() == null && uri.getRawQuery() == null
                && uri.getRawPath() != null && uri.getRawPath().startsWith("/"));
        return uri;
    }
    public static String callback(String input, String redirect, String issuer, String state,
                                  long createdAt, long now) throws Exception {
        require(input != null && input.length() <= 16384 && state != null && state.length() <= 4096);
        require(createdAt > 0 && now >= createdAt && now - createdAt <= 600_000L);
        URI uri = new URI(input), expected = new URI(redirect);
        require(expected.getScheme().equals(uri.getScheme()) && expected.getRawAuthority().equals(uri.getRawAuthority())
                && expected.getRawPath().equals(uri.getRawPath()) && uri.getRawFragment() == null
                && uri.getRawUserInfo() == null && uri.getRawQuery() != null);
        Map<String, String> query = new HashMap<>();
        for (String part : uri.getRawQuery().split("&", -1)) {
            String[] pair = part.split("=", 2);
            require(pair.length == 2);
            String key = URLDecoder.decode(pair[0], "UTF-8"), value = URLDecoder.decode(pair[1], "UTF-8");
            require(!query.containsKey(key) && value.length() <= 4096);
            query.put(key, value);
        }
        require(query.containsKey("state") && MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),
                query.get("state").getBytes(StandardCharsets.UTF_8)));
        require(issuer.equals(query.get("iss")));
        require(!query.containsKey("error") && safeToken(query.get("code"), 4096));
        return query.get("code");
    }
    public static boolean safeToken(String value, int maximum) {
        if (value == null || value.isEmpty() || value.length() > maximum) return false;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 0x21 || value.charAt(i) > 0x7e) return false;
        return true;
    }
    public static boolean exactScopes(String actual, String expected) {
        if (actual == null || actual.isEmpty() || actual.length() > 8192) return false;
        String[] parts = actual.split(" ", -1);
        Set<String> actualSet = new HashSet<>(Arrays.asList(parts));
        return actualSet.size() == parts.length && !actualSet.contains("")
                && actualSet.equals(new HashSet<>(Arrays.asList(expected.split(" "))));
    }
    public static long tokenLifetime(String access, String refresh, String type, String scope,
                                     Object expiry, String expectedScopes) throws Exception {
        require("Bearer".equalsIgnoreCase(type) && safeToken(access, 32768) && safeToken(refresh, 32768)
                && exactScopes(scope, expectedScopes) && (expiry instanceof Integer || expiry instanceof Long));
        long seconds = ((Number) expiry).longValue();
        require(seconds >= 60 && seconds <= 86400);
        return seconds;
    }
}
