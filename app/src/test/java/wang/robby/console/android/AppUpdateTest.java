package wang.robby.console.android;

import static org.junit.Assert.*;
import java.net.URL;
import java.util.List;
import org.junit.Test;

/** The pure decisions behind in-app update: which release, which bytes, which hosts. */
public final class AppUpdateTest {
    private static AppUpdate.Release r(String v) { return new AppUpdate.Release(v, "t", "", "2026-10-15", "robby-console-android-" + v + ".apk", "https://github.com/x", 1000, "https://github.com/y"); }

    @Test public void onlyPlainAppTagsAreReleases() {
        assertEquals("0.4.1", AppUpdate.version("v0.4.1"));
        assertNull(AppUpdate.version("billing-lab-v0.1.0"));
        assertNull(AppUpdate.version("v0.4.1-rc1"));
        assertNull(AppUpdate.version("0.4.1"));
        assertNull(AppUpdate.version(null));
    }
    @Test public void versionsCompareNumerically() {
        assertTrue(AppUpdate.compare("0.10.0", "0.9.9") > 0);
        assertTrue(AppUpdate.compare("1.0.0", "0.99.99") > 0);
        assertEquals(0, AppUpdate.compare("0.4.0", "0.4.0"));
    }
    @Test public void picksTheNewestStrictlyNewerRelease() {
        List<AppUpdate.Release> all = List.of(r("0.3.6"), r("0.5.1"), r("0.4.0"), r("0.5.0"));
        assertEquals("0.5.1", AppUpdate.pick(all, "0.4.0").version);
        assertNull("already newest", AppUpdate.pick(all, "0.5.1"));
        assertNull("a newer local build never downgrades", AppUpdate.pick(all, "0.6.0"));
        assertNull("unknown local version", AppUpdate.pick(all, ""));
    }
    @Test public void checksumLineMustNameTheExactFile() {
        String hash = "4df68091ce8af11b5312842e6ab8359ab06cac22b39fc67de07624f9e28fb8be";
        String sums = hash.toUpperCase() + "  robby-console-android-0.4.0.apk\n" + "0".repeat(64) + "  other.apk\n";
        assertEquals(hash, AppUpdate.expectedSha(sums, "robby-console-android-0.4.0.apk"));
        assertEquals(hash, AppUpdate.expectedSha(hash + " *robby-console-android-0.4.0.apk", "robby-console-android-0.4.0.apk"));
        assertNull(AppUpdate.expectedSha(sums, "robby-console-android-0.4.1.apk"));
        assertNull(AppUpdate.expectedSha("abc  robby-console-android-0.4.0.apk", "robby-console-android-0.4.0.apk"));
    }
    @Test public void downloadsStayOnGitHubOverHttps() throws Exception {
        assertTrue(AppUpdate.allowed(new URL("https://api.github.com/repos/a/b/releases")));
        assertTrue(AppUpdate.allowed(new URL("https://release-assets.githubusercontent.com/x?sig=1")));
        assertFalse(AppUpdate.allowed(new URL("http://github.com/a")));
        assertFalse(AppUpdate.allowed(new URL("https://github.com.evil.example/a")));
        assertFalse(AppUpdate.allowed(new URL("https://user@github.com/a")));
        assertFalse(AppUpdate.allowed(new URL("https://github.com:8443/a")));
    }
    @Test public void assetsComeThroughTheApiFirstAndGitHubComOnlyAsFallback() throws Exception {
        String json = "[{\"tag_name\":\"v0.21.1\",\"draft\":false,\"name\":\"v0.21.1 · x\",\"body\":\"\",\"published_at\":\"2026-10-19T00:00:00Z\",\"assets\":["
            + "{\"name\":\"robby-console-android-0.21.1.apk\",\"size\":500000,\"url\":\"https://api.github.com/repos/a/b/releases/assets/11\",\"browser_download_url\":\"https://github.com/a/b/releases/download/v0.21.1/robby-console-android-0.21.1.apk\"},"
            + "{\"name\":\"SHA256SUMS-0.21.1.txt\",\"size\":99,\"url\":\"https://api.github.com/repos/a/b/releases/assets/12\",\"browser_download_url\":\"https://github.com/a/b/releases/download/v0.21.1/SHA256SUMS-0.21.1.txt\"}]}]";
        AppUpdate.Release r = AppUpdate.parse(json).get(0);
        assertEquals("https://api.github.com/repos/a/b/releases/assets/11", r.apkUrl);
        assertEquals("https://api.github.com/repos/a/b/releases/assets/12", r.sumsUrl);
        assertTrue(r.apkMirror.startsWith("https://github.com/") && r.sumsMirror.startsWith("https://github.com/"));
        assertEquals("application/octet-stream", AppUpdate.accept(new URL(r.apkUrl)));
        assertEquals("application/vnd.github+json", AppUpdate.accept(new URL(AppUpdate.RELEASES)));
        assertNull(AppUpdate.accept(new URL("https://release-assets.githubusercontent.com/x")));
        assertEquals("no API address: the download link is used", "https://github.com/x",
            AppUpdate.assetUrl(new org.json.JSONObject("{\"url\":\"https://example.com/a\",\"browser_download_url\":\"https://github.com/x\"}")));
    }
    @Test public void titleAndSummaryAreShortPlainText() {
        assertEquals("Liquid Glass 外观与更新记录", AppUpdate.theme("v0.4.0 · Liquid Glass 外观与更新记录", "0.4.0"));
        assertEquals("0.4.0", AppUpdate.theme(null, "0.4.0"));
        assertEquals("整体换成 Liquid Glass 风格，见 FEATURE-PARITY。", AppUpdate.summary("整体换成 **Liquid Glass** 风格，见 [FEATURE-PARITY](docs/x.md)。\n\n- 外观"));
        assertTrue(AppUpdate.summary("字".repeat(300)).length() <= 120);
    }
    @Test public void aboutChecksAgainButNotTooOftenNorDuringAnUpdate() {
        long now = 1_000_000L;
        assertTrue("first open of a run", AppUpdate.recheck("idle", false, 0L, now));
        assertTrue("a version was found a minute ago", AppUpdate.recheck("available", false, now - 60_000L, now));
        assertTrue(AppUpdate.recheck("latest", false, now - 120_000L, now));
        assertFalse("checked moments ago", AppUpdate.recheck("available", false, now - 5_000L, now));
        assertFalse("already checking", AppUpdate.recheck("available", true, now - 120_000L, now));
        assertFalse("downloading", AppUpdate.recheck("downloading", false, now - 120_000L, now));
        assertFalse("installing", AppUpdate.recheck("installing", false, now - 120_000L, now));
    }
}
