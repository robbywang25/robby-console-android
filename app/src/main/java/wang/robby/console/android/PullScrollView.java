package wang.robby.console.android;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.ScrollView;

/**
 * The page's ScrollView, with pull-to-refresh where a page asks for it. Pulling down at the very top moves the page
 * with resistance and brings in a glass refresh mark that turns with the pull; past the threshold a haptic tick says
 * "release to refresh", and the mark keeps turning until {@link #finishRefresh()}. Screen readers get the same refresh
 * as an accessibility action. Framework views only; pages without a refresh scroll exactly as before.
 */
@SuppressLint("ViewConstructor") // Built in code with the page's theme flag; never inflated from XML.
final class PullScrollView extends ScrollView {
    private final boolean effects;
    private final float trigger, hold, max; private final int slop;
    private View mark, glyph; private Runnable onRefresh; private String label;
    private float downX, downY, pull; private boolean dragging, armed, refreshing; private long started; private int inset;
    private ValueAnimator settle, spin;

    PullScrollView(Context context, boolean effects) {
        super(context); this.effects = effects;
        float d = context.getResources().getDisplayMetrics().density;
        trigger = 72 * d; hold = 56 * d; max = 132 * d; slop = ViewConfiguration.get(context).getScaledTouchSlop();
    }
    @Override protected float getBottomFadingEdgeStrength() { return 0f; }

    /** Turns pull-to-refresh on for this page: {@code mark} sits above the page, {@code glyph} is the symbol in it. */
    void setRefresh(View mark, View glyph, String label, Runnable onRefresh) {
        this.mark = mark; this.glyph = glyph; this.label = label; this.onRefresh = onRefresh; place(0);
    }
    boolean refreshing() { return refreshing; }
    /** Height of the status bar the page scrolls under, so the mark rides in the visible part of the gap. */
    void setTopInset(int px) { inset = px; }

    /** Ends a refresh: the mark stops and the page settles back; shown for at least half a second so it never flickers. */
    void finishRefresh() {
        if (!refreshing) return;
        long wait = 500 - (SystemClock.uptimeMillis() - started);
        if (wait > 0) { postDelayed(this::finishRefresh, wait); return; }
        refreshing = false; if (spin != null) { spin.cancel(); spin = null; }
        animateTo(0);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { downX = e.getX(); downY = e.getY(); dragging = false; }
        return super.dispatchTouchEvent(e);
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        if (begins(e)) return true;
        return super.onInterceptTouchEvent(e);
    }
    @SuppressLint("ClickableViewAccessibility") // The same refresh is exposed as an accessibility action below.
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!dragging && !begins(e)) return super.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: drag(Math.min(max, Math.max(0f, (e.getY() - downY) * 0.5f))); return true;
            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: dragging = false; release(); return true;
            default: return true;
        }
    }
    /** A downward drag that starts at the top of the page, more vertical than horizontal, while nothing is refreshing. */
    private boolean begins(MotionEvent e) {
        if (dragging) return true;
        if (onRefresh == null || refreshing || e.getActionMasked() != MotionEvent.ACTION_MOVE || getScrollY() > 0) return false;
        float dy = e.getY() - downY, dx = Math.abs(e.getX() - downX);
        if (dy <= slop || dy <= dx) return false;
        // Measure the pull from where it takes over, so a drag that first scrolled the page back to the top starts at 0.
        dragging = true; downY = e.getY(); if (settle != null) settle.cancel();
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }
    private void drag(float to) {
        boolean past = to >= trigger;
        if (past != armed) { armed = past; tick(past); }
        place(to);
    }
    /** Threshold haptics: the gesture-threshold pair on Android 14+, a plain tick on arming before that. */
    private void tick(boolean arming) {
        if (android.os.Build.VERSION.SDK_INT >= 34) performHapticFeedback(arming ? HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE : HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE);
        else if (arming) performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
    }
    private void release() { if (pull >= trigger) start(); else animateTo(0); armed = false; }

    private void start() {
        if (onRefresh == null || refreshing) return;
        refreshing = true; started = SystemClock.uptimeMillis(); animateTo(hold);
        if (effects && glyph != null) {
            float from = glyph.getRotation();
            spin = ValueAnimator.ofFloat(from, from + 360f); spin.setDuration(800); spin.setRepeatCount(ValueAnimator.INFINITE);
            spin.setInterpolator(new LinearInterpolator()); spin.addUpdateListener(a -> glyph.setRotation((float) a.getAnimatedValue())); spin.start();
        }
        announceForAccessibility("正在刷新");
        onRefresh.run();
    }
    private void animateTo(float target) {
        if (settle != null) settle.cancel();
        if (!effects) { place(target); return; }
        settle = ValueAnimator.ofFloat(pull, target); settle.setDuration(220); settle.setInterpolator(new DecelerateInterpolator());
        settle.addUpdateListener(a -> place((float) a.getAnimatedValue())); settle.start();
    }
    /** The page moves down by the pull; the mark rides in the middle of the gap, fading and turning with progress. */
    private void place(float to) {
        pull = to; View page = getChildCount() > 0 ? getChildAt(0) : null;
        if (page != null) page.setTranslationY(to);
        if (mark == null) return;
        float progress = Math.min(1f, to / trigger);
        mark.setVisibility(to > 0 ? VISIBLE : INVISIBLE);
        mark.setTranslationY(getTop() + inset + to / 2f - mark.getHeight() / 2f);
        mark.setAlpha(progress); mark.setScaleX(0.6f + 0.4f * progress); mark.setScaleY(0.6f + 0.4f * progress);
        if (glyph != null && !refreshing && spin == null) glyph.setRotation(progress * 300f);
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (onRefresh != null && !refreshing) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.action_pull_refresh, label));
    }
    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == R.id.action_pull_refresh && onRefresh != null) { if (!refreshing) start(); return true; }
        return super.performAccessibilityAction(action, arguments);
    }
}
