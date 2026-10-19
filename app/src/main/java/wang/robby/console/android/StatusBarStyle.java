package wang.robby.console.android;

import java.util.List;

/**
 * 我 → 外观 → 隐藏状态栏, four ways: keep it; hide it but keep the page where it was; hide it and move the page up to
 * the screen edge; or hide it and wrap the camera cutout in a pure-black island that shows the time and battery, so on
 * an OLED panel the punch-hole disappears into the black. Pure Java for JVM tests: the choice, the top spacing and the
 * island's rectangle.
 */
final class StatusBarStyle {
    private StatusBarStyle() {}
    static final String KEY = "statusBarStyle", LEGACY = "hideStatusBar";
    static final String SHOWN = "shown", HIDE = "hide", HIDE_UP = "hideUp", ISLAND = "island";
    static final List<String> ALL = List.of(SHOWN, HIDE, HIDE_UP, ISLAND);

    /** The saved choice; 0.22.2's on/off switch maps to 隐藏并上移, which is what it did. */
    static String resolve(String saved, boolean legacyHidden) { return saved != null && ALL.contains(saved) ? saved : legacyHidden ? HIDE_UP : SHOWN; }
    static boolean hides(String style) { return !SHOWN.equals(style); }
    static String label(String style) {
        return switch (style) { case HIDE -> "只隐藏"; case HIDE_UP -> "隐藏并上移"; case ISLAND -> "灵动岛"; default -> "不隐藏"; };
    }
    static String detail(String style) {
        return switch (style) {
            case HIDE -> "状态栏隐藏，页面位置不变";
            case HIDE_UP -> "状态栏隐藏，页面上移到屏幕顶端";
            case ISLAND -> "黑色岛包住摄像头开孔，显示时间和电量";
            default -> "显示系统状态栏";
        };
    }

    /**
     * Where a page starts. {@code edge} is a tab root (content scrolls under the top), otherwise a detail page whose
     * fixed header must stay clear of the camera cutout.
     */
    static int top(String style, boolean edge, int statusHeight, int cutoutTop, int visibleTop, int islandBottom, int gap) {
        switch (style) {
            case HIDE: return Math.max(statusHeight, cutoutTop);
            case HIDE_UP: return edge ? gap : Math.max(cutoutTop, gap);
            case ISLAND: return Math.max(statusHeight, islandBottom + gap);
            default: return visibleTop;
        }
    }

    /**
     * The island as {left, top, right, bottom}: a capsule centred on the top cutout, {@code pad} larger than the hole on
     * every side and {@code side} wider on each side for the time and battery. Without a cutout it sits centred at
     * {@code fallbackTop}.
     */
    static int[] island(int screenWidth, int[] cutout, int side, int pad, int minHeight, int fallbackTop) {
        if (cutout == null || cutout[2] <= cutout[0] || cutout[3] <= cutout[1]) {
            int width = 2 * side + minHeight, left = (screenWidth - width) / 2;
            return new int[]{left, fallbackTop, left + width, fallbackTop + minHeight};
        }
        int height = Math.max(minHeight, cutout[3] - cutout[1] + 2 * pad), centreY = (cutout[1] + cutout[3]) / 2;
        int top = Math.max(0, centreY - height / 2), width = cutout[2] - cutout[0] + 2 * pad + 2 * side, centreX = (cutout[0] + cutout[2]) / 2;
        int left = Math.max(0, centreX - width / 2), right = Math.min(screenWidth, left + width);
        return new int[]{left, top, right, top + height};
    }
}
