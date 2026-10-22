package wang.robby.console.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Bars already scaled to 0..1. A missing value is an empty slot. {@code highlight} &lt; 0 paints every bar in full;
 * otherwise only that index is full and the rest are faded. The description is the accessibility text.
 */
@SuppressLint("ViewConstructor") // Built in code with its values; never inflated from XML.
final class WechatBars extends View {
    private final double[] values; private final int color, highlight;
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    WechatBars(Context context, double[] values, int color, int baseline, int highlight, String description) {
        super(context); this.values = values == null ? new double[0] : values; this.color = color; this.highlight = highlight;
        base.setColor(baseline); base.setStrokeWidth(1f);
        setContentDescription(description); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    @Override protected void onDraw(Canvas canvas) {
        int n = values.length; if (n == 0) return;
        float width = getWidth() - getPaddingLeft() - getPaddingRight(), height = getHeight() - getPaddingTop() - getPaddingBottom();
        if (width <= 0 || height <= 0) return;
        float slot = width / n, gap = Math.min(slot * 0.28f, 6f), left = getPaddingLeft(), bottom = getPaddingTop() + height;
        canvas.drawLine(left, bottom, left + width, bottom, base);
        for (int i = 0; i < n; i++) {
            double v = values[i]; if (v <= 0) continue;
            float top = Math.min(bottom - (float) v * height, bottom - 2f);
            rect.set(left + i * slot + gap / 2f, top, left + (i + 1) * slot - gap / 2f, bottom);
            bar.setColor(highlight < 0 || i == highlight ? color : (color & 0x00ffffff) | 0x99000000);
            float radius = Math.min(rect.width() / 2f, 3f); canvas.drawRoundRect(rect, radius, radius, bar);
        }
    }
}
