package wang.robby.console.android;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.SigningInfo;
import android.os.Build;
import java.io.*;
import java.net.URL;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * In-app update from this project's GitHub pre-releases. Only GitHub's API and release-asset hosts are contacted,
 * over HTTPS, and only when the person opens 关于 or taps update. A downloaded APK is handed to Android's installer
 * only when its SHA-256 matches the release's SHA256SUMS file, it is this package with a higher versionCode, and it
 * carries the same signing certificate as the installed app. Android still asks the person to confirm the install.
 */
final class AppUpdate {
    private AppUpdate() {}
    static final String RELEASES = "https://api.github.com/repos/robbywang25/robby-console-android/releases?per_page=15";
    /** github.com answers asset downloads with a redirect to one of the asset hosts. */
    static final Set<String> HOSTS = Set.of("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com");
    static final long MAX_JSON = 2_000_000, MAX_SUMS = 4_096, MAX_APK = 60_000_000;

    static final class Release {
        final String version, title, summary, date, apkName, apkUrl, sumsUrl; final long size;
        Release(String version, String title, String summary, String date, String apkName, String apkUrl, long size, String sumsUrl) {
            this.version = version; this.title = title; this.summary = summary; this.date = date; this.apkName = apkName; this.apkUrl = apkUrl; this.size = size; this.sumsUrl = sumsUrl;
        }
    }
    interface Progress { void on(long done, long total); }

