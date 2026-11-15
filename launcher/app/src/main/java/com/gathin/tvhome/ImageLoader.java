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
    // Background priority: downloads and decodes must never take CPU from the UI and render threads.
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3, r -> new Thread(() -> {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        r.run();
    }, "images"));
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>((int) Math.min(Runtime.getRuntime().maxMemory() / 6, 48L * 1024 * 1024)) {
                @Override
                protected int sizeOf(String key, Bitmap b) {
                    return b.getByteCount();
                }
            };

    /** Width the Watch page backdrops are decoded at; shared so prefetch and display hit the same cache entry. */
    static final int BACKDROP_W = 960;  // under a scrim, 960 px is enough; a quarter of 1080p to sample per frame

    /** Scrims painted into every backdrop (BACKDROP_W) once, so the screen shows one full-screen layer, not three. */
    static volatile android.graphics.drawable.Drawable[] backdropScrims;

    /** Returns a copy of src with the drawables painted over it. Safe off the main thread. */
    static Bitmap bake(Bitmap src, android.graphics.drawable.Drawable... scrims) {
        if (src == null) return null;
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        c.drawBitmap(src, 0, 0, null);
        synchronized (ImageLoader.class) {
            for (android.graphics.drawable.Drawable d : scrims) {
                d.setBounds(0, 0, out.getWidth(), out.getHeight());
                d.draw(c);
            }
        }
        // Back to RGB_565, dithered so the scrim gradients don't band: half the texture to upload and to fill.
        Bitmap opaque = Bitmap.createBitmap(out.getWidth(), out.getHeight(), Bitmap.Config.RGB_565);
        android.graphics.Paint dither = new android.graphics.Paint();
        dither.setDither(true);
        new android.graphics.Canvas(opaque).drawBitmap(out, 0, 0, dither);
        out.recycle();
        return opaque;
    }

    private ImageLoader() {
    }

    /**
     * A card's picture, ready to draw as is: centre-cropped to the card, with its scrim and rounded corners painted
     * in once, off the main thread. The card then needs no clip and no scrim view, which cost GPU time on every
     * frame of every animation on the Fire TV Stick.
     */
    static void intoCard(ImageView view, String url, int w, int h, float radius, boolean scrim) {
        String key = url + "@" + w + "x" + h + (scrim ? "s" : "");
        view.setTag(R.id.image_url, key);
        Bitmap cached = url == null ? null : CACHE.get(key);
        if (cached != null) {
            view.setImageBitmap(cached);
            view.setAlpha(1f);
            return;
        }
        view.setImageDrawable(null);
        view.setAlpha(0f);
        if (url == null || url.isEmpty()) return;
        POOL.execute(() -> {
            Bitmap raw = download(url, w);
            if (raw == null) return;
            Bitmap b = shapeCard(raw, w, h, radius, scrim);
            raw.recycle();   // native memory: don't wait for a GC that a small Java heap rarely runs
            CACHE.put(key, b);
            MAIN.post(() -> {
                if (!key.equals(view.getTag(R.id.image_url))) return;
                view.setImageBitmap(b);
                view.animate().alpha(1f).setDuration(150).setInterpolator(Motion.EASE_OUT).start();
            });
        });
    }

    static Bitmap shapeCard(Bitmap src, int w, int h, float radius, boolean scrim) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        android.graphics.RectF r = new android.graphics.RectF(0, 0, w, h);
        android.graphics.BitmapShader shader = new android.graphics.BitmapShader(src,
                android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
        float s = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());   // centre crop
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(s, s);
        m.postTranslate((w - src.getWidth() * s) / 2f, (h - src.getHeight() * s) / 2f);
        shader.setLocalMatrix(m);
        android.graphics.Paint p = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
        p.setShader(shader);
        c.drawRoundRect(r, radius, radius, p);
        if (scrim) {
            // Dark at the bottom for the title, clear at the top.
            android.graphics.Paint g = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            g.setShader(new android.graphics.LinearGradient(0, h, 0, 0, new int[]{0xD9000000, 0x66000000, 0},
                    null, android.graphics.Shader.TileMode.CLAMP));
            c.drawRoundRect(r, radius, radius, g);
        }
        return out;
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
            Bitmap raw = download(url, maxWidthPx);
            if (raw == null) return;
            android.graphics.drawable.Drawable[] scrims = backdropScrims;
            Bitmap b = maxWidthPx == BACKDROP_W && scrims != null ? bake(raw, scrims) : raw;
            if (b != raw) raw.recycle();
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
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            // Power-of-two sampling leaves a 1280 px YouTube picture at 1280: bring it down to the width asked for,
            // or every full-screen backdrop costs 1.8x the texture and fill it needs.
            if (b != null && b.getWidth() > maxWidthPx * 1.2f) {
                Bitmap s = Bitmap.createScaledBitmap(b, maxWidthPx, Math.round(b.getHeight() * maxWidthPx / (float) b.getWidth()), true);
                if (s != b) b.recycle();
                b = s;
            }
            return b;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
