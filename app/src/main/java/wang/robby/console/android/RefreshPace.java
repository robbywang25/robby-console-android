package wang.robby.console.android;

import org.json.JSONObject;

/**
 * How often what is on screen reads again while God is in front. Token 额度 follows the server's refreshAfterSeconds
 * ({@link TokensModel#refreshMs}); everything else has a fixed pace. The home screen widgets are fed by the background
 * check in {@link TiboWatch}, which has its own interval.
 */
final class RefreshPace {
    private RefreshPace() {}
    /** 总览 cards other than Token 额度 and 重置日历. */
    static final long BOARD_MS = 60_000L;
    /** 运行状态 while it is the visible page. */
    static final long STATUS_MS = 30_000L;

    /** A 总览 card's path: tokens/quotas at the server's pace, every other path once a minute. */
    static long board(String path, JSONObject data) { return "tokens/quotas".equals(path) ? TokensModel.refreshMs(data) : BOARD_MS; }
    /** A page that refreshes itself while it is on screen; 0 for pages that refresh only when pulled. */
    static long page(String featureId, JSONObject data) {
        if ("status".equals(featureId)) return STATUS_MS;
        if ("tokens".equals(featureId)) return TokensModel.refreshMs(data);
        return 0;
    }
    /** Milliseconds until a read finished at {@code readAt} is due again; 0 when it already is. */
    static long wait(long readAt, long every, long now) { return Math.max(0, readAt + every - now); }
}
