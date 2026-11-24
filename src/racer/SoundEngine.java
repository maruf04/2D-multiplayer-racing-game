package racer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * All game audio, synthesized in real time in stereo (no sound files needed):
 *  - vehicles: engines that rise in pitch and loudness as they speed up (with gear changes),
 *    pedal / chain sounds for cycles and rickshaws, panned to where the vehicle is
 *  - surroundings that depend on where you are: water near the lake, traffic and horns near the city,
 *    birds near the trees, and the crowd roaring from the grandstands
 *  - effects: applause, cheering, whistles, air horn, countdown beeps, horns and bells, crashes, fence scrapes.
 * If the computer has no audio device the game simply runs silently.
 */
final class SoundEngine implements Runnable {
    static final float RATE = 22050f;
    static final int VOICES = 4;
    /** Environment layers for setAmbient(). */
    static final int WATER = 0, CITY = 1, BIRDS = 2, CROWD = 3;
    private static final int BLOCK = 512;
    private static final int TABLE = 4096;
    private static final float[] SIN = new float[TABLE];
    static {
        for (int i = 0; i < TABLE; i++) SIN[i] = (float) Math.sin(2 * Math.PI * i / TABLE);
    }

    // pre-generated effects
    final float[] applause, cheer, whistle, wolfWhistle, airHorn, beep, beepGo, crash, scrape;
    final float[] hornCar, hornBike, bellRickshaw, bellCycle;
    private final float[][] ambientLoops;

    private final Random rnd = new Random();
    private SourceDataLine line;
    private volatile boolean running;
    private volatile boolean muted;

    // vehicle voices: [0] = my vehicle, others = the nearest ones
    private final float[] speedTarget = new float[VOICES], gainTarget = new float[VOICES], throttleTarget = new float[VOICES], panTarget = new float[VOICES];
    private final int[] kind = new int[VOICES];
    private final float[] freq = new float[VOICES], gain = new float[VOICES], throttle = new float[VOICES], pan = new float[VOICES];
    private final float[] phase = new float[VOICES], lp = new float[VOICES], lp2 = new float[VOICES], tick = new float[VOICES], tickEnv = new float[VOICES];

    // environment layers, left and right gain each
    private final float[] ambTarget = new float[8], amb = new float[8];
    private final int[] ambPos = new int[4];

    private final List<Shot> shots = new ArrayList<>();

    private static final class Shot {
        final float[] data;
        final float left, right;
        int pos;
        Shot(float[] data, float left, float right) { this.data = data; this.left = left; this.right = right; }
    }

    SoundEngine() {
        applause = makeApplause(3.5f);
        cheer = makeCheer(2.6f);
        whistle = makeWhistle();
        wolfWhistle = makeWolfWhistle();
        airHorn = makeAirHorn();
        beep = makeBeep(660, 0.18f);
        beepGo = makeBeep(1320, 0.55f);
        crash = makeCrash();
        scrape = makeScrape();
        hornCar = makeHorn(415, 520, 0.45f);
        hornBike = makeHorn(620, 780, 0.3f);
        bellRickshaw = makeBell(new float[] { 2150, 3260, 4820 }, 2);
        bellCycle = makeBell(new float[] { 2650, 3950 }, 1);
        ambientLoops = new float[][] { makeWaterLoop(), makeCityLoop(), makeBirdLoop(), makeCrowdLoop() };
    }

    /** Opens the sound card. Returns false (and stays silent) if there is none. */
    boolean start() {
        try {
            AudioFormat fmt = new AudioFormat(RATE, 16, 2, true, false);
            line = AudioSystem.getSourceDataLine(fmt);
            line.open(fmt, BLOCK * 4 * 6);
            line.start();
        } catch (Exception | LinkageError e) {
            System.out.println("[sound] no audio device, running silently (" + e.getMessage() + ")");
            return false;
        }
        running = true;
        Thread t = new Thread(this, "audio");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
        return true;
    }

    void stop() {
        running = false;
    }

    boolean toggleMute() {
        muted = !muted;
        return muted;
    }

