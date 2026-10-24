package wang.robby.console.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 心力球: an always-on recorder in the manner of OPPO's AI 心力球. {@link MindBallService} records ten-minute AAC
 * segments and drops the silent ones; each kept segment is sent to an OpenAI-compatible /audio/transcriptions endpoint
 * (DeepSeek takes no audio) and deleted once transcribed; at most hourly the day's transcript goes to DeepSeek's
 * /chat/completions, which returns the day's 关键事项 and 待办 as one timeline.
 * Everything stays in this app's no-backup storage on the phone for {@link #KEEP_DAYS} days; the two API keys are
 * encrypted with an Android Keystore key and never leave the phone except to their own endpoints. God's server is
 * not involved.
 */
final class MindBall {
    private MindBall() {}
    static final String TAG = "GodMindBall", CHANNEL = "mindball-v1", ACTION_STOP = "wang.robby.console.android.MINDBALL_STOP";
    static final int ID_RECORDING = 7201, ID_PAUSED = 7202;
    static final long SEGMENT = 10 * 60_000L, SUMMARY_INTERVAL = 60 * 60_000L;
    static final int KEEP_DAYS = 30, MIN_VOICED_SECONDS = 8, MAX_DAY_CHARS = 300_000;
    static final String DEFAULT_ASR_URL = "https://api.siliconflow.cn/v1", DEFAULT_ASR_MODEL = "FunAudioLLM/SenseVoiceSmall";
    static final String DEEPSEEK_URL = "https://api.deepseek.com/chat/completions", DEFAULT_MODEL = "deepseek-flash";
    static final String KEY_DEEPSEEK = "deepseekKey", KEY_ASR = "asrKey";
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final Object LOCK = new Object();
    private static Handler main;
    private static synchronized Handler main() { if (main == null) main = new Handler(Looper.getMainLooper()); return main; }
    /** Redraws the 心力球 page when it is showing; set by {@link MindBallPage}. */
    static volatile Runnable listener;
    static volatile boolean recording, busy;
    /** Bumped by 清除全部 under {@link #LOCK}: a round that started before it writes nothing more. */
    private static volatile int epoch;

