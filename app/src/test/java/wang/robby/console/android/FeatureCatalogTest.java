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
}
