package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import androidx.dynamicanimation.animation.DynamicAnimation;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small image loader for posters, thumbnails and backdrops: no extra dependency, a memory cache sized to the
 * TV, downsampling to the size the view needs, and a fade-in when the picture arrives.
 */
final class ImageLoader {

    interface Callback {
        void onLoaded(Bitmap bitmap);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>((int) Math.min(Runtime.getRuntime().maxMemory() / 6, 48L * 1024 * 1024)) {
                @Override
                protected int sizeOf(String key, Bitmap b) {
                    return b.getByteCount();
                }
            };

    /** Width the Watch page backdrops are decoded at; shared so prefetch and display hit the same cache entry. */
    static final int BACKDROP_W = 1280;

    private ImageLoader() {
    }

    static boolean has(String url, int maxWidthPx) {
        return CACHE.get(url + "@" + maxWidthPx) != null;
    }

    private static final java.util.Set<String> PREFETCHING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Warms the cache, once per URL.
     * shortcut: a failed prefetch is not retried; the card still loads its backdrop normally when focused.
     */
    static void prefetch(String url, int maxWidthPx) {
        String key = url + "@" + maxWidthPx;
        if (!has(url, maxWidthPx) && PREFETCHING.add(key)) get(url, maxWidthPx, b -> PREFETCHING.remove(key));
    }

    /** Loads the URL into the view, downsampled to about maxWidthPx wide. A newer request for the view wins. */
    static void into(ImageView view, String url, int maxWidthPx) {
        view.setTag(R.id.image_url, url);
        if (url == null || url.isEmpty()) {
            view.setImageDrawable(null);
            return;
        }
        String key = url + "@" + maxWidthPx;
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            view.setImageBitmap(cached);
            Motion.set(view, DynamicAnimation.ALPHA, 1f);
            return;
        }
        view.setImageDrawable(null);
        Motion.set(view, DynamicAnimation.ALPHA, 0f);
        get(url, maxWidthPx, b -> {
            if (!url.equals(view.getTag(R.id.image_url))) return;
            view.setImageBitmap(b);
            Motion.to(view, DynamicAnimation.ALPHA, 1f, Motion.SMOOTH);
        });
    }

    /** Fetches a bitmap off the main thread; the callback runs on the main thread, only on success. */
    static void get(String url, int maxWidthPx, Callback cb) {
        String key = url + "@" + maxWidthPx;
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            cb.onLoaded(cached);
            return;
        }
        POOL.execute(() -> {
            Bitmap b = download(url, maxWidthPx);
            if (b == null) return;
            CACHE.put(key, b);
            MAIN.post(() -> cb.onLoaded(b));
        });
    }

    private static Bitmap download(String url, int maxWidthPx) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() / 100 != 2) return null;
            byte[] data;
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                data = out.toByteArray();
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= maxWidthPx) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
