package tw.nekomimi.nekogram.glass;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.drawable.Drawable;
import android.os.Build;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;

/**
 * The clear glass lens of the bottom main tabs bar, as FlClash draws its NavigationDock.
 * <p>
 * Ported from prism_ui (https://github.com/chen08209/prism_ui, the UI kit of FlClash),
 * GPL-3.0 like Nekogram: lib/navigation_dock.dart (_GlassLens, _LensContent, _lensShadows,
 * _LensTint) and lib/liquid_glass.dart (LiquidGlassStyle.lens / lensContent, scale(), the
 * uniforms _LiquidGlassLayer feeds shaders/liquid_glass.frag, here res/raw/neko_flclash_glass.agsl).
 * <p>
 * Two passes, each a RenderNode with a RuntimeShader RenderEffect:
 * <ol>
 *     <li>glass: what lies under the lens (the bar's blurred backdrop) bent by the raised lens
 *     ({@code LiquidGlassStyle.lens}: refraction -4.3, bevel 5.5, dispersion 6.5, rim 0.6),</li>
 *     <li>content: the destinations under the lens, magnified by 1 + 0.16 * lift, tinted in the
 *     selection color and warped by {@code LiquidGlassStyle.lensContent} (refraction 14,
 *     bevel 11.2, dispersion 3.6), replacing what lies inside the lens.</li>
 * </ol>
 * Needs API 33 (RuntimeShader); any failure turns it off and the caller falls back.
 */
@RequiresApi(api = 33)
public final class FlClashLens {

    /** prism_ui navigation_dock.dart _lensMagnify. */
    public static final float LENS_MAGNIFY = 0.16f;
    /** prism_ui navigation_dock.dart _lensGrowth (dp each side at full lift). */
    public static final float LENS_GROWTH_DP = 8f;

    public interface ContentPainter {
        /** Draws the destinations, each scaled by {@code magnify} about its own center. */
        void paint(Canvas canvas, float magnify);
    }

    private static String source;

    private RuntimeShader glassShader;
    private RuntimeShader contentShader;
    private final RenderNode glassNode = new RenderNode("nekoFlClashLensGlass");
    private final RenderNode contentNode = new RenderNode("nekoFlClashLensContent");
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private boolean failed;

    public static boolean isAvailable() {
        return Build.VERSION.SDK_INT >= 33;
    }

    public boolean isFailed() {
        return failed;
    }

    private boolean ensure() {
        if (failed) {
            return false;
        }
        if (glassShader != null) {
            return true;
        }
        try {
            if (source == null) {
                source = AndroidUtilities.readRes(R.raw.neko_flclash_glass);
            }
            glassShader = new RuntimeShader(source);
            contentShader = new RuntimeShader(source);
            return true;
        } catch (Throwable e) {
            failed = true;
            FileLog.e(e);
            return false;
        }
    }

    /**
     * prism_ui _lensShadows: one shadow, blur 5, 8dp down, alpha 0.055 (three times that in
     * dark mode) times the lift. Hardware canvases drop mask filters, so it is layered by hand.
     */
    public void drawShadow(Canvas canvas, RectF lens, float lift, boolean dark) {
        final float l = Math.max(0f, Math.min(1f, lift));
        if (l <= 0f) {
            return;
        }
        final float alpha = 0.055f * (dark ? 3f : 1f) * l;
        final float blur = dp(5);
        final int steps = 5;
        for (int i = 0; i < steps; i++) {
            final float t = (i + 0.5f) / steps;
            final float grow = blur * (t - 0.5f) * 2f;
            tmp.set(lens.left - grow, lens.top - grow + dp(8) * l, lens.right + grow, lens.bottom + grow + dp(8) * l);
            final float r = Math.max(0f, tmp.height() / 2f);
            shadowPaint.setColor(Color.argb(Math.round(255 * alpha / steps * 1.6f), 0, 0, 0));
            canvas.drawRoundRect(tmp, r, r, shadowPaint);
        }
    }

    /**
     * Pass 1: the raised clear lens over {@code backdrop}, which is drawn with view-local bounds
     * {@code backdropBounds}.
     */
    public boolean drawGlass(Canvas canvas, int viewW, int viewH, RectF lens, float radius, float lift, boolean dark,
                             Drawable backdrop, int bl, int bt, int br, int bb) {
        if (!ensure() || !canvas.isHardwareAccelerated() || lift <= 0f || backdrop == null) {
            return false;
        }
        try {
            final int margin = dp(14);
            final int l = Math.max(0, (int) Math.floor(lens.left) - margin);
            final int t = Math.max(0, (int) Math.floor(lens.top) - margin);
            final int r = Math.min(viewW, (int) Math.ceil(lens.right) + margin);
            final int b = Math.min(viewH, (int) Math.ceil(lens.bottom) + margin);
            if (r - l <= 0 || b - t <= 0) {
                return false;
            }
            glassNode.setPosition(l, t, r, b);
            final RecordingCanvas c = glassNode.beginRecording(r - l, b - t);
            try {
                c.translate(-l, -t);
                backdrop.setBounds(bl, bt, br, bb);
                backdrop.draw(c);
            } finally {
                glassNode.endRecording();
            }
            // LiquidGlassStyle.lens(colorScheme).scale(lift)
            final float a = Math.max(0f, Math.min(1f, lift));
            setUniforms(glassShader, l, t, r, b, lens, radius,
                /* bevel */ 5.5f, /* blur */ 0f,
                /* refraction */ -4.3f * a, /* saturation */ 1f, /* brightness */ 1f + ((dark ? 1.33f : 1f) - 1f) * a,
                /* rim */ (dark ? 0.3f : 0.6f) * a, /* rimWidth */ 1.1f, /* endShade */ 0.045f * a,
                /* hairline */ 0.42f * a, /* dispersion */ 6.5f * a, /* rimBlur */ 1.5f * a,
                /* rimTail */ 0f, /* rimFocus */ 0f, /* rimAdded */ 0f, /* hairlineSide */ 0.42f * a);
            glassNode.setRenderEffect(RenderEffect.createRuntimeShaderEffect(glassShader, "img"));
            canvas.drawRenderNode(glassNode);
            return true;
        } catch (Throwable e) {
            failed = true;
            FileLog.e(e);
            return false;
        }
    }

