package wang.robby.console.android;

import static org.junit.Assert.*;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class NewFeaturesContractTest {
    @Test public void catalogCleanupsAndFeatureAdditionsMatchContract() {
        assertNull("posthog removed", FeatureCatalog.find("posthog"));
        assertTrue("umami unhidden by default", FeatureCatalog.DEFAULT_HIDDEN_REASONS.isEmpty());
        assertNotNull(FeatureCatalog.find("agent-dispatch"));
        assertNotNull(FeatureCatalog.find("infra-sentinel"));
        assertNotNull(FeatureCatalog.find("prompt-cheatsheet"));
    }

    @Test public void tokensCatalogNumberingAndStalenessMatchSot() throws Exception {
        long now = Instant.parse("2026-11-27T10:00:00Z").toEpochMilli();
        JSONObject agy = new JSONObject().put("id", "antigravity").put("product", "antigravity").put("accountNumber", 2).put("status", "connected");
        JSONObject g1 = new JSONObject().put("id", "gemini-web").put("product", "gemini-web").put("status", "connected");
        JSONObject g2 = new JSONObject().put("id", "gemini-web-3").put("product", "gemini-web").put("status", "connected");
        JSONObject g3 = new JSONObject().put("id", "gemini-web-2").put("product", "gemini-web").put("status", "connected");
        assertEquals("Antigravity 2", TokensModel.productName(agy));
        assertEquals("Gemini 1", TokensModel.productName(g1));
        assertEquals("Gemini 2", TokensModel.productName(g2));
        assertEquals("Gemini 3", TokensModel.productName(g3));

        JSONObject payload = new JSONObject()
            .put("generatedAt", Instant.ofEpochMilli(now - 3 * 3600_000L).toString())
            .put("catalogAccounts", new JSONArray().put(g3).put(agy))
            .put("accounts", new JSONArray());
        List<JSONObject> merged = TokenAccountOrder.accounts(payload);
        assertEquals(2, merged.size());
        assertNotNull(TokensModel.staleBannerText(payload, now, ZoneId.of("Asia/Shanghai")));
    }

    @Test public void dispatchSentinelPromptsDevicesAndMindBallPayloadsParseCleanly() throws Exception {
        DispatchPage.Model disp = DispatchPage.parse(new JSONObject()
            .put("schemaVersion", 1).put("status", "ready")
            .put("dispatcher", new JSONObject().put("online", true).put("running", true).put("paused", false))
            .put("summary", new JSONObject().put("totalAgents", 2).put("onlineAgents", 2).put("workingAgents", 1).put("blockedClaims", 1))
            .put("cards", new JSONObject().put("blocked", new JSONArray().put(new JSONObject().put("memoId", "m1").put("title", "Blocked").put("canRetryRelease", true).put("ticket", "tk-1")))));
        assertNotNull(disp);
        assertEquals(1, disp.blocked.size());
        assertEquals("release_retry", DispatchPage.releaseRetryBody("m1", "tk-1", false).getString("action"));

        OpsReadPages.SentinelSummary sum = OpsReadPages.parseSentinel(new JSONObject()
            .put("schemaVersion", 1)
            .put("alerts", new JSONArray()
                .put(new JSONObject().put("severity", "info").put("area", "dns").put("title", "i"))
                .put(new JSONObject().put("severity", "critical").put("area", "srv").put("title", "c")))
            .put("storage", new JSONObject().put("status", "degraded").put("collectorStale", true).put("summary", new JSONObject().put("totalStores", 4).put("up", 3))));
        assertEquals("critical", sum.alerts.get(0).severity);
        assertEquals(1, sum.criticalCount);
        assertTrue(sum.storageStale);

        assertEquals(List.of("repo", "branch"), PromptCheatsheetPage.extractVariables("Audit {{repo}} on {{ branch }} and {{repo}}"));
        assertEquals("Audit god on main and god", PromptCheatsheetPage.fillVariables("Audit {{repo}} on {{ branch }} and {{repo}}", Map.of("repo", "god", "branch", "main")));

        TokenDeviceActivity.Model devs = TokenDeviceActivity.parse(new JSONObject()
            .put("schemaVersion", 1)
            .put("summary", new JSONObject().put("accounts", 1).put("readyAccounts", 1).put("onlineDevices", 1).put("workingAgents", 1).put("workingSessions", 2))
            .put("byAccount", new JSONObject().put("codex-1", new JSONObject().put("status", "ready").put("devices", new JSONArray()))));
        assertEquals(2, devs.workingSessions);

        JSONObject qm = MindBallPage.quickMemoBody("今日总结内容");
        assertEquals(1, qm.getInt("schemaVersion"));
        assertEquals("心力球", qm.getJSONArray("tags").getString(0));
        assertEquals("floboSent_2026-11-27", MindBallPage.roundPrefKey("2026-11-27"));
    }
}
