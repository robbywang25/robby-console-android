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
        new Entry("0.10.0", "2026-10-15", "Token 额度与网页同款排版",
            "接入 tokens.robby.wang：各 AI 账号的剩余额度、重置倒计时、点数与重置卡。",
            "额度快用完、或即将重置却还剩很多时会提醒；过期读数只作参考，不当成当前余额。",
            "看板可添加 Token 额度卡片；服务端开放读取权限后即可显示，登录不受影响。",
            "访问统计和 OpenAI 用量新增每日趋势图，各功能页按网页布局先给关键数字。"),
        new Entry("0.9.0", "2026-10-15", "自己组合的看板",
            "总览只保留「看板」和「常用」两块。",
            "看板可以自由组合服务状态、提醒、实例、域名、OpenAI、访问、OKX、剪贴板和行情卡片。",
            "点「编辑」开关卡片、调整顺序，随时可以恢复默认组合。",
            "卡片点一下进入对应详情；某个来源读取失败只影响它自己的卡片。"),
        new Entry("0.8.0", "2026-10-15", "改名 God，支持个人头像",
            "应用名称改为 God。",
            "在「我」里点头像即可从相册选择个人头像，「我」这一栏也会显示它；头像只保存在本机。",
            "总览把三项核心数字放进一张卡片，状态、提醒和快照时间收成一行标签。",
            "去掉总览里与底部导航重复的「全部功能」，常用入口更紧凑。"),
        new Entry("0.7.0", "2026-10-15", "应用内登录与紧凑页面",
            "Passkey 登录在应用内的登录页完成，验证后直接回到 God 工作台。",
            "应用图标改为黑底满版，在 Pixel 等桌面上不再是白底上的小图标。",
            "各功能页改成紧凑列表：一行一条，点开看完整字段，一屏能看到更多内容。",
            "汇总数字改为多列排布，数据来源合并成一行标签，空状态只占一行。"),
        new Entry("0.6.0", "2026-10-15", "运行状态一眼看清",
            "运行状态页先给结论：全部符合预期，或有几项需要关注，并写明检测时间。",
            "公开页面、私有入口和官方上游分组显示，状态名称与网页版一致。",
            "点结论里的问题项直接跳到对应行；每行可以展开看 DNS、HTTP 状态、耗时和上游组件。",
            "停留在这一页时每 60 秒自动刷新，切到后台就暂停。"),
        new Entry("0.5.0", "2026-10-15", "在 App 里直接更新",
            "打开「我 → 关于」会自动检查新版本，有新版本时点「立即更新」就在 App 里下载安装。",
            "安装包下载后先核对校验值和签名，再交给系统确认安装，登录和数据都保留。",
            "第一次更新需要在系统设置里允许本应用安装应用，返回后会自动继续。"),
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
