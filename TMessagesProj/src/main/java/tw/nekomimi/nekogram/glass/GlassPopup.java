package tw.nekomimi.nekogram.glass;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.PopupWindow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;

/**
 * Liquid Glass popups (long-press message menu, overflow menus, other ActionBarPopupWindows) and
 * the glass floating buttons of the chat list. Everything here is only reached when
 * {@link NekoGlass#isActive()}; with the switch off Telegram's own drawables and animations stay.
 */
public final class GlassPopup {

    public static final int RADIUS_DP = 20;
    public static final float JELLY_FROM = 0.85f;
    public static final float JELLY_DAMPING = 0.6f;
    public static final float JELLY_STIFFNESS = 520f;

    private GlassPopup() {
    }

    @SuppressWarnings("deprecation")
    static void clipOut(Canvas canvas, Path path) {
        if (Build.VERSION.SDK_INT >= 26) {
            canvas.clipOutPath(path);
        } else {
            canvas.clipPath(path, Region.Op.DIFFERENCE);
        }
    }

    // ---------------------------------------------------------------- popup background

    /**
     * Replaces the 9-patch popup background: soft shadow + blurred snapshot of what is behind the
     * popup + translucent menu-colour tint + sheen + specular rim, 20dp corners. Keeps the 9-patch
     * paddings so the menu layout does not move.
     */
    public static final class Background extends Drawable {
        private final View host;
        private final Rect padding = new Rect();
        private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint blurPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private final Matrix matrix = new Matrix();
        private final int[] loc = new int[2];
        private int color = Color.WHITE;
        private int alpha = 255;
        private Bitmap blur;
        private BitmapShader blurShader;
        private float blurScale = 1f;
        private int blurX, blurY;

        public Background(View host, @Nullable Drawable original) {
            this.host = host;
            if (original != null) {
                original.getPadding(padding);
            }
        }

        public void setColor(int color) {
            this.color = color;
            invalidateSelf();
        }

        void setBlur(@Nullable Bitmap bitmap, float scale, int screenX, int screenY) {
            blur = bitmap;
            blurShader = bitmap == null ? null : new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            blurScale = scale;
            blurX = screenX;
            blurY = screenY;
            invalidateSelf();
        }

        @Override
        public boolean getPadding(@NonNull Rect out) {
            out.set(padding);
            return true;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            final Rect b = getBounds();
            rect.set(b.left + padding.left, b.top + padding.top, b.right - padding.right, b.bottom - padding.bottom);
            if (rect.width() <= 1 || rect.height() <= 1 || alpha <= 0) {
                return;
            }
            final float a = alpha / 255f;
            final float r = Math.min(dp(RADIUS_DP), Math.min(rect.width(), rect.height()) / 2f);
            final int base = color | 0xFF000000;
            final boolean dark = NekoGlass.isDark(base);

            path.rewind();
            path.addRoundRect(rect, r, r, Path.Direction.CW);
            // shadow outside the glass only
            canvas.save();
            clipOut(canvas, path);
            shadowPaint.setColor(dark ? 0xFF000000 : 0xFFFFFFFF);
            shadowPaint.setShadowLayer(dp(10), 0, dp(3), Color.argb((int) ((dark ? 0x90 : 0x38) * a), 0, 0, 0));
            canvas.drawRoundRect(rect, r, r, shadowPaint);
            canvas.restore();

            boolean blurred = false;
            if (blurShader != null && blur != null && !blur.isRecycled() && host != null) {
                host.getLocationOnScreen(loc);
                matrix.setScale(blurScale, blurScale);
                matrix.postTranslate(blurX - loc[0], blurY - loc[1]);
                blurShader.setLocalMatrix(matrix);
                blurPaint.setShader(blurShader);
                blurPaint.setAlpha(alpha);
                canvas.drawRoundRect(rect, r, r, blurPaint);
                blurred = true;
            }
            fillPaint.setShader(null);
            fillPaint.setColor(Theme.multAlpha(base, NekoGlass.fillAlpha(blurred ? (dark ? 0.62f : 0.66f) : 0.92f) * a));
            canvas.drawRoundRect(rect, r, r, fillPaint);
            NekoGlass.drawSheen(canvas, rect, r, dark, a);
            NekoGlass.drawRim(canvas, rect, r, dark, a);
        }

