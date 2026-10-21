package wang.robby.console.android;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import org.json.JSONObject;
import java.util.Locale;

/**
 * Price changes the way Chinese market apps show them: up in red with ▲, down in green with ▼, flat without colour.
 * The arrow carries the direction too, so it does not rest on colour alone.
 */
final class MarketChange {
    private MarketChange() {}
    /** -1, 0 or 1; 0 also when the value is missing or not a finite number. */
    static int direction(JSONObject p, String key) {
        Object v = p.opt(key); if (!(v instanceof Number)) return 0; double d = ((Number) v).doubleValue();
        return !Double.isFinite(d) || Math.abs(d) < 0.005 ? 0 : d > 0 ? 1 : -1;
    }
    /** "▲ 1.23%", "▼ 0.45%", "0.00%", or null when the value is missing. */
    static String text(JSONObject p, String key) {
        Object v = p.opt(key); if (!(v instanceof Number) || !Double.isFinite(((Number) v).doubleValue())) return null;
        double d = ((Number) v).doubleValue(); int dir = direction(p, key);
        String n = String.format(Locale.ROOT, "%.2f%%", Math.abs(d));
        return dir > 0 ? "▲ " + n : dir < 0 ? "▼ " + n : n;
    }
    static int color(int direction, ConsoleTheme t) { return direction > 0 ? t.danger : direction < 0 ? t.success : t.muted; }
    /** Appends "label value" with the value coloured; nothing when the value is missing. */
    static void append(SpannableStringBuilder b, String label, JSONObject p, String key, ConsoleTheme t) {
        String value = text(p, key); if (value == null) return;
        if (b.length() > 0) b.append(" · ");
        if (!label.isEmpty()) b.append(label).append(' ');
        int start = b.length(); b.append(value);
        b.setSpan(new ForegroundColorSpan(color(direction(p, key), t)), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    /** What a screen reader says: the arrows become 上涨 / 下跌. */
    static String speakable(String text) { return text.replace("▲ ", "上涨 ").replace("▼ ", "下跌 "); }
}
