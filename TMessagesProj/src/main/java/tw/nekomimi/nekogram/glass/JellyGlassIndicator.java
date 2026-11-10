package tw.nekomimi.nekogram.glass;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/**
 * Jelly glass pill for selected-tab indicators: follows the finger with a stiff spring,
 * stretches along the motion and squashes when braking, overdrags with a rubber band at
 * the ends and grows (lifts) under a press, then settles with a soft bounce.
 * <p>
 * Behaviour and constants are ported from prism_ui's NavigationDock
 * (lib/navigation_dock.dart, https://github.com/chen08209/prism_ui, part of FlClash),
 * licensed under GPL-3.0 like Nekogram. Flutter springs are converted with
 * stiffness = (2*pi/duration)^2, damping = 2 * ratio * sqrt(stiffness), mass = 1.
 */
public class JellyGlassIndicator {

    // prism_ui constants
    public static final float JELLY_SPEED = 3f;       // destinations per second for full stretch
    public static final float JELLY_STRETCH = 0.2f;
    public static final float JELLY_BRAKE = 25f;      // destinations per second^2 for full squash
    public static final float JELLY_SQUASH = 0.15f;
    public static final float JELLY_ASPECT = 1.4f;
    public static final float OVERDRAG = 0.35f;
    public static final float PULL_STRETCH = 0.5f;
    public static final float LIFT_SNAP = 0.975f;    // _liftSnap
    public static final float LIFT_RELEASE = 0.04f;  // _liftRelease, in destinations
    public static final float PRESS_GROWTH = 1f / 8f; // ElasticPress _pressGrowth
    public static final float MAX_PRESS_GROWTH_DP = 16f; // _maxPressGrowth

    // springs (stiffness, damping)
    private static final float FOLLOW_K = 1024f, FOLLOW_C = 2f * 0.9f * 32f;          // ratio 0.9
    private static final float TRAVEL_K = 246.74f, TRAVEL_C = 2f * 0.85f * 15.708f;    // 400ms, bounce 0.15
    private static final float SETTLE_K = 157.91f, SETTLE_C = 2f * 0.68f * 12.566f;    // 500ms, bounce 0.32
    private static final float JELLY_K = 100f, JELLY_C = 2f * 0.4f * 10f;              // ratio 0.4
    private static final float PRESS_K = 311f, PRESS_C = 2f * 0.593f * 17.635f;        // ratio 0.593
    private static final float LIFT_K = 631.65f, LIFT_C = 2f * 25.133f;                // 250ms, no bounce
    private static final float TRACK_K = 2741.6f, TRACK_C = 2f * 52.36f;               // 120ms, no bounce

    public static final int MODE_TRAVEL = 0;
    public static final int MODE_FOLLOW = 1;
    public static final int MODE_SETTLE = 2;

    private final View host;

    private boolean hasValue;
    private float x, vx, w, vw;
    private float targetX, targetW;
    private float jelly, vJelly;
    private float lift, vLift, liftTarget;
    private float pull, vPull, pullTarget;
    private float swell, vSwell, swellTarget;
    private boolean dropping;
    private int mode = MODE_TRAVEL;
    private float pitch = dp(64);
    private long lastFrameTime;
    private float lastAx;

    public JellyGlassIndicator(View host) {
        this.host = host;
    }

    /** Distance between neighbouring destinations, used to normalise jelly speed. */
    public void setPitch(float pitch) {
        if (pitch > 1) {
            this.pitch = pitch;
        }
    }

    public void setTarget(float centerX, float width, int mode) {
        if (!hasValue) {
            hasValue = true;
            x = targetX = centerX;
            w = targetW = width;
            vx = vw = 0;
            lastFrameTime = 0;
            return;
        }
        if (this.mode != mode || Math.abs(targetX - centerX) > 0.01f || Math.abs(targetW - width) > 0.01f) {
            this.mode = mode;
            targetX = centerX;
            targetW = width;
            host.invalidate();
        }
    }

    public void jumpTo(float centerX, float width) {
        hasValue = true;
        x = targetX = centerX;
        w = targetW = width;
        vx = vw = 0;
        jelly = vJelly = 0;
    }

    public boolean hasValue() {
        return hasValue;
    }

    /** Overshoot (px) of the finger beyond the first/last destination, already rubber-banded. */
    public void setPull(float pullPx) {
        if (pullTarget != pullPx) {
            pullTarget = pullPx;
            host.invalidate();
        }
    }

    /**
     * prism_ui FloatingNavigationBar: a press lifts the lens (_liftSpring) and swells the bar
     * (_pressSpring); on release the bar settles at once while the lens stays lifted until it
     * has risen all the way and arrived (_dropLift).
     */
    public void setPressed(boolean pressed) {
        if (pressed) {
            dropping = false;
            liftTarget = 1f;
            swellTarget = 1f;
        } else {
            dropping = liftTarget > 0f;
            swellTarget = 0f;
        }
        host.invalidate();
    }

    public float getSwell() {
        return swell;
    }

    public float getPull() {
        return pull;
    }

