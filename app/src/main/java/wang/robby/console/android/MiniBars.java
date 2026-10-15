package wang.robby.console.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Small daily bar chart for trend rows (visits, tokens). Bars share one scale from zero; a missing day is drawn as an
 * empty slot rather than a zero bar. The accessibility text states the range, the peak and the latest value.
 */
@SuppressLint("ViewConstructor") // Built in code with its values; never inflated from XML.
final class MiniBars extends View {
    private final double[] values; private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), base = new Paint(Paint.ANTI_ALIAS_FLAG); private final RectF r = new RectF();
    private final int highlight;
    MiniBars(Context context, double[] values, int color, int faint, String description) {
        super(context); this.values = values; bar.setColor(color); base.setColor(faint); base.setStrokeWidth(1f); highlight = color;
        setContentDescription(description); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    @Override protected void onDraw(Canvas c) {
        int n = values.length; if (n == 0) return;
        double max = 0; for (double v : values) if (!Double.isNaN(v)) max = Math.max(max, v);
        float w = getWidth() - getPaddingLeft() - getPaddingRight(), h = getHeight() - getPaddingTop() - getPaddingBottom();
        float slot = w / n, gap = Math.min(slot * 0.28f, 6f), left = getPaddingLeft(), bottom = getPaddingTop() + h;
        c.drawLine(left, bottom, left + w, bottom, base);
        for (int i = 0; i < n; i++) {
            double v = values[i]; if (Double.isNaN(v) || max <= 0) continue;
            float top = bottom - (float) (v / max) * h; if (v > 0) top = Math.min(top, bottom - 2f);
            r.set(left + i * slot + gap / 2, top, left + (i + 1) * slot - gap / 2, bottom);
            bar.setColor(i == n - 1 ? highlight : (highlight & 0x00ffffff) | 0x99000000);
            float radius = Math.min(r.width() / 2, 3f); c.drawRoundRect(r, radius, radius, bar);
        }
    }
}
