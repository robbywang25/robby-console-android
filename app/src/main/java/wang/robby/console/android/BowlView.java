package wang.robby.console.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Random;

/**
 * A hammered brass singing bowl on a brocade cushion, drawn with gradients on a Canvas, with its suede mallet. A tap
 * strikes it (harder and brighter on the rim); a finger circling the rim rubs it, and the faster it goes the louder
 * it sings, as with a real bowl. While it sounds, rings travel across the bowl's mouth and the rim glows with the level.
 */
@SuppressLint("ViewConstructor") // Built in code; never inflated from XML.
final class BowlView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path body = new Path(), mouth = new Path(); private final RectF oval = new RectF();
    private static final float[] BANDS = {.15f, .23f};
    private android.graphics.PathEffect dash;
    private final boolean dark; private final float density;
    private float cx, rimY, r, ry, depth, cushionY, cushionRx, cushionRy, thick;
    private Shader bodyShade, bodyDepth, inner, glow, rim, cushionTop, cushionSide, shadow, stick, head;
    private float[] marks = new float[0];
    // Touch: the last angle round the rim (on the ellipse), when it was read, and how fast the finger turns.
    private boolean touching, moved, rubbing; private float downX, downY, lastAngle, turned, speed; private long lastMove;
    // The mallet eases between resting on the cushion and the finger's point on the rim.
    private float malletX = Float.NaN, malletY, malletA, flashX, flashY; private long flashAt;

    BowlView(Context c, boolean dark) {
        super(c); this.dark = dark; density = c.getResources().getDisplayMetrics().density;
        setClickable(true); setFocusable(true); setContentDescription("颂钵，轻点敲一下，沿钵口画圈让它持续发声");
        stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), Math.round(280 * density)); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        cx = w / 2f; r = Math.min(w * .33f, h * .40f); ry = r * .34f; rimY = h * .30f; depth = r * .80f;
        cushionY = rimY + depth * .90f; cushionRx = r * 1.26f; cushionRy = r * .40f; thick = r * .17f;
        body.reset(); body.moveTo(cx - r, rimY);
        body.cubicTo(cx - r, rimY + depth * .72f, cx - r * .52f, rimY + depth, cx, rimY + depth);
        body.cubicTo(cx + r * .52f, rimY + depth, cx + r, rimY + depth * .72f, cx + r, rimY);
        body.arcTo(cx - r, rimY - ry, cx + r, rimY + ry, 0, 180, false); body.close();
        bodyShade = new LinearGradient(cx - r, 0, cx + r, 0, new int[]{0xff3e240c, 0xff94652a, 0xfff2d494, 0xffc48d45, 0xff8a5a22, 0xff4a2b0e, 0xff2e1a08},
            new float[]{0, .14f, .30f, .44f, .66f, .88f, 1}, Shader.TileMode.CLAMP);
        bodyDepth = new LinearGradient(0, rimY, 0, rimY + depth, new int[]{0x00000000, 0x10000000, 0x80000000}, new float[]{0, .45f, 1}, Shader.TileMode.CLAMP);
        inner = new LinearGradient(0, rimY - ry, 0, rimY + ry, new int[]{0xffe2b466, 0xffa87334, 0xff5a3612, 0xff2a1806}, new float[]{0, .35f, .7f, 1}, Shader.TileMode.CLAMP);
        rim = new SweepGradient(cx, rimY, new int[]{0xfffbe6a8, 0xff9a6a2a, 0xfff4d58c, 0xff6e4518, 0xffe8c47c, 0xff8a5a22, 0xfffbe6a8}, null);
        cushionTop = new RadialGradient(cx - cushionRx * .25f, cushionY - cushionRy * .5f, cushionRx * 1.2f, new int[]{0xffc8323a, 0xff8e1820, 0xff520a10}, new float[]{0, .55f, 1}, Shader.TileMode.CLAMP);
        cushionSide = new LinearGradient(0, cushionY, 0, cushionY + thick + cushionRy, new int[]{0xff6a0e14, 0xff3a0508}, null, Shader.TileMode.CLAMP);
        shadow = new RadialGradient(cx, cushionY + thick + cushionRy * .5f, cushionRx * 1.15f, new int[]{dark ? 0x80000000 : 0x40000000, 0x00000000}, null, Shader.TileMode.CLAMP);
        stick = new LinearGradient(0, -r * .05f, 0, r * .05f, new int[]{0xffe0a868, 0xffa86e38, 0xff6a3e18}, null, Shader.TileMode.CLAMP);
        head = new LinearGradient(0, -r * .09f, 0, r * .09f, new int[]{0xff8a6650, 0xff5a3c2a, 0xff38241a}, null, Shader.TileMode.CLAMP);
        // Hammer marks: fixed, so the bowl looks the same every time; lighter on the lit side.
        Random rnd = new Random(7); marks = new float[90 * 3];
        for (int i = 0; i < 90; i++) {
            float u = rnd.nextFloat() * 2 - 1, v = rnd.nextFloat(), y = rimY + ry * .3f + v * depth * .85f, half = (float) Math.sqrt(Math.max(0, 1 - v * v * .7f)) * r * .92f;
            marks[i * 3] = cx + u * half; marks[i * 3 + 1] = y; marks[i * 3 + 2] = r * (.025f + rnd.nextFloat() * .03f);
        }
        float ir = r * .935f, iry = ry * .9f;
        glow = new RadialGradient(cx, rimY + iry * .35f, ir * .5f, new int[]{0x55ffd98a, 0x00ffd98a}, null, Shader.TileMode.CLAMP);
        mouth.reset(); mouth.addOval(cx - ir, rimY - iry, cx + ir, rimY + iry, Path.Direction.CW);
        dash = new android.graphics.DashPathEffect(new float[]{r * .03f, r * .045f}, 0);
        if (Float.isNaN(malletX)) rest();
    }

    private void rest() { malletX = cx + r * .3f; malletY = cushionY + cushionRy * .9f; malletA = 10; }

    @Override protected void onDraw(Canvas c) {
        float level = BowlSession.level(); long now = SystemClock.uptimeMillis(); float t = now / 1000f;
        // Shadow and cushion.
        fill.setShader(shadow); c.drawOval(cx - cushionRx * 1.15f, cushionY + thick, cx + cushionRx * 1.15f, cushionY + thick + cushionRy * 1.3f, fill);
        fill.setShader(cushionSide); c.drawOval(cx - cushionRx, cushionY - cushionRy + thick, cx + cushionRx, cushionY + cushionRy + thick, fill);
        c.drawRect(cx - cushionRx, cushionY, cx + cushionRx, cushionY + thick, fill);
        fill.setShader(cushionTop); c.drawOval(cx - cushionRx, cushionY - cushionRy, cx + cushionRx, cushionY + cushionRy, fill); fill.setShader(null);
        stroke.setShader(null); stroke.setStrokeWidth(r * .022f); stroke.setColor(0xccd9ac48);
        c.drawOval(cx - cushionRx * .9f, cushionY - cushionRy * .86f, cx + cushionRx * .9f, cushionY + cushionRy * .86f, stroke);
        stroke.setStrokeWidth(r * .01f); stroke.setColor(0x66f2d27a); stroke.setPathEffect(dash);
        c.drawOval(cx - cushionRx * .82f, cushionY - cushionRy * .76f, cx + cushionRx * .82f, cushionY + cushionRy * .76f, stroke); stroke.setPathEffect(null);
        // Body: brass across, darker toward the foot, hammer marks, two engraved bands under the rim.
        fill.setShader(bodyShade); c.drawPath(body, fill);
        int save = c.save(); c.clipPath(body);
        for (int i = 0; i < marks.length; i += 3) {
            float x = marks[i], y = marks[i + 1], m = marks[i + 2], side = (x - cx) / r, squash = (float) Math.sqrt(Math.max(.15f, 1 - side * side));
            fill.setShader(null); fill.setColor(side < -.1f && side > -.75f ? 0x22fff4d0 : 0x1a000000);
            c.drawOval(x - m * squash, y - m * .7f, x + m * squash, y + m * .7f, fill);
        }
        fill.setShader(bodyDepth); c.drawRect(cx - r, rimY, cx + r, rimY + depth, fill); fill.setShader(null);
        for (float band : BANDS) {
            float y = rimY + r * band, w = r * (1 - band * band * .25f);
            oval.set(cx - w, y - ry, cx + w, y + ry);
            stroke.setStrokeWidth(density * 1.2f); stroke.setColor(0x66301a06); c.drawArc(oval, 8, 164, false, stroke);
            oval.offset(0, density * 1.2f); stroke.setColor(0x44ffe6a8); c.drawArc(oval, 8, 164, false, stroke);
        }
        c.restoreToCount(save);
        // Mouth: the far inner wall catches the light; rings cross it while the bowl sounds.
        float ir = r * .935f, iry = ry * .9f;
        fill.setShader(inner); c.drawOval(cx - ir, rimY - iry, cx + ir, rimY + iry, fill); fill.setShader(null);
        fill.setShader(glow);
        c.drawOval(cx - ir * .6f, rimY - iry * .1f, cx + ir * .6f, rimY + iry * .8f, fill); fill.setShader(null);
        if (level > .004f) {
            save = c.save(); c.clipPath(mouth);
            for (int i = 0; i < 4; i++) {
                float p = (t * .55f + i / 4f) % 1f, s = .12f + .88f * p;
                stroke.setStrokeWidth(density * (1.6f - p)); stroke.setColor(Color.argb(Math.round(Math.min(1f, level * 1.6f) * (1 - p) * 150), 255, 240, 200));
                c.drawOval(cx - ir * s, rimY + iry * .12f - iry * s, cx + ir * s, rimY + iry * .12f + iry * s, stroke);
            }
            c.restoreToCount(save);
        }
        // Rim: brushed gold, a highlight at the back left, and a warm glow with the level.
        stroke.setShader(rim); stroke.setStrokeWidth(r * .07f); c.drawOval(cx - r * .965f, rimY - ry * .93f, cx + r * .965f, rimY + ry * .93f, stroke); stroke.setShader(null);
        stroke.setStrokeWidth(density * 1.6f); stroke.setColor(0xb0fffbe8); oval.set(cx - r * .985f, rimY - ry * .985f, cx + r * .985f, rimY + ry * .985f); c.drawArc(oval, 200, 85, false, stroke);
        if (level > .004f) for (int i = 3; i >= 1; i--) {
            stroke.setStrokeWidth(r * .03f * i); stroke.setColor(Color.argb(Math.round(Math.min(1f, level * 1.4f) * 70 / i), 255, 226, 150));
            c.drawOval(cx - r * .965f, rimY - ry * .93f, cx + r * .965f, rimY + ry * .93f, stroke);
        }
        // Where a tap landed: a ring that opens and fades in 450 ms.
        float since = (now - flashAt) / 450f;
        if (since < 1) { stroke.setStrokeWidth(density * 2); stroke.setColor(Color.argb(Math.round((1 - since) * 180), 255, 244, 214)); c.drawCircle(flashX, flashY, r * (.05f + .25f * since), stroke); }
        mallet(c);
        if (level > .004f || touching || since < 1 || BowlSession.singing() || settle()) postInvalidateOnAnimation();
    }

    /** Moves the mallet a step toward where it belongs; true while it is still on its way. */
    private boolean settle() {
        float tx, ty, ta;
        if (touching && rubbing) { double a = Math.toRadians(lastAngle); tx = cx + (float) Math.cos(a) * r * 1.0f; ty = rimY + (float) Math.sin(a) * ry * 1.0f; ta = (float) Math.toDegrees(Math.atan2(ty - (rimY + depth * .4f), tx - cx)); }
        else { tx = cx + r * .3f; ty = cushionY + cushionRy * .9f; ta = 10; }
        float da = ((ta - malletA) % 360 + 540) % 360 - 180;
        boolean moving = Math.abs(tx - malletX) > .5f || Math.abs(ty - malletY) > .5f || Math.abs(da) > .5f;
        malletX += (tx - malletX) * .35f; malletY += (ty - malletY) * .35f; malletA += da * .35f;
        return moving;
    }
    /** The mallet with its head at (malletX, malletY), the handle pointing along malletA degrees. */
    private void mallet(Canvas c) {
        int s = c.save(); c.translate(malletX, malletY); c.rotate(malletA);
        float len = r * 1.15f, hw = r * .045f, headLen = r * .30f, headW = r * .085f;
        fill.setShader(null); fill.setColor(0x33000000); c.drawRoundRect(headLen * .5f, -hw + density * 3, len, hw + density * 3, hw, hw, fill);
        fill.setShader(stick); c.drawRoundRect(headLen * .5f, -hw, len, hw, hw, hw, fill);
        fill.setShader(head); c.drawRoundRect(-headLen * .15f, -headW, headLen, headW, headW, headW, fill); fill.setShader(null);
        stroke.setStrokeWidth(density); stroke.setColor(0x55000000);
        for (int i = 1; i <= 3; i++) { float x = headLen * (.1f + i * .2f); c.drawLine(x, -headW * .92f, x, headW * .92f, stroke); }
        c.restoreToCount(s);
    }

    // ---- touch ----
    private float angle(float x, float y) { return (float) Math.toDegrees(Math.atan2((y - rimY) / ry, (x - cx) / r)); }
    private float rimDistance(float x, float y) { float u = (x - cx) / r, v = (y - rimY) / ry; return (float) Math.sqrt(u * u + v * v); }
    private boolean onBowl(float x, float y) { return Math.abs(x - cx) < r * 1.1f && y > rimY - ry * 1.7f && y < rimY + depth * 1.02f; }

    @SuppressLint("ClickableViewAccessibility") // A tap strikes through performClick; rubbing has no click equivalent.
    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY(); long now = SystemClock.uptimeMillis();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                if (!onBowl(x, y)) return false;
                getParent().requestDisallowInterceptTouchEvent(true);
                touching = true; moved = false; rubbing = false; downX = x; downY = y; lastAngle = angle(x, y); lastMove = now; speed = 0; turned = 0; invalidate();
            }
            case MotionEvent.ACTION_MOVE -> {
                if (!touching) return false;
                if (Math.hypot(x - downX, y - downY) > 8 * density) moved = true;
                float a = angle(x, y), d = ((a - lastAngle) % 360 + 540) % 360 - 180, dt = Math.max(1, now - lastMove) / 1000f, near = rimDistance(x, y);
                lastAngle = a; lastMove = now;
                if (moved && near > .45f && near < 1.9f) {
                    if (!rubbing && BowlSession.level() < .05f) BowlSession.strike(getContext(), .22f);
                    rubbing = true; speed = speed * .82f + .18f * Math.abs(d) / dt;
                    turned += Math.abs(d); if (turned >= 90) { turned -= 90; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); }
                    float turns = speed / 360f; BowlSession.rub(getContext(), Math.min(1f, turns * 1.3f), turns);
                } else if (rubbing) { speed *= .7f; BowlSession.rub(getContext(), Math.min(1f, speed / 360f * 1.3f), speed / 360f); }
            }
            case MotionEvent.ACTION_UP -> {
                if (!touching) return false;
                touching = false;
                if (!moved) performClick(x, y); else BowlSession.rub(getContext(), 0, 0);
                rubbing = false; invalidate();
            }
            case MotionEvent.ACTION_CANCEL -> { touching = false; rubbing = false; BowlSession.rub(getContext(), 0, 0); invalidate(); }
            default -> { }
        }
        return true;
    }
    private void performClick(float x, float y) {
        float near = rimDistance(x, y); boolean rimHit = near > .8f && near < 1.35f && y < rimY + ry * 1.5f;
        flashX = x; flashY = y; flashAt = SystemClock.uptimeMillis();
        BowlSession.strike(getContext(), rimHit ? .95f : y > rimY ? .75f : .6f);
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); super.performClick(); invalidate();
    }
    /** TalkBack's double tap and switch access land here: the rim stroke from the front. */
    @Override public boolean performClick() {
        if (touching) return super.performClick();
        flashX = cx; flashY = rimY + ry; flashAt = SystemClock.uptimeMillis(); BowlSession.strike(getContext(), .85f); invalidate(); return super.performClick();
    }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, "敲钵"));
    }
}
