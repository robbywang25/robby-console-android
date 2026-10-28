package wang.robby.console.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 米家灯光: one Mi Home Wi-Fi lamp driven straight over the home LAN with the miIO protocol, the way Mi Home itself talks
 * to it when both are on the same network. Each command is a hello (the lamp answers with its device id and clock) and
 * one AES-128-CBC packet to UDP 54321, keyed by the lamp's 32-hex token. The lamp is read and set through MIoT
 * properties of its light service (siid 2); which piid holds brightness and colour temperature depends on the model,
 * read once with {@code miIO.info}. The token is typed in by the person, kept under an Android Keystore key, and only
 * ever sent to the lamp's own address: no God server, no Xiaomi cloud, no sync, no backup.
 */
final class MiioLight {
    private MiioLight() {}
    static final int PORT = 54321, TIMEOUT_MS = 1500, TRIES = 2;
    private static final int MAGIC = 0x2131, HEADER = 32, SIID = 2;
    private static final String PREFS = "mijia-light", KEYSTORE = "AndroidKeyStore", ALIAS = "god-mijia-light-v1";

    /** Where brightness and colour temperature live in a model's light service, and its colour temperature range. */
    static final class Layout {
        final int brightness, ct, minCt, maxCt;
        Layout(int brightness, int ct, int minCt, int maxCt) { this.brightness = brightness; this.ct = ct; this.minCt = minCt; this.maxCt = maxCt; }
    }
    /**
     * From each model's miot-spec.org instance. Newer Yeelight lamps ("Lightctl", e.g. the 显示器挂灯1S lamp22) put
     * brightness at piid 2 and colour temperature at 3; the older "Light" layout has mode at 2, so they sit at 3 and 5.
     */
    static Layout layout(String model) {
        return switch (model == null ? "" : model) {
            case "yeelink.light.lamp18", "yeelink.light.lamp19" -> new Layout(3, 5, 1700, 6500);
            case "yeelink.light.lamp20" -> new Layout(3, 5, 2700, 6500);
            case "yeelink.light.lamp4" -> new Layout(2, 3, 2600, 5000);
            case "yeelink.light.lamp23" -> new Layout(2, 3, 3000, 6500);
            case "yeelink.light.lamp39" -> new Layout(2, 3, 2700, 6000);
            default -> new Layout(2, 3, 2700, 6500);
        };
    }

    /** What the lamp said: on or off, brightness 1–100 and colour temperature in kelvin. */
    static final class State {
        final boolean on; final int brightness, ct;
        State(boolean on, int brightness, int ct) { this.on = on; this.brightness = brightness; this.ct = ct; }
    }
    /** The lamp's answer to a hello: who it is and its clock, which every later packet has to carry. */
    static final class Hello {
        final long deviceId, stamp;
        Hello(long deviceId, long stamp) { this.deviceId = deviceId; this.stamp = stamp; }
    }
    /** A failure to show the person; the text is already in Chinese. */
    static final class LightException extends Exception { LightException(String message) { super(message); } }

    // ---- Protocol (pure, unit tested) ----

