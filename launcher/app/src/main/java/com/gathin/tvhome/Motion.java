package com.gathin.tvhome;

import android.view.View;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * Apple's SwiftUI springs, rebuilt with AndroidX SpringForce.
 *
 * SwiftUI describes a spring by its perceptual duration and bounce (Spring(duration:bounce:)). With a mass of 1
 * that is stiffness = (2 pi / duration)^2 and damping ratio = 1 - bounce (bounce >= 0), or 1 / (1 + bounce) for
 * an overdamped spring. The presets use SwiftUI's own values:
 * smooth = 0.5 s / 0, snappy = 0.5 s / 0.15, bouncy = 0.5 s / 0.3.
 *
 * Rules, from Apple's guidance: no bounce unless something earns it, every animation can be retargeted
 * mid-flight (animateToFinalPosition keeps the current velocity), and nothing loops on the focused item.
 */
final class Motion {

    /** Default: panels, fades, scrolling rows, page changes. */
    static final Spec SMOOTH = new Spec(0.5f, 0f);
    /** Focus, tabs, buttons. */
    static final Spec SNAPPY = new Spec(0.5f, 0.15f);
    /** Liquid glass highlight and icons that change shape. */
    static final Spec BOUNCY = new Spec(0.5f, 0.3f);
    /** Ambience: backgrounds and long crossfades. */
    static final Spec SLOW = new Spec(1.0f, 0f);

    static final class Spec {
        final float stiffness;
        final float dampingRatio;

        Spec(float duration, float bounce) {
            double w = 2 * Math.PI / duration;
            stiffness = (float) (w * w);
            dampingRatio = bounce >= 0 ? 1f - bounce : 1f / (1f + bounce);
        }

        SpringForce force(float finalPosition) {
            return new SpringForce(finalPosition).setStiffness(stiffness).setDampingRatio(dampingRatio);
        }
    }

    private Motion() {
    }

    /**
     * Springs a view property to a value, reusing one SpringAnimation per view and property so a new target
     * retargets the running spring instead of restarting it.
     */
    static void to(View v, DynamicAnimation.ViewProperty prop, float value, Spec spec) {
        int key = keyFor(prop);
        SpringAnimation a = (SpringAnimation) v.getTag(key);
        if (a == null) {
            a = new SpringAnimation(v, prop);
            v.setTag(key, a);
        }
        if (a.getSpring() == null || a.getSpring().getStiffness() != spec.stiffness
                || a.getSpring().getDampingRatio() != spec.dampingRatio) {
            a.setSpring(spec.force(value));
        }
        if (prop == DynamicAnimation.ALPHA || prop == DynamicAnimation.SCALE_X || prop == DynamicAnimation.SCALE_Y) {
            a.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE);
        }
        a.animateToFinalPosition(value);
    }

    /** Jumps a property to a value, cancelling any spring on it. */
    static void set(View v, DynamicAnimation.ViewProperty prop, float value) {
        SpringAnimation a = (SpringAnimation) v.getTag(keyFor(prop));
        if (a != null) a.cancel();
        if (prop == DynamicAnimation.ALPHA) v.setAlpha(value);
        else if (prop == DynamicAnimation.SCALE_X) v.setScaleX(value);
        else if (prop == DynamicAnimation.SCALE_Y) v.setScaleY(value);
        else if (prop == DynamicAnimation.TRANSLATION_X) v.setTranslationX(value);
        else if (prop == DynamicAnimation.TRANSLATION_Y) v.setTranslationY(value);
        else if (prop == DynamicAnimation.TRANSLATION_Z) v.setTranslationZ(value);
    }

    static void scale(View v, float s, Spec spec) {
        to(v, DynamicAnimation.SCALE_X, s, spec);
        to(v, DynamicAnimation.SCALE_Y, s, spec);
    }

    private static int keyFor(DynamicAnimation.ViewProperty prop) {
        if (prop == DynamicAnimation.ALPHA) return R.id.motion_alpha;
        if (prop == DynamicAnimation.SCALE_X) return R.id.motion_scale_x;
        if (prop == DynamicAnimation.SCALE_Y) return R.id.motion_scale_y;
        if (prop == DynamicAnimation.TRANSLATION_X) return R.id.motion_tx;
        if (prop == DynamicAnimation.TRANSLATION_Y) return R.id.motion_ty;
        if (prop == DynamicAnimation.TRANSLATION_Z) return R.id.motion_tz;
        throw new IllegalArgumentException("Unsupported property");
    }
}
