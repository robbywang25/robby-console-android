package wang.robby.console.android;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public class ClipboardRevealTest {
    private static final String ID = "0123456789abcdef0123456789abcdef";

    @Test public void revealRouteIsRegisteredForOneLowercaseHexItem() {
        assertEquals("clipboard/" + ID + "/reveal", MobileApi.checkedPath(ClipboardReveal.route(ID)));
        assertThrows(IllegalArgumentException.class, () -> ClipboardReveal.route("0123456789ABCDEF0123456789ABCDEF"));
        assertThrows(IllegalArgumentException.class, () -> ClipboardReveal.route("../reveal"));
        assertThrows(IllegalArgumentException.class, () -> MobileApi.checkedPath("clipboard/" + ID + "/delete"));
        assertThrows(IllegalArgumentException.class, () -> MobileApi.checkedPath("clipboard/abc/reveal"));
    }

    @Test public void contentIsTakenOnlyFromTheItemAskedFor() throws Exception {
        JSONObject ok = new JSONObject().put("apiVersion", "2026-07-01").put("item", new JSONObject().put("id", ID).put("content", "你好\nworld"));
        assertEquals("你好\nworld", ClipboardReveal.content(ok, ID));
        assertEquals("", ClipboardReveal.content(new JSONObject().put("item", new JSONObject().put("id", ID).put("content", "")), ID));
        JSONObject other = new JSONObject().put("item", new JSONObject().put("id", "f".repeat(32)).put("content", "x"));
        assertThrows(IllegalArgumentException.class, () -> ClipboardReveal.content(other, ID));
        JSONObject notText = new JSONObject().put("item", new JSONObject().put("id", ID).put("content", 42));
        assertThrows(IllegalArgumentException.class, () -> ClipboardReveal.content(notText, ID));
        assertThrows(IllegalArgumentException.class, () -> ClipboardReveal.content(new JSONObject(), ID));
        JSONObject huge = new JSONObject().put("item", new JSONObject().put("id", ID).put("content", "a".repeat(ClipboardReveal.MAX_CHARACTERS + 1)));
        assertThrows(IllegalArgumentException.class, () -> ClipboardReveal.content(huge, ID));
    }
}
