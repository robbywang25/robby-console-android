package wang.robby.console.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.view.View;

/**
 * The 灵动岛 status style: a pure-black capsule around the camera cutout with the time on its left and the battery
 * on its right. On an OLED panel the hole vanishes into the black. It draws only; touches pass through to the page.
 */
@SuppressLint("ViewConstructor") // Built in code; never inflated from XML.
final class StatusIsland extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF base = new RectF(), shape = new RectF(); private int holeLeft, holeRight, compact; private boolean hasHole;
    private String leftItem = "time", rightItem = "battery";
    private String left = "", right = "";
    // Time and battery are read once a minute, on the minute, not on every frame.
    private final Runnable tick = () -> { refresh(); schedule(); };
    /** Both sides' text, and the capsule widened symmetrically (like the iPhone's) when a side needs more room. */
    private void refresh() {
        long now = System.currentTimeMillis(); java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        int level = "battery".equals(leftItem) || "battery".equals(rightItem) ? battery() : -1;
        long tibo = "tibo".equals(leftItem) || "tibo".equals(rightItem) ? GodWidget.current(getContext(), now).deadline : 0;
        left = StatusBarStyle.islandText(leftItem, now, zone, level, tibo); right = StatusBarStyle.islandText(rightItem, now, zone, level, tibo);
        shape.set(base);
        if (!base.isEmpty() && left.isEmpty() && right.isEmpty() && compact > 0 && compact < base.width()) {
            // Nothing to show: the narrower resting island.
            float c = base.centerX(); shape.left = c - compact / 2f; shape.right = c + compact / 2f;
        } else if (!base.isEmpty()) {
            float r = base.height() / 2f, gap = base.height() * .3f, centre = base.centerX();
            float need = Math.max(holeRight - centre, centre - holeLeft) + gap + Math.max(text.measureText(left), text.measureText(right)) + r * .9f;
            if (need > base.width() / 2f) { float half = Math.min(need, Math.min(centre, getWidth() > 0 ? getWidth() - centre : need)); shape.left = centre - half; shape.right = centre + half; }
        }
        invalidate();
    }
    /** The items chosen for each side in 我 → 外观. */
    void show(String leftItem, String rightItem) { this.leftItem = leftItem; this.rightItem = rightItem; refresh(); }

    StatusIsland(Context context) {
        super(context); fill.setColor(0xff000000); text.setColor(0xffffffff);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); text.setFontFeatureSettings("tnum");
        text.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 12, context.getResources().getDisplayMetrics()));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); setWillNotDraw(false);
    }
    /** The capsule's rectangle, the hole's span the text keeps clear of, and the width it rests at with no text. */
    void place(int[] island, int[] cutout, int compactWidth) {
        base.set(island[0], island[1], island[2], island[3]); compact = compactWidth;
        hasHole = cutout != null && cutout[2] > cutout[0] && cutout[3] > cutout[1];
        int hole = hasHole ? Math.min(cutout[2] - cutout[0], cutout[3] - cutout[1]) : 0, mid = hasHole ? (cutout[0] + cutout[2]) / 2 : (island[0] + island[2]) / 2;
        holeLeft = mid - hole / 2; holeRight = mid + hole / 2;
        refresh();
    }
    private void schedule() { removeCallbacks(tick); long now = System.currentTimeMillis(); postDelayed(tick, 60_000 - now % 60_000 + 50); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); refresh(); schedule(); }
    @Override protected void onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow(); }

    private int battery() {
        IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        // A sticky read with no receiver: nothing stays registered.
        Intent b = android.os.Build.VERSION.SDK_INT >= 33 ? getContext().registerReceiver(null, f, Context.RECEIVER_NOT_EXPORTED) : getContext().registerReceiver(null, f);
        if (b == null) return -1; int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        return level < 0 || scale <= 0 ? -1 : Math.round(level * 100f / scale);
    }
    @Override protected void onDraw(Canvas canvas) {
        if (shape.isEmpty()) return;
        float r = shape.height() / 2f; canvas.drawRoundRect(shape, r, r, fill);
        float baseline = shape.centerY() - (text.descent() + text.ascent()) / 2f, inner = r * .9f;
        // Each label is centred in the space between the capsule's rounded end and the hole.
        float leftMid = (shape.left + inner + holeLeft) / 2f, rightMid = (holeRight + shape.right - inner) / 2f;
        if (!left.isEmpty()) canvas.drawText(left, leftMid - text.measureText(left) / 2f, baseline, text);
        if (!right.isEmpty()) canvas.drawText(right, rightMid - text.measureText(right) / 2f, baseline, text);
    }
}
