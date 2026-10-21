package wang.robby.console.android;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 我 → 个人中心: the owner's account as god.robby.wang/account/ shows it (sign-in security and passkeys, Apple and
 * WeChat bindings, contact email, phone) plus the God apps signed in to it. Parsing and wording only; the page is
 * drawn by MainActivity. Anything missing or malformed reads as unknown rather than as a wrong state.
 */
final class AccountCenter {
    private AccountCenter() {}
    static final String STATUS = "account/status", DEVICES = "account/devices";
    static final String EMAIL_SEND = "account/email/send", EMAIL_VERIFY = "account/email/verify";
    static final String UNBIND = "account/social/unbind", REVOKE_OTHERS = "account/devices/revoke-others";
    /** The personal signature, saved with the avatar's scope (god.mobile.profile.write). */
    static final String SIGNATURE = "account/profile/signature";
    /** The web user center; binding Apple or WeChat starts there, since it needs the provider's own sign-in page. */
    static final String WEB = "https://god.robby.wang/account/";
    static final String SETTINGS = "https://auth.god.robby.wang/settings/two-factor-authentication";
    /** Devices shown before the rest fold under one row. */
    static final int FOLD = 6;

    static boolean readRoute(String route) { return route.equals(STATUS) || route.equals(DEVICES); }
    static boolean writeRoute(String route) { return route.equals(EMAIL_SEND) || route.equals(EMAIL_VERIFY) || route.equals(UNBIND) || route.equals(REVOKE_OTHERS) || route.equals(SIGNATURE); }

    static final class Passkey {
        final String id, name, detail;
        Passkey(String id, String name, String detail) { this.id = id; this.name = name; this.detail = detail; }
    }
    /** One bound account: its id (null from an older server, which cannot unbind one at a time) and when it was bound. */
    static final class Link {
        final Long id; final String boundAt;
        Link(Long id, String boundAt) { this.id = id; this.boundAt = boundAt; }
    }
    static final class Social {
        List<Link> links = new ArrayList<>(); int limit = 1;
        final String provider, label; final boolean available, bound; final String boundAt;
        Social(String provider, String label, boolean available, boolean bound, String boundAt) { this.provider = provider; this.label = label; this.available = available; this.bound = bound; this.boundAt = boundAt; }
    }
    static final class Status {
        String displayName; boolean canWrite, canSaveProfile;
        /** The account's signature: null when the server has none on record (or did not answer), "" when it was cleared. */
        String signature; boolean profileKnown;
        Boolean totp, passkeyBound; List<Passkey> passkeys; String settingsUrl = SETTINGS;
        List<Social> social; Boolean emailBound; String emailMasked, emailVerifiedAt; boolean emailCanSend; Boolean phoneAvailable, phoneBound;
        final List<String> unavailable = new ArrayList<>();
    }
    static final class Device {
        final String id, client, signedInAt, lastActiveAt; final boolean current;
        Device(String id, String client, String signedInAt, String lastActiveAt, boolean current) { this.id = id; this.client = client; this.signedInAt = signedInAt; this.lastActiveAt = lastActiveAt; this.current = current; }
    }
    static final class Devices { final boolean currentFound; final List<Device> list; Devices(boolean f, List<Device> l) { currentFound = f; list = l; } }

