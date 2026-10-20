package wang.robby.console.android;

import static org.junit.Assert.*;
import java.time.Instant;
import org.json.JSONObject;
import org.junit.Test;

public final class AccountCenterTest {
    private static final String STATUS = "{\"schemaVersion\":1,\"canWrite\":false,\"displayName\":\"Robby\",\"unavailable\":[\"email\"],"
        + "\"security\":{\"totpBound\":true,\"passkeyBound\":true,\"settingsUrl\":\"https://auth.god.robby.wang/settings/two-factor-authentication\",\"passkeys\":["
        + "{\"id\":\"0123456789abcdef\",\"description\":\"Find X9 Ultra\",\"createdAt\":\"2026-10-09T06:59:12Z\",\"lastUsedAt\":null,\"attachment\":\"platform\",\"backupState\":true},"
        + "{\"id\":\"BAD\",\"description\":\"x\"},{\"id\":\"fedcba9876543210\",\"description\":null,\"attachment\":\"cross-platform\",\"cloneWarning\":true}]},"
        + "\"social\":{\"apple\":{\"available\":true,\"bound\":true,\"boundAt\":\"2026-10-09T07:00:00Z\"},\"wechat\":{\"available\":false,\"bound\":false,\"boundAt\":null}},"
        + "\"email\":null,\"phone\":{\"available\":false,\"bound\":false}}";

    @Test public void statusReadsWhatTheWebUserCenterShows() throws Exception {
        AccountCenter.Status s = AccountCenter.status(new JSONObject(STATUS));
        assertEquals("Robby", s.displayName); assertFalse(s.canWrite); assertTrue(s.unavailable.contains("email"));
        assertEquals(Boolean.TRUE, s.totp); assertEquals(2, s.passkeys.size());
        assertEquals("Find X9 Ultra", s.passkeys.get(0).name); assertTrue(s.passkeys.get(0).detail.startsWith("设备内置 · 已同步 · 添加于 "));
        assertEquals("未命名 Passkey", s.passkeys.get(1).name); assertTrue(s.passkeys.get(1).detail.contains("外部安全密钥")); assertTrue(s.passkeys.get(1).detail.contains("可能被复制"));
        assertEquals(2, s.social.size()); assertEquals("Apple", s.social.get(0).label); assertTrue(s.social.get(0).bound); assertFalse(s.social.get(1).available);
        assertNull("an unreadable part stays unknown", s.emailBound); assertEquals(Boolean.FALSE, s.phoneAvailable);
    }

    @Test public void foreignSettingsLinksAreIgnored() throws Exception {
        AccountCenter.Status s = AccountCenter.status(new JSONObject(STATUS.replace("https://auth.god.robby.wang/settings/two-factor-authentication", "https://evil.example/x")));
        assertEquals(AccountCenter.SETTINGS, s.settingsUrl);
    }

    @Test public void devicesKeepOnlyKnownAppsAndCountTheOthers() throws Exception {
        AccountCenter.Devices d = AccountCenter.devices(new JSONObject("{\"currentFound\":true,\"devices\":["
            + "{\"id\":\"00000000000000a0\",\"client\":\"android\",\"signedInAt\":\"2026-10-11T08:00:00Z\",\"lastActiveAt\":\"2026-10-20T00:13:35Z\",\"current\":true},"
            + "{\"id\":\"00000000000000a1\",\"client\":\"ios\",\"signedInAt\":null,\"lastActiveAt\":null,\"current\":false},"
            + "{\"id\":\"00000000000000a2\",\"client\":\"web\",\"current\":false}]}"));
        assertTrue(d.currentFound); assertEquals(2, d.list.size()); assertEquals(1, AccountCenter.others(d));
        assertEquals("God for Android", AccountCenter.deviceTitle(d.list.get(0))); assertTrue(d.list.get(0).current); assertEquals("God for iOS", AccountCenter.deviceTitle(d.list.get(1)));
        assertTrue(AccountCenter.deviceDetail(d.list.get(0), Instant.parse("2026-10-20T00:20:40Z")).startsWith("最近活动 7 分钟前 · 登录于 2026-10-"));
        assertEquals("时间未知", AccountCenter.deviceDetail(d.list.get(1), Instant.now()));
    }

    @Test public void agesReadLikeChat() {
        Instant now = Instant.parse("2026-10-20T12:00:00Z");
        assertEquals("刚刚", AccountCenter.ago("2026-10-20T11:59:30Z", now));
        assertEquals("3 小时前", AccountCenter.ago("2026-10-20T09:00:00Z", now));
        assertEquals("2 天前", AccountCenter.ago("2026-10-18T09:00:00Z", now));
        assertEquals("未知", AccountCenter.ago("soon", now));
    }

    @Test public void refusalsSayWhatToDo() {
        assertTrue(AccountCenter.needsSignIn(403, "insufficient_scope"));
        assertFalse(AccountCenter.needsSignIn(409, "current_device_unknown"));
        assertEquals("验证码已过期，请重新发送。", AccountCenter.explain(400, "code_expired"));
        assertEquals("个人中心接口尚未启用。", AccountCenter.explain(404, "http_error"));
        assertNull(AccountCenter.explain(500, "http_error"));
        assertTrue(AccountCenter.emailLooksValid("a@example.com")); assertFalse(AccountCenter.emailLooksValid("a@b")); assertFalse(AccountCenter.emailLooksValid("a b@example.com"));
    }

    @Test public void onlyTheAccountRoutesAreRegistered() {
        assertEquals("account/status", MobileApi.checkedPath("account/status"));
        assertEquals("account/devices/revoke-others", MobileApi.checkedPath("account/devices/revoke-others"));
        assertThrows(IllegalArgumentException.class, () -> MobileApi.checkedPath("account/other"));
        assertTrue(AccountCenter.writeRoute("account/social/unbind")); assertFalse(AccountCenter.writeRoute("account/status"));
    }
}
