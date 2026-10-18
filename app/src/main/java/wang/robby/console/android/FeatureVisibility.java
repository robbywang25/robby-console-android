package wang.robby.console.android;

import java.util.*;

/**
 * Which features 功能 leaves out. A feature is hidden when the person hid it, or when it ships hidden by default
 * ({@link FeatureCatalog#DEFAULT_HIDDEN}, features that do not read yet) and the person has not shown it again.
 * Showing a default-hidden feature is remembered, so it stays visible after an update; hiding it again forgets that.
 * Only feature ids are kept, on this phone. Pure Java for JVM tests.
 */
final class FeatureVisibility {
    private FeatureVisibility() {}
    static final String HIDDEN = "hiddenFeatures", SHOWN = "shownFeatures";

    static Set<String> parse(String saved) {
        Set<String> out = new LinkedHashSet<>(); if (saved == null) return out;
        for (String id : saved.split(",")) if (id.matches("[a-z0-9-]{1,40}")) out.add(id);
        return out;
    }
    static String serialize(Set<String> ids) { return String.join(",", ids); }

    /** Hidden ids: the person's own, plus the defaults they have not shown again. */
    static Set<String> hidden(Set<String> userHidden, Set<String> userShown, Set<String> defaults) {
        Set<String> out = new LinkedHashSet<>(userHidden);
        for (String id : defaults) if (!userShown.contains(id)) out.add(id);
        return out;
    }
    /** New {hidden, shown} after hiding {@code id}. */
    static List<Set<String>> hide(Set<String> userHidden, Set<String> userShown, String id) {
        Set<String> h = new LinkedHashSet<>(userHidden), s = new LinkedHashSet<>(userShown); h.add(id); s.remove(id); return List.of(h, s);
    }
    /** New {hidden, shown} after showing {@code id}; a default-hidden one is remembered as shown. */
    static List<Set<String>> show(Set<String> userHidden, Set<String> userShown, String id, Set<String> defaults) {
        Set<String> h = new LinkedHashSet<>(userHidden), s = new LinkedHashSet<>(userShown); h.remove(id); if (defaults.contains(id)) s.add(id); return List.of(h, s);
    }
}
