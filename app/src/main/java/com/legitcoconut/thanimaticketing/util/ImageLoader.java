package com.legitcoconut.thanimaticketing.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import com.legitcoconut.thanimaticketing.R;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads the event logo and keeps it, so it is on screen the moment the list draws.
 *
 * The important detail is the cache key. The server hands out a presigned URL whose
 * X-Amz query parameters are different on every single fetch, so keying on the whole URL
 * would miss every time and re-download the same picture forever. The path alone is stable,
 * and because the object name carries an upload timestamp, replacing a logo produces a new
 * path and therefore a new key on its own.
 */
public final class ImageLoader {

    private static final int MAX_PX = 512;
    private static final long MAX_DISK_BYTES = 8L * 1024 * 1024;

    private static final LruCache<String, Bitmap> MEMORY =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 16)) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    private static final ExecutorService IO = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static File cacheDir;

    private ImageLoader() {
    }

    public static void init(Context context) {
        cacheDir = new File(context.getApplicationContext().getCacheDir(), "logos");
        if (!cacheDir.exists()) cacheDir.mkdirs();
        IO.execute(ImageLoader::prune);
    }

    /**
     * Shows the picture at url, or the fallback drawable when url is null or the fetch fails.
     * Safe inside a RecyclerView: the view remembers which image it is waiting for, so a
     * recycled row never shows the previous row's logo.
     */
    public static void load(ImageView view, @Nullable String url, @DrawableRes int fallbackRes) {
        if (url == null || url.isEmpty()) {
            view.setTag(R.id.tag_image_url, null);
            view.setImageResource(fallbackRes);
            return;
        }

        String key = stableKey(url);
        Object showing = view.getTag(R.id.tag_image_url);
        view.setTag(R.id.tag_image_url, key);

        Bitmap cached = MEMORY.get(key);
        if (cached != null) {
            // Straight in, no fade and no placeholder: this is the refresh path and the
            // picture is already correct, so anything else would read as a reload.
            view.setImageBitmap(cached);
            return;
        }

        // Only fall back to the placeholder when this view is not already showing this image.
        if (!key.equals(showing)) view.setImageResource(fallbackRes);

        IO.execute(() -> {
            Bitmap bitmap = fromDisk(key);
            boolean fresh = false;
            if (bitmap == null) {
                byte[] bytes = download(url);
                if (bytes != null) {
                    bitmap = decode(bytes);
                    if (bitmap != null) {
                        writeDisk(key, bytes);
                        fresh = true;
                    }
                }
            }
            if (bitmap == null) return;
            MEMORY.put(key, bitmap);

            final Bitmap result = bitmap;
            final boolean animate = fresh;
            MAIN.post(() -> {
                // The row may have been rebound to a different event while this was in flight.
                if (!key.equals(view.getTag(R.id.tag_image_url))) return;
                view.setImageBitmap(result);
                if (!animate) return;
                view.setAlpha(0f);
                view.animate().alpha(1f).setDuration(180).start();
            });
        });
    }

    /** The URL without its query string, which is what stays the same between fetches. */
    private static String stableKey(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }

    // ------------------------------------------------------------------ disk

    private static File fileFor(String key) {
        if (cacheDir == null) return null;
        return new File(cacheDir, hash(key));
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(value.hashCode());
        }
    }

    @Nullable
    private static Bitmap fromDisk(String key) {
        File file = fileFor(key);
        if (file == null || !file.exists()) return null;
        try (InputStream in = new java.io.FileInputStream(file)) {
            return decode(readAll(in));
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeDisk(String key, byte[] bytes) {
        File file = fileFor(key);
        if (file == null) return;
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        } catch (Exception ignored) {
        }
    }

    /** Keeps the logo folder small by dropping the least recently used files. */
    private static void prune() {
        File[] files = cacheDir == null ? null : cacheDir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= MAX_DISK_BYTES) return;

        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File f : files) {
            if (total <= MAX_DISK_BYTES) break;
            total -= f.length();
            f.delete();
        }
    }

    // ------------------------------------------------------------------ network

    @Nullable
    private static byte[] download(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                return readAll(in);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    @Nullable
    private static Bitmap decode(@Nullable byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
    }

    private static int sampleSize(int width, int height) {
        int longest = Math.max(width, height);
        int sample = 1;
        while (longest / sample > MAX_PX) sample *= 2;
        return sample;
    }
}
