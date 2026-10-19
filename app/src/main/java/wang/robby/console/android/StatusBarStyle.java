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
     * Where the camera hole really is, as {left, top, right, bottom}. Devices that describe the true shape of the
     * cutout (Pixels draw the round hole) give a roughly round outline: use it. Others declare a band from the screen
     * edge through the status bar (OPPO's is 84 x 160 px): then the hole is taken as a circle of the band's narrower
     * side, centred in it. Null when there is no top cutout.
     */
    static int[] hole(int[] declared, int[] outline) {
        if (outline != null && outline[2] > outline[0] && outline[3] > outline[1]) {
            int w = outline[2] - outline[0], h = outline[3] - outline[1];
            if (h * 10 <= w * 13 && w * 10 <= h * 13) return outline;
        }
        if (declared == null || declared[2] <= declared[0] || declared[3] <= declared[1]) return null;
        int d = Math.min(declared[2] - declared[0], declared[3] - declared[1]), cx = (declared[0] + declared[2]) / 2, cy = (declared[1] + declared[3]) / 2;
        return new int[]{cx - d / 2, cy - d / 2, cx - d / 2 + d, cy - d / 2 + d};
    }

    /** iPhone Pro Max Dynamic Island at rest: 126 × 37.33 pt on a 440 pt wide screen, its top 11 pt down. */
    static final float ISLAND_WIDTH = 126f / 440f, ISLAND_HEIGHT = 37.33f / 440f, ISLAND_TOP = 11f / 440f;
    /**
     * With nothing to show on either side the island narrows to the smaller Dynamic Island reported for the iPhone 18
     * Pro Max (about 96 pt on a 440 pt screen; Apple has not published it, so this is an estimate), never narrower than
     * the hole plus {@code pad} on each side.
     */
    static final float ISLAND_COMPACT_WIDTH = 96f / 440f;
    static int compactWidth(int screenWidth, int[] hole, int pad) {
        int width = Math.round(screenWidth * ISLAND_COMPACT_WIDTH);
        return hole == null ? width : Math.max(width, hole[2] - hole[0] + 2 * pad);
    }

    /**
     * The island as {left, top, right, bottom}: a floating capsule in the iPhone Pro Max proportions, scaled to this
     * screen's width and centred on the camera hole from {@link #hole}, which always stays at least {@code pad}
     * inside the capsule. Without a cutout it sits centred where the iPhone's does.
     */
    static int[] island(int screenWidth, int[] cutout, int pad) {
        int width = Math.round(screenWidth * ISLAND_WIDTH), height = Math.round(screenWidth * ISLAND_HEIGHT);
        if (cutout == null || cutout[2] <= cutout[0] || cutout[3] <= cutout[1]) {
            int left = (screenWidth - width) / 2, top = Math.round(screenWidth * ISLAND_TOP);
            return new int[]{left, top, left + width, top + height};
        }
        int hole = Math.min(cutout[2] - cutout[0], cutout[3] - cutout[1]);
        height = Math.max(height, hole + 2 * pad); width = Math.max(width, height * 2);
        int centreX = (cutout[0] + cutout[2]) / 2, centreY = (cutout[1] + cutout[3]) / 2;
        int top = Math.max(0, centreY - height / 2), left = Math.max(0, Math.min(screenWidth - width, centreX - width / 2));
        return new int[]{left, top, left + width, top + height};
    }

    /** What each side of the island can show; chosen separately for left and right in 我 → 外观. */
    static final String LEFT_KEY = "islandLeft", RIGHT_KEY = "islandRight";
    static final List<String> ITEMS = List.of("time", "date", "weekday", "battery", "tibo", "none");
    static String item(String saved, boolean left) { return saved != null && ITEMS.contains(saved) ? saved : left ? "time" : "battery"; }
    static String itemLabel(String item) {
        return switch (item) { case "date" -> "日期"; case "weekday" -> "星期"; case "battery" -> "电量"; case "tibo" -> "Tibo 倒计时"; case "none" -> "不显示"; default -> "时间"; };
    }
    /**
     * The text for one side at {@code now}. {@code battery} is a percentage or -1; {@code tiboDeadline} is the public
     * reset or card announcement time the widget keeps, or 0. Empty when there is nothing to show.
     */
    static String islandText(String item, long now, java.time.ZoneId zone, int battery, long tiboDeadline) {
        java.time.ZonedDateTime t = java.time.Instant.ofEpochMilli(now).atZone(zone);
        switch (item) {
            case "date": return t.getMonthValue() + "/" + t.getDayOfMonth();
            case "weekday": return "周" + "一二三四五六日".charAt(t.getDayOfWeek().getValue() - 1);
            case "battery": return battery < 0 ? "" : battery + "%";
            case "tibo": { if (tiboDeadline <= now) return ""; long m = (tiboDeadline - now + 59_999) / 60_000; return "Tibo " + (m / 60) + ":" + String.format(java.util.Locale.ROOT, "%02d", m % 60); }
            case "none": return "";
            default: return t.getHour() + ":" + String.format(java.util.Locale.ROOT, "%02d", t.getMinute());
        }
    }
}
