package wang.robby.console.android;

import java.net.URI;
import java.util.Locale;
import org.json.JSONObject;

/**
 * Canonical discovery/bootstrap contract (`GET /api/mobile/v1/bootstrap`) with
 * safe HTTPS robby.wang validation and local fallbacks for offline/older gateways.
 */
final class Bootstrap {
    final String godWeb, accountWeb, tokensWeb, authIssuer, securitySettingsUrl, tokensCatalogPath, engineeringPath;

    private Bootstrap(String godWeb, String accountWeb, String tokensWeb, String authIssuer,
                      String securitySettingsUrl, String tokensCatalogPath, String engineeringPath) {
        this.godWeb = godWeb;
        this.accountWeb = accountWeb;
        this.tokensWeb = tokensWeb;
        this.authIssuer = authIssuer;
        this.securitySettingsUrl = securitySettingsUrl;
        this.tokensCatalogPath = tokensCatalogPath;
        this.engineeringPath = engineeringPath;
    }

    static Bootstrap defaults() {
        return new Bootstrap(
            "https://god.robby.wang/",
            AccountCenter.WEB,
            "https://token.robby.wang/",
            "https://auth.robby.wang",
            AccountCenter.SETTINGS,
            "/api/mobile/v1/" + AccountCenter.TOKENS_CATALOG,
            "/api/mobile/v1/" + AccountCenter.ENGINEERING_V2
        );
    }

    static Bootstrap parse(JSONObject root) {
        Bootstrap d = defaults();
        if (root == null || root.optInt("schemaVersion", -1) != 1) return d;
        JSONObject auth = root.optJSONObject("auth");
        JSONObject urls = root.optJSONObject("urls");
        JSONObject caps = root.optJSONObject("capabilities");
        JSONObject catalog = caps == null ? null : caps.optJSONObject("tokensCatalog");
        JSONObject engineering = caps == null ? null : caps.optJSONObject("engineering");
        return new Bootstrap(
            trustedUrl(urls == null ? null : urls.optString("godWeb", null), d.godWeb),
            trustedUrl(urls == null ? null : urls.optString("accountWeb", null), d.accountWeb),
            trustedUrl(urls == null ? null : urls.optString("tokensWeb", null), d.tokensWeb),
            trustedUrl(auth == null ? null : auth.optString("issuer", null), d.authIssuer),
            trustedUrl(auth == null ? null : auth.optString("securitySettingsUrl", null), d.securitySettingsUrl),
            safePath(catalog == null ? null : catalog.optString("path", null), d.tokensCatalogPath),
            safePath(engineering == null ? null : engineering.optString("path", null), d.engineeringPath)
        );
    }

    private static String trustedUrl(String raw, String fallback) {
        if (raw == null || raw.isEmpty()) return fallback;
        try {
            URI uri = URI.create(raw.trim());
            String scheme = uri.getScheme(), host = uri.getHost();
            if (!"https".equalsIgnoreCase(scheme) || host == null || uri.getRawUserInfo() != null) return fallback;
            String lower = host.toLowerCase(Locale.ROOT);
            if (!lower.equals("robby.wang") && !lower.endsWith(".robby.wang")) return fallback;
            return uri.toString();
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static String safePath(String raw, String fallback) {
        if (raw == null || !raw.startsWith("/api/mobile/v1/") || raw.contains("..")) return fallback;
        return raw.length() <= 160 ? raw : fallback;
    }
}
