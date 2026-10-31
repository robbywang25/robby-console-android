package wang.robby.console.android;

import static org.junit.Assert.*;
import org.junit.Test;

/** 番茄钟 moves focus → break → focus by end times, survives being away, and keeps its settings line canonical. */
public final class PomodoroTest {
    private static final long MIN = 60_000L, T0 = 1_800_000_000_000L;

    @Test public void settingsRoundTripAndFallBack() {
        Pomodoro.Settings s = new Pomodoro.Settings(50, 10, 20, 3, false, 2, 60, 25);
        assertEquals(s, Pomodoro.parse(s.serialize()));
        assertEquals(Pomodoro.Settings.DEFAULT, Pomodoro.parse(null));
        Pomodoro.Settings odd = Pomodoro.parse("focus=26;short=x;rounds=99;bowl=7;bowlVol=150;color=red");
        assertEquals(25, odd.focus); assertEquals(5, odd.shortBreak); assertEquals(4, odd.rounds); assertEquals(2, odd.bowl); assertEquals(100, odd.bowlVolume);
        assertNull(Pomodoro.parse(12)); assertNull(Pomodoro.normalize(true));
        assertEquals(Pomodoro.Settings.DEFAULT.serialize(), Pomodoro.normalize(""));
        assertEquals("focus=45;short=5;long=15;rounds=4;auto=1;bowl=1;bowlVol=80;noiseVol=40", Pomodoro.normalize("noiseVol=40;focus=45"));
    }

    @Test public void aCycleRunsFocusBreaksAndALongBreak() {
        Pomodoro.Settings s = new Pomodoro.Settings(25, 5, 15, 2, true, 1, 80, 40);
        Pomodoro.State st = Pomodoro.start(s, T0);
        assertEquals(Pomodoro.FOCUS, st.phase); assertEquals(1, st.round); assertEquals(25 * MIN, st.left(T0));
        st = Pomodoro.advance(s, st, T0 + 25 * MIN);
        assertEquals(Pomodoro.SHORT, st.phase); assertEquals(1, st.round); assertEquals(T0 + 30 * MIN, st.endsAt);
        st = Pomodoro.advance(s, st, T0 + 30 * MIN);
        assertEquals(Pomodoro.FOCUS, st.phase); assertEquals(2, st.round);
        st = Pomodoro.advance(s, st, T0 + 55 * MIN);
        assertEquals(Pomodoro.LONG, st.phase); assertEquals(T0 + 70 * MIN, st.endsAt);
        st = Pomodoro.advance(s, st, T0 + 70 * MIN);
        assertEquals(Pomodoro.FOCUS, st.phase); assertEquals(1, st.round);
    }

    @Test public void timeAwayIsCaughtUpFromEachEnd() {
        Pomodoro.Settings s = Pomodoro.Settings.DEFAULT;
        Pomodoro.State st = Pomodoro.advance(s, Pomodoro.start(s, T0), T0 + 57 * MIN);
        // 25 focus, 5 break, 25 focus, then 2 minutes into the second break.
        assertEquals(Pomodoro.SHORT, st.phase); assertEquals(2, st.round); assertEquals(3 * MIN, st.left(T0 + 57 * MIN));
    }

    @Test public void withoutAutoStartTheNextPhaseWaits() {
        Pomodoro.Settings s = Pomodoro.Settings.DEFAULT.with("auto", 0);
        Pomodoro.State st = Pomodoro.advance(s, Pomodoro.start(s, T0), T0 + 90 * MIN);
        assertEquals(Pomodoro.SHORT, st.phase); assertFalse(st.running); assertEquals(5 * MIN, st.left(T0 + 90 * MIN));
        st = Pomodoro.resume(st, T0 + 91 * MIN); assertEquals(T0 + 96 * MIN, st.endsAt);
    }

    @Test public void pauseKeepsTheRemainingTime() {
        Pomodoro.State st = Pomodoro.pause(Pomodoro.start(Pomodoro.Settings.DEFAULT, T0), T0 + 10 * MIN);
        assertFalse(st.running); assertEquals(15 * MIN, st.left(T0 + 99 * MIN));
        assertSame(st, Pomodoro.advance(Pomodoro.Settings.DEFAULT, st, T0 + 999 * MIN));
        st = Pomodoro.resume(st, T0 + 20 * MIN); assertEquals(T0 + 35 * MIN, st.endsAt);
    }

    @Test public void stateRoundTripsAndRejectsGarbage() {
        Pomodoro.State st = Pomodoro.start(Pomodoro.Settings.DEFAULT, T0);
        Pomodoro.State back = Pomodoro.State.parse(st.serialize());
        assertEquals(st.phase, back.phase); assertEquals(st.endsAt, back.endsAt); assertTrue(back.running);
        assertFalse(Pomodoro.State.parse("nap,1,1,5,0").active());
        assertFalse(Pomodoro.State.parse("focus,1,1").active());
        assertFalse(Pomodoro.State.parse("focus,x,1,5,0").active());
        assertFalse(Pomodoro.State.parse("").active());
    }

    @Test public void theClockRoundsUp() {
        assertEquals("25:00", Pomodoro.clock(25 * MIN));
        assertEquals("00:01", Pomodoro.clock(1));
        assertEquals("00:00", Pomodoro.clock(0));
        assertEquals("1:05:00", Pomodoro.clock(65 * MIN));
    }
}
