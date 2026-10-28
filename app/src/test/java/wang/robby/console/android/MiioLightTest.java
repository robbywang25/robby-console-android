package wang.robby.console.android;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** The miIO packets match python-miio's framing; the vector below was sealed independently with openssl. */
public final class MiioLightTest {
    private static final String TOKEN = "00112233445566778899aabbccddeeff";
    private static final String GET_POWER = "{\"id\":1,\"method\":\"get_prop\",\"params\":[\"power\"]}";
    /** openssl enc -aes-128-cbc with key md5(token), iv md5(key+token); checksum md5(header[0:16]+token+body). */
    private static final String SEALED = "21310050000000000102030400000064bfd43c52f976a268a49e919e043f22fb"
        + "a5516ec6151955dc2bb2d43e7c84c1833ad6abd2560c09de4318b095b7713e230bbed4ee40764c0304f323716693cc0a";

    private static byte[] hex(String s) { byte[] b = new byte[s.length() / 2]; for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16); return b; }
    private static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }

    private static String rep(String s, int n) { StringBuilder b = new StringBuilder(); for (int i = 0; i < n; i++) b.append(s); return b.toString(); }

    @Test public void helloIsTheFixed32BytePacket() {
        assertEquals("21310020" + rep("ff", 28), hex(MiioLight.hello()));
    }
    @Test public void helloReplyGivesTheDeviceIdAndClock() {
        byte[] r = hex("21310020000000002276f2f2000004d2" + rep("ff", 16));
        MiioLight.Hello h = MiioLight.readHello(r, r.length);
        assertEquals(578_220_786L, h.deviceId); assertEquals(1234L, h.stamp);
        assertNull("too short", MiioLight.readHello(r, 20));
        assertNull("not miIO", MiioLight.readHello(hex("00000020" + rep("00", 28)), 32));
    }
    @Test public void tokenMustBe32HexDigits() {
        assertArrayEquals(hex(TOKEN), MiioLight.token(TOKEN));
        assertArrayEquals(hex(TOKEN), MiioLight.token(" 0x0011223344556677 8899AABBCCDDEEFF "));
        assertNull(MiioLight.token("0011")); assertNull(MiioLight.token("zz112233445566778899aabbccddeeff")); assertNull(MiioLight.token(null));
    }
    @Test public void packetMatchesTheIndependentlySealedVector() {
        assertEquals(SEALED, hex(MiioLight.packet(hex(TOKEN), 0x01020304L, 100, GET_POWER)));
    }
    @Test public void openVerifiesTheChecksumAndDecrypts() {
        byte[] p = hex(SEALED);
        assertEquals(GET_POWER, MiioLight.open(hex(TOKEN), p, p.length));
        assertNull("wrong token", MiioLight.open(MiioLight.token("ffeeddccbbaa99887766554433221100"), p, p.length));
        byte[] tampered = p.clone(); tampered[40] ^= 1; assertNull(MiioLight.open(hex(TOKEN), tampered, tampered.length));
        assertNull("length field disagrees", MiioLight.open(hex(TOKEN), p, p.length - 16));
    }
    @Test public void openDropsTheLampsTrailingNul() {
        byte[] token = hex(TOKEN), p = MiioLight.packet(token, 7, 8, "{\"id\":3,\"result\":[\"ok\"]}\u0000");
        assertEquals("{\"id\":3,\"result\":[\"ok\"]}", MiioLight.open(token, p, p.length));
    }
    @Test public void onlyTheReplyToThisRequestIsAccepted() {
        byte[] token = hex(TOKEN), mine = MiioLight.packet(token, 7, 8, "{\"id\":42,\"result\":[]}"), older = MiioLight.packet(token, 7, 8, "{\"id\":41,\"result\":{\"model\":\"x\"}}");
        assertEquals("{\"id\":42,\"result\":[]}", MiioLight.reply(token, mine, 42));
        assertNull("a late reply to the previous request", MiioLight.reply(token, older, 42));
        assertNull("a late second hello", MiioLight.reply(token, hex("21310020000000002276f2f2000004d2" + rep("ff", 16)), 42));
        assertNull("sealed with another token", MiioLight.reply(MiioLight.token("ffeeddccbbaa99887766554433221100"), mine, 42));
        assertTrue(MiioLight.isHelloReply(hex("21310020000000002276f2f2000004d2" + rep("ff", 16))));
        assertFalse("a command reply is not a hello", MiioLight.isHelloReply(mine));
    }
    @Test public void layoutFollowsTheModelsSpec() {
        MiioLight.Layout lamp22 = MiioLight.layout("yeelink.light.lamp22");
        assertEquals(2, lamp22.brightness); assertEquals(3, lamp22.ct); assertEquals(2700, lamp22.minCt); assertEquals(6500, lamp22.maxCt);
        MiioLight.Layout lamp19 = MiioLight.layout("yeelink.light.lamp19");
        assertEquals(3, lamp19.brightness); assertEquals(5, lamp19.ct); assertEquals(1700, lamp19.minCt);
        assertEquals(2, MiioLight.layout(null).brightness);
    }
    @Test public void readParamsAskForOnBrightnessAndColourTemperature() throws Exception {
        JSONArray p = MiioLight.readParams(581269746L, MiioLight.layout("yeelink.light.lamp19"));
        assertEquals("581269746", p.getJSONObject(0).getString("did"));
        assertEquals(1, p.getJSONObject(0).getInt("piid")); assertEquals(3, p.getJSONObject(1).getInt("piid")); assertEquals(5, p.getJSONObject(2).getInt("piid"));
        assertFalse(p.getJSONObject(0).has("value"));
        JSONObject set = MiioLight.setParams(9L, 1, true).getJSONObject(0);
        assertEquals(2, set.getInt("siid")); assertTrue(set.getBoolean("value"));
    }
    @Test public void stateIsReadFromGetProperties() throws Exception {
        MiioLight.Layout l = MiioLight.layout("yeelink.light.lamp22");
        Object r = MiioLight.result("{\"id\":5,\"result\":[{\"did\":\"1\",\"siid\":2,\"piid\":1,\"code\":0,\"value\":true},"
            + "{\"did\":\"1\",\"siid\":2,\"piid\":2,\"code\":0,\"value\":80},{\"did\":\"1\",\"siid\":2,\"piid\":3,\"code\":0,\"value\":4000}]}", 5);
        MiioLight.State s = MiioLight.readState(r, l);
        assertTrue(s.on); assertEquals(80, s.brightness); assertEquals(4000, s.ct);
    }
    @Test public void missingPropertiesSayTheLayoutDiffers() throws Exception {
        Object r = MiioLight.result("{\"id\":5,\"result\":[{\"code\":0,\"value\":true},{\"code\":-4003},{\"code\":0,\"value\":4000}]}", 5);
        try { MiioLight.readState(r, MiioLight.layout(null)); fail(); } catch (MiioLight.LightException e) { assertTrue(e.getMessage().contains("piid 2")); }
    }
    @Test public void errorsAndForeignRepliesBecomeMessages() {
        try { MiioLight.result("{\"id\":4,\"error\":{\"code\":-9999,\"message\":\"user ack timeout\"}}", 4); fail(); }
        catch (MiioLight.LightException e) { assertTrue(e.getMessage().contains("-9999")); }
        try { MiioLight.result("{\"id\":3,\"result\":[]}", 4); fail(); } catch (MiioLight.LightException e) { assertTrue(e.getMessage().contains("不是这次请求")); }
    }
    @Test public void setMustBeAcknowledgedWithCodeZero() throws Exception {
        MiioLight.checkSet(new JSONArray("[{\"code\":0}]"));
        try { MiioLight.checkSet(new JSONArray("[{\"code\":-4004}]")); fail(); } catch (MiioLight.LightException e) { assertTrue(e.getMessage().contains("-4004")); }
        try { MiioLight.checkSet(new JSONArray()); fail(); } catch (MiioLight.LightException expected) {}
    }
    @Test public void onlyPrivateIpv4AddressesAreAccepted() {
        assertTrue(MiioLight.validAddress("192.168.1.15")); assertTrue(MiioLight.validAddress("10.0.0.2")); assertTrue(MiioLight.validAddress("172.20.1.1"));
        assertFalse(MiioLight.validAddress("8.8.8.8")); assertFalse(MiioLight.validAddress("172.32.0.1")); assertFalse(MiioLight.validAddress("192.168.1.256"));
        assertFalse(MiioLight.validAddress("lamp.local")); assertFalse(MiioLight.validAddress(null));
    }
}