    boolean isMuted() {
        return muted;
    }

    /**
     * speed in px/s (0..520), volume 0..1, vehicle = Protocol.CYCLE.., throttle 0..1 (accelerating),
     * pan -1 (left) .. +1 (right).
     */
    void setVehicle(int voice, int vehicle, double speedPx, double volume, double throttleAmount, double panLR) {
        kind[voice] = vehicle;
        speedTarget[voice] = (float) Math.abs(speedPx);
        gainTarget[voice] = (float) volume;
        throttleTarget[voice] = (float) throttleAmount;
        panTarget[voice] = (float) Math.max(-1, Math.min(1, panLR));
    }

    /** Environment loudness, left and right: levels[2*layer] = left, levels[2*layer+1] = right (0..1). */
    void setAmbient(float[] levels) {
        for (int i = 0; i < 8; i++) ambTarget[i] = Math.max(0, Math.min(1, levels[i]));
    }

    void play(float[] effect, double volume) {
        play(effect, volume, 0);
    }

    /** pan -1 = left .. +1 = right */
    void play(float[] effect, double volume, double panLR) {
        if (!running || muted || volume <= 0.01) return;
        float v = (float) Math.min(1, volume), p = (float) Math.max(-1, Math.min(1, panLR));
        synchronized (shots) {
            if (shots.size() < 24) shots.add(new Shot(effect, v * Math.min(1, 1 - p), v * Math.min(1, 1 + p)));
        }
    }

    @Override
    public void run() {
        byte[] buf = new byte[BLOCK * 4];
        float[] left = new float[BLOCK], right = new float[BLOCK];
        float[] mono = new float[BLOCK];
        float[] ambLevel = { 0.32f, 0.38f, 0.34f, 0.95f };
        while (running) {
            Arrays.fill(left, 0);
            Arrays.fill(right, 0);
            for (int v = 0; v < VOICES; v++) {
                Arrays.fill(mono, 0);
                mixVehicle(v, mono);
                pan[v] += (panTarget[v] - pan[v]) * 0.05f;
                float l = Math.min(1, 1 - pan[v]), r = Math.min(1, 1 + pan[v]);
                for (int i = 0; i < BLOCK; i++) { left[i] += mono[i] * l; right[i] += mono[i] * r; }
            }
            for (int layer = 0; layer < 4; layer++) {
                float[] loop = ambientLoops[layer];
                int pos = ambPos[layer];
                for (int i = 0; i < BLOCK; i++) {
                    amb[2 * layer] += (ambTarget[2 * layer] - amb[2 * layer]) * 0.0005f;
                    amb[2 * layer + 1] += (ambTarget[2 * layer + 1] - amb[2 * layer + 1]) * 0.0005f;
                    float s = loop[pos] * ambLevel[layer];
                    pos = (pos + 1) % loop.length;
                    left[i] += s * amb[2 * layer];
                    right[i] += s * amb[2 * layer + 1];
                }
                ambPos[layer] = pos;
            }
            synchronized (shots) {
                for (int s = shots.size() - 1; s >= 0; s--) {
                    Shot sh = shots.get(s);
                    int n = Math.min(BLOCK, sh.data.length - sh.pos);
                    for (int i = 0; i < n; i++) {
                        float d = sh.data[sh.pos + i];
                        left[i] += d * sh.left;
                        right[i] += d * sh.right;
                    }
                    sh.pos += n;
                    if (sh.pos >= sh.data.length) shots.remove(s);
                }
            }
            for (int i = 0; i < BLOCK; i++) {
                float l = muted ? 0 : left[i], r = muted ? 0 : right[i];
                l = l / (1 + Math.abs(l));                    // soft limiter
                r = r / (1 + Math.abs(r));
                int vl = (int) (l * 30000), vr = (int) (r * 30000);
                buf[4 * i] = (byte) vl;
                buf[4 * i + 1] = (byte) (vl >> 8);
                buf[4 * i + 2] = (byte) vr;
                buf[4 * i + 3] = (byte) (vr >> 8);
            }
            line.write(buf, 0, buf.length);
        }
        line.drain();
        line.close();
    }

