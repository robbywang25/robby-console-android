package wang.robby.console.android;

import java.time.LocalDate;
import java.util.List;

/**
 * User-facing release notes shipped inside the APK, newest first. Written for the person using the app:
 * what changed for them, in plain words, at most six lines per version. Build, test and protocol details
 * stay in the GitHub release notes. Dates are the China-time publish dates of the GitHub releases.
 */
final class ReleaseNotes {
    private ReleaseNotes() {}
    static final class Entry {
        final String version, date, headline; final List<String> items;
        Entry(String version, String date, String headline, String... items) { this.version = version; this.date = date; this.headline = headline; this.items = List.of(items); }
    }
    static final List<Entry> ALL = List.of(
        new Entry("0.4.0", "2026-10-15", "全新 Liquid Glass 外观",
            "界面换上玻璃质感：天空渐变背景、半透明卡片，底部导航悬浮并显示文字。",
            "首页先告诉你服务是否都在线、有几项需要处理，点数字直接看详情。",
            "状态旁增加颜色提示，正常、需关注和不可用一眼区分。",
            "账本的「记一笔」移到右上角「+」，长列表里随时可记。",
            "「设置」改名为「我」，新增这一页更新记录。",
            "登录过期时自动续期，并修复账本和运行状态的若干问题。"),
        new Entry("0.3.6", "2026-10-12", "记住你看到哪里",
            "每个 Tab 记住各自打开的页面和阅读位置，切回来仍在原处。",
            "从详情返回、刷新或从后台回来，不再跳回顶部。",
            "账本按「账目 → 账本列表 → 功能列表」逐层返回。"),
        new Entry("0.3.5", "2026-10-12", "腾讯蓝与更矮的底栏",
            "主要按钮改用腾讯蓝，更容易找到。",
            "底部导航矮了 20dp，内容区更大。"),
        new Entry("0.3.4", "2026-10-12", "黑白主题与 God 标志",
            "底部导航固定贴底，浅色白底、深色黑底。",
            "应用图标和总览使用 God 网页的光环标志。",
            "选中的 Tab 用实心图标表示。"),
        new Entry("0.3.3", "2026-10-12", "纯图标导航",
            "底部导航只保留图标，长按可以看到名称。",
            "导航栏采用和账本网页一致的玻璃样式。"),
        new Entry("0.3.2", "2026-10-12", "暖金界面与数据修复",
            "修正暖金界面里导航文字的对齐。",
            "公众号发布、小程序目录和 Prompt 配置适配新的数据格式。",
            "配合服务端修复，总览可以正常读取。"),
        new Entry("0.3.1", "2026-10-12", "暖金界面",
            "界面改用账本网页的暖金配色：浅色暖米色，深色深蓝配柔金。",
            "卡片和列表更紧凑，金额和人民币参考合计更醒目。"),
        new Entry("0.2.2", "2026-10-11", "登录更稳",
            "登录时的校验更严格，会话更安全。",
            "网络或服务临时出错时可以重试，不会被直接退出登录。",
            "升级后需要重新登录一次。"),
        new Entry("0.2.0", "2026-10-11", "没有本机 Passkey 也能登录",
            "新增「通过已有设备授权」：在已登录的电脑或手机上输入一次性代码即可登录。",
            "原有的 Passkey 登录保持不变。"),
        new Entry("0.1.0", "2026-10-11", "第一个版本",
            "用原生界面查看访问统计、资产、服务状态和旅行账本等 22 项功能。",
            "支持多账本记账和人员管理，提交前先预览确认。",
            "用 Passkey 登录，会话加密保存在本机。")
    );
    static Entry find(String version) { for (Entry e : ALL) if (e.version.equals(version)) return e; return null; }
    /**
     * Whether the red dot should show. Unread only when the installed version has notes and they were not
     * opened since this version arrived. A fresh install starts read; an upgrade from a build that never
     * recorded anything ({@code seen == null}) counts as unread.
     */
    static boolean unread(String seen, String current, boolean upgraded) {
        if (current == null || find(current) == null) return false;
        return seen == null ? upgraded : !seen.equals(current);
    }
    /** "10月15日"; the year is shown only when it is not the current year. */
    static String dateLabel(String iso, LocalDate today) {
        LocalDate d = LocalDate.parse(iso);
        return (d.getYear() == today.getYear() ? "" : d.getYear() + "年") + d.getMonthValue() + "月" + d.getDayOfMonth() + "日";
    }
}
