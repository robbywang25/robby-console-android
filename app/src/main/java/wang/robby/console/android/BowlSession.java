package wang.robby.console.android;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import java.util.List;

/**
 * Runtime of 颂钵: one audio thread that streams {@link BowlSynth} into an {@link AudioTrack} only while something
 * sounds, which ambient sound is on, the 番茄钟 state and the {@link BowlService} that keeps both going with the screen
 * off. Settings (phase lengths, bowl, volumes) live in god-ui as {@code bowlSettings} and sync as {@code bowl}; the
 * timer and what is playing stay on this phone. Main thread, except the audio loop.
 */
final class BowlSession {
    private BowlSession() {}
    static final String TAG = "GodBowl", SETTINGS = "bowlSettings", CHANNEL = "bowl", CHANNEL_DONE = "bowl-done";
    static final String ACTION_TICK = "wang.robby.console.android.BOWL_TICK", ACTION_TOGGLE = "wang.robby.console.android.BOWL_TOGGLE", ACTION_STOP = "wang.robby.console.android.BOWL_STOP";
    static final int ID_PLAYING = 7301, ID_DONE = 7302;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static BowlSynth synth; private static Thread audio; private static AudioFocusRequest focus;
    /** 持续唱钵: the rim is rubbed for you at an even pace. */
    private static boolean sing;
    /** Called on the main thread whenever what the page shows may have changed. */
    static Runnable listener;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("singing-bowl", Context.MODE_PRIVATE); }
    static Pomodoro.Settings settings(Context c) {
        Pomodoro.Settings s = Pomodoro.parse(c.getSharedPreferences("god-ui", Context.MODE_PRIVATE).getString(SETTINGS, null));
        return s == null ? Pomodoro.Settings.DEFAULT : s;
    }
    static void save(Context c, Pomodoro.Settings s) {
        c.getSharedPreferences("god-ui", Context.MODE_PRIVATE).edit().putString(SETTINGS, s.serialize()).apply();
        if (!PreviewMode.isBuild()) PreferenceSyncHub.note(c, "bowl");
        apply(c, s); changed(c);
    }
    /** Settings that arrived from another phone or were just saved: the bowl and volumes follow at once. */
    static void apply(Context c, Pomodoro.Settings s) { BowlSynth x = synth(c); x.setBowl(s.bowl); x.setVolumes(s.bowlVolume / 100f, s.noiseVolume / 100f); }

    static synchronized BowlSynth synth(Context c) {
        if (synth == null) {
            int rate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC);
            synth = new BowlSynth(rate >= 8000 && rate <= 192000 ? rate : 48000, (int) System.nanoTime());
            Pomodoro.Settings s = settings(c); synth.setBowl(s.bowl); synth.setVolumes(s.bowlVolume / 100f, s.noiseVolume / 100f);
        }
        return synth;
    }

    // ---- sound ----
    static void strike(Context c, float strength) { synth(c).strike(strength); ensureAudio(c); }
    static void rub(Context c, float amount, float turns) { synth(c).setRub(Math.max(amount, sing ? .55f : 0f), sing && amount <= 0 ? .5f : turns); if (amount > 0) ensureAudio(c); }
    static int noise(Context c) { return synth(c).noise(); }
    static void setNoise(Context c, int index) { synth(c).setNoise(index); continuous(c); }
    static boolean singing() { return sing; }
    static void setSing(Context c, boolean on) { sing = on; synth(c).setRub(on ? .55f : 0f, .5f); continuous(c); }
    static float level() { return synth == null ? 0 : synth.level; }
    /** Ambient sound or 持续唱钵 changed: take or release audio focus and the service. */
    private static void continuous(Context c) {
        Context app = c.getApplicationContext();
        if (playing(app)) { ensureAudio(app); requestFocus(app); } else abandonFocus(app);
        update(app); changed(app);
    }
    static boolean playing(Context c) { return synth(c).noise() != 0 || sing; }
    /** What is playing, for the page and the notification: "粉红噪音 · 持续唱钵", or "" when nothing is. */
    static String sound(Context c) {
        int n = synth(c).noise(); String a = n == 0 ? "" : BowlSynth.NOISE_NAMES[n];
        return sing ? (a.isEmpty() ? "" : a + " · ") + "持续唱钵" : a;
    }

    private static synchronized void ensureAudio(Context c) {
        if (audio != null) return;
        BowlSynth s = synth(c);
        audio = new Thread(() -> loop(s), "God-bowl-audio"); audio.start();
    }
    /** The audio loop. It ends itself four seconds after everything has gone quiet, so no idle stream is left open. */
    private static void loop(BowlSynth s) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        AudioTrack track = null;
        try {
            int min = AudioTrack.getMinBufferSize(s.rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
            track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(s.rate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(min * 2, 8192)).build();
            track.play();
            int frames = 256; float[] buf = new float[frames * 2]; long quiet = 0;
            while (true) {
                s.render(buf, frames);
                if (track.write(buf, 0, buf.length, AudioTrack.WRITE_BLOCKING) < 0) break;
                quiet = s.silent() ? quiet + frames : 0;
                if (quiet > s.rate * 4L) synchronized (BowlSession.class) { if (s.silent()) { audio = null; break; } quiet = 0; }
            }
        } catch (RuntimeException e) { Log.w(TAG, "audio_failed " + e.getClass().getSimpleName()); }
        finally {
            if (track != null) { try { track.stop(); } catch (RuntimeException ignored) {} track.release(); }
            synchronized (BowlSession.class) { if (audio == Thread.currentThread()) audio = null; }
        }
    }

    private static void requestFocus(Context c) {
        if (focus != null) return;
        AudioManager am = c.getSystemService(AudioManager.class);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setWillPauseWhenDucked(false).setOnAudioFocusChangeListener(change -> {
                BowlSynth s = synth(c);
                // A call or another player taking over for good stops the ambient sound; a short interruption only mutes it.
                switch (change) {
                    case AudioManager.AUDIOFOCUS_LOSS -> { s.setDuck(1f); focus = null; sing = false; s.setRub(0, 0); s.setNoise(0); update(c); changed(c); }
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> s.setDuck(0f);
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> s.setDuck(.3f);
                    default -> s.setDuck(1f);
                }
            }, MAIN).build();
        if (am.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_FAILED) synth(c).setDuck(0f); else synth(c).setDuck(1f);
    }
    private static void abandonFocus(Context c) { if (focus == null) return; c.getSystemService(AudioManager.class).abandonAudioFocusRequest(focus); focus = null; synth(c).setDuck(1f); }

    // ---- 番茄钟 ----
    /** The timer brought up to now; a phase that ended since the last look is passed, with its chime if it just ended. */
    static Pomodoro.State timer(Context c) {
        Pomodoro.State was = Pomodoro.State.parse(prefs(c).getString("timer", "")), now = Pomodoro.advance(settings(c), was, System.currentTimeMillis());
        if (now != was) {
            prefs(c).edit().putString("timer", now.serialize()).apply();
            // Chime only for an end within the last minute; a phase that ran out while God was stopped passes quietly.
            if (System.currentTimeMillis() - was.endsAt < 60_000L) ended(c, was.phase, now);
            update(c); changed(c);
        }
        return now;
    }
    static void setTimer(Context c, Pomodoro.State st) { prefs(c).edit().putString("timer", st.serialize()).apply(); c.getSystemService(NotificationManager.class).cancel(ID_DONE); update(c); changed(c); }
    static void startTimer(Context c) { setTimer(c, Pomodoro.start(settings(c), System.currentTimeMillis())); strike(c, .7f); }
    static void toggleTimer(Context c) {
        Pomodoro.State st = timer(c); long now = System.currentTimeMillis();
        if (!st.active()) startTimer(c); else setTimer(c, st.running ? Pomodoro.pause(st, now) : Pomodoro.resume(st, now));
    }
    static void skip(Context c) { Pomodoro.State st = timer(c); if (!st.active()) return; Pomodoro.Settings s = settings(c); Pomodoro.State n = Pomodoro.next(s, st, System.currentTimeMillis()); setTimer(c, n.running ? n : Pomodoro.resume(n, System.currentTimeMillis())); }
    static void stopTimer(Context c) { setTimer(c, Pomodoro.State.IDLE); }
    /** 停止 in the notification: the timer, the ambient sound and 持续唱钵 all stop. */
    static void stopAll(Context c) { setTimer(c, Pomodoro.State.IDLE); sing = false; synth(c).setRub(0, 0); setNoise(c, 0); }

    /** A phase ran out: the bowl sounds (three strokes after focus, one after a break), the phone buzzes once and a notice says what is next. */
    private static void ended(Context c, String phase, Pomodoro.State next) {
        Context app = c.getApplicationContext();
        PowerManager.WakeLock wake = app.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "God:bowl-chime"); wake.acquire(12_000L);
        int strokes = Pomodoro.FOCUS.equals(phase) ? 3 : 1;
        for (int i = 0; i < strokes; i++) MAIN.postDelayed(() -> strike(app, .85f), i * 2600L);
        Vibrator v = app.getSystemService(Vibrator.class);
        if (v != null && v.hasVibrator()) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 60, 120, 60}, -1));
        Pomodoro.Settings s = settings(app);
        String title = Pomodoro.FOCUS.equals(phase) ? "专注完成" : "休息结束";
        String text = next.running ? "开始" + Pomodoro.label(next.phase) + " " + s.minutes(next.phase) + " 分钟" + (Pomodoro.FOCUS.equals(next.phase) ? "，第 " + next.round + "/" + s.rounds + " 轮" : "")
            : "下一段：" + Pomodoro.label(next.phase) + " " + s.minutes(next.phase) + " 分钟，点开开始";
        channels(app);
        Notification.Builder b = new Notification.Builder(app, CHANNEL_DONE).setSmallIcon(R.drawable.ic_stat_tibo).setContentTitle(title).setContentText(text)
            .setContentIntent(openPage(app)).setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER).setTimeoutAfter(15 * 60_000L);
        app.getSystemService(NotificationManager.class).notify(ID_DONE, b.build());
    }

    // ---- service and notification ----
    /** The service runs while the timer runs or a sound plays; it is what keeps either going after God leaves the screen. */
    static boolean needsService(Context c) { Pomodoro.State st = Pomodoro.State.parse(prefs(c).getString("timer", "")); return st.running || playing(c); }
    static void update(Context c) {
        Context app = c.getApplicationContext(); boolean need = needsService(app);
        if (need) {
            try { app.startForegroundService(new Intent(app, BowlService.class)); } catch (RuntimeException e) { Log.w(TAG, "service_refused"); }
        } else if (BowlService.running) app.stopService(new Intent(app, BowlService.class));
        alarm(app);
    }
    /**
     * An exact alarm at the end of the running phase wakes the service to chime even in Doze; no timer, no alarm. After
     * a reboot only the alarm is set again: Android 15 does not let a media service start from boot.
     */
    static void alarm(Context c) {
        AlarmManager alarms = c.getSystemService(AlarmManager.class);
        PendingIntent p = PendingIntent.getForegroundService(c, ACTION_TICK.hashCode(), new Intent(c, BowlService.class).setAction(ACTION_TICK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Pomodoro.State st = Pomodoro.State.parse(prefs(c).getString("timer", ""));
        if (!st.running) { alarms.cancel(p); return; }
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, st.endsAt, p);
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, st.endsAt, p);
    }
    static void channels(Context c) {
        NotificationChannel playing = new NotificationChannel(CHANNEL, "颂钵与番茄钟", NotificationManager.IMPORTANCE_LOW);
        playing.setDescription("颂钵在播放或番茄钟在计时时常驻，可暂停或停止"); playing.setShowBadge(false);
        NotificationChannel done = new NotificationChannel(CHANNEL_DONE, "番茄钟阶段提醒", NotificationManager.IMPORTANCE_HIGH);
        done.setDescription("专注或休息结束时提示下一段；声音由颂钵本身发出"); done.setSound(null, null); done.enableVibration(false);
        c.getSystemService(NotificationManager.class).createNotificationChannels(List.of(playing, done));
    }
    static PendingIntent openPage(Context c) {
        Intent open = new Intent(c, MainActivity.class).putExtra(TiboWatch.EXTRA_OPEN, "singing-bowl").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, "singing-bowl".hashCode(), open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
    static Notification notification(Context c) {
        channels(c);
        Pomodoro.State st = Pomodoro.State.parse(prefs(c).getString("timer", "")); Pomodoro.Settings s = settings(c); String sound = sound(c);
        String title = !st.active() ? "颂钵" : Pomodoro.label(st.phase) + (Pomodoro.FOCUS.equals(st.phase) ? " · 第 " + st.round + "/" + s.rounds + " 轮" : "") + (st.running ? "" : " · 已暂停");
        Notification.Builder b = new Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_stat_tibo).setContentTitle(title)
            .setContentText(sound.isEmpty() ? st.active() && !st.running ? "剩余 " + Pomodoro.clock(st.remaining) : "颂钵安静中" : sound)
            .setContentIntent(openPage(c)).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).setOnlyAlertOnce(true);
        if (st.running) b.setWhen(st.endsAt).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true); else b.setShowWhen(false);
        if (st.active()) b.addAction(new Notification.Action.Builder(null, st.running ? "暂停" : "继续", service(c, ACTION_TOGGLE)).build());
        b.addAction(new Notification.Action.Builder(null, "停止", service(c, ACTION_STOP)).build());
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }
    private static PendingIntent service(Context c, String action) { return PendingIntent.getService(c, action.hashCode(), new Intent(c, BowlService.class).setAction(action), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT); }

    private static void changed(Context c) {
        if (BowlService.running && needsService(c)) c.getApplicationContext().getSystemService(NotificationManager.class).notify(ID_PLAYING, notification(c.getApplicationContext()));
        if (listener != null) MAIN.post(() -> { if (listener != null) listener.run(); });
    }
}
