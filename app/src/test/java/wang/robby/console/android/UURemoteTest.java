package wang.robby.console.android;

import static org.junit.Assert.*;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** The UU 远程 links follow netease-uuyc/uuremote-integration-guide; results God did not ask for are dropped. */
public final class UURemoteTest {
    @Test public void bindRequestEncodesTheCallbackAndCarriesTheNonce() {
        String url = UURemote.bindRequest(UURemote.bindCallback("wang.robby.console.android"), "n-1");
        assertEquals("uuremote://external/device/authorize?callback=wang.robby.console.android%3A%2F%2Fuuremote%2Fbind&nonce=n-1", url);
    }
    @Test public void controlRequestOnlySendsACallbackForPictureInPicture() {
        String id = "ab+c/d==";
        assertEquals("uuremote://external/device/control?anonymous_device_id=ab%2Bc%2Fd%3D%3D&window_type=0", UURemote.controlRequest(id, UURemote.FULL_SCREEN, "x://y"));
        String cb = UURemote.controlCallback("wang.robby.console.android", "k1", "n2");
        assertEquals("wang.robby.console.android://uuremote/control?d=k1&n=n2", cb);
        assertEquals("uuremote://external/device/control?anonymous_device_id=ab%2Bc%2Fd%3D%3D&window_type=1&callback=wang.robby.console.android%3A%2F%2Fuuremote%2Fcontrol%3Fd%3Dk1%26n%3Dn2",
            UURemote.controlRequest(id, UURemote.PICTURE_IN_PICTURE, cb));
    }
    @Test public void queryDecodesValues() {
        Map<String, String> q = UURemote.query("anonymous_device_id=ab%2Bc%2Fd%3D%3D&nonce=&flag");
        assertEquals("ab+c/d==", q.get("anonymous_device_id")); assertEquals("", q.get("nonce")); assertEquals("", q.get("flag"));
        assertTrue(UURemote.query(null).isEmpty());
    }
    @Test public void bindResultMustMatchThePendingNonce() {
        assertNull(UURemote.readBind(UURemote.query("anonymous_device_id=x&nonce=other"), "mine"));
        assertNull("nothing pending", UURemote.readBind(UURemote.query("anonymous_device_id=x&nonce=mine"), null));
        UURemote.Result ok = UURemote.readBind(UURemote.query("anonymous_device_id=x%3D&nonce=mine"), "mine");
        assertTrue(ok.bind); assertTrue(ok.ok); assertEquals("x=", ok.code);
        UURemote.Result no = UURemote.readBind(UURemote.query("error=no_device&nonce=mine"), "mine");
        assertFalse(no.ok); assertEquals("no_device", no.code);
        assertEquals("internal_error", UURemote.readBind(UURemote.query("nonce=mine"), "mine").code);
    }
    @Test public void controlResultKeepsOurParametersAndMapsUnknownReasons() {
        UURemote.Result ok = UURemote.readControl(UURemote.query("d=k1&n=n2&status=success"), "n2");
        assertFalse(ok.bind); assertTrue(ok.ok); assertEquals("k1", ok.device);
        UURemote.Result off = UURemote.readControl(UURemote.query("d=k1&n=n2&status=failure&reason=device_offline"), "n2");
        assertFalse(off.ok); assertEquals("device_offline", off.code);
        assertEquals("connect_failed", UURemote.readControl(UURemote.query("d=k1&n=n2&status=failure&reason=brand_new"), "n2").code);
        assertEquals("connect_failed", UURemote.readControl(UURemote.query("d=k1&n=n2&status=failure"), "n2").code);
        assertNull(UURemote.readControl(UURemote.query("d=k1&n=old&status=success"), "n2"));
    }
    @Test public void everyDocumentedCodeHasText() {
        for (String c : List.of("no_device", "user_cancel", "timeout", "not_logined", "superseded", "logout", "internal_error")) assertFalse(UURemote.bindText(c).isEmpty());
        for (String r : List.of("busy", "device_offline", "device_not_found", "pip_unsupported", "login_timeout", "network_unavailable", "not_allowed",
                "connect_timeout", "connect_failed", "user_cancel", "invalid_request", "internal_error")) assertNotNull(r, UURemote.controlText(r));
        assertNull(UURemote.controlText("brand_new"));
    }
    @Test public void versionsCompareNumerically() {
        assertEquals(0, UURemote.compareVersions("4.42.0", UURemote.MIN_VERSION));
        assertEquals(1, UURemote.compareVersions("4.100.0", "4.42.0"));
        assertEquals(-1, UURemote.compareVersions("4.9.9", "4.42.0"));
        assertEquals(0, UURemote.compareVersions("4.42", "4.42.0"));
        assertEquals(1, UURemote.compareVersions("4.42.1-beta", "4.42.0"));
        assertEquals(-1, UURemote.compareVersions("", "4.42.0"));
    }
    @Test public void devicesRoundTripAndGetFreeDefaultNames() {
        List<UURemote.Device> two = List.of(new UURemote.Device("a", "id1", "电脑 1", 10, "", 0), new UURemote.Device("b", "id2", "书房", 20, "device_not_found", 30));
        List<UURemote.Device> back = UURemote.parseDevices(UURemote.serialize(two));
        assertEquals(2, back.size()); assertEquals("id2", back.get(1).id); assertEquals("书房", back.get(1).name); assertTrue(back.get(1).stale()); assertFalse(back.get(0).stale());
        assertEquals("电脑 2", UURemote.defaultName(two));
        assertTrue(UURemote.parseDevices("not json").isEmpty());
        assertTrue("rows without an id are dropped", UURemote.parseDevices("[{\"key\":\"a\"}]").isEmpty());
    }
    @Test public void catalogListsItAsALocalTool() {
        FeatureCatalog.Feature f = FeatureCatalog.find("uu-remote");
        assertNotNull(f); assertTrue(f.local()); assertEquals("工具", f.group);
        assertTrue(FeatureCatalog.search("远程桌面").contains(f));
        assertTrue(FeatureCatalog.search("远控").contains(f));
    }
}
