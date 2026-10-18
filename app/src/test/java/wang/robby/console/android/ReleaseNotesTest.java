package wang.robby.console.android;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** Keeps the in-app changelog honest: ordered, short, and never missing the version being built. */
public final class ReleaseNotesTest {
    private static int[] parts(String v) { String[] p = v.split("\\."); return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])}; }
    private static int compare(String a, String b) { int[] x = parts(a), y = parts(b); for (int i = 0; i < 3; i++) if (x[i] != y[i]) return Integer.compare(x[i], y[i]); return 0; }

    @Test public void newestFirstWithReadableEntries() {
        assertFalse(ReleaseNotes.ALL.isEmpty());
        for (int i = 0; i < ReleaseNotes.ALL.size(); i++) {
            ReleaseNotes.Entry e = ReleaseNotes.ALL.get(i);
            assertTrue(e.version, e.version.matches("\\d+\\.\\d+\\.\\d+"));
            LocalDate date = LocalDate.parse(e.date);
            assertFalse(e.headline.isBlank()); assertTrue(e.headline, e.headline.length() <= 18);
            assertTrue(e.version + " needs 1-6 items", e.items.size() >= 1 && e.items.size() <= 6);
            for (String item : e.items) { assertFalse(item.isBlank()); assertTrue(item, item.length() <= 48); }
            if (i > 0) { ReleaseNotes.Entry newer = ReleaseNotes.ALL.get(i - 1);
                assertTrue(newer.version + " must be newer than " + e.version, compare(newer.version, e.version) > 0);
                assertFalse(LocalDate.parse(newer.date).isBefore(date)); }
        }
    }
    /** Bumping versionName without writing its notes fails the build. */
    @Test public void currentBuildVersionHasNotesOnTop() throws Exception {
        String gradle = new String(Files.readAllBytes(Paths.get("build.gradle")), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("versionName\\s+'([^']+)'").matcher(gradle);
        assertTrue("versionName not found", m.find());
        assertEquals(m.group(1), ReleaseNotes.ALL.get(0).version);
    }
    @Test public void dateLabelHidesTheCurrentYear() {
        assertEquals("10月15日", ReleaseNotes.dateLabel("2026-10-15", LocalDate.of(2026, 12, 1)));
        assertEquals("2026年10月15日", ReleaseNotes.dateLabel("2026-10-15", LocalDate.of(2027, 1, 2)));
    }
}
