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
    private final RectF shape = new RectF(); private int holeLeft, holeRight; private boolean hasHole;
    private final java.text.SimpleDateFormat clock = new java.text.SimpleDateFormat("H:mm", java.util.Locale.ROOT);
    private String time = "", power = "";
    // Time and battery are read once a minute, on the minute, not on every frame.
    private final Runnable tick = () -> { refresh(); schedule(); };
    private void refresh() { time = clock.format(new java.util.Date()); int level = battery(); power = level < 0 ? "" : level + "%"; invalidate(); }

    StatusIsland(Context context) {
        super(context); fill.setColor(0xff000000); text.setColor(0xffffffff);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); text.setFontFeatureSettings("tnum");
        text.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 13, context.getResources().getDisplayMetrics()));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); setWillNotDraw(false);
    }
    /** The capsule's rectangle and, when there is one, the cutout's horizontal span the text keeps clear of. */
    void place(int[] island, int[] cutout) {
        shape.set(island[0], island[1], island[2], island[3]);
        hasHole = cutout != null && cutout[2] > cutout[0]; holeLeft = hasHole ? cutout[0] : (island[0] + island[2]) / 2; holeRight = hasHole ? cutout[2] : holeLeft;
        invalidate();
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
        canvas.drawText(time, leftMid - text.measureText(time) / 2f, baseline, text);
        if (!power.isEmpty()) canvas.drawText(power, rightMid - text.measureText(power) / 2f, baseline, text);
    }
}
