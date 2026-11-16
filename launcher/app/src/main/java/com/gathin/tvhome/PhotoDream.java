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

    // Stills from Nicolas's reels, as they are (portrait or square); fill() crops them to the screen.
    private static final int[] PHOTOS = {
            R.drawable.bg_cat_01, R.drawable.bg_cat_02, R.drawable.bg_cat_03, R.drawable.bg_cat_04,
            R.drawable.bg_cat_05, R.drawable.bg_cat_06, R.drawable.bg_cat_07, R.drawable.bg_cat_08,
            R.drawable.bg_cat_09, R.drawable.bg_cat_10, R.drawable.bg_cat_11};
    /** Where the cats sit in each still, as a fraction of its height: the screen shows the band around it. */
    private static final float[] FOCUS = {0.5f, 0.55f, 0.62f, 0.38f, 0.62f, 0.4f, 0.45f, 0.52f, 0.55f, 0.42f, 0.45f};
    private static final long SLIDE_MS = 20_000;
    private static final long FADE_MS = 2_500;
    private static final float ZOOM = 1.03f;   // reached over one slide, then the next photo takes over

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

        for (int i = 0; i < PHOTOS.length; i++) order.add(i);
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
        int i = order.get(next);
        next = (next + 1) % order.size();
        // Decoding a still takes a moment: build it off the main thread, then fade it in.
        new Thread(() -> {
            Bitmap b = fill(BitmapFactory.decodeResource(getResources(), PHOTOS[i]), FOCUS[i]);
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

    /** The still cropped to fill the 16:9 screen, the band kept where the cats are (focus: 0 top, 1 bottom). */
    static Bitmap fill(Bitmap src, float focus) {
        final int w = 1920, h = 1080;
        int bw = src.getWidth(), bh = Math.round(src.getWidth() * (float) h / w);
        if (bh > src.getHeight()) {   // wider than 16:9: crop the sides instead
            bh = src.getHeight();
            bw = Math.round(bh * (float) w / h);
        }
        int top = Math.max(0, Math.min(src.getHeight() - bh, Math.round(src.getHeight() * focus - bh / 2f)));
        int left = (src.getWidth() - bw) / 2;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(src, new Rect(left, top, left + bw, top + bh), new Rect(0, 0, w, h),
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        src.recycle();
        return out;
    }
}
