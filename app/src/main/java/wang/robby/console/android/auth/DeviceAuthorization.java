package wang.robby.console.android.auth;

/** Sensitive display model. Show only on the protected device screen; never log or persist. */
public final class DeviceAuthorization {
    public final String verificationUri;
    public final String userCode;
    public final long expiresAt;
    public final int interval;
    DeviceAuthorization(String verificationUri, String userCode, long expiresAt, int interval) {
        this.verificationUri = verificationUri; this.userCode = userCode;
        this.expiresAt = expiresAt; this.interval = interval;
    }
    @Override public String toString() { return "DeviceAuthorization{redacted}"; }
}
