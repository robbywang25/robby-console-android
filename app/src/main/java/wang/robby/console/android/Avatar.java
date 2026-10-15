package wang.robby.console.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Outline;
import android.net.Uri;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Personal avatar for 我. It lives only in this app's private files (backup is disabled) and is never uploaded:
 * the chosen photo is center-cropped to a 512 px square on the device and stored as a single file.
 */
final class Avatar {
    private Avatar() {}
    private static final int SIZE = 512;
    private static Bitmap cached; private static boolean loaded;
    private static File file(Context c) { return new File(c.getFilesDir(), "avatar.jpg"); }

    /** The saved avatar, decoded once per process; null when none is set. */
    static synchronized Bitmap get(Context c) {
        if (!loaded) { File f = file(c); cached = f.isFile() ? BitmapFactory.decodeFile(f.getPath()) : null; loaded = true; }
        return cached;
    }
    /** Decodes, crops and stores the picked image. Runs off the UI thread. */
    static void save(Context c, Uri uri) throws IOException {
        Bitmap source = ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.getContentResolver(), uri), (decoder, info, src) -> {
            int w = info.getSize().getWidth(), h = info.getSize().getHeight(), shortSide = Math.min(w, h);
            // Decode no larger than needed: the short side lands near 2x the stored size.
            if (shortSide > SIZE * 2) { float k = SIZE * 2f / shortSide; decoder.setTargetSize(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k))); }
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
        int side = Math.min(source.getWidth(), source.getHeight());
        Bitmap square = Bitmap.createBitmap(source, (source.getWidth() - side) / 2, (source.getHeight() - side) / 2, side, side);
        Bitmap scaled = Bitmap.createScaledBitmap(square, SIZE, SIZE, true);
        File target = file(c), temp = new File(c.getFilesDir(), "avatar.tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) { if (!scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)) throw new IOException("无法保存头像"); }
        if (!temp.renameTo(target)) { temp.delete(); throw new IOException("无法保存头像"); }
        synchronized (Avatar.class) { cached = scaled; loaded = true; }
    }
    static synchronized void clear(Context c) { file(c).delete(); cached = null; loaded = true; }

    /** Circular image view of the avatar at {@code sizePx}. */
    static ImageView view(Context c, Bitmap bitmap) {
        ImageView v = new ImageView(c); v.setImageBitmap(bitmap); v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setOutlineProvider(new ViewOutlineProvider() { @Override public void getOutline(View view, Outline o) { o.setOval(0, 0, view.getWidth(), view.getHeight()); } });
        v.setClipToOutline(true); v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }
}