        @Override
        public void setAlpha(int alpha) {
            if (this.alpha != alpha) {
                this.alpha = alpha;
                invalidateSelf();
            }
        }

        @Override
        public int getAlpha() {
            return alpha;
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            // tint comes from setColor(); Telegram's MULTIPLY filter would make the glass opaque
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** Wraps a popup layout's 9-patch background when Liquid Glass is on, otherwise returns it as is. */
    public static Drawable wrapBackground(View host, @Nullable Drawable original) {
        if (original == null || !NekoGlass.isActive()) {
            return original;
        }
        return new Background(host, original);
    }

    // ---------------------------------------------------------------- show: blur + jelly

    /** Called right after an {@link ActionBarPopupWindow} is shown. */
    public static void onShow(PopupWindow window, @Nullable View anchor) {
        if (!NekoGlass.isActive() || window == null) {
            return;
        }
        final View content = window.getContentView();
        if (content == null) {
            return;
        }
        final ArrayList<Background> backgrounds = new ArrayList<>();
        collect(content, backgrounds, 0);
        if (!backgrounds.isEmpty() && anchor != null) {
            try {
                final View root = anchor.getRootView();
                if (root != null && root.getWidth() > 0 && root.getHeight() > 0) {
                    final float downscale = 8f;
                    final Bitmap bitmap = AndroidUtilities.makeBlurBitmap(root, downscale, 14);
                    final int[] rootLoc = new int[2];
                    root.getLocationOnScreen(rootLoc);
                    final float scale = root.getWidth() / (float) Math.max(1, bitmap.getWidth());
                    for (Background bg : backgrounds) {
                        bg.setBlur(bitmap, scale, rootLoc[0], rootLoc[1]);
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        jellyIn(content);
    }

    private static void collect(View view, ArrayList<Background> out, int depth) {
        if (depth > 6 || view == null) {
            return;
        }
        if (view instanceof ActionBarPopupWindow.ActionBarPopupWindowLayout) {
            final Drawable d = ((ActionBarPopupWindow.ActionBarPopupWindowLayout) view).getBackgroundDrawable();
            if (d instanceof Background) {
                out.add((Background) d);
            }
        }
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0, n = group.getChildCount(); i < n; i++) {
                collect(group.getChildAt(i), out, depth + 1);
            }
        }
    }

    /** Jelly pop-in: scale from 0.85 to 1 on an under-damped spring (overshoots a little). */
    public static void jellyIn(final View view) {
        view.setScaleX(JELLY_FROM);
        view.setScaleY(JELLY_FROM);
        view.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                view.getViewTreeObserver().removeOnPreDrawListener(this);
                final int w = view.getWidth(), h = view.getHeight();
                final int[] loc = new int[2];
                view.getLocationOnScreen(loc);
                final int screenW = AndroidUtilities.displaySize.x;
                final int screenH = AndroidUtilities.displaySize.y;
                boolean fromBottom = view instanceof ActionBarPopupWindow.ActionBarPopupWindowLayout
                    && ((ActionBarPopupWindow.ActionBarPopupWindowLayout) view).shownFromBottom;
                if (!(view instanceof ActionBarPopupWindow.ActionBarPopupWindowLayout)) {
                    fromBottom = loc[1] + h / 2f > screenH * 0.6f;
                }
                view.setPivotX(loc[0] + w / 2f > screenW / 2f ? w : 0);
                view.setPivotY(fromBottom ? h : 0);
                spring(view, DynamicAnimation.SCALE_X, JELLY_FROM);
                spring(view, DynamicAnimation.SCALE_Y, JELLY_FROM);
                return true;
            }
        });
    }

