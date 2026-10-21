package wang.robby.console.android;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** A QR code as black modules on a white rounded tile with the standard four-module quiet zone, in both themes. */
final class QrDrawable extends Drawable {
    private final QrCode code; private final Paint paint = new Paint();
    QrDrawable(QrCode code) { this.code = code; }
    @Override public void draw(Canvas c) {
        Rect b = getBounds(); int side = Math.min(b.width(), b.height()), total = code.size + 8;
        float cell = side / (float) total, left = b.left + (b.width() - side) / 2f, top = b.top + (b.height() - side) / 2f;
        paint.setAntiAlias(true); paint.setColor(0xffffffff); c.drawRoundRect(left, top, left + side, top + side, cell * 2, cell * 2, paint);
        paint.setAntiAlias(false); paint.setColor(0xff000000);
        for (int y = 0; y < code.size; y++)
            for (int x = 0; x < code.size; x++)
                if (code.dark(x, y)) {
                    // Whole pixels so neighbouring modules meet without hairline gaps at any density.
                    float l = left + (x + 4) * cell, t = top + (y + 4) * cell;
                    c.drawRect(Math.round(l), Math.round(t), Math.round(l + cell), Math.round(t + cell), paint);
                }
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
