package wang.robby.console.android;

import static org.junit.Assert.*;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class ControlPlaneUpgradeTest {
    @Test public void accountCenterUsesCanonicalAccountWebHost() {
        assertEquals("https://account.robby.wang/", AccountCenter.WEB);
        assertEquals("https://auth.robby.wang/settings/two-factor-authentication", AccountCenter.SETTINGS);
    }

    @Test public void bootstrapParsesCanonicalFieldsAndRejectsUntrustedHosts() throws Exception {
        Bootstrap defaults = Bootstrap.defaults();
        assertEquals("https://account.robby.wang/", defaults.accountWeb);
        assertEquals("/api/mobile/v1/engineering/v2", defaults.engineeringPath);

        JSONObject payload = new JSONObject()
            .put("schemaVersion", 1)
            .put("auth", new JSONObject().put("issuer", "https://auth.robby.wang").put("securitySettingsUrl", "https://evil.example/phish"))
            .put("urls", new JSONObject().put("godWeb", "https://god.robby.wang/").put("accountWeb", "https://account.robby.wang/").put("tokensWeb", "http://insecure.robby.wang/"))
            .put("capabilities", new JSONObject()
                .put("tokensCatalog", new JSONObject().put("path", "/api/mobile/v1/tokens/catalog"))
                .put("engineering", new JSONObject().put("path", "/api/mobile/v1/engineering/v2")));
        Bootstrap parsed = Bootstrap.parse(payload);
        assertEquals("https://account.robby.wang/", parsed.accountWeb);
        assertEquals("https://auth.robby.wang/settings/two-factor-authentication", parsed.securitySettingsUrl);
        assertEquals("https://token.robby.wang/", parsed.tokensWeb);
        assertEquals("/api/mobile/v1/tokens/catalog", parsed.tokensCatalogPath);
        assertEquals("/api/mobile/v1/engineering/v2", parsed.engineeringPath);
    }

    @Test public void mobileApiAllowsControlPlaneRoutes() {
        assertEquals("bootstrap", MobileApi.checkedPath("bootstrap"));
        assertEquals("tokens/catalog", MobileApi.checkedPath("tokens/catalog"));
        assertEquals("engineering/v2", MobileApi.checkedPath("engineering/v2"));
    }

    @Test public void antigravityAndGeminiWebThreeOrderAndSectionMatchSot() throws Exception {
        JSONObject g1 = new JSONObject().put("id", "gemini-web").put("product", "gemini-web").put("status", "connected");
        JSONObject g3 = new JSONObject().put("id", "gemini-web-3").put("product", "gemini-web").put("status", "connected");
        JSONObject g2 = new JSONObject().put("id", "gemini-web-2").put("product", "gemini-web").put("status", "connected");
        JSONObject ag = new JSONObject().put("id", "antigravity").put("product", "antigravity").put("status", "connected");
        List<JSONObject> sorted = TokenAccountOrder.ordered(List.of(ag, g2, g3, g1));
        assertEquals("gemini-web", sorted.get(0).getString("id"));
        assertEquals("gemini-web-3", sorted.get(1).getString("id"));
        assertEquals("gemini-web-2", sorted.get(2).getString("id"));
        assertEquals("antigravity", sorted.get(3).getString("id"));
        assertEquals("Antigravity", TokensModel.section(ag));
        assertEquals("Antigravity", TokensModel.productName(ag));
        assertTrue(TokensModel.SECTIONS.contains("Antigravity"));
    }

    @Test public void quotaCalendarCodingOnlyAndWidgetServerPriorityWorkSafely() throws Exception {
        long now = Instant.parse("2026-11-26T00:00:00Z").toEpochMilli();
        String future = Instant.ofEpochMilli(now + 3600_000L).toString();
        JSONObject codex = new JSONObject().put("id", "codex-1").put("product", "codex").put("status", "connected")
            .put("lastSuccessAt", Instant.ofEpochMilli(now - 10_000).toString())
            .put("windows", new JSONArray().put(new JSONObject().put("id", "w").put("label", "5h").put("quotaGroup", "default").put("remainingPercent", 80).put("resetsAt", future)));
        JSONObject gemini = new JSONObject().put("id", "gemini-web-3").put("product", "gemini-web").put("status", "connected")
            .put("lastSuccessAt", Instant.ofEpochMilli(now - 10_000).toString())
            .put("windows", new JSONArray().put(new JSONObject().put("id", "d").put("label", "daily").put("quotaGroup", "default").put("remainingPercent", 60).put("resetsAt", future)));

        QuotaCalendar.Model codingOnly = QuotaCalendar.build(List.of(codex, gemini), ZoneId.of("Asia/Shanghai"), now, true);
        assertEquals(1, codingOnly.events.size());
        assertEquals("Codex", codingOnly.events.get(0).platform);

        JSONObject quotas = new JSONObject()
            .put("accounts", new JSONArray().put(codex))
            .put("extendedAccounts", new JSONArray().put(gemini))
            .put("catalog", new JSONObject().put("catalogVersion", 1).put("entries", new JSONArray()
                .put(new JSONObject().put("id", "gemini-web-3").put("widgetPriority", "hidden").put("unsubscribed", true))));
        Map<String, JSONObject> entries = TokenAccountOrder.catalogEntries(quotas);
        assertEquals("hidden", entries.get("gemini-web-3").getString("widgetPriority"));

        JSONObject widget = WidgetSnapshot.tokens(quotas, java.util.Set.of());
        assertEquals(1, widget.getJSONArray("rows").length());
        assertEquals("OpenAI", widget.getJSONArray("rows").getJSONObject(0).getString("kind"));
    }

    @Test public void engineeringV2PayloadPassesCompatibilityCheck() throws Exception {
        FeatureCatalog.Feature eng = FeatureCatalog.find("engineering");
        JSONObject v2 = new JSONObject()
            .put("schemaVersion", 2)
            .put("apiVersion", "2026-11-26")
            .put("repositoryIndex", new JSONArray())
            .put("items", new JSONArray());
        assertTrue(FeatureRenderer.isCompatible(eng, v2));
    }
}
