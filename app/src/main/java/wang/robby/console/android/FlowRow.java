package wang.robby.console.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Lays children left to right and wraps to a new line when the width runs out; used for compact chip rows. */
@SuppressLint("ViewConstructor") // Built in code with its spacing; never inflated from XML.
final class FlowRow extends ViewGroup {
    private final int gapX, gapY;
    FlowRow(Context context, int gapX, int gapY) { super(context); this.gapX = gapX; this.gapY = gapY; }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int max = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight(), x = 0, y = 0, line = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i); if (c.getVisibility() == GONE) continue;
            c.measure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            if (x > 0 && x + c.getMeasuredWidth() > max) { x = 0; y += line + gapY; line = 0; }
            x += c.getMeasuredWidth() + gapX; line = Math.max(line, c.getMeasuredHeight());
        }
        setMeasuredDimension(resolveSize(MeasureSpec.getSize(widthSpec), widthSpec), resolveSize(y + line + getPaddingTop() + getPaddingBottom(), heightSpec));
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int max = r - l - getPaddingLeft() - getPaddingRight(), x = 0, y = 0, line = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i); if (c.getVisibility() == GONE) continue;
            if (x > 0 && x + c.getMeasuredWidth() > max) { x = 0; y += line + gapY; line = 0; }
            int left = getPaddingLeft() + x, top = getPaddingTop() + y;
            c.layout(left, top, left + c.getMeasuredWidth(), top + c.getMeasuredHeight());
            x += c.getMeasuredWidth() + gapX; line = Math.max(line, c.getMeasuredHeight());
        }
    }
}
