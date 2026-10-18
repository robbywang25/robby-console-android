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
 * Personal avatar for 我. The chosen photo is center-cropped to a 512 px square JPEG on the device (no EXIF is
 * written) and kept in this app's private files (backup is disabled). Since 0.17.0 the same file is kept on the God
 * server for the owner, so it follows a reinstall or another phone; the server strips any metadata again.
 */
final class Avatar {
    private Avatar() {}
    private static final int SIZE = 512;
    private static Bitmap cached; private static boolean loaded;
    private static File file(Context c) { return new File(c.getFilesDir(), "avatar.jpg"); }
    private static android.content.SharedPreferences prefs(Context c) { return c.getSharedPreferences("god-ui", Context.MODE_PRIVATE); }
    static final String UPLOAD = "upload", DELETE = "delete";
    /** What the phone still owes the server: {@link #UPLOAD}, {@link #DELETE} or null. */
    static String pending(Context c) { return prefs(c).getString("avatarPending", null); }

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
        prefs(c).edit().putString("avatarPending", UPLOAD).apply();
    }
    static synchronized void clear(Context c) { file(c).delete(); cached = null; loaded = true; prefs(c).edit().putString("avatarPending", DELETE).remove("avatarEtag").apply(); }

    /**
     * Brings phone and server to the same avatar; runs off the UI thread. A pending upload or removal is sent when the
     * session may write; otherwise the server's copy is fetched by ETag. A photo set before 0.17.0 that the server has
     * never seen is uploaded once. Returns true when the picture shown on 我 changed.
     */
    static boolean sync(Context c, MobileApi api, boolean canWrite) throws Exception {
        String pending = pending(c), etag = prefs(c).getString("avatarEtag", null);
        if (pending != null) {
            if (!canWrite) return false;
            if (UPLOAD.equals(pending) && file(c).isFile()) {
                MobileApi.AvatarResult r = api.avatar("PUT", java.nio.file.Files.readAllBytes(file(c).toPath()), null);
                prefs(c).edit().putString("avatarEtag", r.etag).remove("avatarPending").apply(); return false;
            }
            api.avatar("DELETE", null, null); prefs(c).edit().remove("avatarEtag").remove("avatarPending").apply(); return false;
        }
        MobileApi.AvatarResult r = api.avatar("GET", null, file(c).isFile() ? etag : null);
        if (r.status == 304) return false;
        if (r.status == 200) { store(c, r.bytes); prefs(c).edit().putString("avatarEtag", r.etag).apply(); return true; }
        // 404: removed elsewhere when this phone had the server's copy; never uploaded when it had not.
        if (etag != null) { synchronized (Avatar.class) { file(c).delete(); cached = null; loaded = true; } prefs(c).edit().remove("avatarEtag").apply(); return true; }
        if (file(c).isFile()) { prefs(c).edit().putString("avatarPending", UPLOAD).apply(); return canWrite && sync(c, api, true); }
        return false;
    }
    /** Keeps a downloaded avatar only if it decodes as an image of sane size. */
    private static void store(Context c, byte[] jpeg) throws IOException {
        Bitmap decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (decoded == null || decoded.getWidth() < 64 || decoded.getWidth() > 1024 || decoded.getHeight() < 64 || decoded.getHeight() > 1024) throw new IOException("头像格式不匹配");
        File target = file(c), temp = new File(c.getFilesDir(), "avatar.tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) { out.write(jpeg); }
        if (!temp.renameTo(target)) { temp.delete(); throw new IOException("无法保存头像"); }
        synchronized (Avatar.class) { cached = decoded; loaded = true; }
    }

    /** Circular image view of the avatar at {@code sizePx}. */
    static ImageView view(Context c, Bitmap bitmap) {
        ImageView v = new ImageView(c); v.setImageBitmap(bitmap); v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setOutlineProvider(new ViewOutlineProvider() { @Override public void getOutline(View view, Outline o) { o.setOval(0, 0, view.getWidth(), view.getHeight()); } });
        v.setClipToOutline(true); v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }
}
