package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Screensaver: Nicolas's reel stills, each slowly zooming in, crossfading every SLIDE_MS, with a
 * small clock in the corner. Chosen with:
 *   settings put secure screensaver_components com.gathin.tvhome/com.gathin.tvhome.PhotoDream
 * and Amazon's back with:
 *   settings put secure screensaver_components com.amazon.ftv.screensaver/.app.services.ScreensaverService
 */
public class PhotoDream extends DreamService {

    // Stills from Nicolas's reels, as they are (portrait or square); wallpaper() lays them out for 16:9.
    private static final int[] PHOTOS = {
            R.drawable.bg_cat_01, R.drawable.bg_cat_02, R.drawable.bg_cat_03, R.drawable.bg_cat_04,
            R.drawable.bg_cat_05, R.drawable.bg_cat_06, R.drawable.bg_cat_07, R.drawable.bg_cat_08,
            R.drawable.bg_cat_09, R.drawable.bg_cat_10, R.drawable.bg_cat_11};
    private static final long SLIDE_MS = 20_000;
    private static final long FADE_MS = 2_500;
    private static final float ZOOM = 1.08f;   // reached over one slide, then the next photo takes over

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Integer> order = new ArrayList<>();
    private ImageView front, back;
    private TextClock clock;
    private int next;

    private final Runnable advance = new Runnable() {
        @Override
        public void run() {
            showNext();
            handler.postDelayed(this, SLIDE_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        front = photoView();
        back = photoView();
        root.addView(back);
        root.addView(front);
        clock = clock();
        root.addView(clock);
        setContentView(root);

        for (int p : PHOTOS) order.add(p);
        Collections.shuffle(order);
        front.setAlpha(0f);
        back.setAlpha(0f);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        handler.post(advance);
    }

    @Override
    public void onDreamingStopped() {
        handler.removeCallbacks(advance);
        super.onDreamingStopped();
    }

    /** Fade the next photo in over the current one, and start its slow zoom. */
    private void showNext() {
        int res = order.get(next);
        next = (next + 1) % order.size();
        // Blurring a still takes a moment: build it off the main thread, then fade it in.
        new Thread(() -> {
            Bitmap b = wallpaper(
                    BitmapFactory.decodeResource(getResources(), res));
            handler.post(() -> fadeIn(b));
        }).start();
    }

    private void fadeIn(Bitmap b) {
        ImageView incoming = back, outgoing = front;
        incoming.setImageBitmap(b);
        incoming.animate().cancel();
        incoming.setScaleX(1f);
        incoming.setScaleY(1f);
        incoming.setAlpha(0f);
        incoming.bringToFront();
        incoming.animate().alpha(1f).setDuration(FADE_MS).start();
        incoming.animate().scaleX(ZOOM).scaleY(ZOOM).setDuration(SLIDE_MS + FADE_MS).start();
        outgoing.animate().alpha(0f).setStartDelay(FADE_MS).setDuration(1).start();
        front = incoming;
        back = outgoing;
        clock.bringToFront();
    }

    private ImageView photoView() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return v;
    }

    private TextClock clock() {
        TextClock c = new TextClock(this);
        c.setFormat12Hour("h:mm");
        c.setFormat24Hour("HH:mm");
        c.setTextColor(0xE6FFFFFF);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        c.setTypeface(getResources().getFont(R.font.open_sans));
        c.setFontVariationSettings("'wght' 300");
        c.setShadowLayer(12f, 0f, 2f, 0x99000000);
        float dp = getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, (int) (48 * dp), (int) (36 * dp));
        c.setLayoutParams(lp);
        return c;
    }

    /**
     * A portrait still on a 1080p screen: sharp at full height on the right, the rest filled by the same picture
     * blurred and darkened (downscaled step by step, then stretched back), the seam faded over 200 px.
     */
    static Bitmap wallpaper(Bitmap src) {
        final int w = 1920, h = 1080;
        android.graphics.Paint filter = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        // Cover crop of the picture, then halve until tiny: each step averages, so the result is a real blur.
        float cover = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        int cw = Math.round(w / cover), ch = Math.round(h / cover);
        int cx = (src.getWidth() - cw) / 2, cy = (src.getHeight() - ch) / 2;
        Bitmap small = Bitmap.createBitmap(w / 4, h / 4, Bitmap.Config.ARGB_8888);
        new Canvas(small).drawBitmap(src, new Rect(cx, cy, cx + cw, cy + ch), new Rect(0, 0, w / 4, h / 4), filter);
        while (small.getWidth() > 60) {
            Bitmap half = Bitmap.createScaledBitmap(small, small.getWidth() / 2, small.getHeight() / 2, true);
            small.recycle();
            small = half;
        }
        // Grow back in doublings: one big bilinear stretch would show the small picture's pixels as blocks.
        Bitmap soft = small;
        while (soft.getWidth() < 480) {
            Bitmap twice = Bitmap.createScaledBitmap(soft, soft.getWidth() * 2, soft.getHeight() * 2, true);
            soft.recycle();
            soft = twice;
        }

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        android.graphics.Paint dim = new android.graphics.Paint(filter);
        dim.setColorFilter(new android.graphics.LightingColorFilter(0xFFA0A0A0, 0));   // 63 % brightness
        c.drawBitmap(soft, null, new Rect(0, 0, w, h), dim);
        soft.recycle();

        int fw = Math.min(w, Math.round(src.getWidth() * (float) h / src.getHeight()));
        int left = w - fw, fade = 200;
        int layer = c.saveLayer(left, 0, w, h, null);
        c.drawBitmap(src, null, new Rect(left, 0, w, h), filter);
        android.graphics.Paint mask = new android.graphics.Paint();
        mask.setShader(new android.graphics.LinearGradient(left, 0, left + fade, 0, 0x00000000, 0xFF000000,
                android.graphics.Shader.TileMode.CLAMP));
        mask.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
        c.drawRect(left, 0, left + fade, h, mask);
        c.restoreToCount(layer);
        src.recycle();
        return out;
    }
}
