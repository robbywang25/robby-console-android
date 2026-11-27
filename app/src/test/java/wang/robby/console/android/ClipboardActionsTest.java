package wang.robby.console.android;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public final class ClipboardActionsTest {
    @Test public void createPublishAndTrashPayloadsMatchSotConfirmations() throws Exception {
        String text = "hello clipboard";
        String expectedHash = ClipboardActions.sha256Hex(text);
        JSONObject create = ClipboardActions.createBody(text, 7);
        assertEquals(1, create.getInt("schemaVersion"));
        assertEquals(7, create.getInt("ttlDays"));
        assertEquals("create-private:7:" + expectedHash, create.getString("confirmation"));

        String id = "0123456789abcdef0123456789abcdef";
        JSONObject pub = ClipboardActions.visibilityBody(id, 3, expectedHash, true);
        assertEquals("publish:" + id + ":3:" + expectedHash, pub.getString("confirmation"));

        JSONObject unpub = ClipboardActions.visibilityBody(id, 4, expectedHash, false);
        assertEquals("unpublish:" + id + ":4:" + expectedHash, unpub.getString("confirmation"));

        JSONObject trash = ClipboardActions.trashBody(id, 4, expectedHash, false);
        assertEquals("trash:" + id + ":4:" + expectedHash + ":0", trash.getString("confirmation"));
        assertEquals("clipboard/" + id + "/actions/trash", ClipboardActions.trashRoute(id));
        assertTrue(MobileApi.writable(AccountCenter.CLIPBOARD_CREATE));
        assertTrue(MobileApi.writable(ClipboardActions.visibilityRoute(id)));
        assertTrue(MobileApi.writable(ClipboardActions.trashRoute(id)));
    }

    @Test public void insufficientScopeReturnsFriendlyChineseGuidance() {
        String msg = ClipboardActions.explainError(new MobileApi.ApiError(403, "insufficient_scope", "forbidden"));
        assertTrue(msg.contains("403 insufficient_scope"));
    }
}
