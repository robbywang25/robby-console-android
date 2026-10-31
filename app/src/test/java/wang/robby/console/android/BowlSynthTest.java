package wang.robby.console.android;

import static org.junit.Assert.*;
import org.junit.Test;

/** The synthesized bowl rings at its partials, beats, fades to silence, and the mix never leaves [-1, 1]. */
public final class BowlSynthTest {
    private static final int RATE = 8000;

    private static float[] render(BowlSynth s, double seconds) {
        int frames = (int) (seconds * RATE), block = 256; float[] all = new float[frames * 2], buf = new float[block * 2];
        for (int at = 0; at < frames; at += block) { int n = Math.min(block, frames - at); s.render(buf, n); System.arraycopy(buf, 0, all, at * 2, n * 2); }
        return all;
    }
    /** Power of the left channel at {@code hz} (Goertzel). */
    private static double power(float[] stereo, double hz) {
        double w = 2 * Math.PI * hz / RATE, k = 2 * Math.cos(w), s1 = 0, s2 = 0;
        for (int i = 0; i < stereo.length; i += 2) { double s0 = stereo[i] + k * s1 - s2; s2 = s1; s1 = s0; }
        return s1 * s1 + s2 * s2 - k * s1 * s2;
    }
    private static double rms(float[] x, int from, int to) { double sum = 0; for (int i = from; i < to; i++) sum += x[i] * x[i]; return Math.sqrt(sum / Math.max(1, to - from)); }

    @Test public void aStrikeRingsAtTheBowlsPartials() {
        BowlSynth s = new BowlSynth(RATE, 42); s.setBowl(1); s.strike(.9f);
        float[] out = render(s, 1.5);
        double f0 = BowlSynth.BOWLS[1], at = power(out, f0), beside = Math.max(power(out, f0 * .9), power(out, f0 * 1.1));
        assertTrue("fundamental stands out", at > beside * 50);
        assertTrue("second partial present", power(out, f0 * BowlSynth.RATIO[1]) > power(out, f0 * 2) * 10);
        assertTrue(s.level > 0);
    }

    @Test public void aStrikeFadesAndTheSynthFallsSilent() {
        BowlSynth s = new BowlSynth(RATE, 1); s.strike(1f);
        float[] out = render(s, .5), late = render(s, 12);
        // A bowl rings long: the fundamental's time constant is nine seconds.
        assertTrue(rms(out, 0, out.length) > rms(late, late.length - RATE, late.length) * 3);
        assertFalse(s.silent());
        render(s, 150);
        assertTrue("every voice has died away", s.silent());
    }

    @Test public void theMixNeverClips() {
        BowlSynth s = new BowlSynth(RATE, 3); s.setVolumes(1, 1); s.setNoise(4); s.setRub(1, 1);
        for (int i = 0; i < 12; i++) s.strike(1f);
        for (float x : render(s, 4)) assertTrue(x >= -1f && x <= 1f);
    }

    @Test public void pinkAndBrownAreDarkerThanWhite() {
        double[] roughness = new double[4];
        for (int type = 1; type <= 3; type++) {
            BowlSynth s = new BowlSynth(RATE, 9); s.setVolumes(0, 1); s.setNoise(type); render(s, 1.5);
            float[] out = render(s, 2); double diff = 0, energy = 0;
            for (int i = 2; i < out.length; i += 2) { double d = out[i] - out[i - 2]; diff += d * d; energy += out[i] * out[i]; }
            assertTrue(energy > 0); roughness[type] = diff / energy;
        }
        assertTrue(roughness[1] > roughness[2] * 2); assertTrue(roughness[2] > roughness[3] * 2);
    }

    @Test public void noiseFadesInAndOutAndChangesWithoutAJump() {
        BowlSynth s = new BowlSynth(RATE, 5); s.setVolumes(0, 1); s.setNoise(2);
        float[] start = render(s, .05); assertTrue("fades in", rms(start, 0, start.length) < .05);
        render(s, 2); s.setNoise(3);
        float[] swap = render(s, 3); assertTrue(rms(swap, swap.length - 2000, swap.length) > .01);
        s.setNoise(0); render(s, 3);
        assertTrue(s.silent());
    }

    @Test public void rubbingSwellsAndLetsGo() {
        BowlSynth s = new BowlSynth(RATE, 8); s.setRub(1, .5f);
        float[] early = render(s, .2), later = render(s, 3);
        assertTrue(rms(later, later.length - 4000, later.length) > rms(early, 0, early.length) * 2);
        s.setRub(0, 0); render(s, 30);
        assertTrue(s.silent());
    }

    @Test public void theSameSeedRendersTheSameSound() {
        BowlSynth a = new BowlSynth(RATE, 77), b = new BowlSynth(RATE, 77); a.setNoise(4); b.setNoise(4); a.strike(.7f); b.strike(.7f);
        assertArrayEquals(render(a, .5), render(b, .5), 0f);
    }
}