    public float getLift() {
        return lift;
    }

    public float getX() {
        return x;
    }

    public float getTargetX() {
        return targetX;
    }

    /** Velocity of the lens in px/s. */
    public float getVelocity() {
        return vx;
    }

    /** prism_ui _rubberBand: limit * (1 - 1 / (|overshoot| * 0.55 / limit + 1)) */
    public static float rubberBand(float overshoot, float limit) {
        if (limit <= 0) {
            return 0;
        }
        final float pull = 1f - 1f / (Math.abs(overshoot) * 0.55f / limit + 1f);
        return limit * pull * Math.signum(overshoot);
    }

    private static float saturate(float v) {
        return (float) (1.0 - 2.0 / (Math.exp(2.0 * v) + 1.0));
    }

    /**
     * Advances the springs to now. Returns true while anything is still moving, in which
     * case the host has been asked for another frame.
     */
    public boolean step() {
        final long now = SystemClock.uptimeMillis();
        float dt = lastFrameTime == 0 ? 1f / 60f : (now - lastFrameTime) / 1000f;
        lastFrameTime = now;
        if (dt <= 0) {
            dt = 1f / 120f;
        }
        dt = Math.min(dt, 0.05f);

        final float k, c;
        if (mode == MODE_FOLLOW) {
            k = FOLLOW_K;
            c = FOLLOW_C;
        } else if (mode == MODE_SETTLE) {
            k = SETTLE_K;
            c = SETTLE_C;
        } else {
            k = TRAVEL_K;
            c = TRAVEL_C;
        }
        if (dropping && lift >= LIFT_SNAP && Math.abs(targetX - x) <= LIFT_RELEASE * pitch) {
            dropping = false;
            liftTarget = 0f;
        }
        final float liftK = LIFT_K;
        final float liftC = LIFT_C;
        final float pullK = pullTarget == 0f ? PRESS_K : TRACK_K;
        final float pullC = pullTarget == 0f ? PRESS_C : TRACK_C;

        final int steps = Math.max(1, (int) Math.ceil(dt / (1f / 240f)));
        final float h = dt / steps;
        for (int i = 0; i < steps; i++) {
            final float ax = k * (targetX - x) - c * vx;
            vx += ax * h;
            x += vx * h;
            lastAx = ax;

            final float aw = k * (targetW - w) - c * vw;
            vw += aw * h;
            w += vw * h;

            // prism_ui _wobble(): stretch with speed, squash while braking
            final float speed = Math.abs(vx) / pitch;
            final float braking = Math.max(0f, -lastAx * Math.signum(vx)) / pitch;
            final float jellyTarget = JELLY_STRETCH * saturate(speed / JELLY_SPEED) - JELLY_SQUASH * saturate(braking / JELLY_BRAKE);
            final float aj = JELLY_K * (jellyTarget - jelly) - JELLY_C * vJelly;
            vJelly += aj * h;
            jelly += vJelly * h;

            final float al = liftK * (liftTarget - lift) - liftC * vLift;
            vLift += al * h;
            lift += vLift * h;

            final float ap = pullK * (pullTarget - pull) - pullC * vPull;
            vPull += ap * h;
            pull += vPull * h;

            final float as = PRESS_K * (swellTarget - swell) - PRESS_C * vSwell;
            vSwell += as * h;
            swell += vSwell * h;
        }

        final boolean moving = Math.abs(targetX - x) > 0.3f || Math.abs(vx) > 2f
            || Math.abs(targetW - w) > 0.3f || Math.abs(vw) > 2f
            || Math.abs(jelly) > 0.003f || Math.abs(vJelly) > 0.01f
            || Math.abs(liftTarget - lift) > 0.002f || Math.abs(vLift) > 0.01f
            || Math.abs(pullTarget - pull) > 0.3f || Math.abs(vPull) > 2f
            || Math.abs(swellTarget - swell) > 0.002f || Math.abs(vSwell) > 0.01f
            || dropping;
        if (moving) {
            host.postInvalidateOnAnimation();
        } else {
            x = targetX;
            w = targetW;
            vx = vw = 0;
            jelly = vJelly = 0;
            lift = liftTarget;
            vLift = 0;
            pull = pullTarget;
            vPull = 0;
            swell = swellTarget;
            vSwell = 0;
            lastFrameTime = 0;
        }
        return moving;
    }

    /**
     * prism_ui _lensRect: the lens grows by {@code growthPx} on each side when lifted,
     * widens with jelly and flattens by jelly * aspect. (A pull past the ends stretches the
     * whole bar, see _PressTransform, which the host applies to itself.)
     */
    public void getRect(RectF out, float centerY, float height, float growthPx) {
        final float growth = growthPx * 2f * lift;
        final float baseW = Math.max(0, w);
        final float lensW = (baseW + growth) * (1f + jelly);
        final float lensH = (height + growth) * Math.max(0.5f, 1f - jelly * JELLY_ASPECT);
        out.set(x - lensW / 2f, centerY - lensH / 2f, x + lensW / 2f, centerY + lensH / 2f);
    }
}