    /** "v0.4.1" → "0.4.1"; anything else (billing-lab tags, suffixes) is not an app release. */
    static String version(String tag) { return tag != null && tag.matches("v\\d{1,4}\\.\\d{1,4}\\.\\d{1,4}") ? tag.substring(1) : null; }
    static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < 3; i++) { int c = Integer.compare(Integer.parseInt(x[i]), Integer.parseInt(y[i])); if (c != 0) return c; }
        return 0;
    }
    /** Newest release strictly newer than {@code current}, or null when the installed build is the newest. */
    static Release pick(List<Release> releases, String current) {
        if (current == null || !current.matches("\\d+\\.\\d+\\.\\d+")) return null;
        Release best = null;
        for (Release r : releases) if (compare(r.version, current) > 0 && (best == null || compare(r.version, best.version) > 0)) best = r;
        return best;
    }
    /** SHA-256 recorded for {@code name} in a "<hex>  <name>" checksum file, lower case; null when absent. */
    static String expectedSha(String sums, String name) {
        for (String line : sums.split("\\r?\\n")) {
            String[] p = line.trim().split("\\s+\\*?", 2);
            if (p.length == 2 && p[1].equals(name) && p[0].matches("[0-9a-fA-F]{64}")) return p[0].toLowerCase(Locale.ROOT);
        }
        return null;
    }
    static boolean allowed(URL u) { return "https".equals(u.getProtocol()) && HOSTS.contains(u.getHost()) && (u.getPort() == -1 || u.getPort() == 443) && u.getUserInfo() == null; }
    /** Release title after "vX.Y.Z · ", or the version when there is none. */
    static String theme(String name, String version) { if (name == null) return version; int i = name.indexOf('·'); String t = (i < 0 ? name : name.substring(i + 1)).trim(); return t.isEmpty() ? version : t; }
    /** First paragraph of the release body as plain text, at most 120 characters. */
    static String summary(String body) {
        if (body == null) return "";
        String first = body.trim().split("\\r?\\n\\s*\\r?\\n", 2)[0].replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1").replaceAll("[`*_#>]", "").replaceAll("\\s+", " ").trim();
        return first.length() > 120 ? first.substring(0, 119) + "…" : first;
    }

    /** App releases from the GitHub list response; drafts and releases without both expected assets are skipped. */
    static List<Release> parse(String json) throws Exception {
        JSONArray list = new JSONArray(json); List<Release> out = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject r = list.optJSONObject(i); if (r == null || r.optBoolean("draft")) continue;
            String v = version(r.optString("tag_name")); if (v == null) continue;
            String apkName = "robby-console-android-" + v + ".apk", sumsName = "SHA256SUMS-" + v + ".txt", apkUrl = null, sumsUrl = null; long size = -1;
            JSONArray assets = r.optJSONArray("assets");
            for (int j = 0; assets != null && j < assets.length(); j++) { JSONObject a = assets.optJSONObject(j); if (a == null) continue;
                if (apkName.equals(a.optString("name"))) { apkUrl = a.optString("browser_download_url"); size = a.optLong("size", -1); }
                if (sumsName.equals(a.optString("name"))) sumsUrl = a.optString("browser_download_url"); }
            if (apkUrl == null || sumsUrl == null || size <= 0 || size > MAX_APK) continue;
            String date = r.optString("published_at", ""); date = date.length() >= 10 ? date.substring(0, 10) : "";
            out.add(new Release(v, theme(r.optString("name", null), v), summary(r.optString("body", "")), date, apkName, apkUrl, size, sumsUrl));
        }
        return out;
    }
    static Release check(String current) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream(); fetch(RELEASES, MAX_JSON, body, null, null);
        return pick(parse(body.toString("UTF-8")), current);
    }

    /** Streams one URL into {@code out}, following at most four redirects and only within {@link #HOSTS}. */
    static void fetch(String url, long max, OutputStream out, Progress progress, AtomicBoolean cancel) throws IOException {
        URL u = new URL(url);
        for (int hop = 0; ; hop++) {
            if (!allowed(u)) throw new IOException("下载地址不在允许的范围内");
            HttpsURLConnection c = (HttpsURLConnection) u.openConnection();
            try {
                c.setInstanceFollowRedirects(false); c.setConnectTimeout(15_000); c.setReadTimeout(20_000);
                c.setRequestProperty("User-Agent", "God-Android"); if ("api.github.com".equals(u.getHost())) c.setRequestProperty("Accept", "application/vnd.github+json");
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) { String to = c.getHeaderField("Location"); if (to == null || hop >= 4) throw new IOException("下载跳转异常"); u = new URL(u, to); continue; }
                if (code == 403 || code == 429) throw new IOException("GitHub 暂时限制了请求，请稍后再试");
                if (code != 200) throw new IOException("下载失败（HTTP " + code + "）");
                long length = c.getContentLengthLong(); if (length > max) throw new IOException("文件大小异常");
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[16_384]; long done = 0; int n;
                    while ((n = in.read(buf)) > 0) {
                        if (cancel != null && cancel.get()) throw new InterruptedIOException("已取消");
                        done += n; if (done > max) throw new IOException("文件大小异常");
                        out.write(buf, 0, n); if (progress != null) progress.on(done, length);
                    }
                }
                return;
            } finally { c.disconnect(); }
        }
    }

    /** Downloads the release APK into the app cache and returns it only after every check passed. */
    static File download(Context context, Release r, Progress progress, AtomicBoolean cancel) throws Exception {
        ByteArrayOutputStream sums = new ByteArrayOutputStream(); fetch(r.sumsUrl, MAX_SUMS, sums, null, cancel);
        String want = expectedSha(sums.toString("UTF-8"), r.apkName); if (want == null) throw new IOException("校验文件里没有这个安装包");
        File dir = new File(context.getCacheDir(), "updates"); clear(dir); if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("无法写入缓存");
        File apk = new File(dir, r.apkName); MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (OutputStream out = new DigestOutputStream(new BufferedOutputStream(new FileOutputStream(apk)), sha)) { fetch(r.apkUrl, MAX_APK, out, progress, cancel); }
        catch (IOException e) { apk.delete(); throw e; }
        if (!want.equals(hex(sha.digest()))) { apk.delete(); throw new IOException("安装包校验失败，已删除"); }
        try { verifyArchive(context, apk); } catch (Exception e) { apk.delete(); throw e; }
        return apk;
    }
    static void clear(File dir) { File[] files = dir.listFiles(); if (files != null) for (File f : files) f.delete(); }
    private static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x)); return s.toString(); }

    /** Same package, newer versionCode, same single signing certificate as the running app. */
    static void verifyArchive(Context context, File apk) throws Exception {
        PackageManager pm = context.getPackageManager(); String name = context.getPackageName(); int flags = PackageManager.GET_SIGNING_CERTIFICATES;
        PackageInfo mine = Build.VERSION.SDK_INT >= 33 ? pm.getPackageInfo(name, PackageManager.PackageInfoFlags.of(flags)) : pm.getPackageInfo(name, flags);
        PackageInfo next = Build.VERSION.SDK_INT >= 33 ? pm.getPackageArchiveInfo(apk.getPath(), PackageManager.PackageInfoFlags.of(flags)) : pm.getPackageArchiveInfo(apk.getPath(), flags);
        if (next == null) throw new IOException("无法读取安装包");
        SigningInfo a = next.signingInfo, b = mine.signingInfo;
        if (a == null || b == null) throw new IOException("无法读取安装包签名");
        if (!name.equals(next.packageName)) throw new IOException("安装包不属于本应用");
        if (next.getLongVersionCode() <= mine.getLongVersionCode()) throw new IOException("安装包不比当前版本新");
        if (a.hasMultipleSigners() || b.hasMultipleSigners() || !Arrays.equals(a.getApkContentsSigners(), b.getApkContentsSigners()))
            throw new IOException("安装包签名与当前应用不一致");
    }

    /** Hands the APK to Android's installer; the result, including the confirmation step, arrives at {@link UpdateReceiver}. */
    static void install(Context context, File apk) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName()); params.setSize(apk.length());
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (OutputStream out = session.openWrite("base.apk", 0, apk.length()); InputStream in = new FileInputStream(apk)) {
                byte[] buf = new byte[65_536]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n); session.fsync(out);
            }
            Intent status = new Intent(context, UpdateReceiver.class).setAction(UpdateReceiver.ACTION);
            // The installer fills in status extras, so the PendingIntent must be mutable; it is explicit and targets a non-exported receiver.
            PendingIntent callback = PendingIntent.getBroadcast(context, id, status, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
            session.commit(callback.getIntentSender());
        } catch (IOException | RuntimeException e) { installer.abandonSession(id); throw e; }
        finally { apk.delete(); }
    }
}
