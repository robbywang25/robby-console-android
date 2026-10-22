package wang.robby.console.android;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import java.io.File;

/**
 * Records 心力球 while it is switched on, as a microphone foreground service: the system shows its own microphone
 * indicator and the ongoing notification can stop it. Segments of {@link MindBall#SEGMENT} are 16 kHz mono AAC; the
 * loudest level is sampled every second and a segment with fewer than {@link MindBall#MIN_VOICED_SECONDS} loud
 * seconds is deleted without leaving the phone. Kept segments are handed to {@link MindBall#processAsync}.
 */
public final class MindBallService extends Service {
    /** Peak amplitude (of 32767) that counts a second as speech rather than room noise. */
    static final int VOICED = 1800;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaRecorder recorder; private File file; private long started; private int voiced; private PowerManager.WakeLock wake;
    private final Runnable tick = new Runnable() { @Override public void run() {
        if (recorder == null) return;
        try { if (recorder.getMaxAmplitude() >= VOICED) voiced++; } catch (RuntimeException ignored) {}
        if (System.currentTimeMillis() - started >= MindBall.SEGMENT) { finish(); begin(); } else handler.postDelayed(this, 1_000L);
    } };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && MindBall.ACTION_STOP.equals(intent.getAction())) { MindBall.disable(this); return START_NOT_STICKY; }
        if (!MindBall.enabled(this) || !MindBall.micAllowed(this)) { stopSelf(); return START_NOT_STICKY; }
        // A system restart after ColorOS stopped the process comes from the background, where Android 14+ hands the
        // microphone only silence: rather than record nothing while claiming to record, ask for God to be opened.
        if (intent == null && recorder == null) { MindBall.stopped(this); stopSelf(); return START_NOT_STICKY; }
        try {
            startForeground(MindBall.ID_RECORDING, MindBall.recordingNotification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } catch (RuntimeException refused) {
            // A restart from the background may not use the microphone: wait until God is opened again.
            Log.w(MindBall.TAG, "foreground_refused"); MindBall.afterRestart(this); stopSelf(); return START_NOT_STICKY;
        }
        if (recorder == null) { MindBall.recording = true; begin(); MindBall.processAsync(this, false); MindBall.notifyChanged(); }
        return START_STICKY;
    }
    private void begin() {
        started = System.currentTimeMillis(); voiced = 0; file = new File(MindBall.audioDir(this), "recording-" + started + ".m4a");
        if (wake == null) { wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "God:MindBall"); wake.setReferenceCounted(false); }
        wake.acquire(MindBall.SEGMENT + 60_000L);
        MediaRecorder r = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC); r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setAudioChannels(1); r.setAudioSamplingRate(16_000); r.setAudioEncodingBitRate(32_000); r.setOutputFile(file.getAbsolutePath());
            r.setOnErrorListener((mr, what, extra) -> { Log.w(MindBall.TAG, "recorder_error " + what); finish(); handler.postDelayed(this::begin, 5_000L); });
            r.prepare(); r.start(); recorder = r; r.getMaxAmplitude(); handler.postDelayed(tick, 1_000L);
        } catch (Exception e) {
            // The microphone is held by a call or another recorder: try again in a minute.
            Log.w(MindBall.TAG, "recorder_start_failed"); r.release(); file.delete(); recorder = null; handler.postDelayed(this::begin, 60_000L);
        }
    }
    /** Closes the current segment; a mostly silent one is deleted, a spoken one queued for transcription. */
    private void finish() {
        handler.removeCallbacks(tick); MediaRecorder r = recorder; recorder = null; if (r == null) return;
        boolean ok = true; try { r.stop(); } catch (RuntimeException tooShort) { ok = false; } r.release();
        long end = System.currentTimeMillis();
        if (ok && voiced >= MindBall.MIN_VOICED_SECONDS && file.renameTo(MindBall.segmentFile(this, started, end))) MindBall.processAsync(this, false);
        else file.delete();
    }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null); finish(); MindBall.recording = false;
        // Switched off: the last segment is in, so the day is summarized now instead of within the hour.
        if (!MindBall.enabled(this)) MindBall.processAsync(this, true);
        if (wake != null && wake.isHeld()) wake.release();
        MindBall.notifyChanged(); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
