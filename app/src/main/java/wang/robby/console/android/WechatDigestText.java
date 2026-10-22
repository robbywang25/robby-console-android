package wang.robby.console.android;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Formatting for a digest that has already been validated. No Android types, so the rules can be unit tested. */
final class WechatDigestText {
    private WechatDigestText() {}
    static final String[] TYPE_NAMES = {"文字", "图片", "语音", "视频", "链接", "文件", "其他"};

    static String count(long n) { return java.text.NumberFormat.getIntegerInstance(Locale.SIMPLIFIED_CHINESE).format(n); }

    /** Same steps as {@code AccountCenter.ago}. A future instant reads as 刚刚. */
    static String ago(Instant then, Instant now) {
        long s = Math.max(0, now.getEpochSecond() - then.getEpochSecond());
        if (s < 120) return "刚刚";
        if (s < 3600) return s / 60 + " 分钟前";
        if (s < 86400) return s / 3600 + " 小时前";
        if (s < 30 * 86400L) return s / 86400 + " 天前";
        return DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault()).format(then);
    }
    /** More than two hours after generatedAt. Exactly two hours is still current. */
    static boolean stale(Instant generatedAt, Instant now) { return now.isAfter(generatedAt.plus(2, ChronoUnit.HOURS)); }

    /** Archive clock in the digest timezone, so it matches the chart's 今天. */
    static String header(WechatDigest.Doc d, Instant now) {
        String archive = DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(d.zone).format(d.archiveTime);
        String line = "数据截至 " + archive + " · 生成于 " + ago(d.generatedAt, now);
        if (stale(d.generatedAt, now)) line += " · 数据可能不是最新";
        return line;
    }
    /** messages/7, one decimal, half up. A zero fraction is omitted: 7 → "1", 8 → "1.1". */
    static String dailyAverage(long messages) {
        long tenths = (messages * 10 + 3) / 7;
        long whole = tenths / 10; int frac = (int) (tenths % 10);
        return frac == 0 ? count(whole) : count(whole) + "." + frac;
    }
    static String kindLabel(String kind) { return "group".equals(kind) ? "群" : "private".equals(kind) ? "私聊" : ""; }
    static String boardCaption(long messages7d, String name) {
        String line = "今天 · 近 7 天日均 " + dailyAverage(messages7d);
        if (name != null && !name.isEmpty()) line += " · " + name;
        return line;
    }
    static String conversationMeta(String kind, long sent7d, Instant lastAt, Instant now) {
        return kindLabel(kind) + " · 发出 " + count(sent7d) + " · " + ago(lastAt, now);
    }
    static String dayLabel(String date) {
        try { LocalDate d = LocalDate.parse(date); return d.getMonthValue() + "月" + d.getDayOfMonth() + "日"; }
        catch (RuntimeException e) { return date; }
    }
    /** First strict maximum, or -1 when there is nothing to compare. Ties stay with the earlier index. */
    static int peakIndex(long[] values) {
        if (values == null || values.length == 0) return -1;
        int at = 0; for (int i = 1; i < values.length; i++) if (values[i] > values[at]) at = i;
        return at;
    }
    /** Bar heights as a fraction of the largest value. A zero maximum stays all zeros. */
    static double[] normalize(long[] values) {
        if (values == null) return new double[0];
        double[] out = new double[values.length]; long max = 0;
        for (long v : values) if (v > max) max = v;
        if (max == 0) return out;
        for (int i = 0; i < values.length; i++) out[i] = values[i] / (double) max;
        return out;
    }

    static final class Share {
        final String name; final long count; final int tenths; final String percent; final double fraction;
        Share(String name, long count, int tenths, String percent, double fraction) {
            this.name = name; this.count = count; this.tenths = tenths; this.percent = percent; this.fraction = fraction;
        }
    }
    /**
     * Shares in tenths of a percent (69.7% is 697) by largest remainder, so they sum to 1000 when the total is
     * positive. A tie goes to the earlier index. A zero total stays all 0.0% and does not sum to 1000.
     */
    static List<Share> shares(long[] counts) {
        int n = counts == null ? 0 : counts.length;
        long total = 0; for (int i = 0; i < n; i++) total += counts[i];
        int[] tenths = new int[n]; int[] rem = new int[n]; int sum = 0;
        if (total > 0) {
            for (int i = 0; i < n; i++) { long prod = counts[i] * 1000L; tenths[i] = (int) (prod / total); rem[i] = (int) (prod % total); sum += tenths[i]; }
            boolean[] used = new boolean[n];
            for (int left = 1000 - sum; left > 0; left--) {
                int best = -1;
                for (int i = 0; i < n; i++) if (!used[i] && (best < 0 || rem[i] > rem[best])) best = i;
                if (best < 0) break;
                used[best] = true; tenths[best]++;
            }
        }
        List<Share> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String name = i < TYPE_NAMES.length ? TYPE_NAMES[i] : "";
            int whole = tenths[i] / 10, frac = tenths[i] % 10;
            out.add(new Share(name, counts[i], tenths[i], whole + "." + frac + "%", total == 0 ? 0 : counts[i] / (double) total));
        }
        return out;
    }
}
