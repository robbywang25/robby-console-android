package wang.robby.console.android;

/**
 * 我 → 个人中心 → 个性签名: one short line shown under the name on 我's account card. Kept on this phone only (like a
 * preference, not account data), so it never leaves the device and survives signing out.
 */
final class Signature {
    private Signature() {}
    static final String KEY = "signature";
    static final int MAX = 30;
    /** One line: control characters and line breaks become spaces, runs of spaces collapse, at most MAX characters. */
    static String clean(String value) {
        if (value == null) return "";
        String one = value.replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]", " ").replaceAll("\\s+", " ").trim();
        int end = one.offsetByCodePoints(0, Math.min(MAX, one.codePointCount(0, one.length())));
        return one.substring(0, end).trim();
    }
}
