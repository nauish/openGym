package ch.duartesantos.opengym;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bounded thumbnail cache. Rendering always returns immediately, including offline. */
final class WorkoutNotificationArtwork {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int THUMB_SIZE = 256;
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private static final LruCache<String, Long> FAILED = new LruCache<>(16);
    private static final Set<String> PENDING = new HashSet<>();
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private WorkoutNotificationArtwork() {}

    static synchronized Bitmap get(Context context, WorkoutNotificationState state) {
        String source = state.exerciseImage;
        if (source.isEmpty() || !source.startsWith("https://")) return null;
        Bitmap cached = CACHE.get(source);
        if (cached != null) return cached;
        Long failed = FAILED.get(source);
        if ((failed != null && android.os.SystemClock.elapsedRealtime() - failed < 60_000)
                || PENDING.contains(source) || PENDING.size() >= 4) return null;
        PENDING.add(source);
        Context app = context.getApplicationContext();
        LOADER.execute(() -> {
            Bitmap image = null;
            // Discard queued work if the workout has already moved on.
            WorkoutNotificationState current = WorkoutNotification.savedState(app);
            if (state.sessionId.equals(current.sessionId) && source.equals(current.exerciseImage)) {
                try { image = download(source); } catch (Exception ignored) { /* use the status icon */ }
            }
            synchronized (WorkoutNotificationArtwork.class) {
                PENDING.remove(source);
                if (image != null) CACHE.put(source, image);
                else FAILED.put(source, android.os.SystemClock.elapsedRealtime());
            }
            if (image != null) MAIN.post(() -> {
                WorkoutNotificationState latest = WorkoutNotification.savedState(app);
                if (state.sessionId.equals(latest.sessionId) && source.equals(latest.exerciseImage)
                        && latest.rest == null && !latest.dismissed) WorkoutNotification.postCurrent(app);
            });
        });
        return null;
    }

    private static Bitmap download(String source) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(source).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        // Exercise media URLs supplied by the existing app media configuration are direct.
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200 || connection.getContentLengthLong() > MAX_BYTES) return null;
            byte[] bytes;
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = input.read(chunk)) != -1) {
                    if (output.size() + n > MAX_BYTES) return null;
                    output.write(chunk, 0, n);
                }
                bytes = output.toByteArray();
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0
                    || options.outWidth > 8192 || options.outHeight > 8192) return null;
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > THUMB_SIZE * 2) {
                options.inSampleSize *= 2;
            }
            options.inJustDecodeBounds = false;
            Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (decoded == null) return null;
            float scale = Math.min(1f, THUMB_SIZE / (float) Math.max(decoded.getWidth(), decoded.getHeight()));
            Bitmap thumb = Bitmap.createScaledBitmap(decoded, Math.max(1, Math.round(decoded.getWidth() * scale)),
                    Math.max(1, Math.round(decoded.getHeight() * scale)), true);
            if (thumb != decoded) decoded.recycle();
            return thumb;
        } finally {
            connection.disconnect();
        }
    }
}
