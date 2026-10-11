package wang.robby.console.android.auth;

/** RFC 8628 timing, with a monotonic clock supplied by the caller. */
final class DevicePollPolicy {
    static final String GRANT = "urn:ietf:params:oauth:grant-type:device_code";
    private final long deadline;
    private long next;
    private int interval;
    DevicePollPolicy(int seconds, int interval, long now) throws Exception {
        OAuthGuards.require(seconds > 0 && seconds <= 900 && interval >= 5 && interval <= 900);
        this.interval = interval; deadline = now + seconds * 1000L; next = now + interval * 1000L;
    }
    static int lifetime(Object value) throws Exception {
        OAuthGuards.require(value instanceof Integer || value instanceof Long);
        long seconds = ((Number) value).longValue();
        OAuthGuards.require(seconds > 0 && seconds <= 900);
        return (int) seconds;
    }
    static int interval(Object value) throws Exception {
        if (value == null) return 5;
        OAuthGuards.require(value instanceof Integer || value instanceof Long);
        long seconds = ((Number) value).longValue();
        OAuthGuards.require(seconds > 0 && seconds <= 900);
        return (int) Math.max(5, seconds);
    }
    static String verificationUri(String value) throws Exception {
        OAuthGuards.endpoint(value, AuthSession.ISSUER);
        OAuthGuards.require(value.equals(AuthSession.ISSUER + "/consent/openid/device-authorization"));
        return value;
    }
    long delay(long now) throws Exception {
        if (now >= deadline) throw new Exception("设备登录已过期，请重新开始。");
        return Math.max(0, Math.min(next, deadline) - now);
    }
    void response(String error, long now) throws Exception {
        delay(now);
        if ("slow_down".equals(error)) interval = Math.min(900, interval + 5);
        else if (!"authorization_pending".equals(error)) {
            if ("access_denied".equals(error)) throw new Exception("已拒绝设备登录。");
            if ("expired_token".equals(error)) throw new Exception("设备登录已过期，请重新开始。");
            throw new Exception("设备登录未完成，请重新开始。");
        }
        next = now + interval * 1000L;
    }
    void timeout(long now) throws Exception { delay(now); interval = Math.min(900, interval * 2); next = now + interval * 1000L; }
}