    /**
     * Pass 2: the destinations under the lens, magnified, tinted in {@code tint} and warped.
     */
    public boolean drawContent(Canvas canvas, int viewW, int viewH, RectF lens, float radius, float lift, boolean dark,
                               int tint, ContentPainter painter) {
        if (!ensure() || !canvas.isHardwareAccelerated() || lift <= 0f) {
            return false;
        }
        try {
            final int margin = dp(2);
            final int l = Math.max(0, (int) Math.floor(lens.left) - margin);
            final int t = Math.max(0, (int) Math.floor(lens.top) - margin);
            final int r = Math.min(viewW, (int) Math.ceil(lens.right) + margin);
            final int b = Math.min(viewH, (int) Math.ceil(lens.bottom) + margin);
            if (r - l <= 0 || b - t <= 0) {
                return false;
            }
            final float a = Math.max(0f, Math.min(1f, lift));
            contentNode.setPosition(l, t, r, b);
            final RecordingCanvas c = contentNode.beginRecording(r - l, b - t);
            try {
                c.translate(-l, -t);
                painter.paint(c, 1f + LENS_MAGNIFY * a);
            } finally {
                contentNode.endRecording();
            }
            // LiquidGlassStyle.lensContent(colorScheme).scale(lift)
            setUniforms(contentShader, l, t, r, b, lens, radius,
                /* bevel */ 11.2f, /* blur */ 0f,
                /* refraction */ 14f * a, /* saturation */ 1f, /* brightness */ 1f + ((dark ? 1.33f : 1f) - 1f) * a,
                /* rim */ 0f, /* rimWidth */ 1.3f, /* endShade */ 0f,
                /* hairline */ 0f, /* dispersion */ 3.6f * a, /* rimBlur */ 2.5f * a,
                /* rimTail */ 0f, /* rimFocus */ 0f, /* rimAdded */ 0f, /* hairlineSide */ 0f);
            // _LensTint: ColorFilter.mode(primary, BlendMode.srcIn), then the lensContent pane
            final RenderEffect tinted = RenderEffect.createColorFilterEffect(new PorterDuffColorFilter(tint | 0xFF000000, PorterDuff.Mode.SRC_IN));
            contentNode.setRenderEffect(RenderEffect.createChainEffect(
                RenderEffect.createRuntimeShaderEffect(contentShader, "img"), tinted));
            canvas.drawRenderNode(contentNode);
            return true;
        } catch (Throwable e) {
            failed = true;
            FileLog.e(e);
            return false;
        }
    }

    /**
     * What _LiquidGlassLayer writes: the texture's size and mapping, then _look(style). Lengths
     * of the style are dp; the node is in pixels with (l, t) its top left in the view.
     */
    private static void setUniforms(RuntimeShader s, int l, int t, int r, int b, RectF lens, float radiusPx,
                                    float bevel, float blur, float refraction, float saturation, float brightness,
                                    float rim, float rimWidth, float endShade,
                                    float hairline, float dispersion, float rimBlur,
                                    float rimTail, float rimFocus, float rimAdded, float hairlineSide) {
        final float d = AndroidUtilities.density;
        final float w = r - l, h = b - t;
        // glass point (dp from the lens' top left) = ((l - lens.left) + fragCoord) / density
        final float ox = (l - lens.left) / d, oy = (t - lens.top) / d;
        s.setFloatUniform("uSize", w, h);
        s.setFloatUniform("uFrame", ox, oy, 1f / d, 1f / d);
        s.setFloatUniform("uSample", ox, oy, 1f / d, 1f / d);
        s.setFloatUniform("uClamp", 0.5f, 0.5f, w - 0.5f, h - 0.5f);
        s.setFloatUniform("uTexels", 1f / d, 0f);
        final float rd = radiusPx / d;
        s.setFloatUniform("uShape", lens.width() / d, lens.height() / d, bevel, blur);
        s.setFloatUniform("uCorners", rd, rd, rd, rd);
        s.setFloatUniform("uOptics", refraction, saturation, brightness);
        s.setFloatUniform("uTint", 0f, 0f, 0f, 0f);
        s.setFloatUniform("uLight", rim, rimWidth, endShade);
        s.setFloatUniform("uEdge", hairline, dispersion, rimBlur);
        s.setFloatUniform("uRim", rimTail, rimFocus, rimAdded, hairlineSide);
    }

    @Nullable
    public static FlClashLens create() {
        return isAvailable() ? new FlClashLens() : null;
    }
}
