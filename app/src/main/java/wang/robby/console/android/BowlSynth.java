package wang.robby.console.android;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The 颂钵 sound, synthesized: struck and rubbed singing bowls over a bed of white, pink, brown or rain noise, mixed
 * to interleaved stereo floats. Pure Java with no Android calls, so the JVM tests can render it. Other threads only set
 * volatile targets or queue strikes; {@link #render} runs on the audio thread alone.
 *
 * The bowl follows the open singing-bowl synths on GitHub (evoluteur/tibetan-singing-bowls, MIT; FranzisWorkshop's
 * measured bowl after J. Stever's thesis; wkbaran/bowllab): five inharmonic partials, each a pair of sines a few hertz
 * apart so the tone beats, the higher ones dying sooner; a 30 ms band-passed click for the mallet; rubbing swells the
 * lower partials with a slow wobble instead of decaying. Noise is Paul Kellet's refined pink filter and a leaky
 * integrator for brown, as in chmouel/noise-timer (MIT); rain is filtered pink with random drops. A small Schroeder
 * reverb gives the bowl a room.
 */
final class BowlSynth {
    /** Partial frequency ratios, relative amplitudes, decay time constants (s, at the middle bowl) and beat rates (Hz). */
    static final float[] RATIO = {1f, 2.72f, 5.15f, 8.25f, 11.9f}, AMP = {1f, .55f, .28f, .12f, .05f},
        TAU = {9f, 5f, 2.4f, 1.2f, .65f}, BEAT = {.5f, 1.2f, 2.4f, 3.6f, 5f}, SING = {1f, .42f, .12f, .04f, 0f};
    static final int PARTIALS = RATIO.length, VOICES = 8;
    /** Bowl fundamentals, large to small: G3, C4, G4. */
    static final int[] BOWLS = {196, 262, 392};
    static final String[] BOWL_NAMES = {"大钵", "中钵", "小钵"};
    static final String[] NOISES = {"off", "white", "pink", "brown", "rain"};
    static final String[] NOISE_NAMES = {"关闭", "白噪音", "粉红噪音", "棕噪音", "雨声"};

    final int rate;
    private final ConcurrentLinkedQueue<float[]> strikes = new ConcurrentLinkedQueue<>();
    private volatile int bowl = 1, noise;
    private volatile float bowlVolume = .8f, noiseVolume = .4f, rubTarget, wobbleRate = .38f, duck = 1f;
    /** Smoothed peak of the dry bowl, 0–1, for the page's ripples. */
    volatile float level;

    // Struck voices: per voice and partial, two phasors (cos, sin) and their per-sample rotation, plus the envelope.
    private final float[] ac = new float[VOICES * PARTIALS], as = new float[VOICES * PARTIALS], ar = new float[VOICES * PARTIALS], ai = new float[VOICES * PARTIALS];
    private final float[] bc = new float[VOICES * PARTIALS], bs = new float[VOICES * PARTIALS], br = new float[VOICES * PARTIALS], bi = new float[VOICES * PARTIALS];
    private final float[] env = new float[VOICES * PARTIALS], decay = new float[VOICES * PARTIALS], mix = new float[VOICES * PARTIALS];
    private final int[] age = new int[VOICES];
    private final boolean[] live = new boolean[VOICES];
    // The rubbed bowl: one continuous set of phasors whose level follows the finger.
    private final float[] rc = new float[PARTIALS * 2], rs = new float[PARTIALS * 2], rr = new float[PARTIALS * 2], ri = new float[PARTIALS * 2];
    private int rubBowl = -1; private float rub, wobble;
    // Mallet click: summed bursts through one band-pass at 2.6 kHz.
    private float click, clickDecay, bp1, bp2; private final float bpA1, bpA2, bpB0;
    // Noise state per channel.
    private final float[][] pink = new float[2][7]; private final float[] brown = new float[2], lowRain = new float[2];
    private int playing; private float noiseGain;
    private final float[] dropC = new float[16], dropS = new float[16], dropR = new float[16], dropI = new float[16], dropEnv = new float[16], dropDecay = new float[16], dropPan = new float[16];
    // Reverb: four combs and two all-passes per channel.
    private final float[][] comb = new float[8][], allpass = new float[4][]; private final int[] combAt = new int[8], allAt = new int[4]; private final float[] combLow = new float[8];
    private int seed; private float peak; private float[] dry = new float[0];

    BowlSynth(int rate) { this(rate, 0x2545f491); }
    BowlSynth(int rate, int seed) {
        this.rate = rate; this.seed = seed == 0 ? 1 : seed;
        double w = 2 * Math.PI * 2600 / rate, alpha = Math.sin(w) / (2 * .8), a0 = 1 + alpha;
        bpB0 = (float) (alpha / a0); bpA1 = (float) (-2 * Math.cos(w) / a0); bpA2 = (float) ((1 - alpha) / a0);
        int[] combs = {1557, 1617, 1491, 1422}, alls = {556, 441};
        for (int i = 0; i < 8; i++) comb[i] = new float[Math.max(1, (combs[i % 4] + (i / 4) * 23) * rate / 44100)];
        for (int i = 0; i < 4; i++) allpass[i] = new float[Math.max(1, (alls[i % 2] + (i / 2) * 23) * rate / 44100)];
    }

    void setBowl(int index) { bowl = Math.max(0, Math.min(BOWLS.length - 1, index)); }
    int bowl() { return bowl; }
    void setNoise(int index) { noise = Math.max(0, Math.min(NOISES.length - 1, index)); }
    int noise() { return noise; }
    void setVolumes(float bowl, float noise) { bowlVolume = clamp(bowl); noiseVolume = clamp(noise); }
    /** Audio focus: 1 normally, lower while another app ducks us, 0 during a call. */
    void setDuck(float gain) { duck = clamp(gain); }
    /** How hard the rim is being rubbed, 0–1, and how fast the finger goes round (turns a second). */
    void setRub(float amount, float turnsPerSecond) { rubTarget = clamp(amount); wobbleRate = .3f + Math.min(.9f, Math.abs(turnsPerSecond) * .6f); }
    float rubTarget() { return rubTarget; }
    /** Strikes the current bowl; {@code strength} 0–1 also brightens the tone. */
    void strike(float strength) { strikes.add(new float[]{clamp(strength), bowl}); }

    /** Nothing sounds and nothing is about to: the audio thread may stop once the reverb has rung out. */
    boolean silent() {
        if (!strikes.isEmpty() || rubTarget > 0 || noise != 0 || rub > 1e-4f || noiseGain > 1e-4f) return false;
        for (boolean v : live) if (v) return false;
        return true;
    }

    /** Fills {@code out} with {@code frames} interleaved stereo samples. */
    void render(float[] out, int frames) {
        java.util.Arrays.fill(out, 0, frames * 2, 0f);
        for (float[] s; (s = strikes.poll()) != null; ) start(s[0], (int) s[1]);
        if (dry.length < frames) dry = new float[frames]; else java.util.Arrays.fill(dry, 0, frames, 0f);
        float gain = bowlVolume * bowlVolume * .45f;
        for (int v = 0; v < VOICES; v++) if (live[v]) voice(v, out, dry, frames, gain);
        sing(out, dry, frames, gain);
        mallet(dry, frames, gain);
        reverb(out, dry, frames);
        noise(out, frames);
        float d = duck, top = peak * (float) Math.exp(-frames / (rate * .25));
        for (int i = 0; i < frames; i++) top = Math.max(top, Math.abs(dry[i]) / Math.max(.05f, gain));
        peak = top; level = Math.min(1f, top);
        for (int i = 0; i < frames * 2; i++) { float x = out[i] * d; out[i] = x > 1.5f ? 1f : x < -1.5f ? -1f : x - x * x * x * (4f / 27f); }
    }

    private void start(float strength, int index) {
        int v = 0; float quiet = Float.MAX_VALUE;
        for (int i = 0; i < VOICES; i++) { if (!live[i]) { v = i; quiet = -1; break; } float e = env[i * PARTIALS]; if (e < quiet) { quiet = e; v = i; } }
        float f0 = BOWLS[index] * (1 + (rand() * .0016f)), scale = (float) Math.sqrt(262f / BOWLS[index]);
        for (int k = 0; k < PARTIALS; k++) {
            int j = v * PARTIALS + k; float f = f0 * RATIO[k], beat = BEAT[k] * (1 + rand() * .2f);
            if (f + beat >= rate * .45f) { env[j] = 0; continue; }
            double wa = 2 * Math.PI * f / rate, wb = 2 * Math.PI * (f + beat) / rate, pa = Math.PI * (rand() + 1), pb = Math.PI * (rand() + 1);
            ac[j] = (float) Math.cos(pa); as[j] = (float) Math.sin(pa); ar[j] = (float) Math.cos(wa); ai[j] = (float) Math.sin(wa);
            bc[j] = (float) Math.cos(pb); bs[j] = (float) Math.sin(pb); br[j] = (float) Math.cos(wb); bi[j] = (float) Math.sin(wb);
            env[j] = AMP[k] * strength * (float) Math.pow(Math.max(.05f, strength), .35f * k);
            decay[j] = (float) Math.exp(-1.0 / (TAU[k] * scale * (.75 + .25 * strength) * rate));
            mix[j] = .7f + .3f * Math.abs(rand());
        }
        age[v] = 0; live[v] = true;
        click += .5f * strength * strength; clickDecay = (float) Math.exp(-1.0 / (.008 * rate));
    }

    private void voice(int v, float[] out, float[] dry, int frames, float gain) {
        int attack = Math.max(1, rate / 250); boolean any = false;
        for (int k = 0; k < PARTIALS; k++) {
            int j = v * PARTIALS + k; float e = env[j]; if (e < 1e-5f) continue; any = true;
            float c1 = ac[j], s1 = as[j], r1 = ar[j], i1 = ai[j], c2 = bc[j], s2 = bs[j], r2 = br[j], i2 = bi[j], d = decay[j], m = mix[j];
            for (int n = 0, a = age[v]; n < frames; n++, a++) {
                float t = c1 * r1 - s1 * i1; s1 = s1 * r1 + c1 * i1; c1 = t;
                t = c2 * r2 - s2 * i2; s2 = s2 * r2 + c2 * i2; c2 = t;
                float g = e * gain * (a < attack ? a / (float) attack : 1f), x = s1 * g, y = s2 * g * m;
                out[n * 2] += x * .62f + y * .38f; out[n * 2 + 1] += x * .38f + y * .62f; dry[n] += (x + y) * .5f; e *= d;
            }
            float k1 = 1f / (float) Math.sqrt(c1 * c1 + s1 * s1), k2 = 1f / (float) Math.sqrt(c2 * c2 + s2 * s2);
            ac[j] = c1 * k1; as[j] = s1 * k1; bc[j] = c2 * k2; bs[j] = s2 * k2; env[j] = e;
        }
        age[v] += frames; live[v] = any;
    }

    private void sing(float[] out, float[] dry, int frames, float gain) {
        float target = rubTarget;
        if (target <= 0 && rub < 1e-4f) { rub = 0; return; }
        int index = bowl;
        if (index != rubBowl) {
            rubBowl = index;
            for (int k = 0; k < PARTIALS; k++) for (int p = 0; p < 2; p++) {
                int j = k * 2 + p; double w = 2 * Math.PI * (BOWLS[index] * RATIO[k] + p * BEAT[k] * .8) / rate;
                if (rc[j] == 0 && rs[j] == 0) rc[j] = 1;
                rr[j] = (float) Math.cos(w); ri[j] = (float) Math.sin(w);
            }
        }
        float up = (float) Math.exp(-1.0 / (.9 * rate)), down = (float) Math.exp(-1.0 / (1.3 * rate)), r = rub, ph = wobble, dph = (float) (2 * Math.PI * wobbleRate / rate);
        for (int n = 0; n < frames; n++) {
            float coef = target > r ? up : down; r = target + (r - target) * coef; ph += dph; if (ph > 2 * Math.PI) ph -= (float) (2 * Math.PI);
            float w = r * (.82f + .16f * (float) Math.sin(ph)) * gain * .9f, l = 0, rt = 0;
            for (int k = 0; k < PARTIALS; k++) {
                if (SING[k] == 0) continue;
                for (int p = 0; p < 2; p++) {
                    int j = k * 2 + p; float t = rc[j] * rr[j] - rs[j] * ri[j]; rs[j] = rs[j] * rr[j] + rc[j] * ri[j]; rc[j] = t;
                    float x = rs[j] * SING[k] * w; if (p == 0) { l += x * .6f; rt += x * .4f; } else { l += x * .4f; rt += x * .6f; }
                }
            }
            out[n * 2] += l; out[n * 2 + 1] += rt; dry[n] += (l + rt) * .5f;
        }
        for (int j = 0; j < PARTIALS * 2; j++) { float k = 1f / (float) Math.sqrt(rc[j] * rc[j] + rs[j] * rs[j]); rc[j] *= k; rs[j] *= k; }
        rub = r; wobble = ph;
    }

    private void mallet(float[] dry, int frames, float gain) {
        if (click < 1e-5f) { click = 0; return; }
        float c = click, x1 = bp1, x2 = bp2;
        for (int n = 0; n < frames; n++) {
            float in = rand() * c, y = bpB0 * in + x1; x1 = -bpA1 * y + x2; x2 = -bpB0 * in - bpA2 * y;
            dry[n] += y * gain; c *= clickDecay;
        }
        click = c; bp1 = x1; bp2 = x2;
    }

    private void reverb(float[] out, float[] dry, int frames) {
        for (int n = 0; n < frames; n++) {
            float in = dry[n] * .3f;
            for (int ch = 0; ch < 2; ch++) {
                float sum = 0;
                for (int i = ch * 4; i < ch * 4 + 4; i++) {
                    float[] b = comb[i]; int at = combAt[i]; float y = b[at];
                    combLow[i] = y * .7f + combLow[i] * .3f; b[at] = in + combLow[i] * .8f; combAt[i] = (at + 1) % b.length; sum += y;
                }
                for (int i = ch * 2; i < ch * 2 + 2; i++) {
                    float[] b = allpass[i]; int at = allAt[i]; float y = b[at]; b[at] = sum + y * .5f; allAt[i] = (at + 1) % b.length; sum = y - sum * .5f;
                }
                out[n * 2 + ch] += sum * .25f;
            }
        }
    }

    private void noise(float[] out, int frames) {
        int want = noise;
        float target = want == playing && want != 0 ? noiseVolume * noiseVolume * 1.4f : 0f, step = 1f / (1.2f * rate), g = noiseGain;
        if (want != playing && g < 1e-4f) { playing = want; g = 0; }
        if (playing == 0) { noiseGain = 0; return; }
        float[] p0 = pink[0], p1 = pink[1];
        for (int n = 0; n < frames; n++) {
            g = g < target ? Math.min(target, g + step) : Math.max(target, g - step);
            for (int ch = 0; ch < 2; ch++) {
                float w = rand(), x;
                switch (playing) {
                    case 1 -> x = w * .28f;
                    case 2 -> x = pink(ch == 0 ? p0 : p1, w);
                    case 3 -> { float b = (brown[ch] + .02f * w) / 1.02f; brown[ch] = b; x = b * 3.5f; }
                    default -> { float p = pink(ch == 0 ? p0 : p1, w); lowRain[ch] += (p - lowRain[ch]) * .35f; x = lowRain[ch] * 1.1f; }
                }
                out[n * 2 + ch] += x * g;
            }
            if (playing == 4) drops(out, n, g);
        }
        noiseGain = g;
    }
    private static float pink(float[] b, float w) {
        b[0] = .99886f * b[0] + w * .0555179f; b[1] = .99332f * b[1] + w * .0750759f; b[2] = .969f * b[2] + w * .153852f;
        b[3] = .8665f * b[3] + w * .3104856f; b[4] = .55f * b[4] + w * .5329522f; b[5] = -.7616f * b[5] - w * .016898f;
        float p = (b[0] + b[1] + b[2] + b[3] + b[4] + b[5] + b[6] + w * .5362f) * .11f; b[6] = w * .115926f; return p;
    }
    /** Rain: about 40 drops a second, each a short falling-pitch ping placed somewhere between the ears. */
    private void drops(float[] out, int n, float g) {
        if (Math.abs(rand()) < 40f / rate) for (int i = 0; i < dropEnv.length; i++) if (dropEnv[i] < 1e-4f) {
            double w = 2 * Math.PI * (1400 + 3600 * Math.abs(rand())) / rate; float a = rand();
            dropC[i] = 1; dropS[i] = 0; dropR[i] = (float) Math.cos(w); dropI[i] = (float) Math.sin(w);
            dropEnv[i] = .12f * a * a; dropDecay[i] = (float) Math.exp(-1.0 / ((.003 + .008 * Math.abs(rand())) * rate)); dropPan[i] = .5f + .45f * rand(); break;
        }
        for (int i = 0; i < dropEnv.length; i++) {
            float e = dropEnv[i]; if (e < 1e-4f) continue;
            float t = dropC[i] * dropR[i] - dropS[i] * dropI[i]; dropS[i] = dropS[i] * dropR[i] + dropC[i] * dropI[i]; dropC[i] = t;
            float x = dropS[i] * e * g * 2.4f; out[n * 2] += x * (1 - dropPan[i]); out[n * 2 + 1] += x * dropPan[i]; dropEnv[i] = e * dropDecay[i];
        }
    }

    /** Uniform in [-1, 1), xorshift: cheap and repeatable from the seed. */
    private float rand() { int x = seed; x ^= x << 13; x ^= x >>> 17; x ^= x << 5; seed = x; return x * (1f / 2147483648f); }
    private static float clamp(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
}
