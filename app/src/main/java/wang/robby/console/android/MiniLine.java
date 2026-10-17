package wang.robby.console.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * The 近 1 小时 balance curve of a Token tile, as tokens.robby.wang draws it: remaining over the real sample times,
 * falling with use and jumping back up at a reset. Percent readings use a fixed 0–100 scale; other units fit their range.
 * Missing samples break the line instead of being drawn as zero.
 */
@SuppressLint("ViewConstructor") // Built in code with its values; never inflated from XML.
final class MiniLine extends View {
    private final double[] values; private final boolean percent;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), base = new Paint(Paint.ANTI_ALIAS_FLAG); private final Path path = new Path();
    MiniLine(Context context, double[] values, boolean percent, int color, int faint, String description) {
        super(context); this.values = values; this.percent = percent;
        float d = context.getResources().getDisplayMetrics().density;
        line.setColor(color); line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(1.6f * d); line.setStrokeJoin(Paint.Join.ROUND); line.setStrokeCap(Paint.Cap.ROUND);
        base.setColor(faint); base.setStrokeWidth(1f);
        setContentDescription(description); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    @Override protected void onDraw(Canvas c) {
        int n = values.length; if (n < 2) return;
        double lo = percent ? 0 : Double.MAX_VALUE, hi = percent ? 100 : -Double.MAX_VALUE;
        if (!percent) for (double v : values) if (!Double.isNaN(v)) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
        if (hi < lo) return; if (hi == lo) { hi = lo + 1; lo = lo - 1; }
        float left = getPaddingLeft(), top = getPaddingTop() + line.getStrokeWidth(), w = getWidth() - getPaddingLeft() - getPaddingRight(), h = getHeight() - getPaddingTop() - getPaddingBottom() - 2 * line.getStrokeWidth();
        c.drawLine(left, top + h, left + w, top + h, base);
        path.reset(); boolean open = false;
        for (int i = 0; i < n; i++) {
            double v = values[i]; if (Double.isNaN(v)) { open = false; continue; }
            float x = left + w * i / (n - 1), y = top + (float) ((hi - Math.max(lo, Math.min(hi, v))) / (hi - lo)) * h;
            if (open) path.lineTo(x, y); else { path.moveTo(x, y); open = true; }
        }
        c.drawPath(path, line);
    }
}
