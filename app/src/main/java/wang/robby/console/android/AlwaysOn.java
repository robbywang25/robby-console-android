package wang.robby.console.android;

import java.util.List;

/**
 * 我 → 外观 → 常亮展示: while God is in front the screen does not go dark, so a board or page can stay up on a stand.
 * After the chosen time without a touch the page gives way to pure black at the lowest brightness, which leaves an
 * OLED panel's pixels off so nothing static burns in; a touch brings the page back. Pure Java for JVM tests: the
 * saved choice and its words. The setting belongs to this phone and is never written into the account document.
 */
final class AlwaysOn {
    private AlwaysOn() {}
    static final String KEY = "alwaysOn", SAVER = "alwaysOnSaver";
    /** How long a page may stay up untouched before the black screen, in seconds; there is deliberately no 从不. */
    static final List<Integer> SAVER_CHOICES = List.of(30, 60, 120, 300, 600, 900, 1800, 3600);
    static final int SAVER_DEFAULT = 300;

    /** The saved idle time, or the default when nothing (or a value no longer offered) is saved. */
    static int saver(int saved) { return SAVER_CHOICES.contains(saved) ? saved : SAVER_DEFAULT; }
    static long saverMillis(int saved) { return saver(saved) * 1000L; }
    static String saverLabel(int seconds) {
        int s = saver(seconds);
        return s < 60 ? s + " 秒" : s < 3600 ? s / 60 + " 分钟" : s / 3600 + " 小时";
    }
    static String detail(boolean on) { return on ? "God 在前台时屏幕不熄灭" : "关闭 · 按系统设置自动熄屏"; }
    static String saverDetail(int seconds) { return "无操作 " + saverLabel(seconds) + "后显示纯黑画面，轻触恢复"; }
    /** The line under 外观 on 我; empty while it is off. */
    static String summary(boolean on, int seconds) { return on ? " · 常亮，" + saverLabel(seconds) + "后屏保" : ""; }
}
