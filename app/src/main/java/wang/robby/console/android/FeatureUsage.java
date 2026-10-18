package wang.robby.console.android;

import java.util.*;
import java.util.function.Predicate;

/**
 * 常用 on 总览: features ranked by how often and how lately they were opened. Each open adds 1 to a score that halves
 * every seven days, so a page opened daily outranks one opened often last month, and one opened once today does not
 * displace a habit at once. Only feature ids and open times are kept, on this phone. Pure Java for JVM tests.
 */
final class FeatureUsage {
    private FeatureUsage() {}
    static final double HALF_LIFE_MS = 7 * 86_400_000d;
    static final int KEEP = 40;
    /** What 常用 shows before anything has been opened, and fills the gaps with. */
    static final List<String> DEFAULT = List.of("travel-ledger", "clipboard", "openai", "domains");

    static final class Entry { final double score; final long last; Entry(double score, long last) { this.score = score; this.last = last; } }

    static Map<String, Entry> parse(String saved) {
        Map<String, Entry> out = new LinkedHashMap<>(); if (saved == null) return out;
        for (String line : saved.split("\n")) {
            String[] p = line.split("\\|"); if (p.length != 3 || !p[0].matches("[a-z0-9-]{1,40}")) continue;
            try { double score = Double.parseDouble(p[1]); long last = Long.parseLong(p[2]); if (score > 0 && score < 1e6 && last > 0) out.put(p[0], new Entry(score, last)); } catch (NumberFormatException ignored) { }
        }
        return out;
    }
    static String serialize(Map<String, Entry> usage) {
        StringBuilder s = new StringBuilder();
        for (Map.Entry<String, Entry> e : usage.entrySet()) s.append(s.length() == 0 ? "" : "\n").append(e.getKey()).append('|').append(String.format(Locale.ROOT, "%.4f", e.getValue().score)).append('|').append(e.getValue().last);
        return s.toString();
    }
    static double weight(Entry e, long now) { return e.score * Math.pow(0.5, Math.max(0, now - e.last) / HALF_LIFE_MS); }
    /** One more open of {@code id} at {@code now}; only the {@link #KEEP} strongest entries are kept. */
    static Map<String, Entry> record(Map<String, Entry> usage, String id, long now) {
        Map<String, Entry> next = new LinkedHashMap<>(usage); Entry was = next.get(id);
        next.put(id, new Entry((was == null ? 0 : weight(was, now)) + 1, now));
        if (next.size() > KEEP) {
            List<String> weakest = new ArrayList<>(next.keySet()); weakest.sort(Comparator.comparingDouble(k -> weight(next.get(k), now)));
            for (String k : weakest.subList(0, next.size() - KEEP)) next.remove(k);
        }
        return next;
    }
    /** The {@code n} strongest known features, strongest first; ties go to the more recent; gaps are filled from {@link #DEFAULT}. */
    static List<String> top(Map<String, Entry> usage, long now, int n, Predicate<String> known) {
        List<String> ids = new ArrayList<>(); for (String id : usage.keySet()) if (known.test(id)) ids.add(id);
        ids.sort((a, b) -> { int c = Double.compare(weight(usage.get(b), now), weight(usage.get(a), now)); return c != 0 ? c : Long.compare(usage.get(b).last, usage.get(a).last); });
        List<String> out = new ArrayList<>(ids.subList(0, Math.min(n, ids.size())));
        for (String id : DEFAULT) if (out.size() < n && !out.contains(id) && known.test(id)) out.add(id);
        return out;
    }
}