    static byte[] hello() { byte[] p = new byte[HEADER]; Arrays.fill(p, (byte) 0xff); p[0] = 0x21; p[1] = 0x31; p[2] = 0; p[3] = HEADER; return p; }
    static Hello readHello(byte[] p, int length) {
        if (length < HEADER || (p[0] & 0xff) != 0x21 || (p[1] & 0xff) != 0x31) return null;
        ByteBuffer b = ByteBuffer.wrap(p, 0, HEADER); return new Hello(b.getInt(8) & 0xffffffffL, b.getInt(12) & 0xffffffffL);
    }
    /** The token as 16 bytes, or null unless it is exactly 32 hex digits (spaces and a 0x prefix are forgiven). */
    static byte[] token(String hex) {
        if (hex == null) return null; String s = hex.trim().replaceAll("\\s", "").toLowerCase(Locale.ROOT); if (s.startsWith("0x")) s = s.substring(2);
        if (!s.matches("[0-9a-f]{32}")) return null;
        byte[] out = new byte[16]; for (int i = 0; i < 16; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16); return out;
    }
    static boolean validToken(String hex) { return token(hex) != null; }
    private static byte[] md5(byte[]... parts) {
        try { MessageDigest d = MessageDigest.getInstance("MD5"); for (byte[] p : parts) d.update(p); return d.digest(); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    private static byte[] crypt(int mode, byte[] token, byte[] data) {
        try {
            byte[] key = md5(token); Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(md5(key, token))); return c.doFinal(data);
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    /** A command packet: header with the lamp's id and clock, MD5 checksum over header, token and the encrypted JSON. */
    static byte[] packet(byte[] token, long deviceId, long stamp, String json) {
        byte[] body = crypt(Cipher.ENCRYPT_MODE, token, json.getBytes(StandardCharsets.UTF_8));
        ByteBuffer b = ByteBuffer.allocate(HEADER + body.length);
        b.putShort((short) MAGIC).putShort((short) (HEADER + body.length)).putInt(0).putInt((int) deviceId).putInt((int) stamp);
        byte[] head = Arrays.copyOf(b.array(), 16); b.put(md5(head, token, body)).put(body); return b.array();
    }
    /** The JSON inside a reply, or null when it is not a well-formed packet sealed with this token. */
    static String open(byte[] token, byte[] p, int length) {
        if (length <= HEADER || (p[0] & 0xff) != 0x21 || (p[1] & 0xff) != 0x31 || ((p[2] & 0xff) << 8 | p[3] & 0xff) != length) return null;
        byte[] head = Arrays.copyOf(p, 16), sum = Arrays.copyOfRange(p, 16, HEADER), body = Arrays.copyOfRange(p, HEADER, length);
        if (!MessageDigest.isEqual(sum, md5(head, token, body))) return null;
        try { String s = new String(crypt(Cipher.DECRYPT_MODE, token, body), StandardCharsets.UTF_8); int end = s.lastIndexOf('}'); return end < 0 ? s : s.substring(0, end + 1); }
        catch (IllegalStateException e) { return null; }
    }
    static String request(int id, String method, JSONArray params) {
        try { return new JSONObject().put("id", id).put("method", method).put("params", params).toString(); } catch (JSONException e) { throw new IllegalStateException(e); }
    }
    private static JSONObject prop(long deviceId, int piid, Object value) throws JSONException {
        JSONObject o = new JSONObject().put("did", String.valueOf(deviceId)).put("siid", SIID).put("piid", piid); return value == null ? o : o.put("value", value);
    }
    static JSONArray readParams(long deviceId, Layout l) {
        try { return new JSONArray().put(prop(deviceId, 1, null)).put(prop(deviceId, l.brightness, null)).put(prop(deviceId, l.ct, null)); } catch (JSONException e) { throw new IllegalStateException(e); }
    }
    static JSONArray setParams(long deviceId, int piid, Object value) {
        try { return new JSONArray().put(prop(deviceId, piid, value)); } catch (JSONException e) { throw new IllegalStateException(e); }
    }
    /** The {@code result} of a reply to request {@code id}; a reply carrying {@code error} becomes the person's message. */
    static Object result(String json, int id) throws LightException {
        try {
            JSONObject o = new JSONObject(json); if (o.optInt("id", -1) != id) throw new LightException("灯回复的不是这次请求，请重试。");
            JSONObject e = o.optJSONObject("error"); if (e != null) throw new LightException("灯拒绝了这个指令（" + e.optInt("code") + " " + e.optString("message") + "）。");
            if (!o.has("result")) throw new LightException("灯的回复里没有结果。"); return o.get("result");
        } catch (JSONException e) { throw new LightException("看不懂灯的回复。"); }
    }
    /** Reads on, brightness and colour temperature from a get_properties result, in the order {@link #readParams} asked. */
    static State readState(Object result, Layout l) throws LightException {
        if (!(result instanceof JSONArray) || ((JSONArray) result).length() < 3) throw new LightException("灯没有按预期回复属性。");
        JSONArray a = (JSONArray) result; Object[] v = new Object[3];
        for (int i = 0; i < 3; i++) { JSONObject p = a.optJSONObject(i); if (p == null || p.optInt("code", -1) != 0 || !p.has("value")) throw new LightException(unsupported(l)); v[i] = p.opt("value"); }
        if (!(v[0] instanceof Boolean) || !(v[1] instanceof Number) || !(v[2] instanceof Number)) throw new LightException(unsupported(l));
        return new State((Boolean) v[0], ((Number) v[1]).intValue(), ((Number) v[2]).intValue());
    }
    private static String unsupported(Layout l) { return "这盏灯的属性和 God 认识的布局不一样（亮度 piid " + l.brightness + "、色温 piid " + l.ct + "）。"; }
    /** A set_properties result is a list with one entry per property; code 0 means it took. */
    static void checkSet(Object result) throws LightException {
        if (!(result instanceof JSONArray) || ((JSONArray) result).length() == 0) throw new LightException("灯没有确认这个指令。");
        int code = ((JSONArray) result).optJSONObject(0) == null ? -1 : ((JSONArray) result).optJSONObject(0).optInt("code", -1);
        if (code != 0) throw new LightException("灯没有执行这个指令（code " + code + "）。");
    }
    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    /** Accepts a dotted IPv4 address on a private network; the lamp is never addressed by a name or a public address. */
    static boolean validAddress(String s) {
        if (s == null || !s.trim().matches("\\d{1,3}(\\.\\d{1,3}){3}")) return false;
        String[] p = s.trim().split("\\."); int[] n = new int[4]; for (int i = 0; i < 4; i++) { n[i] = Integer.parseInt(p[i]); if (n[i] > 255) return false; }
        return n[0] == 10 || n[0] == 172 && n[1] >= 16 && n[1] <= 31 || n[0] == 192 && n[1] == 168;
    }

    // ---- The lamp ----

    /** One conversation with the lamp: hello, then commands carrying its id and a clock that moves with ours. */
    static final class Session implements AutoCloseable {
        private final DatagramSocket socket; private final InetAddress address; private final byte[] token;
        private long deviceId, stamp, helloAt; private int nextId = (int) (System.currentTimeMillis() % 10_000) + 1;
        Session(String address, byte[] token) throws LightException {
            this.token = token;
            try { this.address = InetAddress.getByName(address); socket = new DatagramSocket(); socket.setSoTimeout(TIMEOUT_MS); }
            catch (IOException e) { throw new LightException("无法在这台手机上打开局域网连接。"); }
        }
        long deviceId() { return deviceId; }
        private byte[] exchange(byte[] out) throws LightException {
            byte[] in = new byte[4096];
            for (int t = 0; t < TRIES; t++) {
                try {
                    socket.send(new DatagramPacket(out, out.length, address, PORT)); DatagramPacket r = new DatagramPacket(in, in.length); socket.receive(r);
                    if (r.getAddress().equals(address)) return Arrays.copyOf(in, r.getLength());
                } catch (SocketTimeoutException e) { /* one more try */ } catch (IOException e) { throw new LightException("这台手机现在连不上局域网：" + e.getMessage()); }
            }
            throw new LightException("灯没有回应。确认手机和灯连着同一个 Wi-Fi、地址没有变，并且灯通着电。");
        }
        private void greet() throws LightException {
            byte[] r = exchange(hello()); Hello h = readHello(r, r.length); if (h == null) throw new LightException("这个地址回复的不是米家设备。");
            deviceId = h.deviceId; stamp = h.stamp; helloAt = System.currentTimeMillis();
        }
        Object call(String method, JSONArray params) throws LightException {
            if (helloAt == 0) greet();
            int id = nextId++; long now = stamp + (System.currentTimeMillis() - helloAt) / 1000;
            byte[] r = exchange(packet(token, deviceId, now, request(id, method, params))); String json = open(token, r, r.length);
            if (json == null) throw new LightException("Token 不对：灯的回复解不开。请核对 32 位 token。");
            return result(json, id);
        }
        @Override public void close() { socket.close(); }
    }

    /** What 刷新 learns: the model (null when the lamp would not say) and the state. */
    static final class Reading {
        final String model, firmware; final State state; final Layout layout;
        Reading(String model, String firmware, State state, Layout layout) { this.model = model; this.firmware = firmware; this.state = state; this.layout = layout; }
    }
    /** Asks the lamp who it is, then reads its light. Runs on a background thread. */
    static Reading read(String address, byte[] token) throws LightException {
        try (Session s = new Session(address, token)) {
            Object info = s.call("miIO.info", new JSONArray()); String model = null, fw = null;
            if (info instanceof JSONObject) { model = ((JSONObject) info).optString("model", null); fw = ((JSONObject) info).optString("fw_ver", null); }
            Layout l = layout(model); return new Reading(model, fw, readState(s.call("get_properties", readParams(s.deviceId(), l)), l), l);
        }
    }
    /** Sets one property of the light service (piid 1 on, or the layout's brightness or colour temperature). */
    static void set(String address, byte[] token, int piid, Object value) throws LightException {
        try (Session s = new Session(address, token)) { s.greet(); checkSet(s.call("set_properties", setParams(s.deviceId(), piid, value))); }
    }

    // ---- This phone ----

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static String address(Context c) { return prefs(c).getString("address", ""); }
    static String name(Context c) { return prefs(c).getString("name", "米家灯"); }
    static String model(Context c) { return prefs(c).getString("model", null); }
    static boolean configured(Context c) { return validAddress(address(c)) && prefs(c).contains("token"); }
    static void configure(Context c, String address, String name, String tokenHex) {
        SharedPreferences.Editor e = prefs(c).edit().putString("address", address.trim()).putString("name", name == null || name.trim().isEmpty() ? "米家灯" : name.trim());
        if (tokenHex != null && !tokenHex.trim().isEmpty()) e.putString("token", seal(tokenHex.trim().replaceAll("\\s", "").toLowerCase(Locale.ROOT).replaceFirst("^0x", ""))).remove("model");
        e.apply();
    }
    static void remember(Context c, String model) { if (model != null) prefs(c).edit().putString("model", model).apply(); }
    static void forget(Context c) { prefs(c).edit().clear().apply(); }
    /** The saved token as bytes, or null when there is none or this phone's Keystore can no longer open it. */
    static byte[] savedToken(Context c) { return token(unseal(prefs(c).getString("token", null))); }

    private static SecretKey keystoreKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE); ks.load(null);
        if (ks.getKey(ALIAS, null) instanceof SecretKey) return (SecretKey) ks.getKey(ALIAS, null);
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return g.generateKey();
    }
    private static String seal(String value) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, keystoreKey());
            return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "." + Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        } catch (Exception e) { throw new IllegalStateException("token 无法加密保存", e); }
    }
    private static String unseal(String stored) {
        if (stored == null) return null;
        try {
            String[] parts = stored.split("\\.", 2); Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) { return null; }
    }
}
