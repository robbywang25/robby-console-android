package wang.robby.console.android;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 番茄钟 for the 颂钵 page: focus, short break and, every few rounds, a long break. Pure Java. The running phase is kept
 * as its end time on the wall clock, never as a count of ticks, so a page reopened later, a service restarted by the
 * system or a reboot all read the same remaining time (Goodtime's approach). Settings are one canonical line that
 * syncs with the account as {@code bowl}; the timer itself stays on this phone.
 */
final class Pomodoro {
    private Pomodoro() {}
    static final String FOCUS = "focus", SHORT = "short", LONG = "long";
    static final int[] FOCUS_CHOICES = {10, 15, 20, 25, 30, 45, 50, 60, 90}, SHORT_CHOICES = {3, 5, 10, 15}, LONG_CHOICES = {10, 15, 20, 30}, ROUND_CHOICES = {2, 3, 4, 5, 6};

    /** The person's choices: minutes for each phase, focus rounds before a long break, the bowl and the two volumes. */
    static final class Settings {
        final int focus, shortBreak, longBreak, rounds, bowl, bowlVolume, noiseVolume; final boolean auto;
        Settings(int focus, int shortBreak, int longBreak, int rounds, boolean auto, int bowl, int bowlVolume, int noiseVolume) {
            this.focus = pick(focus, FOCUS_CHOICES, 25); this.shortBreak = pick(shortBreak, SHORT_CHOICES, 5); this.longBreak = pick(longBreak, LONG_CHOICES, 15);
            this.rounds = pick(rounds, ROUND_CHOICES, 4); this.auto = auto; this.bowl = Math.max(0, Math.min(BowlSynth.BOWLS.length - 1, bowl));
            this.bowlVolume = Math.max(0, Math.min(100, bowlVolume)); this.noiseVolume = Math.max(0, Math.min(100, noiseVolume));
        }
        static final Settings DEFAULT = new Settings(25, 5, 15, 4, true, 1, 80, 40);
        int minutes(String phase) { return FOCUS.equals(phase) ? focus : SHORT.equals(phase) ? shortBreak : longBreak; }
        Settings with(String key, int value) {
            return new Settings("focus".equals(key) ? value : focus, "short".equals(key) ? value : shortBreak, "long".equals(key) ? value : longBreak,
                "rounds".equals(key) ? value : rounds, "auto".equals(key) ? value != 0 : auto, "bowl".equals(key) ? value : bowl,
                "bowlVol".equals(key) ? value : bowlVolume, "noiseVol".equals(key) ? value : noiseVolume);
        }
        String serialize() {
            return "focus=" + focus + ";short=" + shortBreak + ";long=" + longBreak + ";rounds=" + rounds + ";auto=" + (auto ? 1 : 0)
                + ";bowl=" + bowl + ";bowlVol=" + bowlVolume + ";noiseVol=" + noiseVolume;
        }
        @Override public boolean equals(Object o) { return o instanceof Settings s && s.serialize().equals(serialize()); }
        @Override public int hashCode() { return serialize().hashCode(); }
    }

    /** Reads a saved or synced line; unknown keys are dropped and anything out of range falls back to its default. Null for a non-string. */
    static Settings parse(Object raw) {
        if (!(raw instanceof String s) || s.length() > 200) return raw == null ? Settings.DEFAULT : null;
        Map<String, Integer> v = new LinkedHashMap<>();
        for (String part : s.split(";")) {
            int eq = part.indexOf('='); if (eq <= 0) continue;
            try { v.put(part.substring(0, eq).trim(), Integer.parseInt(part.substring(eq + 1).trim())); } catch (NumberFormatException ignored) {}
        }
        Settings d = Settings.DEFAULT;
        return new Settings(v.getOrDefault("focus", d.focus), v.getOrDefault("short", d.shortBreak), v.getOrDefault("long", d.longBreak), v.getOrDefault("rounds", d.rounds),
            v.getOrDefault("auto", 1) != 0, v.getOrDefault("bowl", d.bowl), v.getOrDefault("bowlVol", d.bowlVolume), v.getOrDefault("noiseVol", d.noiseVolume));
    }
    /** The canonical synced form, or null when {@code raw} is not a settings line. */
    static String normalize(Object raw) { Settings s = raw == null ? null : parse(raw); return s == null ? null : s.serialize(); }

    /** Where the timer stands. {@code phase} null means idle; {@code round} is the focus round in this cycle, from 1. */
    static final class State {
        final String phase; final int round; final boolean running; final long endsAt, remaining;
        State(String phase, int round, boolean running, long endsAt, long remaining) { this.phase = phase; this.round = round; this.running = running; this.endsAt = endsAt; this.remaining = remaining; }
        static final State IDLE = new State(null, 1, false, 0, 0);
        boolean active() { return phase != null; }
        long left(long now) { return !active() ? 0 : running ? Math.max(0, endsAt - now) : remaining; }
        String serialize() { return phase == null ? "" : phase + "," + round + "," + (running ? 1 : 0) + "," + endsAt + "," + remaining; }
        static State parse(String s) {
            if (s == null || s.isEmpty()) return IDLE;
            String[] p = s.split(",");
            try {
                if (p.length != 5 || !(FOCUS.equals(p[0]) || SHORT.equals(p[0]) || LONG.equals(p[0]))) return IDLE;
                return new State(p[0], Math.max(1, Math.min(6, Integer.parseInt(p[1]))), "1".equals(p[2]), Long.parseLong(p[3]), Math.max(0, Long.parseLong(p[4])));
            } catch (NumberFormatException e) { return IDLE; }
        }
    }

    static State start(Settings s, long now) { return new State(FOCUS, 1, true, now + s.focus * 60_000L, 0); }
    static State pause(State st, long now) { return !st.running ? st : new State(st.phase, st.round, false, 0, st.left(now)); }
    static State resume(State st, long now) { return st.running || !st.active() ? st : new State(st.phase, st.round, true, now + st.remaining, 0); }

    /** The phase after {@code st}'s, starting at {@code at}: running when the settings start it automatically, else waiting at full length. */
    static State next(Settings s, State st, long at) {
        String phase; int round = st.round;
        if (FOCUS.equals(st.phase)) phase = round >= s.rounds ? LONG : SHORT;
        else { phase = FOCUS; round = LONG.equals(st.phase) ? 1 : Math.min(s.rounds, round + 1); }
        long length = s.minutes(phase) * 60_000L;
        return s.auto ? new State(phase, round, true, at + length, 0) : new State(phase, round, false, 0, length);
    }

    /** {@code st} brought up to {@code now}: every phase that ended in between is passed, each next one starting where the last ended. */
    static State advance(Settings s, State st, long now) {
        for (int guard = 0; st.running && now >= st.endsAt && guard < 1000; guard++) st = next(s, st, st.endsAt);
        return st;
    }

    /** "24:59", or "1:05:00" past an hour; rounds up so a phase shows its full length at the start and 0:00 only when done. */
    static String clock(long ms) {
        long sec = (Math.max(0, ms) + 999) / 1000, h = sec / 3600, m = sec / 60 % 60, x = sec % 60;
        return h > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, x) : String.format(java.util.Locale.ROOT, "%02d:%02d", m, x);
    }
    static String label(String phase) { return FOCUS.equals(phase) ? "专注" : SHORT.equals(phase) ? "短休息" : LONG.equals(phase) ? "长休息" : "番茄钟"; }

    private static int pick(int value, int[] choices, int fallback) { for (int c : choices) if (c == value) return value; return fallback; }
}