    private static void spring(View view, DynamicAnimation.ViewProperty property, float from) {
        final SpringAnimation animation = new SpringAnimation(view, property, 1f);
        animation.getSpring().setDampingRatio(JELLY_DAMPING).setStiffness(JELLY_STIFFNESS);
        animation.setStartValue(from);
        animation.start();
    }

    // ---------------------------------------------------------------- menu item pill

    /** Pressed menu row: a small glass pill instead of a full-width ripple. */
    public static Drawable createItemSelector(int selectorColor) {
        final StateListDrawable d = new StateListDrawable();
        final Pill pill = new Pill(selectorColor);
        d.addState(new int[]{android.R.attr.state_pressed}, pill);
        d.addState(new int[]{android.R.attr.state_selected}, new Pill(selectorColor));
        d.addState(new int[]{android.R.attr.state_focused}, new Pill(selectorColor));
        d.setEnterFadeDuration(90);
        d.setExitFadeDuration(180);
        return d;
    }

    private static final class Pill extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final int color;
        private int alpha = 255;

        Pill(int color) {
            this.color = color;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            final Rect b = getBounds();
            rect.set(b.left + dp(6), b.top + dpf2(2), b.right - dp(6), b.bottom - dpf2(2));
            if (rect.width() <= 0 || rect.height() <= 0) {
                return;
            }
            final float a = alpha / 255f;
            final float r = Math.min(dp(14), rect.height() / 2f);
            final boolean dark = Theme.isCurrentThemeDark();
            paint.setColor(Theme.multAlpha(color | 0xFF000000, Math.min(1f, Color.alpha(color) / 255f * 1.6f) * a));
            canvas.drawRoundRect(rect, r, r, paint);
            paint.setColor(Color.argb((int) ((dark ? 0x10 : 0x2A) * a), 255, 255, 255));
            canvas.drawRoundRect(rect, r, r, paint);
            NekoGlass.drawSheen(canvas, rect, r, dark, a);
            NekoGlass.drawRim(canvas, rect, r, dark, a * 0.8f);
        }

        @Override
        public void setAlpha(int alpha) {
            this.alpha = alpha;
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // ---------------------------------------------------------------- floating buttons

    private static final Paint fabShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint fabFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path fabPath = new Path();

    /**
     * Glass circle for a floating button: soft shadow (outside only), translucent fill of
     * {@code fillColor}, sheen and specular rim. {@code pressed} lightens the glass a little.
     */
    public static void drawFab(Canvas canvas, RectF rect, int fillColor, float fillAlpha, boolean pressed, boolean shadow) {
        if (rect.width() <= 1) {
            return;
        }
        final float r = Math.min(rect.width(), rect.height()) / 2f;
        final boolean dark = Theme.isCurrentThemeDark();
        if (shadow) {
            fabPath.rewind();
            fabPath.addRoundRect(rect, r, r, Path.Direction.CW);
            canvas.save();
            clipOut(canvas, fabPath);
            fabShadow.setColor(0xFF000000);
            fabShadow.setShadowLayer(dpf2(3.5f), 0, dpf2(1.5f), dark ? 0x80000000 : 0x40000000);
            canvas.drawRoundRect(rect, r, r, fabShadow);
            canvas.restore();
        }
        fabFill.setColor(Theme.multAlpha(fillColor | 0xFF000000, NekoGlass.fillAlpha(fillAlpha)));
        canvas.drawRoundRect(rect, r, r, fabFill);
        if (pressed) {
            fabFill.setColor(Color.argb(dark ? 0x22 : 0x38, 255, 255, 255));
            canvas.drawRoundRect(rect, r, r, fabFill);
        }
        NekoGlass.drawSheen(canvas, rect, r, dark, 1f);
        NekoGlass.drawRim(canvas, rect, r, dark, 1f);
    }
}
