package wang.robby.console.android;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class TokenAccountOrderTest {
    private static JSONObject account(String id, String product) throws Exception {
        return new JSONObject().put("id", id).put("product", product).put("status", "connected");
    }
    private static JSONObject order(String provider, Object providerWeight, Object accountWeight) throws Exception {
        return new JSONObject().put("version", 1).put("providerKey", provider).put("providerWeight", providerWeight).put("accountWeight", accountWeight);
    }
    private static List<String> ids(List<JSONObject> values) {
        List<String> ids = new ArrayList<>(); for (JSONObject value : values) ids.add(value.optString("id")); return ids;
    }
    private static List<JSONObject> sample() throws Exception {
        return new ArrayList<>(List.of(account("gemini-web-2", "gemini-web"), account("today-global", "today-global"),
            account("codex-2", "codex"), account("grok-bot", "grok-bot"), account("claude-2", "claude"), account("cursor", "cursor"),
            account("gemini-web", "gemini-web"), account("claude-1", "claude"), account("grok", "grok"), account("today-cn", "today-cn"), account("codex-1", "codex")));
    }
    @Test public void sharedDefaultsGroupAllSuppliersRegardlessOfWireOrder() throws Exception {
        List<JSONObject> values = sample(); List<String> before = ids(values);
        List<String> expected = List.of("codex-1", "codex-2", "claude-1", "claude-2", "grok", "grok-bot", "cursor", "today-cn", "today-global", "gemini-web", "gemini-web-2");
        for (int i = 0; i < values.size(); i++) { Collections.rotate(values, 1); assertEquals(expected, ids(TokenAccountOrder.ordered(values))); }
        assertEquals(before, ids(values)); assertEquals(expected, ids(Dashboard.tokenBoardAccounts(values, Set.of())));
        assertEquals("Gemini", TokensModel.section(values.get(0)));
    }
    @Test public void remoteWeightsMoveWholeGroupsAndAccountsWithoutTouchingData() throws Exception {
        JSONObject one = account("gemini-web", "gemini-web").put("displayOrder", order("google", 50, 20));
        JSONObject two = account("gemini-web-2", "gemini-web").put("displayOrder", order("google", 50, 10));
        JSONObject codex = account("codex-1", "codex"); String before = one.toString();
        assertEquals(List.of("gemini-web-2", "gemini-web", "codex-1"), ids(TokenAccountOrder.ordered(List.of(codex, one, two))));
        assertEquals(before, one.toString());
        two.getJSONObject("displayOrder").put("providerWeight", 999);
        assertEquals(List.of("codex-1", "gemini-web-2", "gemini-web"), ids(TokenAccountOrder.ordered(List.of(one, codex, two))));
    }
    @Test public void invalidOrUnknownContractUsesOneSharedFallback() throws Exception {
        JSONObject value = account("gemini-web-2", "gemini-web");
        for (Object invalid : new Object[]{-1, 1.5, 1_000_001, "10", true, JSONObject.NULL}) {
            value.put("displayOrder", order("google", invalid, 0)); assertEquals(200, TokenAccountOrder.accountWeight(value));
        }
        value.put("displayOrder", order("openai", 1, 0)); assertEquals(200, TokenAccountOrder.accountWeight(value));
        value.put("displayOrder", order("google", 1, 0).put("version", 2)); assertEquals(200, TokenAccountOrder.accountWeight(value));
        value.put("displayOrder", order("google", 0, 0)); assertEquals(0, TokenAccountOrder.accountWeight(value));
    }
    @Test public void extensionListIsOptionalAndCannotOverrideAnExistingSourceID() throws Exception {
        JSONObject codex = account("codex-1", "codex"), google = account("gemini-web", "gemini-web");
        JSONObject payload = new JSONObject().put("accounts", new JSONArray().put(codex))
            .put("additionalAccounts", new JSONArray().put(account("today-cn", "today-cn")))
            .put("extendedAccounts", new JSONArray().put(google).put(account("codex-1", "claude")).put(JSONObject.NULL));
        List<JSONObject> values = TokenAccountOrder.accounts(payload);
        assertEquals(List.of("codex-1", "today-cn", "gemini-web"), ids(values)); assertSame(codex, values.get(0));
        payload.remove("extendedAccounts"); assertEquals(List.of("codex-1", "today-cn"), ids(TokenAccountOrder.accounts(payload)));
    }
    @Test public void unknownAccountTiesUseOrdinalSourceIDsAcrossLocales() throws Exception {
        List<JSONObject> values = List.of(account("future-b", "future"), account("future-A", "future"), account("future-a", "future"));
        assertEquals(List.of("future-A", "future-a", "future-b"), ids(TokenAccountOrder.ordered(values)));
    }
    @Test public void widgetUsesTheSameExtensionAndSharedWeightsAsThePage() throws Exception {
        JSONObject one = account("gemini-web", "gemini-web").put("displayOrder", order("google", 50, 20));
        JSONObject two = account("gemini-web-2", "gemini-web").put("displayOrder", order("google", 50, 10));
        JSONObject payload = new JSONObject().put("accounts", new JSONArray().put(account("codex-1", "codex")))
            .put("extendedAccounts", new JSONArray().put(one).put(two));
        JSONArray rows = WidgetSnapshot.tokens(payload, Set.of()).getJSONArray("rows");
        assertEquals("Gemini", rows.getJSONObject(0).getString("kind"));
        assertEquals("Gemini", rows.getJSONObject(1).getString("kind"));
        assertEquals("OpenAI", rows.getJSONObject(2).getString("kind"));
    }
}