    private static String str(JSONObject o, String key, String pattern) {
        if (o == null || o.isNull(key)) return null; Object v = o.opt(key);
        return v instanceof String && ((String) v).matches(pattern) ? (String) v : null;
    }
    private static Boolean flag(JSONObject o, String key) { Object v = o == null ? null : o.opt(key); return v instanceof Boolean ? (Boolean) v : null; }
    private static final String STAMP = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,3})?Z";
    private static final String TEXT = "[^\\p{Cc}]{1,100}";

    static Status status(JSONObject v) {
        Status s = new Status();
        s.displayName = str(v, "displayName", TEXT); s.canWrite = Boolean.TRUE.equals(flag(v, "canWrite")); s.canSaveProfile = Boolean.TRUE.equals(flag(v, "canSaveProfile"));
        JSONObject profile = v.optJSONObject("profile");
        if (profile != null) { s.profileKnown = true; s.signature = profile.isNull("signature") ? null : Signature.clean(profile.optString("signature", "")); }
        JSONArray missing = v.optJSONArray("unavailable"); if (missing != null) for (int i = 0; i < missing.length(); i++) s.unavailable.add(missing.optString(i));
        JSONObject sec = v.optJSONObject("security");
        if (sec != null) {
            s.totp = flag(sec, "totpBound"); s.passkeyBound = flag(sec, "passkeyBound");
            String url = str(sec, "settingsUrl", "https://auth\\.god\\.robby\\.wang/[A-Za-z0-9/_-]{1,80}"); if (url != null) s.settingsUrl = url;
            JSONArray keys = sec.optJSONArray("passkeys");
            if (keys != null) { s.passkeys = new ArrayList<>(); for (int i = 0; i < keys.length() && i < 50; i++) { Passkey p = passkey(keys.optJSONObject(i)); if (p != null) s.passkeys.add(p); } }
        }
        JSONObject social = v.optJSONObject("social");
        if (social != null) {
            s.social = new ArrayList<>();
            for (String[] p : new String[][]{{"apple", "Apple"}, {"google", "Google"}, {"wechat", "微信"}}) {
                JSONObject m = social.optJSONObject(p[0]); if (m == null) continue;
                Boolean bound = flag(m, "bound"), available = flag(m, "available");
                Social x = new Social(p[0], p[1], Boolean.TRUE.equals(available), Boolean.TRUE.equals(bound), str(m, "boundAt", STAMP));
                JSONArray links = m.optJSONArray("links");
                if (links != null) for (int i = 0; i < links.length() && i < 10; i++) {
                    JSONObject l = links.optJSONObject(i); if (l == null) continue;
                    Object id = l.opt("id"); Long value = id instanceof Number && ((Number) id).longValue() > 0 && ((Number) id).doubleValue() == ((Number) id).longValue() ? ((Number) id).longValue() : null;
                    x.links.add(new Link(value, str(l, "boundAt", STAMP)));
                }
                else if (x.bound) x.links.add(new Link(null, x.boundAt));
                Object limit = m.opt("limit"); if (limit instanceof Number && ((Number) limit).intValue() >= 0 && ((Number) limit).intValue() <= 10) x.limit = ((Number) limit).intValue();
                s.social.add(x);
            }
        }
        JSONObject email = v.optJSONObject("email");
        if (email != null) { s.emailBound = flag(email, "bound"); s.emailMasked = str(email, "masked", "[^\\s@*]\\*\\*\\*@[A-Za-z0-9.-]{3,253}"); s.emailVerifiedAt = str(email, "verifiedAt", STAMP); s.emailCanSend = Boolean.TRUE.equals(flag(email, "canSend")); }
        JSONObject phone = v.optJSONObject("phone");
        if (phone != null) { s.phoneAvailable = flag(phone, "available"); s.phoneBound = flag(phone, "bound"); }
        return s;
    }

    static Passkey passkey(JSONObject p) {
        String id = str(p, "id", "[a-f0-9]{16}"); if (id == null) return null;
        String name = str(p, "description", "[^\\p{Cc}]{1,64}");
        List<String> parts = new ArrayList<>();
        String attachment = str(p, "attachment", "platform|cross-platform");
        if ("platform".equals(attachment)) parts.add("设备内置"); else if ("cross-platform".equals(attachment)) parts.add("外部安全密钥");
        if (Boolean.TRUE.equals(flag(p, "backupState"))) parts.add("已同步");
        String created = str(p, "createdAt", STAMP); if (created != null) parts.add("添加于 " + day(created));
        String used = str(p, "lastUsedAt", STAMP); if (used != null) parts.add("最近使用 " + day(used));
        if (Boolean.TRUE.equals(flag(p, "cloneWarning"))) parts.add("检测到可能被复制");
        return new Passkey(id, name == null ? "未命名 Passkey" : name, String.join(" · ", parts));
    }

    static Devices devices(JSONObject v) {
        List<Device> list = new ArrayList<>(); JSONArray a = v.optJSONArray("devices");
        if (a != null) for (int i = 0; i < a.length() && i < 100; i++) {
            JSONObject d = a.optJSONObject(i); String id = str(d, "id", "[a-f0-9]{16}"), client = str(d, "client", "android|ios");
            if (id == null || client == null) continue;
            list.add(new Device(id, client, str(d, "signedInAt", STAMP), str(d, "lastActiveAt", STAMP), Boolean.TRUE.equals(flag(d, "current"))));
        }
        return new Devices(Boolean.TRUE.equals(flag(v, "currentFound")), list);
    }
    static int others(Devices d) { int n = 0; for (Device x : d.list) if (!x.current) n++; return n; }

    /** The asking device carries a 本机 badge beside its title, so the title itself only names the app. */
    static String deviceTitle(Device d) { return "ios".equals(d.client) ? "God for iOS" : "God for Android"; }
    static String deviceDetail(Device d, Instant now) {
        List<String> parts = new ArrayList<>();
        if (d.lastActiveAt != null) parts.add("最近活动 " + ago(d.lastActiveAt, now));
        if (d.signedInAt != null) parts.add("登录于 " + day(d.signedInAt));
        return parts.isEmpty() ? "时间未知" : String.join(" · ", parts);
    }

    static String day(String stamp) {
        try { return DateTimeFormatter.ofPattern("yyyy-MM-dd").format(Instant.parse(stamp).atZone(ZoneId.systemDefault())); } catch (RuntimeException e) { return "未知"; }
    }
    static String ago(String stamp, Instant now) {
        try {
            long s = Math.max(0, now.getEpochSecond() - Instant.parse(stamp).getEpochSecond());
            if (s < 120) return "刚刚"; if (s < 3600) return s / 60 + " 分钟前"; if (s < 86400) return s / 3600 + " 小时前";
            if (s < 30 * 86400L) return s / 86400 + " 天前"; return day(stamp);
        } catch (RuntimeException e) { return "未知"; }
    }

    /** What to tell the person for a refused account request; null means "use the transport's own message". */
    static String explain(int status, String code) {
        if (code == null) code = "";
        switch (code) {
            case "insufficient_scope": return "需要重新登录一次，授权 God 管理账号。";
            case "invalid_signature": return "签名最多 30 个字，且只能是一行。";
            case "invalid_email": return "邮箱地址格式不正确。";
            case "invalid_code": return "验证码不正确，请检查后重试。";
            case "code_expired": return "验证码已过期，请重新发送。";
            case "send_limited": return "发送太频繁，请一分钟后再试。";
            case "mail_unavailable": return "邮件服务暂不可用，请稍后再试。";
            case "native_recovery_required": return "请先保留可用的 Passkey，再解除绑定。";
            case "current_device_unknown": return "暂时无法确认本机的登录，请下拉刷新后再试。";
            case "account_unavailable": return "账号服务暂不可用，请稍后再试。";
            default: return status == 403 ? "需要重新登录一次，授权 God 管理账号。" : status == 404 ? "个人中心接口尚未启用。" : null;
        }
    }
    static boolean needsSignIn(int status, String code) { return status == 403 || "insufficient_scope".equals(code); }
    static boolean emailLooksValid(String s) { return s != null && s.length() >= 3 && s.length() <= 254 && s.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"); }
}