    private static float sin(float cycles) {
        return SIN[(int) ((cycles - (int) cycles) * TABLE) & (TABLE - 1)];
    }

    /** Engine pitch for a speed: rises through the gears, drops a little at each gear change. */
    private static float engineFreq(int vehicle, float sp, float th) {
        float[] gears = { 0, 95, 190, 290, 400, 530 };
        int g = 0;
        while (g < gears.length - 2 && sp >= gears[g + 1]) g++;
        float frac = Math.min(1, (sp - gears[g]) / (gears[g + 1] - gears[g]));
        if (vehicle == Protocol.MOTORBIKE) return 95 + frac * 150 + g * 22 + th * 10;
        return 52 + frac * 78 + g * 11 + th * 6;
    }

    /** One vehicle's running sound. */
    private void mixVehicle(int v, float[] out) {
        for (int i = 0; i < BLOCK; i++) {
            gain[v] += (gainTarget[v] - gain[v]) * 0.002f;
            throttle[v] += (throttleTarget[v] - throttle[v]) * 0.003f;
            if (gain[v] < 0.002f) continue;
            float th = throttle[v], s;
            if (kind[v] == Protocol.CAR || kind[v] == Protocol.MOTORBIKE) {
                float target = engineFreq(kind[v], speedTarget[v], th);
                freq[v] += (target - freq[v]) * 0.004f;
                phase[v] += freq[v] / RATE;
                if (phase[v] >= 1) phase[v] -= 1;
                float p = phase[v];
                if (kind[v] == Protocol.CAR) {         // smooth, deep 4-cylinder hum
                    s = sin(p) + 0.65f * sin(2 * p) + 0.42f * sin(3 * p + 0.25f) + 0.28f * sin(4 * p)
                            + (0.12f + 0.15f * th) * sin(6 * p) + (0.05f + 0.1f * th) * sin(8 * p);
                    s *= 0.85f + 0.15f * sin(2 * p);    // firing pulses
                    s /= 2.6f;
                } else {                               // buzzy single-cylinder bike
                    s = sin(p) + 0.8f * sin(2 * p) + 0.6f * sin(3 * p) + 0.45f * sin(5 * p) + (0.2f + 0.25f * th) * sin(7 * p);
                    s *= 0.7f + 0.3f * sin(p);
                    s /= 2.8f;
                }
                float noise = (rnd.nextFloat() - 0.5f) * (0.06f + 0.1f * th);
                lp[v] += ((s + noise) - lp[v]) * (0.35f + 0.3f * th);
                s = lp[v] * (0.75f + 0.35f * th);
            } else {
                // cycle / rickshaw: wind + chain whirr + freewheel ticks, all faster as they speed up
                float sp = speedTarget[v];
                float white = rnd.nextFloat() * 2 - 1;
                lp[v] += (white - lp[v]) * (0.05f + sp / 3000f);
                lp2[v] += (lp[v] - lp2[v]) * 0.05f;
                float whirr = (lp[v] - lp2[v]) * (0.25f + sp / 700f);
                float rate = (kind[v] == Protocol.RICKSHAW ? 5 : 7) + sp * (kind[v] == Protocol.RICKSHAW ? 0.03f : 0.05f);
                tick[v] += rate / RATE;
                if (tick[v] >= 1) {
                    tick[v] -= 1;
                    tickEnv[v] = kind[v] == Protocol.RICKSHAW && rnd.nextInt(3) == 0 ? 0.9f : 0.55f;
                }
                tickEnv[v] *= 0.991f;
                s = whirr + white * tickEnv[v] * 0.35f * (0.4f + th * 0.6f);
            }
            out[i] += s * gain[v];
        }
    }

    // ------------------------------------------------------------------ environment loops

