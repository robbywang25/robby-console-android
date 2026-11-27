package wang.robby.console.android;

import android.content.Context;
import java.net.URI;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Canonical discovery/bootstrap contract (`GET /api/mobile/v1/bootstrap`) with
 * safe HTTPS robby.wang validation and local fallbacks for offline/older gateways.
 */
final class Bootstrap {
    private static final String PREFS = "god-bootstrap", KEY_JSON = "json";
    final String godWeb, accountWeb, tokensWeb, infraWeb, floboWeb, promptsWeb;
    final String authIssuer, securitySettingsUrl;
    final String tokensCatalogPath, tokensDevicesPath, engineeringPath;
    final String dispatchOverviewPath, dispatchControlPath, infraSentinelPath, promptsLibraryPath, floboQuickMemoPath;

    private Bootstrap(String godWeb, String accountWeb, String tokensWeb, String infraWeb, String floboWeb, String promptsWeb,
                      String authIssuer, String securitySettingsUrl,
                      String tokensCatalogPath, String tokensDevicesPath, String engineeringPath,
                      String dispatchOverviewPath, String dispatchControlPath, String infraSentinelPath,
                      String promptsLibraryPath, String floboQuickMemoPath) {
        this.godWeb = godWeb;
        this.accountWeb = accountWeb;
        this.tokensWeb = tokensWeb;
        this.infraWeb = infraWeb;
        this.floboWeb = floboWeb;
        this.promptsWeb = promptsWeb;
        this.authIssuer = authIssuer;
        this.securitySettingsUrl = securitySettingsUrl;
        this.tokensCatalogPath = tokensCatalogPath;
        this.tokensDevicesPath = tokensDevicesPath;
        this.engineeringPath = engineeringPath;
        this.dispatchOverviewPath = dispatchOverviewPath;
        this.dispatchControlPath = dispatchControlPath;
        this.infraSentinelPath = infraSentinelPath;
        this.promptsLibraryPath = promptsLibraryPath;
        this.floboQuickMemoPath = floboQuickMemoPath;
    }

    static Bootstrap defaults() {
        return new Bootstrap(
            "https://god.robby.wang/",
            AccountCenter.WEB,
            "https://token.robby.wang/",
            "https://infra.robby.wang/",
            "https://flobo.robby.wang/",
            "https://prompts.robby.wang/",
            "https://auth.robby.wang",
            AccountCenter.SETTINGS,
            "/api/mobile/v1/" + AccountCenter.TOKENS_CATALOG,
            "/api/mobile/v1/" + AccountCenter.TOKENS_DEVICES,
            "/api/mobile/v1/" + AccountCenter.ENGINEERING_V2,
            "/api/mobile/v1/" + AccountCenter.DISPATCH_OVERVIEW,
            "/api/mobile/v1/" + AccountCenter.DISPATCH_CONTROL,
            "/api/mobile/v1/" + AccountCenter.INFRA_SENTINEL,
            "/api/mobile/v1/" + AccountCenter.PROMPTS_LIBRARY,
            "/api/mobile/v1/" + AccountCenter.FLOBO_QUICK_MEMO
        );
    }

    static Bootstrap parse(JSONObject root) {
        Bootstrap d = defaults();
        if (root == null || root.optInt("schemaVersion", -1) != 1) return d;
        JSONObject auth = root.optJSONObject("auth");
        JSONObject urls = root.optJSONObject("urls");
        JSONObject caps = root.optJSONObject("capabilities");
        return new Bootstrap(
            trustedUrl(urls == null ? null : urls.optString("godWeb", null), d.godWeb),
            trustedUrl(urls == null ? null : urls.optString("accountWeb", null), d.accountWeb),
            trustedUrl(urls == null ? null : urls.optString("tokensWeb", null), d.tokensWeb),
            trustedUrl(urls == null ? null : urls.optString("infraWeb", null), d.infraWeb),
            trustedUrl(urls == null ? null : urls.optString("floboWeb", null), d.floboWeb),
            trustedUrl(urls == null ? null : urls.optString("promptsWeb", null), d.promptsWeb),
            trustedUrl(auth == null ? null : auth.optString("issuer", null), d.authIssuer),
            trustedUrl(auth == null ? null : auth.optString("securitySettingsUrl", null), d.securitySettingsUrl),
            capPath(caps, "tokensCatalog", d.tokensCatalogPath),
            capPath(caps, "tokensDevices", d.tokensDevicesPath),
            capPath(caps, "engineering", d.engineeringPath),
            capPath(caps, "dispatchOverview", d.dispatchOverviewPath),
            capPath(caps, "dispatchControl", d.dispatchControlPath),
            capPath(caps, "infraSentinel", d.infraSentinelPath),
            capPath(caps, "promptsLibrary", d.promptsLibraryPath),
            capPath(caps, "floboQuickMemo", d.floboQuickMemoPath)
        );
    }

    /** Persist a valid `schemaVersion == 1` bootstrap envelope into local app preferences. */
    static void save(Context context, JSONObject root) {
        if (context == null || root == null || root.optInt("schemaVersion", -1) != 1) return;
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_JSON, root.toString()).apply();
    }

    /** Load the last cached bootstrap envelope, or fall back to canonical defaults offline/on cold start. */
    static Bootstrap cached(Context context) {
        if (context == null) return defaults();
        String raw = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null);
        if (raw == null || raw.isEmpty()) return defaults();
        try {
            return parse(new JSONObject(raw));
        } catch (JSONException e) {
            return defaults();
        }
    }

    /** Convert a `/api/mobile/v1/...` capability path to a relative `MobileApi` route, or return `fallback`. */
    String route(String capPath, String fallback) {
        if (capPath == null || !capPath.startsWith("/api/mobile/v1/")) return fallback;
        String rel = capPath.substring("/api/mobile/v1/".length());
        return rel.isEmpty() || rel.contains("..") ? fallback : rel;
    }

    private static String capPath(JSONObject caps, String key, String fallback) {
        JSONObject cap = caps == null ? null : caps.optJSONObject(key);
        return safePath(cap == null ? null : cap.optString("path", null), fallback);
    }

    static String trustedUrl(String raw, String fallback) {
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