    static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences("mindball", Context.MODE_PRIVATE); }
    static boolean enabled(Context c) { return prefs(c).getBoolean("enabled", false); }
    static boolean micAllowed(Context c) { return c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED; }
    static String asrUrl(Context c) { return prefs(c).getString("asrUrl", DEFAULT_ASR_URL); }
    static String asrModel(Context c) { return prefs(c).getString("asrModel", DEFAULT_ASR_MODEL); }
    static String model(Context c) { return prefs(c).getString("model", DEFAULT_MODEL); }

    static void enable(Context c) { prefs(c).edit().putBoolean("enabled", true).remove("lastError").apply(); start(c); }
    static void disable(Context c) {
        prefs(c).edit().putBoolean("enabled", false).apply();
        c.stopService(new Intent(c, MindBallService.class));
        c.getSystemService(NotificationManager.class).cancel(ID_PAUSED);
    }
    /** Starts recording; only allowed while God is in the foreground, so a refusal leaves a tap-to-resume notice. */
    static void start(Context c) {
        if (!enabled(c) || recording) return;
        if (!micAllowed(c)) { paused(c, "没有麦克风权限，点按打开 God 处理"); return; }
        try { c.startForegroundService(new Intent(c, MindBallService.class)); c.getSystemService(NotificationManager.class).cancel(ID_PAUSED); }
        catch (RuntimeException refused) { Log.w(TAG, "service_start_refused"); paused(c, "点按打开 God 继续录音"); }
    }
    /** After a reboot or an update Android does not let the microphone start in the background: say so instead. */
    static void afterRestart(Context c) { if (enabled(c)) paused(c, "手机重启或 God 更新后，点按打开 God 继续录音"); }
    static void stopped(Context c) { if (enabled(c)) paused(c, "God 被系统停止了，点按打开 God 继续录音"); }

    // ---------------------------------------------------------------- notifications

    static void channel(Context c) {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "心力球", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("心力球录音中的常驻通知"); ch.setShowBadge(false);
        c.getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }
    static PendingIntent openPage(Context c) {
        Intent open = new Intent(c, MainActivity.class).putExtra(TiboWatch.EXTRA_OPEN, "mindball").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, "mindball".hashCode(), open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
    static Notification recordingNotification(Context c) {
        channel(c);
        PendingIntent stop = PendingIntent.getService(c, ACTION_STOP.hashCode(), new Intent(c, MindBallService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_stat_tibo).setContentTitle("心力球正在录音").setContentText(status(c))
            .setContentIntent(openPage(c)).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(new Notification.Action.Builder(null, "停止录音", stop).build());
        if (android.os.Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }
    static void refreshNotification(Context c) {
        if (!recording) return;
        Notification n = recordingNotification(c);
        // Posted where the service stops: a refresh racing 停止录音 must not bring back an ongoing 「正在录音」.
        main().post(() -> { if (recording) c.getSystemService(NotificationManager.class).notify(ID_RECORDING, n); });
    }
    private static void paused(Context c, String text) {
        channel(c);
        Notification n = new Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_stat_tibo).setContentTitle("心力球已暂停").setContentText(text)
            .setContentIntent(openPage(c)).setAutoCancel(true).build();
        try { c.getSystemService(NotificationManager.class).notify(ID_PAUSED, n); } catch (SecurityException noPermission) { Log.w(TAG, "paused_notice_refused"); }
    }

    // ---------------------------------------------------------------- status

    /** "今天 6 段 · 52 分钟 · 待转写 1 段 · 上次整理 14:05", or the last error. */
    static String status(Context c) { return status(c, day(c, today())); }
    /** The same, from today's day already read. */
    static String status(Context c, JSONObject day) {
        List<String> parts = new ArrayList<>();
        JSONArray segs = day.optJSONArray("segments"); int n = segs == null ? 0 : segs.length();
        parts.add("今天 " + n + " 段 · " + minutes(segs) + " 分钟");
        int pending = pending(c).size(); if (pending > 0) parts.add("待转写 " + pending + " 段");
        long at = day.optLong("summarizedAt", 0); if (at > 0) parts.add("上次整理 " + clock(at));
        return String.join(" · ", parts);
    }
    /** The page's line about the segment being recorded, or "" when not recording. */
    static String live() { MindBallService s = MindBallService.current; return s == null ? "" : liveLine(s.recordedMs(), s.voicedSeconds()); }
    /** "这一段已录 3 分钟 · 听到说话 40 秒 · 满 10 分钟或点「立即整理」就转写"; pure for the tests. */
    static String liveLine(long recordedMs, int voicedSeconds) {
        if (recordedMs < 0) return "麦克风被通话或其他录音占用，一分钟后再试";
        long m = recordedMs / 60_000; String when = "这一段已录 " + (m == 0 ? "不到 1 分钟" : m + " 分钟");
        if (voicedSeconds == 0) return when + " · 还没听到说话声";
        String heard = when + " · 听到说话 " + (voicedSeconds < 60 ? voicedSeconds + " 秒" : voicedSeconds / 60 + " 分 " + voicedSeconds % 60 + " 秒");
        // A full segment needs MIN_VOICED_SECONDS of speech to be kept; 立即整理 keeps any.
        return heard + (voicedSeconds < MIN_VOICED_SECONDS ? " · 点「立即整理」可现在转写" : " · 满 " + SEGMENT / 60_000 + " 分钟或点「立即整理」就转写");
    }
    static String lastError(Context c) { return prefs(c).getString("lastError", ""); }
    static int minutes(JSONArray segs) {
        long ms = 0; if (segs != null) for (int i = 0; i < segs.length(); i++) { JSONObject s = segs.optJSONObject(i); if (s != null) ms += Math.max(0, s.optLong("end") - s.optLong("start")); }
        return (int) Math.round(ms / 60_000.0);
    }
    static String clock(long at) { return DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())); }
    static String today() { return LocalDate.now(ZoneId.systemDefault()).toString(); }
    static String dayOf(long at) { return Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().toString(); }

    // ---------------------------------------------------------------- storage

    static File dir(Context c) { File d = new File(c.getNoBackupFilesDir(), "mindball"); d.mkdirs(); return d; }
    static File audioDir(Context c) { File d = new File(dir(c), "audio"); d.mkdirs(); return d; }
    private static File dayFile(Context c, String day) { return new File(dir(c), day + ".json"); }
    /** A recorded segment waiting for transcription, named {@code <start>-<end>.m4a}. */
    static File segmentFile(Context c, long start, long end) { return new File(audioDir(c), start + "-" + end + ".m4a"); }
    static List<File> pending(Context c) {
        File[] files = audioDir(c).listFiles((d, name) -> name.matches("\\d+-\\d+\\.m4a"));
        List<File> out = files == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(files)); out.sort(Comparator.comparing(File::getName)); return out;
    }
    /** Days with anything recorded, newest first. */
    static List<String> days(Context c) {
        String[] names = dir(c).list((d, name) -> name.matches("\\d{4}-\\d{2}-\\d{2}\\.json")); List<String> out = new ArrayList<>();
        if (names != null) for (String n : names) out.add(n.substring(0, 10));
        out.sort(Comparator.reverseOrder()); return out;
    }
    static JSONObject day(Context c, String day) {
        synchronized (LOCK) {
            File f = dayFile(c, day); if (!f.exists()) return new JSONObject();
            try { return new JSONObject(new String(read(f), StandardCharsets.UTF_8)); } catch (IOException | JSONException broken) { Log.w(TAG, "day_unreadable"); return new JSONObject(); }
        }
    }
    private static void save(Context c, String day, JSONObject value) throws IOException {
        synchronized (LOCK) {
            File tmp = new File(dir(c), day + ".json.tmp");
            try (OutputStream out = new FileOutputStream(tmp)) { out.write(value.toString().getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(dayFile(c, day))) throw new IOException("rename");
        }
    }
    /** Marks a 待办 done or open again; the mark survives later re-summaries because it is keyed by the item's text. */
    static void setDone(Context c, String day, String key, boolean done) {
        synchronized (LOCK) {
            try { JSONObject d = day(c, day); JSONObject marks = d.optJSONObject("done"); if (marks == null) marks = new JSONObject();
                if (done) marks.put(key, System.currentTimeMillis()); else marks.remove(key); d.put("done", marks); save(c, day, d); }
            catch (IOException | JSONException e) { Log.w(TAG, "done_not_saved"); }
        }
    }
    /** Deletes every recording, transcript and timeline, the segment being recorded too; the switch and keys stay. Main thread. */
    static void clearAll(Context c) {
        MindBallService s = MindBallService.current; if (s != null) s.discard();
        synchronized (LOCK) {
            epoch++;
            File[] audio = audioDir(c).listFiles(); if (audio != null) for (File f : audio) if (!f.getName().equals(MindBallService.writing)) f.delete();
            File[] all = dir(c).listFiles(); if (all != null) for (File f : all) if (f.isFile()) f.delete();
        }
        notifyChanged();
    }
    /** Drops days older than {@link #KEEP_DAYS}, audio that could not be transcribed for two days, and segments cut off by a killed process. */
    static void purge(Context c) {
        String oldest = LocalDate.now(ZoneId.systemDefault()).minusDays(KEEP_DAYS - 1).toString();
        for (String d : days(c)) if (d.compareTo(oldest) < 0) dayFile(c, d).delete();
        long cutoff = System.currentTimeMillis() - 2 * 86_400_000L;
        for (File f : pending(c)) if (startOf(f) < cutoff) f.delete();
        // An update, a reboot or ColorOS stopping God ends a segment without its index: unusable audio nothing else removes.
        // The name being recorded is read after the listing, and it is set before its file exists.
        File[] cut = audioDir(c).listFiles((d, name) -> name.startsWith("recording-"));
        if (cut != null) for (File f : cut) if (!f.getName().equals(MindBallService.writing)) f.delete();
    }
    static long startOf(File f) { try { return Long.parseLong(f.getName().split("-")[0]); } catch (RuntimeException e) { return 0; } }
    static long endOf(File f) { try { return Long.parseLong(f.getName().split("[-.]")[1]); } catch (RuntimeException e) { return 0; } }

    // ---------------------------------------------------------------- keys

    private static final String KEYSTORE = "AndroidKeyStore", ALIAS = "god-mindball-v1";
    private static SecretKey keystoreKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE); ks.load(null);
        if (ks.getKey(ALIAS, null) instanceof SecretKey) return (SecretKey) ks.getKey(ALIAS, null);
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return g.generateKey();
    }
    static boolean hasSecret(Context c, String name) { return prefs(c).contains(name); }
    static void setSecret(Context c, String name, String value) {
        if (value == null || value.trim().isEmpty()) { prefs(c).edit().remove(name).apply(); return; }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, keystoreKey());
            byte[] iv = cipher.getIV(), sealed = cipher.doFinal(value.trim().getBytes(StandardCharsets.UTF_8));
            prefs(c).edit().putString(name, Base64.encodeToString(iv, Base64.NO_WRAP) + "." + Base64.encodeToString(sealed, Base64.NO_WRAP)).remove("lastError").apply();
        } catch (Exception e) { throw new IllegalStateException("密钥无法加密保存", e); }
    }
    static String secret(Context c, String name) {
        String stored = prefs(c).getString(name, null); if (stored == null) return null;
        try {
            String[] parts = stored.split("\\.", 2); Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) { Log.w(TAG, "secret_unreadable"); return null; }
    }
    static void configure(Context c, String asrUrl, String asrModel, String model) {
        prefs(c).edit().putString("asrUrl", blank(asrUrl) ? DEFAULT_ASR_URL : asrUrl.trim().replaceAll("/+$", ""))
            .putString("asrModel", blank(asrModel) ? DEFAULT_ASR_MODEL : asrModel.trim()).putString("model", blank(model) ? DEFAULT_MODEL : model.trim()).apply();
    }
    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }

    // ---------------------------------------------------------------- pipeline

    static void notifyChanged() { Runnable r = listener; if (r != null) main().post(r); }
    /** 立即整理 and the page's pull, on the main thread: the segment being recorded is closed first so it is transcribed now too. */
    static void processNow(Context c, Runnable done) { MindBallService s = MindBallService.current; if (s != null) s.cut(); processAsync(c, true, done); }
    /** Transcribes waiting segments, then summarizes days with new text: at most hourly unless {@code force}. */
    static void processAsync(Context c, boolean force) { processAsync(c, force, null); }
    static void processAsync(Context c, boolean force, Runnable done) {
        Context app = c.getApplicationContext();
        WORK.execute(() -> { busy = true; notifyChanged(); try { process(app, force); } finally { busy = false; notifyChanged(); refreshNotification(app); if (done != null) main().post(done); } });
    }
    /** Brings the keys from the account (or sends this phone's), then redraws; on the worker, so it never races a transcription. */
    static void syncAsync(Context c) { syncAsync(c, 0); }
    /** The same, skipped when this phone has nothing to send and synced within {@code maxAge}. */
    static void syncAsync(Context c, long maxAge) { Context app = c.getApplicationContext(); WORK.execute(() -> { if (MindBallSync.runIfDue(app, maxAge)) notifyChanged(); }); }
    private static void process(Context c, boolean force) {
        int round = epoch;
        // A key typed on another phone is used for this round already; the account is asked at most every half hour.
        MindBallSync.runIfDue(c, MindBallSync.ROUND_AGE);
        purge(c);
        String error = ""; int dropped = 0;
        List<String> transcribed = new ArrayList<>(); List<File> refused = new ArrayList<>();
        for (File f : pending(c)) {
            if (epoch != round) return;
            String key = secret(c, KEY_ASR); if (key == null) { error = "未填写转写服务的 API Key，录音先留在手机上"; break; }
            try {
                String text = transcribe(asrUrl(c), asrModel(c), key, f).trim();
                String d = dayOf(startOf(f)); if (!text.isEmpty() && append(c, round, d, startOf(f), endOf(f), text)) transcribed.add(d);
                f.delete();
                // The service took this segment with the same settings, so the one it refused just before is an unusable file.
                for (File r : refused) r.delete(); dropped += refused.size(); refused.clear(); error = "";
            } catch (IOException | JSONException e) {
                error = "转写失败：" + reason(e);
                // One refused file must not hold the queue behind it for two days; two in a row point at the settings instead.
                if (!(e instanceof HttpError) || !fileRefused(((HttpError) e).status) || !refused.isEmpty()) break;
                refused.add(f);
            }
        }
        if (error.isEmpty() && dropped > 0) error = "有 " + dropped + " 段录音转写服务无法处理，已删除";
        String today = today();
        for (String d : summaryDays(transcribed, days(c), today)) {
            if (epoch != round) return;
            JSONObject day = day(c, d); JSONArray segs = day.optJSONArray("segments"); if (segs == null || segs.length() == 0) continue;
            long newest = 0; for (int i = 0; i < segs.length(); i++) { JSONObject s = segs.optJSONObject(i); if (s != null) newest = Math.max(newest, s.optLong("end")); }
            if (!summaryDue(force, d.equals(today), newest, day.optLong("summarizedThrough", 0), day.optLong("summarizedAt", 0), System.currentTimeMillis())) continue;
            String key = secret(c, KEY_DEEPSEEK); if (key == null) { if (error.isEmpty()) error = "未填写 DeepSeek API Key，暂不能整理时间线"; break; }
            try {
                JSONObject timeline = summarize(model(c), key, d, segs);
                synchronized (LOCK) { if (epoch != round) return; JSONObject latest = day(c, d); latest.put("timeline", timeline).put("summarizedThrough", newest).put("summarizedAt", System.currentTimeMillis()); save(c, d, latest); }
            } catch (JSONException e) { if (error.isEmpty()) error = "整理失败：" + reason(e); }
            // The key, the balance or the network: the other days would fail the same way, so they wait for the next round.
            catch (IOException e) { if (error.isEmpty()) error = "整理失败：" + reason(e); break; }
        }
        prefs(c).edit().putString("lastError", error).apply();
    }
    /** False when 清除全部 came after the round began: the text is dropped with everything else. */
    private static boolean append(Context c, int round, String d, long start, long end, String text) throws IOException, JSONException {
        synchronized (LOCK) {
            if (epoch != round) return false;
            JSONObject day = day(c, d); JSONArray segs = day.optJSONArray("segments"); if (segs == null) segs = new JSONArray();
            segs.put(new JSONObject().put("start", start).put("end", end).put("text", text.length() > 20_000 ? text.substring(0, 20_000) : text));
            day.put("segments", segs); save(c, d, day); return true;
        }
    }
    /** A status the transcription service gives one file (bad, too large, an unknown format), not the key or the settings. */
    static boolean fileRefused(int status) { return status == 400 || status == 413 || status == 415 || status == 422; }
    /**
     * Days whose timeline may be behind, newest first: today, the days just transcribed into, and the last three days with
     * a record. Talk after a day's last hourly summary is summarized on the first round after it, the next morning included.
     */
    static List<String> summaryDays(Collection<String> transcribed, List<String> days, String today) {
        Set<String> out = new TreeSet<>(Comparator.reverseOrder()); out.add(today); out.addAll(transcribed);
        List<String> newest = new ArrayList<>(days); newest.sort(Comparator.reverseOrder()); out.addAll(newest.subList(0, Math.min(3, newest.size())));
        return new ArrayList<>(out);
    }
    /** New text since the last summary, and today's summary at most hourly unless {@code force}; a past day at once. */
    static boolean summaryDue(boolean force, boolean today, long newest, long through, long summarizedAt, long now) {
        return newest > through && (force || !today || now - summarizedAt >= SUMMARY_INTERVAL);
    }
    static String reason(Exception e) {
        if (e instanceof HttpError) {
            int s = ((HttpError) e).status;
            return switch (s) { case 401, 403 -> "API Key 无效或没有权限"; case 402 -> "账户余额不足"; case 413 -> "录音文件过大"; case 429 -> "请求过于频繁，稍后自动重试"; default -> s >= 500 ? "服务暂不可用（HTTP " + s + "）" : "请求被拒绝（HTTP " + s + "）"; };
        }
        return e instanceof JSONException ? "返回内容无法解析" : "网络不可用，稍后自动重试";
    }
    static final class HttpError extends IOException { final int status; HttpError(int status) { super("HTTP " + status); this.status = status; } }

    /** POST {base}/audio/transcriptions with the segment as multipart form data; the OpenAI-compatible reply is {"text": …}. */
    static String transcribe(String base, String model, String key, File audio) throws IOException, JSONException {
        String boundary = "god" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection h = (HttpURLConnection) new URL(base + "/audio/transcriptions").openConnection();
        try {
            h.setConnectTimeout(15_000); h.setReadTimeout(180_000); h.setInstanceFollowRedirects(false); h.setDoOutput(true); h.setRequestMethod("POST");
            h.setRequestProperty("Authorization", "Bearer " + key); h.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            byte[] head = multipartHead(boundary, model, audio.getName()), tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
            h.setFixedLengthStreamingMode(head.length + audio.length() + tail.length);
            try (OutputStream out = h.getOutputStream(); InputStream in = new FileInputStream(audio)) { out.write(head); byte[] b = new byte[16384]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); out.write(tail); }
            return new JSONObject(body(h)).optString("text", "");
        } finally { h.disconnect(); }
    }
    static byte[] multipartHead(String boundary, String model, String filename) {
        return ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n" + model + "\r\n"
            + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\nContent-Type: audio/mp4\r\n\r\n").getBytes(StandardCharsets.UTF_8);
    }
    /** DeepSeek chat completion in JSON Output mode with thinking off; returns the validated timeline. */
    static JSONObject summarize(String model, String key, String day, JSONArray segs) throws IOException, JSONException {
        JSONObject req = new JSONObject().put("model", model).put("thinking", new JSONObject().put("type", "disabled")).put("max_tokens", 8192)
            .put("response_format", new JSONObject().put("type", "json_object"))
            .put("messages", new JSONArray().put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT)).put(new JSONObject().put("role", "user").put("content", transcript(day, segs, ZoneId.systemDefault()))));
        HttpURLConnection h = (HttpURLConnection) new URL(DEEPSEEK_URL).openConnection();
        try {
            h.setConnectTimeout(15_000); h.setReadTimeout(240_000); h.setInstanceFollowRedirects(false); h.setDoOutput(true); h.setRequestMethod("POST");
            h.setRequestProperty("Authorization", "Bearer " + key); h.setRequestProperty("Content-Type", "application/json");
            byte[] payload = req.toString().getBytes(StandardCharsets.UTF_8); h.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = h.getOutputStream()) { out.write(payload); }
            JSONObject reply = new JSONObject(body(h)); JSONObject choice = reply.getJSONArray("choices").getJSONObject(0);
            JSONObject timeline = parseTimeline(choice.getJSONObject("message").optString("content", ""));
            if ("length".equals(choice.optString("finish_reason"))) timeline.put("truncated", true);
            return timeline.put("model", reply.optString("model", model));
        } finally { h.disconnect(); }
    }
    private static String body(HttpURLConnection h) throws IOException {
        int status = h.getResponseCode(); if (status < 200 || status >= 300) throw new HttpError(status);
        try (InputStream in = h.getInputStream()) { return new String(readAll(in, 4 << 20), StandardCharsets.UTF_8); }
    }

    // ---------------------------------------------------------------- prompt and result (pure, unit tested)

    static final String SYSTEM_PROMPT = "你是用户随身的「心力球」助理。下面是用户手机全天后台录音的语音转写，按时间分段，可能夹杂环境声、他人对话、电视或识别错误。"
        + "请从中提炼当天的关键事项和待办，按时间排成一条时间线，只输出 JSON，格式为："
        + "{\"items\":[{\"time\":\"HH:mm\",\"kind\":\"event 或 todo\",\"title\":\"一句话，不超过 30 字\",\"detail\":\"补充说明，可为空\",\"due\":\"待办的截止时间，YYYY-MM-DD HH:mm 或 YYYY-MM-DD，没提到就为空\"}],\"summary\":\"一两句话概括今天\"}。"
        + "规则：time 用该内容出现的分段开始时间；event 是已经发生的重要事情、决定、约定或信息；todo 是用户需要去做或答应别人要做的事，用「动词＋对象」写清楚；"
        + "相对时间（明天、下周三、下午三点）按录音日期换算成具体日期；同一件事只保留一条；闲聊、广告、电视内容和无法确认的内容不要写；不要编造录音里没有的信息；没有内容时 items 为空数组。";

    /** "录音日期：2026-10-22（星期四）" then one "[09:40–09:50] …" line per segment, capped at {@link #MAX_DAY_CHARS}. */
    static String transcript(String day, JSONArray segs, ZoneId zone) {
        DateTimeFormatter hm = DateTimeFormatter.ofPattern("HH:mm");
        String week = "一二三四五六日".substring(LocalDate.parse(day).getDayOfWeek().getValue() - 1, LocalDate.parse(day).getDayOfWeek().getValue());
        StringBuilder out = new StringBuilder("录音日期：" + day + "（星期" + week + "）\n");
        List<String> lines = new ArrayList<>(); int total = 0;
        for (int i = segs.length() - 1; i >= 0; i--) {
            JSONObject s = segs.optJSONObject(i); if (s == null) continue;
            String line = "[" + hm.format(Instant.ofEpochMilli(s.optLong("start")).atZone(zone)) + "–" + hm.format(Instant.ofEpochMilli(s.optLong("end")).atZone(zone)) + "] " + s.optString("text").replace('\n', ' ');
            if (total + line.length() > MAX_DAY_CHARS) break; total += line.length() + 1; lines.add(line);
        }
        Collections.reverse(lines); if (lines.size() < segs.length()) out.append("（只附上最近的 ").append(lines.size()).append(" 段）\n");
        return out.append(String.join("\n", lines)).toString();
    }
    /** Keeps only well-formed items, sorted by time; text fields are trimmed and capped so a reply cannot flood the page. */
    static JSONObject parseTimeline(String content) throws JSONException {
        String json = content == null ? "" : content.trim();
        if (json.startsWith("```")) json = json.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
        JSONObject in = new JSONObject(json); JSONArray items = in.optJSONArray("items"); List<JSONObject> kept = new ArrayList<>();
        if (items != null) for (int i = 0; i < items.length() && kept.size() < 200; i++) {
            JSONObject it = items.optJSONObject(i); if (it == null) continue;
            String title = cap(it.optString("title"), 80); if (title.isEmpty()) continue;
            String time = it.optString("time").trim(); if (!time.matches("([01]\\d|2[0-3]):[0-5]\\d")) time = time.matches("\\d:[0-5]\\d") ? "0" + time : "";
            String due = it.optString("due").trim(); if (!due.matches("\\d{4}-\\d{2}-\\d{2}( ([01]\\d|2[0-3]):[0-5]\\d)?")) due = "";
            kept.add(new JSONObject().put("time", time).put("kind", "todo".equals(it.optString("kind")) ? "todo" : "event").put("title", title).put("detail", cap(it.optString("detail"), 400)).put("due", due));
        }
        kept.sort(Comparator.comparing((JSONObject o) -> o.optString("time").isEmpty() ? "99:99" : o.optString("time")));
        return new JSONObject().put("items", new JSONArray(kept)).put("summary", cap(in.optString("summary"), 300));
    }
    private static String cap(String s, int max) { String t = s == null ? "" : s.trim().replaceAll("\\s+", " "); return t.length() > max ? t.substring(0, max) + "…" : t; }
    /** Stable identity of a 待办 across re-summaries: its normalized title. */
    static String todoKey(JSONObject item) { return Integer.toHexString(FeatureCatalog.normalize(item.optString("title")).replaceAll("[\\s\\p{P}]", "").hashCode()); }

    // ---------------------------------------------------------------- io

    private static byte[] read(File f) throws IOException { try (InputStream in = new FileInputStream(f)) { return readAll(in, 32 << 20); } }
    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[16384]; int n;
        while ((n = in.read(b)) > 0) { out.write(b, 0, n); if (out.size() > max) throw new IOException("too large"); }
        return out.toByteArray();
    }
}
