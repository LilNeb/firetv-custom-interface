package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

/**
 * Apple's Liquid Glass, drawn: a nearly clear body, a bright specular band along the top, a light rim that is
 * brightest top-left and bottom-right, and a soft inner glow at the bottom edge.
 *
 * Fire OS 8 is Android 11, which has no RenderEffect, so the system can't blur or bend what is behind a view.
 * Glass surfaces sample the launcher's own background instead (Backdrop: frosted body, magnified rim). The
 * focus lens, which sits over posters, stays clear and reads as glass through its highlights.
 */
final class LiquidGlass {

    private LiquidGlass() {
    }

    /** Paints one glass shape. {@link #draw} can be called with a new rect every frame (the moving lens). */
    static final class Painter {
        private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint specular = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF r = new RectF();
        private final float density;
        /** 0 = clear lens (over pictures), 1 = filled button. */
        private final float fill;
        private float lastW = -1, lastH = -1;
        private int alpha = 255;

        Painter(float density, float fill) {
            this.density = density;
            this.fill = fill;
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(1.5f * density);
            glow.setStyle(Paint.Style.STROKE);
            shadow.setStyle(Paint.Style.STROKE);
        }

        void setAlpha(int a) {
            alpha = a;
        }

        /**
         * Draws the glass. With a host view, the body is the frosted, refracted background (Backdrop); without
         * one (the focus lens over posters) it stays clear.
         */
        void draw(Canvas c, android.view.View host, float left, float top, float right, float bottom, float radius,
                  boolean outerGlow) {
            float w = right - left, h = bottom - top;
            if (w <= 0 || h <= 0) return;
            if (host != null && Backdrop.available() && alpha == 255) {
                Backdrop.draw(c, host, left, top, right, bottom, radius, density);
            } else if (host != null) {
                Backdrop.draw(c, host, 0, 0, 0, 0, 0, density);   // registers for the next background
            }
            if (w != lastW || h != lastH) {
                lastW = w;
                lastH = h;
                int bodyTop = mix(0x40, 0x10, fill), bodyBottom = mix(0x22, 0x06, fill);
                body.setShader(new LinearGradient(0, 0, 0, h,
                        bodyTop << 24 | 0xFFFFFF, bodyBottom << 24 | 0xFFFFFF, Shader.TileMode.CLAMP));
                specular.setShader(new LinearGradient(0, 0, 0, h * 0.5f,
                        0x5CFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                // Light enters top-left and leaves bottom-right; the sides stay dim.
                rim.setShader(new LinearGradient(0, 0, w, h,
                        new int[]{0xF2FFFFFF, 0x33FFFFFF, 0x26FFFFFF, 0x99FFFFFF},
                        new float[]{0f, 0.35f, 0.65f, 1f}, Shader.TileMode.CLAMP));
            }
            int save = c.save();
            c.translate(left, top);
            r.set(0, 0, w, h);

            if (outerGlow) {
                // Soft lift under the glass.
                for (int i = 1; i <= 3; i++) {
                    shadow.setStrokeWidth(i * 2.5f * density);
                    shadow.setColor(((0x22 / i) * alpha / 255) << 24);
                    float o = i * density;
                    c.drawRoundRect(-o, -o + 2 * density, w + o, h + o + 2 * density, radius + o, radius + o, shadow);
                }
            }

            body.setAlpha(alpha);
            c.drawRoundRect(r, radius, radius, body);

            clip.reset();
            clip.addRoundRect(r, radius, radius, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            specular.setAlpha(alpha);
            // Specular band: a lens-shaped highlight inset from the top edge.
            c.drawRoundRect(1.5f * density, 1.5f * density, w - 1.5f * density, h * 0.5f, radius, radius, specular);
            // Inner glow along the bottom edge, where light exits the glass.
            glow.setStrokeWidth(5f * density);
            glow.setColor((0x26 * alpha / 255) << 24 | 0xFFFFFF);
            c.drawLine(radius, h - 1f * density, w - radius, h - 1f * density, glow);
            c.restore();

            rim.setAlpha(alpha);
            float i = rim.getStrokeWidth() / 2f;
            c.drawRoundRect(i, i, w - i, h - i, radius - i, radius - i, rim);
            c.restoreToCount(save);
        }

        private static int mix(int a, int b, float t) {
            return Math.round(a + (b - a) * (1 - t));
        }
    }

    /** Liquid Glass as a view background (buttons, chips, the tab bar). */
    static final class GlassDrawable extends Drawable {
        private final Painter painter;
        private final float radius;

        GlassDrawable(float density, float radiusPx, float fill) {
            painter = new Painter(density, fill);
            radius = radiusPx;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            android.graphics.Rect b = getBounds();
            Object cb = getCallback();
            android.view.View host = cb instanceof android.view.View ? (android.view.View) cb : null;
            painter.draw(canvas, host, b.left, b.top, b.right, b.bottom, Math.min(radius, b.height() / 2f), false);
        }

        @Override
        public void setAlpha(int alpha) {
            painter.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
