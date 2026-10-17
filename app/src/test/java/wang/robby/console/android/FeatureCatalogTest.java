package wang.robby.console.android;

import static org.junit.Assert.*;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/** Guards the catalog's routing and authority boundary as more features are added. */
public final class FeatureCatalogTest {
    @Test public void routesStayWithinMobileReadSurface() {
        Set<String> ids = new HashSet<>();
        for (FeatureCatalog.Feature f : FeatureCatalog.all()) {
            assertTrue("Duplicate navigation identity: " + f.id, ids.add(f.id));
            assertSame(f, FeatureCatalog.find(f.id));
            assertTrue(f.path.matches("[a-z0-9]+(?:[-/][a-z0-9]+)*"));
            assertFalse(f.path.contains("/actions/"));
            assertFalse(f.path.contains("/reveal"));
            assertFalse(f.path.contains("/uploads"));
            assertTrue("Read-only scope required for " + f.id, f.scope.matches("god\\.mobile(?:\\.[a-z-]+)*\\.read"));
            assertFalse(f.scope.contains("write"));
            assertFalse(f.scope.contains("export"));
        }
    }
    @Test public void unknownIdentityDoesNotResolveToAnotherFeature() {
        assertNull(FeatureCatalog.find("not-a-feature"));
        assertNull(FeatureCatalog.find(null));
    }
    @Test(expected = UnsupportedOperationException.class)
    public void callersCannotChangeGlobalNavigation() { FeatureCatalog.all().clear(); }
    private static java.util.List<String> ids(String query) {
        java.util.List<String> out = new java.util.ArrayList<>(); for (FeatureCatalog.Feature f : FeatureCatalog.search(query)) out.add(f.id); return out;
    }
    @Test public void searchFindsFeaturesByNameDescriptionAndAliasesLikeIos() {
        assertTrue(ids("服务器").contains("infrastructure"));
        assertTrue(ids("账本").contains("travel-ledger"));
        assertTrue(ids("额度").contains("tokens"));
        assertTrue(ids("codex").contains("tokens"));
        assertTrue(ids("提示词").contains("prompts"));
        assertTrue("full-width and upper case", ids("ＣＯＤＥＸ").contains("tokens"));
        assertEquals("every term must match", java.util.List.of("wechat-login-audit"), ids("微信 审计"));
        assertTrue("settings entries are searchable too", ids("凭证").contains("secrets"));
        assertTrue(ids("没有这个功能xyz").isEmpty());
    }
    @Test public void blankSearchListsEveryFeatureButTheInternalWorkbench() {
        assertEquals(FeatureCatalog.all().size() - 1, FeatureCatalog.search("  ").size());
        assertFalse(ids("").contains("overview"));
        for (String section : FeatureCatalog.SECTIONS) assertTrue(FeatureCatalog.all().stream().anyMatch(f -> f.group.equals(section)));
    }
}
