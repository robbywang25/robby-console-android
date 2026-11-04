package wang.robby.console.android;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;

/** Version 1 of the shared Tokens display order. Wire arrays retain their original contract. */
final class TokenAccountOrder {
    private TokenAccountOrder() {}
    private static final Map<String, Integer> PROVIDERS = Map.of(
        "openai", 100, "anthropic", 200, "xai", 300, "cursor", 400,
        "today", 500, "google", 600, "other", 900);
    private static final Map<String, Integer> ACCOUNTS = Map.ofEntries(
        Map.entry("codex-1", 100), Map.entry("codex-2", 200), Map.entry("chatgpt-dots", 300), Map.entry("chatgpt-chat", 400),
        Map.entry("claude-1", 100), Map.entry("claude-2", 200), Map.entry("grok", 100), Map.entry("grok-bot", 200),
        Map.entry("cursor", 100), Map.entry("today-cn", 100), Map.entry("today-global", 200),
        Map.entry("gemini-web", 100), Map.entry("gemini-web-2", 200), Map.entry("gemini-cli", 300),
        Map.entry("gemini-cli-2", 400), Map.entry("gemini-api", 500), Map.entry("gemini-api-2", 600));

    static String providerKey(JSONObject account) {
        String product = account.optString("product").toLowerCase(Locale.ROOT);
        String provider = account.optString("provider").toLowerCase(Locale.ROOT);
        if (List.of("gemini-cli", "gemini-web", "gemini-api").contains(product) || provider.equals("google")) return "google";
        if (List.of("today-cn", "today-global").contains(product) || provider.equals("todayai")) return "today";
        if (product.equals("cursor") || provider.equals("cursor")) return "cursor";
        if (product.equals("claude") || List.of("anthropic", "claude").contains(provider)) return "anthropic";
        if (List.of("grok", "grok-bot").contains(product) || List.of("xai", "x.ai", "grok").contains(provider)) return "xai";
        if (List.of("codex", "chatgpt-dots", "chatgpt-chat").contains(product) || List.of("openai", "chatgpt").contains(provider)) return "openai";
        return "other";
    }
    private static boolean weight(Object value) {
        if (!(value instanceof Number)) return false;
        double n = ((Number) value).doubleValue();
        return Double.isFinite(n) && n >= 0 && n <= 1_000_000 && n == Math.rint(n);
    }
    private static JSONObject supplied(JSONObject account) {
        JSONObject value = account.optJSONObject("displayOrder");
        if (value == null || !weight(value.opt("version")) || ((Number) value.opt("version")).intValue() != 1
            || !providerKey(account).equals(value.opt("providerKey"))
            || !weight(value.opt("providerWeight")) || !weight(value.opt("accountWeight"))) return null;
        return value;
    }
    static int accountWeight(JSONObject account) {
        JSONObject value = supplied(account);
        return value == null ? ACCOUNTS.getOrDefault(account.optString("id"), 10_000) : ((Number) value.opt("accountWeight")).intValue();
    }
    static final Comparator<JSONObject> ACCOUNT_ORDER = Comparator.comparingInt(TokenAccountOrder::accountWeight)
        .thenComparing(account -> account.optString("id"));

    /** Merge the unchanged legacy lists and additive extension list. First occurrence of a source ID wins. */
    static List<JSONObject> accounts(JSONObject payload) {
        Map<String, JSONObject> unique = new LinkedHashMap<>();
        if (payload != null) for (String key : List.of("accounts", "additionalAccounts", "extendedAccounts")) {
            org.json.JSONArray list = payload.optJSONArray(key);
            if (list == null) continue;
            for (int i = 0; i < list.length() && unique.size() < 1000; i++) {
                JSONObject account = list.optJSONObject(i);
                if (account == null || !(account.opt("id") instanceof String)) continue;
                String id = account.optString("id");
                if (id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")) unique.putIfAbsent(id, account);
            }
        }
        return ordered(new ArrayList<>(unique.values()));
    }

    /** Sort provider groups first, including disagreement fallback, then accounts; never use quota state or locale. */
    static List<JSONObject> ordered(List<JSONObject> accounts) {
        Map<String, List<JSONObject>> grouped = new LinkedHashMap<>();
        if (accounts != null) for (JSONObject account : accounts) if (account != null)
            grouped.computeIfAbsent(providerKey(account), key -> new ArrayList<>()).add(account);
        Map<String, Integer> weights = new LinkedHashMap<>();
        for (Map.Entry<String, List<JSONObject>> group : grouped.entrySet()) {
            int fallback = PROVIDERS.get(group.getKey()); Integer chosen = null; boolean conflict = false;
            for (JSONObject account : group.getValue()) {
                JSONObject value = supplied(account);
                int current = value == null ? fallback : ((Number) value.opt("providerWeight")).intValue();
                if (chosen == null) chosen = current; else if (chosen != current) conflict = true;
            }
            weights.put(group.getKey(), conflict || chosen == null ? fallback : chosen);
            group.getValue().sort(ACCOUNT_ORDER);
        }
        List<String> providers = new ArrayList<>(grouped.keySet());
        providers.sort(Comparator.comparingInt((String key) -> weights.get(key)).thenComparing(key -> key));
        List<JSONObject> ordered = new ArrayList<>();
        for (String provider : providers) ordered.addAll(grouped.get(provider));
        return ordered;
    }
}
