package wang.robby.console.android;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * UU 远程: the cross-app protocol NetEase published in netease-uuyc/uuremote-integration-guide (MIT). God opens
 * {@code uuremote://external/device/authorize} so the person picks one of their Windows/macOS computers in UU 远程,
 * keeps the encrypted {@code anonymous_device_id} it returns, and later opens {@code device/control} with it, full screen
 * or picture-in-picture. Results come back to {@code <applicationId>://uuremote/...}; a nonce kept here ties each result
 * to the request God made, so a result God did not ask for is dropped. The ids stay in this phone's private storage: no
 * God server, no account sync, no backup.
 */
final class UURemote {
    private UURemote() {}
    static final String PACKAGE = "com.netease.uuremote", MIN_VERSION = "4.42.0", HOST = "uuremote";
    static final String BIND = "/bind", CONTROL = "/control";
    static final int FULL_SCREEN = 0, PICTURE_IN_PICTURE = 1;
    /** UU 远程 queues a bind for up to 180 s while it signs in; past this a result is too old to be the one asked for. */
    static final long PENDING_MILLIS = 30 * 60_000L;
    private static final String PREFS = "uu-remote", DEVICES = "devices", BIND_NONCE = "bindNonce", BIND_AT = "bindAt",
        CONTROL_NONCE = "controlNonce", CONTROL_AT = "controlAt";

    static final class Device {
        final String key, id, name; final long boundAt; final String last; final long lastAt;
        Device(String key, String id, String name, long boundAt, String last, long lastAt) { this.key = key; this.id = id; this.name = name; this.boundAt = boundAt; this.last = last == null ? "" : last; this.lastAt = lastAt; }
        Device named(String n) { return new Device(key, id, n, boundAt, last, lastAt); }
        Device result(String r, long at) { return new Device(key, id, name, boundAt, r, at); }
        /** UU 远程 said the id no longer resolves (unbound, or another UU account): it needs binding again. */
        boolean stale() { return "device_not_found".equals(last); }
        JSONObject json() { try { return new JSONObject().put("key", key).put("id", id).put("name", name).put("boundAt", boundAt).put("last", last).put("lastAt", lastAt); } catch (org.json.JSONException e) { throw new IllegalStateException(e); } }
        static Device of(JSONObject o) { return new Device(o.optString("key"), o.optString("id"), o.optString("name"), o.optLong("boundAt"), o.optString("last"), o.optLong("lastAt")); }
    }

    /** What a callback said, for the page to show; {@code device} is the local key it concerns, or null. */
    static final class Result {
        final boolean bind, ok; final String code, device;
        Result(boolean bind, boolean ok, String code, String device) { this.bind = bind; this.ok = ok; this.code = code == null ? "" : code; this.device = device; }
    }

    // ---- Protocol (pure, unit tested) ----

    static String bindRequest(String callback, String nonce) { return "uuremote://external/device/authorize?callback=" + enc(callback) + "&nonce=" + enc(nonce); }
    /** {@code callback} is only answered for picture-in-picture; full screen never calls back, so it is left out there. */
    static String controlRequest(String deviceId, int windowType, String callback) {
        String s = "uuremote://external/device/control?anonymous_device_id=" + enc(deviceId) + "&window_type=" + windowType;
        return windowType == PICTURE_IN_PICTURE && callback != null ? s + "&callback=" + enc(callback) : s;
    }
    static String bindCallback(String scheme) { return scheme + "://" + HOST + BIND; }
    /** UU 远程 keeps the callback's own query and appends its result, so the device and nonce ride along. */
    static String controlCallback(String scheme, String deviceKey, String nonce) { return scheme + "://" + HOST + CONTROL + "?d=" + enc(deviceKey) + "&n=" + enc(nonce); }

    static Map<String, String> query(String raw) {
        Map<String, String> out = new LinkedHashMap<>(); if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) { if (pair.isEmpty()) continue; int eq = pair.indexOf('=');
            String k = dec(eq < 0 ? pair : pair.substring(0, eq)), v = eq < 0 ? "" : dec(pair.substring(eq + 1)); out.put(k, v); }
        return out;
    }
    /** -1, 0 or 1 comparing dotted versions numerically; a missing or non-numeric part counts as 0. */
    static int compareVersions(String a, String b) {
        String[] x = (a == null ? "" : a).split("[.\\-]"), y = (b == null ? "" : b).split("[.\\-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) { int p = part(x, i), q = part(y, i); if (p != q) return p < q ? -1 : 1; }
        return 0;
    }
    private static int part(String[] v, int i) { if (i >= v.length) return 0; String d = v[i].replaceAll("\\D.*", ""); try { return d.isEmpty() ? 0 : Integer.parseInt(d); } catch (NumberFormatException e) { return 0; } }

    /** Bind result: the new id, or an error code; a nonce other than the one pending is not God's request. */
    static Result readBind(Map<String, String> q, String pendingNonce) {
        if (pendingNonce == null || !pendingNonce.equals(q.get("nonce"))) return null;
        String id = q.get("anonymous_device_id");
        if (id != null && !id.isEmpty()) return new Result(true, true, id, null);
        String error = q.get("error"); return new Result(true, false, error == null || error.isEmpty() ? "internal_error" : error, null);
    }
    /** Picture-in-picture result; an unknown or missing failure reason reads as connect_failed, as the guide asks. */
    static Result readControl(Map<String, String> q, String pendingNonce) {
        if (pendingNonce == null || !pendingNonce.equals(q.get("n"))) return null;
        String device = q.get("d"), status = q.get("status");
        if ("success".equals(status)) return new Result(false, true, "success", device);
        String reason = q.get("reason"); return new Result(false, false, reason == null || controlText(reason) == null ? "connect_failed" : reason, device);
    }
    static String bindText(String code) {
        return switch (code) {
            case "no_device" -> "当前 UU 账号下没有可绑定的 Windows 或 Mac 电脑。先在电脑上安装 UU 远程并登录同一账号。";
            case "user_cancel" -> "已取消绑定。";
            case "timeout" -> "UU 远程等待登录或加载设备列表超时，请重试。";
            case "not_logined" -> "UU 远程还没有登录，登录后再绑定。";
            case "superseded" -> "这次绑定被之后的请求取代了。";
            case "logout" -> "UU 远程退出了登录，绑定已取消。";
            default -> "UU 远程暂时无法完成绑定，请重试。";
        };
    }
    /** What to tell the person for a picture-in-picture failure; null for a reason the guide does not define. */
    static String controlText(String reason) {
        return switch (reason) {
            case "busy" -> "UU 远程正在远控其他设备。先在 UU 远程结束当前远控再试。";
            case "device_offline" -> "这台电脑离线。开机或唤醒后再试。";
            case "device_not_found" -> "这台电脑在 UU 远程里找不到了：可能已解绑，或 UU 远程换了账号。请重新绑定。";
            case "pip_unsupported" -> "这台手机现在不能用小窗，可以改用全屏远控。";
            case "login_timeout" -> "UU 远程没有及时完成登录。";
            case "network_unavailable" -> "这台手机没有可用网络，检查网络后再试。";
            case "not_allowed" -> "这台电脑没有开启允许被控，或系统不受支持。在电脑上的 UU 远程里开启后再试。";
            case "connect_timeout" -> "连接超时，请重试。";
            case "connect_failed" -> "连接失败，请重试。";
            case "user_cancel" -> "已取消远控。";
            case "invalid_request" -> "请求参数不正确，请重新绑定这台电脑。";
            case "internal_error" -> "UU 远程暂时无法处理，请重试。";
            default -> null;
        };
    }
    /** A short state for the device row, from the last picture-in-picture answer. */
    static String lastLabel(String last) {
        return switch (last) {
            case "" -> ""; case "success" -> "上次小窗远控成功"; case "device_offline" -> "上次：电脑离线"; case "device_not_found" -> "需要重新绑定";
            case "not_allowed" -> "上次：未允许被控"; case "busy" -> "上次：UU 远程忙"; case "pip_unsupported" -> "上次：不支持小窗"; case "user_cancel" -> "上次：已取消";
            default -> "上次：连接失败";
        };
    }
    static List<Device> parseDevices(String json) {
        List<Device> out = new ArrayList<>(); if (json == null || json.isEmpty()) return out;
        try { JSONArray a = new JSONArray(json); for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o == null) continue; Device d = Device.of(o); if (!d.key.isEmpty() && !d.id.isEmpty()) out.add(d); } }
        catch (org.json.JSONException ignored) {}
        return out;
    }
    static String serialize(List<Device> devices) { JSONArray a = new JSONArray(); for (Device d : devices) a.put(d.json()); return a.toString(); }
    /** The next unused 电脑 N, for a computer bound without a name yet. */
    static String defaultName(List<Device> devices) {
        for (int n = 1; ; n++) { String name = "电脑 " + n; boolean used = false; for (Device d : devices) if (d.name.equals(name)) { used = true; break; } if (!used) return name; }
    }
    private static String enc(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch (UnsupportedEncodingException e) { throw new IllegalStateException(e); } }
    private static String dec(String s) { try { return URLDecoder.decode(s, "UTF-8"); } catch (UnsupportedEncodingException | IllegalArgumentException e) { return s; } }

    // ---- This phone ----

    /** Installed UU 远程 version, or null when it is not installed. */
    static String installedVersion(Context ctx) {
        try { String v = ctx.getPackageManager().getPackageInfo(PACKAGE, 0).versionName; return v == null ? "" : v; }
        catch (PackageManager.NameNotFoundException e) { return null; }
    }
    /** UU 远程 answers the protocol's links (4.42.0 or later and the deep link resolves). */
    static boolean ready(Context ctx) {
        String v = installedVersion(ctx); if (v == null || compareVersions(v, MIN_VERSION) < 0) return false;
        return new Intent(Intent.ACTION_VIEW, Uri.parse("uuremote://external")).resolveActivity(ctx.getPackageManager()) != null;
    }
    /** The app store page for UU 远程: the phone's store first, the web page when there is none. */
    static void openStore(Context ctx) {
        try { ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + PACKAGE))); }
        catch (ActivityNotFoundException e) { ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://uuyc.163.com/"))); }
    }
    private static SharedPreferences prefs(Context ctx) { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static List<Device> devices(Context ctx) { return parseDevices(prefs(ctx).getString(DEVICES, null)); }
    private static void save(Context ctx, List<Device> devices) { prefs(ctx).edit().putString(DEVICES, serialize(devices)).apply(); }
    static Device device(Context ctx, String key) { for (Device d : devices(ctx)) if (d.key.equals(key)) return d; return null; }
    static void rename(Context ctx, String key, String name) { replace(ctx, key, d -> d.named(name)); }
    static void remove(Context ctx, String key) { List<Device> all = devices(ctx); all.removeIf(d -> d.key.equals(key)); save(ctx, all); }
    private static void replace(Context ctx, String key, java.util.function.UnaryOperator<Device> change) {
        List<Device> all = devices(ctx); for (int i = 0; i < all.size(); i++) if (all.get(i).key.equals(key)) all.set(i, change.apply(all.get(i))); save(ctx, all);
    }
    private static String scheme(Context ctx) { return ctx.getPackageName(); }

    /** Opens UU 远程's computer picker; false when UU 远程 could not be opened. */
    static boolean bind(Context ctx) {
        String nonce = UUID.randomUUID().toString(); prefs(ctx).edit().putString(BIND_NONCE, nonce).putLong(BIND_AT, System.currentTimeMillis()).apply();
        return open(ctx, bindRequest(bindCallback(scheme(ctx)), nonce));
    }
    /** Opens the computer in UU 远程; only picture-in-picture reports back. */
    static boolean control(Context ctx, Device d, int windowType) {
        String callback = null;
        if (windowType == PICTURE_IN_PICTURE) { String nonce = UUID.randomUUID().toString(); prefs(ctx).edit().putString(CONTROL_NONCE, nonce).putLong(CONTROL_AT, System.currentTimeMillis()).apply(); callback = controlCallback(scheme(ctx), d.key, nonce); }
        return open(ctx, controlRequest(d.id, windowType, callback));
    }
    private static boolean open(Context ctx, String uri) {
        try { ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(PACKAGE)); return true; }
        catch (ActivityNotFoundException e) { return false; }
    }
    /** A link to God's own {@code <applicationId>://uuremote} callback. */
    static boolean isCallback(Context ctx, Uri uri) { return uri != null && scheme(ctx).equals(uri.getScheme()) && HOST.equals(uri.getHost()); }
    /**
     * Applies a callback: a new computer is added (or an existing one with the same id kept), a picture-in-picture
     * answer is noted on its row. Each pending nonce is used once. Null when the link was not a result God asked for.
     */
    static Result receive(Context ctx, Uri uri) {
        SharedPreferences p = prefs(ctx); Map<String, String> q = query(uri.getEncodedQuery()); long now = System.currentTimeMillis();
        if (BIND.equals(uri.getPath())) {
            String nonce = now - p.getLong(BIND_AT, 0) <= PENDING_MILLIS ? p.getString(BIND_NONCE, null) : null;
            Result r = readBind(q, nonce); if (r == null) return null; p.edit().remove(BIND_NONCE).remove(BIND_AT).apply();
            if (!r.ok) return r;
            List<Device> all = devices(ctx); for (Device d : all) if (d.id.equals(r.code)) return new Result(true, true, "", d.key);
            Device d = new Device(UUID.randomUUID().toString().substring(0, 8), r.code, defaultName(all), now, "", 0); all.add(d); save(ctx, all);
            return new Result(true, true, "new", d.key);
        }
        if (CONTROL.equals(uri.getPath())) {
            String nonce = now - p.getLong(CONTROL_AT, 0) <= PENDING_MILLIS ? p.getString(CONTROL_NONCE, null) : null;
            Result r = readControl(q, nonce); if (r == null) return null; p.edit().remove(CONTROL_NONCE).remove(CONTROL_AT).apply();
            if (r.device != null) replace(ctx, r.device, d -> d.result(r.code, now));
            return r;
        }
        return null;
    }
}
