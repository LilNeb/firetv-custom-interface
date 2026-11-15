package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Frosted glass without RenderEffect (Fire OS 8 is Android 11). The launcher draws its own background, so the
 * glass can sample it: when the background changes we keep a tiny, heavily downscaled copy of it, and every
 * glass surface draws that copy, stretched back to screen size with bilinear filtering (which reads as a blur),
 * at its own position on screen. Near the edges a slightly magnified copy is drawn on top, so the background
 * bends at the rim like light through a lens. It costs one small bitmap per background change.
 */
final class Backdrop {

    /** Width of the frosted copy in pixels. Smaller is blurrier. */
    private static final int FROST_WIDTH = 64;
    /** How much the background is magnified at the rim. */
    private static final float EDGE_MAGNIFY = 1.10f;

    private static Bitmap frosted;
    private static int srcW, srcH;
    private static final Set<View> USERS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Paint EDGE = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Paint DIM = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Matrix M = new Matrix();
    private static final int[] LOC = new int[2];

    static {
        EDGE.setStyle(Paint.Style.STROKE);
        // The photo behind is darkened by scrims; dim the glass to match so it doesn't glow.
        DIM.setColor(0x47000000);
    }

    private Backdrop() {
    }

    /** The picture now filling the screen (center-cropped), or null for none. */
    static void set(Bitmap source) {
        if (source == null || source.getWidth() == 0) {
            frosted = null;
        } else {
            srcW = source.getWidth();
            srcH = source.getHeight();
            int w = FROST_WIDTH, h = Math.max(1, Math.round(FROST_WIDTH * srcH / (float) srcW));
            // Two halving steps smooth the result more than one big jump.
            Bitmap mid = Bitmap.createScaledBitmap(source, w * 3, h * 3, true);
            frosted = Bitmap.createScaledBitmap(mid, w, h, true);
            if (mid != source && mid != frosted) mid.recycle();
            BitmapShader s = new BitmapShader(frosted, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            FILL.setShader(s);
            EDGE.setShader(new BitmapShader(frosted, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        }
        for (View v : USERS.toArray(new View[0])) if (v != null) v.invalidate();
    }

    static boolean available() {
        return frosted != null;
    }

    /**
     * Draws the frosted background inside the rounded rect (host coordinates), aligned with the real
     * background. Registers the host so it redraws when the background changes.
     */
    static void draw(Canvas c, View host, float left, float top, float right, float bottom, float radius, float density) {
        USERS.add(host);
        if (frosted == null) return;
        View root = host.getRootView();
        int screenW = root.getWidth(), screenH = root.getHeight();
        if (screenW == 0 || screenH == 0) return;
        host.getLocationInWindow(LOC);
        // Same mapping as a full-screen centerCrop ImageView, then into the host's coordinates.
        float s = Math.max(screenW / (float) srcW, screenH / (float) srcH);
        float dx = (screenW - srcW * s) / 2f - LOC[0];
        float dy = (screenH - srcH * s) / 2f - LOC[1];
        float fs = s * srcW / frosted.getWidth();
        M.setScale(fs, fs);
        M.postTranslate(dx, dy);
        FILL.getShader().setLocalMatrix(M);
        c.drawRoundRect(left, top, right, bottom, radius, radius, FILL);

        // Rim refraction: the same background, magnified around the shape's centre, in a band along the edge.
        float band = Math.min(10f * density, Math.min(right - left, bottom - top) / 4f);
        float cx = (left + right) / 2f, cy = (top + bottom) / 2f;
        M.postScale(EDGE_MAGNIFY, EDGE_MAGNIFY, cx, cy);
        EDGE.getShader().setLocalMatrix(M);
        EDGE.setStrokeWidth(band);
        float i = band / 2f;
        EDGE.setAlpha(150);
        c.drawRoundRect(left + i, top + i, right - i, bottom - i, Math.max(0, radius - i), Math.max(0, radius - i), EDGE);

        c.drawRoundRect(left, top, right, bottom, radius, radius, DIM);
    }
}
