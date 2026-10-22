package wang.robby.console.android;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

/** What is on screen reads again at a steady pace: Token 额度 as the server asks, the rest on fixed intervals. */
public final class RefreshPaceTest {
    @Test public void boardCardsFollowTheirSource() throws Exception {
        assertEquals(20_000L, RefreshPace.board("tokens/quotas", new JSONObject().put("refreshAfterSeconds", 20)));
        assertEquals(30_000L, RefreshPace.board("tokens/quotas", null));
        assertEquals(60_000L, RefreshPace.board("overview", new JSONObject().put("refreshAfterSeconds", 20)));
        assertEquals(60_000L, RefreshPace.board("wechat/digest", null));
    }
    @Test public void onlyStatusAndTokenPagesRefreshThemselves() throws Exception {
        assertEquals(30_000L, RefreshPace.page("status", null));
        assertEquals(45_000L, RefreshPace.page("tokens", new JSONObject().put("refreshAfterSeconds", 45)));
        assertEquals("重置日历 keeps the chosen day until pulled", 0L, RefreshPace.page("quota-calendar", null));
        assertEquals(0L, RefreshPace.page("travel-ledger", null));
        assertEquals(0L, RefreshPace.page(null, null));
    }
    @Test public void anOverdueReadIsDueAtOnce() {
        assertEquals(20_000L, RefreshPace.wait(100_000L, 30_000L, 110_000L));
        assertEquals(0L, RefreshPace.wait(100_000L, 30_000L, 130_000L));
        assertEquals(0L, RefreshPace.wait(100_000L, 30_000L, 500_000L));
    }
}