    /** Loops seamlessly by cross-fading the tail into the start. */
    private static float[] loop(float[] raw, int n, float peak) {
        int fade = raw.length - n;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = raw[i];
            if (i < fade) {
                float a = i / (float) fade;
                out[i] = raw[i] * a + raw[n + i] * (1 - a);
            }
        }
        return normalize(out, peak);
    }

    /** Small waves lapping against the embankment. */
    private float[] makeWaterLoop() {
        int n = (int) (6 * RATE), fade = (int) (0.6f * RATE);
        float[] out = new float[n + fade];
        float l1 = 0, l2 = 0;
        for (int i = 0; i < out.length; i++) {
            float t = i / RATE;
            float white = rnd.nextFloat() * 2 - 1;
            l1 += (white - l1) * 0.08f;
            l2 += (l1 - l2) * 0.08f;
            float swell = 0.5f + 0.5f * (float) Math.sin(2 * Math.PI * 0.31 * t) * (float) Math.sin(2 * Math.PI * 0.13 * t + 1);
            out[i] = l2 * 3 * swell;
        }
        for (int k = 0; k < 14; k++) {                     // laps and little splashes
            int start = rnd.nextInt(out.length - 4000);
            float prev = 0, amp = 0.3f + rnd.nextFloat() * 0.4f;
            for (int i = 0; i < 3500; i++) {
                float white = rnd.nextFloat() * 2 - 1;
                float hp = white - prev;
                prev = white;
                out[start + i] += hp * amp * (float) Math.exp(-i / 700.0) * (float) Math.sin(Math.PI * Math.min(1, i / 300.0));
            }
        }
        return loop(out, n, 0.6f);
    }

    /** Dhaka traffic: engine rumble, tyres, distant horns and rickshaw bells. */
    private float[] makeCityLoop() {
        int n = (int) (8 * RATE), fade = (int) (0.6f * RATE);
        float[] out = new float[n + fade];
        float l1 = 0, l2 = 0, l3 = 0;
        for (int i = 0; i < out.length; i++) {
            float t = i / RATE;
            float white = rnd.nextFloat() * 2 - 1;
            l1 += (white - l1) * 0.03f;                    // rumble
            l2 += (white - l2) * 0.25f;
            l3 += (l2 - l3) * 0.25f;                       // tyre hiss
            float wave = 0.7f + 0.3f * (float) Math.sin(2 * Math.PI * 0.17 * t);
            out[i] = l1 * 6 * wave + (l2 - l3) * 0.5f;
        }
        for (int k = 0; k < 9; k++) {                      // distant horns
            int start = rnd.nextInt(out.length - (int) (0.6f * RATE));
            float f1 = 380 + rnd.nextInt(260), f2 = f1 * 1.25f, len = 0.12f + rnd.nextFloat() * 0.35f, amp = 0.12f + rnd.nextFloat() * 0.2f;
            float lpv = 0;
            for (int i = 0; i < (int) (len * RATE); i++) {
                float t = i / RATE;
                float sq = (Math.sin(2 * Math.PI * f1 * t) > 0 ? 1 : -1) + (Math.sin(2 * Math.PI * f2 * t) > 0 ? 1 : -1);
                lpv += (sq - lpv) * 0.2f;
                float env = Math.min(1, t / 0.01f) * Math.min(1, (len - t) / 0.03f);
                out[start + i] += lpv * amp * env;
            }
        }
        float[] bell = makeBell(new float[] { 2150, 3260 }, 2);
        for (int k = 0; k < 3; k++) {
            int start = rnd.nextInt(out.length - bell.length);
            for (int i = 0; i < bell.length; i++) out[start + i] += bell[i] * 0.25f;
        }
        return loop(out, n, 0.55f);
    }

    /** Birds in the trees: short chirps and trills. */
    private float[] makeBirdLoop() {
        int n = (int) (7 * RATE), fade = (int) (0.5f * RATE);
        float[] out = new float[n + fade];
        for (int k = 0; k < 22; k++) {
            int start = rnd.nextInt(out.length - (int) RATE);
            int notes = 1 + rnd.nextInt(4);
            float base = 2200 + rnd.nextInt(2200), amp = 0.3f + rnd.nextFloat() * 0.7f;
            for (int note = 0; note < notes; note++) {
                int s0 = start + note * (int) (0.11f * RATE);
                float len = 0.05f + rnd.nextFloat() * 0.07f, ph = 0;
                boolean up = rnd.nextBoolean();
                for (int i = 0; i < (int) (len * RATE) && s0 + i < out.length; i++) {
                    float u = i / (len * RATE);
                    float f = base * (up ? 0.85f + 0.4f * u : 1.25f - 0.4f * u) * (1 + 0.04f * (float) Math.sin(2 * Math.PI * 60 * i / RATE));
                    ph += f / RATE;
                    out[s0 + i] += (float) Math.sin(2 * Math.PI * ph) * amp * (float) Math.sin(Math.PI * u);
                }
            }
        }
        float l = 0;
        for (int i = 0; i < out.length; i++) {             // soft leaves rustling underneath
            l += ((rnd.nextFloat() * 2 - 1) - l) * 0.3f;
            out[i] += l * 0.05f;
        }
        return loop(out, n, 0.45f);
    }

    /** Thousands of fans: "aaah / ooh" + the "shhh" of many people, slowly swelling. */
    private float[] makeCrowdLoop() {
        int n = (int) (6f * RATE), fade = (int) (0.6f * RATE);
        float[] out = new float[n + fade];
        for (int v = 0; v < 40; v++) {
            float f0 = 120 + rnd.nextFloat() * 260, ph = 0, l1 = 0, l2 = 0;
            float swell1 = 0.15f + rnd.nextFloat() * 0.5f, swell2 = 0.05f + rnd.nextFloat() * 0.2f, off = rnd.nextFloat() * 6.28f;
            float vib = 4 + rnd.nextFloat() * 3;
            for (int i = 0; i < out.length; i++) {
                float t = i / RATE;
                float f = f0 * (1 + 0.03f * (float) Math.sin(2 * Math.PI * vib * t + off));
                ph += f / RATE;
                ph -= (int) ph;
                float saw = 2 * ph - 1;
                l1 += (saw - l1) * 0.2f;
                l2 += (l1 - l2) * 0.2f;
                float env = 0.5f + 0.5f * (float) Math.sin(2 * Math.PI * swell1 * t + off) * (float) Math.sin(2 * Math.PI * swell2 * t + off * 2);
                out[i] += (l1 - 0.6f * l2) * env * 0.12f;
            }
        }
        float b1 = 0, b2 = 0;
        for (int i = 0; i < out.length; i++) {
            float white = rnd.nextFloat() * 2 - 1;
            b1 += (white - b1) * 0.35f;
            b2 += (b1 - b2) * 0.08f;
            out[i] += (b1 - b2) * 0.6f;
        }
        return loop(out, n, 0.55f);
    }

    // ------------------------------------------------------------------ effects

    private float[] makeApplause(float seconds) {
        int n = (int) (seconds * RATE);
        float[] out = new float[n];
        int claps = (int) (seconds * 420);
        for (int c = 0; c < claps; c++) {
            int start = rnd.nextInt(n);
            float amp = 0.15f + rnd.nextFloat() * 0.35f;
            float decay = 0.80f + rnd.nextFloat() * 0.1f;
            int len = (int) (RATE * 0.025f);
            float prev = 0;
            for (int i = 0; i < len && start + i < n; i++) {
                float white = rnd.nextFloat() * 2 - 1;
                float hp = white - prev;
                prev = white;
                out[start + i] += hp * amp * (float) Math.pow(decay, i / 8.0);
            }
        }
        envelope(out, 0.25f, 1.4f);
        return normalize(out, 0.85f);
    }

    /** A crowd roaring "yeaaah!". */
    private float[] makeCheer(float seconds) {
        int n = (int) (seconds * RATE);
        float[] out = new float[n];
        for (int v = 0; v < 30; v++) {
            float f0 = 150 + rnd.nextFloat() * 260, glide = 1.15f + rnd.nextFloat() * 0.35f;
            float vib = 4 + rnd.nextFloat() * 3, ph = 0, lpv = 0;
            int delay = rnd.nextInt((int) (RATE * 0.35f));
            for (int i = delay; i < n; i++) {
                float t = (i - delay) / RATE;
                float f = f0 * (1 + (glide - 1) * Math.min(1, t / 0.6f)) * (1 + 0.02f * (float) Math.sin(2 * Math.PI * vib * t));
                ph += f / RATE;
                ph -= (int) ph;
                float saw = 2 * ph - 1;
                lpv += (saw - lpv) * 0.18f;
                out[i] += lpv * 0.12f;
            }
        }
        float b1 = 0, b2 = 0;
        for (int i = 0; i < n; i++) {
            float white = rnd.nextFloat() * 2 - 1;
            b1 += (white - b1) * 0.3f;
            b2 += (b1 - b2) * 0.3f;
            out[i] += (b1 - b2) * 0.5f;
        }
        envelope(out, 0.3f, 1.3f);
        return normalize(out, 0.7f);
    }

    private float[] makeWhistle() {
        int n = (int) (0.7f * RATE);
        float[] out = new float[n];
        float ph = 0;
        for (int i = 0; i < n; i++) {
            float t = i / RATE;
            float f = 2750 + 60 * (float) Math.sin(2 * Math.PI * 6 * t);
            ph += f / RATE;
            float trill = 0.65f + 0.35f * (float) Math.sin(2 * Math.PI * 32 * t);
            out[i] = (float) Math.sin(2 * Math.PI * ph) * trill + (rnd.nextFloat() - 0.5f) * 0.08f;
        }
        envelope(out, 0.02f, 0.12f);
        return normalize(out, 0.35f);
    }

    private float[] makeWolfWhistle() {
        int n = (int) (1.15f * RATE);
        float[] out = new float[n];
        float ph = 0;
        for (int i = 0; i < n; i++) {
            float t = i / RATE, f, amp;
            if (t < 0.35f) { f = 1100 + 1700 * (t / 0.35f); amp = 1; }
            else if (t < 0.5f) { f = 2800; amp = 0; }
            else { float u = (t - 0.5f) / 0.65f; f = 1000 + 1800 * (float) Math.sin(Math.PI * Math.min(1, u * 1.4f)) - 600 * u; amp = 1; }
            ph += f / RATE;
            out[i] = (float) Math.sin(2 * Math.PI * ph) * amp;
        }
        envelope(out, 0.02f, 0.1f);
        return normalize(out, 0.3f);
    }

    private float[] makeAirHorn() {
        int n = (int) (1.1f * RATE);
        float[] out = new float[n];
        float p1 = 0, p2 = 0, lpv = 0;
        for (int i = 0; i < n; i++) {
            p1 += 330 / RATE; p1 -= (int) p1;
            p2 += 415 / RATE; p2 -= (int) p2;
            float raw = (2 * p1 - 1) + (2 * p2 - 1);
            lpv += (raw - lpv) * 0.35f;
            out[i] = lpv;
        }
        envelope(out, 0.03f, 0.2f);
        return normalize(out, 0.45f);
    }

    /** Vehicle horn: two notes together, like a real car or motorbike horn. */
    private float[] makeHorn(float f1, float f2, float seconds) {
        int n = (int) (seconds * RATE);
        float[] out = new float[n];
        float lpv = 0;
        for (int i = 0; i < n; i++) {
            float t = i / RATE;
            float raw = (Math.sin(2 * Math.PI * f1 * t) > 0 ? 1 : -1) + (Math.sin(2 * Math.PI * f2 * t) > 0 ? 1 : -1);
            lpv += (raw - lpv) * 0.3f;
            out[i] = lpv;
        }
        envelope(out, 0.01f, 0.04f);
        return normalize(out, 0.4f);
    }

    private float[] makeBeep(float f, float seconds) {
        int n = (int) (seconds * RATE);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = (float) Math.sin(2 * Math.PI * f * i / RATE) > 0 ? 0.5f : -0.5f;
        envelope(out, 0.005f, 0.05f);
        return normalize(out, 0.3f);
    }

    /** "Kring kring" (rickshaw) or "ting" (cycle): metallic partials with a ringing decay. */
    private float[] makeBell(float[] partials, int strikes) {
        float strikeGap = 0.17f, ring = 0.7f;
        int n = (int) ((strikeGap * (strikes - 1) + ring) * RATE);
        float[] out = new float[n];
        for (int s = 0; s < strikes; s++) {
            int start = (int) (s * strikeGap * RATE);
            for (int i = start; i < n; i++) {
                float t = (i - start) / RATE, v = 0;
                for (int k = 0; k < partials.length; k++) {
                    v += (float) Math.sin(2 * Math.PI * partials[k] * t) * (float) Math.exp(-t * (5 + k * 3)) / (k + 1);
                }
                out[i] += v * (1 + 0.15f * (float) Math.sin(2 * Math.PI * 18 * t));
            }
        }
        return normalize(out, 0.4f);
    }

    /** A real-sounding bump: a heavy low thud, a crunch of body panels and a few rattling bits. */
    private float[] makeCrash() {
        int n = (int) (0.5f * RATE);
        float[] out = new float[n];
        float ph = 0, l1 = 0, l2 = 0;
        for (int i = 0; i < n; i++) {
            float t = i / RATE;
            float f = 55 + 70 * (float) Math.exp(-t * 30);            // the thud drops in pitch
            ph += f / RATE;
            float thud = (float) Math.sin(2 * Math.PI * ph) * (float) Math.exp(-t * 16);
            float white = rnd.nextFloat() * 2 - 1;
            l1 += (white - l1) * 0.22f;                                // crunch: dark filtered noise
            l2 += (l1 - l2) * 0.5f;
            float crunch = l2 * 1.6f * (float) Math.exp(-t * 22) * (0.6f + 0.4f * rnd.nextFloat());
            out[i] = thud * 0.9f + crunch;
        }
        for (int k = 0; k < 26; k++) {                                // rattling bits
            int start = rnd.nextInt((int) (0.16f * RATE));
            float amp = 0.15f + rnd.nextFloat() * 0.3f, prev = 0;
            for (int i = 0; i < 50 && start + i < n; i++) {
                float white = rnd.nextFloat() * 2 - 1;
                out[start + i] += (white - prev) * amp * (float) Math.exp(-i / 12.0) * (float) Math.exp(-start / (0.1 * RATE));
                prev = white;
            }
        }
        envelope(out, 0.002f, 0.08f);
        return normalize(out, 0.6f);
    }

    /** Metal scraping along the fence. */
    private float[] makeScrape() {
        int n = (int) (0.3f * RATE);
        float[] out = new float[n];
        float l1 = 0, l2 = 0;
        for (int i = 0; i < n; i++) {
            float t = i / RATE;
            float white = rnd.nextFloat() * 2 - 1;
            l1 += (white - l1) * 0.6f;
            l2 += (l1 - l2) * 0.15f;
            float rough = 0.6f + 0.4f * (float) Math.sin(2 * Math.PI * 47 * t) * (float) Math.sin(2 * Math.PI * 13 * t);
            out[i] = (l1 - l2) * rough;
        }
        envelope(out, 0.02f, 0.12f);
        return normalize(out, 0.3f);
    }

    private static void envelope(float[] a, float attack, float release) {
        int at = (int) (attack * RATE), rl = (int) (release * RATE);
        for (int i = 0; i < a.length; i++) {
            float g = 1;
            if (i < at) g = i / (float) at;
            int fromEnd = a.length - i;
            if (fromEnd < rl) g *= fromEnd / (float) rl;
            a[i] *= g;
        }
    }

    private static float[] normalize(float[] a, float peak) {
        float max = 1e-6f;
        for (float v : a) max = Math.max(max, Math.abs(v));
        for (int i = 0; i < a.length; i++) a[i] = a[i] / max * peak;
        return a;
    }
}
