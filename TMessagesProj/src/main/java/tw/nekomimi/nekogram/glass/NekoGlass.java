package tw.nekomimi.nekogram.glass;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

import tw.nekomimi.nekogram.NekoConfig;

/**
 * Nekogram "Liquid Glass" master switch helpers.
 * <p>
 * Optics (lens refraction + light blur + vibrancy + specular rim) follow KunBox's
 * LiquidGlassTheme (https://github.com/roseforljh/KunBox, MIT).
 * When {@link NekoConfig#liquidGlass} is off nothing in here changes the app.
 */
public final class NekoGlass {

    private NekoGlass() {
    }

    /** Liquid glass needs AGSL runtime shaders (Android 13, API 33). */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= 33;
    }

    /** True when the user turned the switch on and the device can render it. */
    public static boolean isActive() {
        return Build.VERSION.SDK_INT >= 33 && NekoConfig.liquidGlass;
    }

    public static boolean isDark(int backgroundColor) {
        return AndroidUtilities.computePerceivedBrightness(backgroundColor) < 0.721f;
    }

    private static String shaderSource;

    @RequiresApi(api = 33)
    public static RuntimeShader createShader() {
        if (shaderSource == null) {
            shaderSource = AndroidUtilities.readRes(R.raw.neko_liquid_glass_shader);
        }
        return new RuntimeShader(shaderSource);
    }

    /**
     * KunBox-like optics: opticsScale = clamp(minSide / 64dp, 0.25, 1);
     * controls: lens height 40dp, amount 40dp; dialogs: 48dp / 28dp. Vibrancy 1.5.
     */
    @RequiresApi(api = 33)
    public static void setOpticsUniforms(RuntimeShader shader, float width, float height, boolean dialog, boolean dark) {
        final float min = Math.max(1f, Math.min(width, height));
        final float scale = Math.max(0.25f, Math.min(1f, min / dp(64)));
        final float lensHeight = Math.min((dialog ? dp(48) : dp(40)) * scale, min * 0.5f);
        final float lensAmount = (dialog ? dp(28) : dp(40)) * scale;
        shader.setFloatUniform("lens_height", lensHeight);
        shader.setFloatUniform("lens_amount", lensAmount);
        shader.setFloatUniform("vibrancy", 1.5f);
        shader.setFloatUniform("highlight", dark ? 0.30f : 0.55f);
        shader.setFloatUniform("rim_width", dpf2(1.33f));
    }

    /** Native cross-window blur for dialog windows (bottom sheets). */
    public static void applyBlurBehind(WindowManager.LayoutParams params, int radius) {
        if (!isActive() || params == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 31) {
            params.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            params.setBlurBehindRadius(radius);
        }
    }

    /**
     * Draws a glass slab whose backdrop is {@code backdrop} (already positioned in the canvas'
     * local coordinates, e.g. a blurred screenshot BitmapShader), refracted by the lens shader.
     */
    public static final class Painter {
        private Object shader; // RuntimeShader, kept as Object so the class loads on any API
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean failed;

        public void draw(Canvas canvas, RectF rect, float radius, @Nullable Shader backdrop, int tintColor, float alpha, boolean dialog) {
            if (rect.width() <= 0 || rect.height() <= 0 || alpha <= 0) {
                return;
            }
            final boolean dark = isDark(tintColor | 0xFF000000);
            if (backdrop != null && !failed && Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated()) {
                try {
                    if (shader == null) {
                        shader = createShader();
                    }
                    final RuntimeShader s = (RuntimeShader) shader;
                    s.setInputShader("img", backdrop);
                    final float r = Math.min(radius, Math.min(rect.width(), rect.height()) / 2f);
                    s.setFloatUniform("resolution", rect.width(), rect.height());
                    s.setFloatUniform("center", rect.centerX(), rect.centerY());
                    s.setFloatUniform("size", rect.width() / 2f, rect.height() / 2f);
                    s.setFloatUniform("radius", r, r, r, r);
                    s.setFloatUniform("thickness", dp(11));
                    s.setFloatUniform("refract_intensity", 1f);
                    s.setFloatUniform("refract_index", 1.5f);
                    final float a = Color.alpha(tintColor) / 255f;
                    s.setFloatUniform("foreground_color_premultiplied",
                        Color.red(tintColor) / 255f * a,
                        Color.green(tintColor) / 255f * a,
                        Color.blue(tintColor) / 255f * a,
                        a);
                    setOpticsUniforms(s, rect.width(), rect.height(), dialog, dark);
                    paint.setShader(s);
                    paint.setAlpha((int) (255 * Math.min(1f, alpha)));
                    canvas.drawRoundRect(rect, r, r, paint);
                    return;
                } catch (Throwable e) {
                    failed = true;
                    FileLog.e(e);
                }
            }
            fallbackPaint.setColor(tintColor);
            fallbackPaint.setAlpha((int) (Color.alpha(tintColor) * Math.min(1f, alpha)));
            canvas.drawRoundRect(rect, radius, radius, fallbackPaint);
            drawRim(canvas, rect, radius, dark, alpha);
        }
    }

    private static final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static LinearGradient rimShader;
    private static float rimShaderH = -1;
    private static boolean rimShaderDark;

    static {
        rimPaint.setStyle(Paint.Style.STROKE);
    }

    private static Shader rimShader(float top, float height, boolean dark) {
        if (rimShader == null || Math.abs(rimShaderH - height) > 0.5f || rimShaderDark != dark) {
            final int a1 = dark ? 0x70 : 0xC0;
            final int a2 = dark ? 0x14 : 0x30;
            final int a3 = dark ? 0x38 : 0x70;
            rimShader = new LinearGradient(0, 0, 0, Math.max(1f, height),
                new int[]{Color.argb(a1, 255, 255, 255), Color.argb(a2, 255, 255, 255), Color.argb(a3, 255, 255, 255)},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
            rimShaderH = height;
            rimShaderDark = dark;
        }
        final android.graphics.Matrix m = new android.graphics.Matrix();
        m.setTranslate(0, top);
        rimShader.setLocalMatrix(m);
        return rimShader;
    }

    /** Specular rim: bright on top, faint in the middle, a little light again at the bottom. */
    public static void drawRim(Canvas canvas, RectF rect, float radius, boolean dark, float alpha) {
        rimPaint.setStrokeWidth(dpf2(1f));
        rimPaint.setShader(rimShader(rect.top, rect.height(), dark));
        rimPaint.setAlpha((int) (255 * Math.max(0f, Math.min(1f, alpha))));
        final float inset = rimPaint.getStrokeWidth() / 2f;
        AndroidUtilities.rectTmp.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset);
        final float r = Math.max(0, radius - inset);
        canvas.drawRoundRect(AndroidUtilities.rectTmp, r, r, rimPaint);
    }

    public static void drawRim(Canvas canvas, Path path, float top, float height, boolean dark, float alpha) {
        rimPaint.setStrokeWidth(dpf2(1f));
        rimPaint.setShader(rimShader(top, height, dark));
        rimPaint.setAlpha((int) (255 * Math.max(0f, Math.min(1f, alpha))));
        canvas.drawPath(path, rimPaint);
    }

    /** Top sheen used on surfaces that have no live backdrop (bubbles, floating buttons, pills). */
    public static void drawSheen(Canvas canvas, RectF rect, float radius, boolean dark, float alpha) {
        final int a = (int) ((dark ? 0x16 : 0x2C) * Math.max(0f, Math.min(1f, alpha)));
        if (a <= 0) {
            return;
        }
        sheenPaint.setShader(new LinearGradient(0, rect.top, 0, rect.top + rect.height() * 0.6f,
            Color.argb(a, 255, 255, 255), 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, radius, radius, sheenPaint);
    }

    private static final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** The glass pill used as the selected-tab indicator (see {@link JellyGlassIndicator}). */
    public static void drawPill(Canvas canvas, RectF rect, float radius, int accentColor, float lift, float alpha) {
        final boolean dark = Theme.isCurrentThemeDark();
        final float l = Math.max(0f, Math.min(1f, lift));
        pillPaint.setShader(null);
        pillPaint.setColor(Theme.multAlpha(accentColor, (0.13f + 0.07f * l) * alpha));
        canvas.drawRoundRect(rect, radius, radius, pillPaint);
        if (l > 0.01f) {
            pillPaint.setColor(Color.argb((int) ((dark ? 0x14 : 0x30) * l * alpha), 255, 255, 255));
            canvas.drawRoundRect(rect, radius, radius, pillPaint);
        }
        drawSheen(canvas, rect, radius, dark, alpha);
        drawRim(canvas, rect, radius, dark, alpha * (0.75f + 0.25f * l));
    }
}
