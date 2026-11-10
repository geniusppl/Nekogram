package tw.nekomimi.nekogram.glass;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogCell;

/**
 * Liquid Glass chat list: every {@link DialogCell} becomes an independent rounded glass card
 * (FlClash dashboard style: 12dp side margin, 6dp gap, ~19dp radius, translucent fill, light rim,
 * soft shadow) floating over a soft theme-tinted backdrop. Only installed when {@link NekoGlass#isActive()}.
 */
public final class GlassDialogCards {

    public static final int SIDE_DP = 12;
    public static final int GAP_HALF_DP = 3;
    public static final int RADIUS_DP = 19;

    private GlassDialogCards() {
    }

    public static float radius() {
        return dp(RADIUS_DP);
    }

    private static final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path cardPath = new Path();
    private static final RectF tmp = new RectF();

    /** Soft shadow (outside only) + translucent fill + optional tint + sheen + specular rim. */
    public static void drawCard(Canvas canvas, RectF rect, float radius, float alpha, int tint) {
        if (alpha <= 0.01f || rect.width() <= 1 || rect.height() <= 1) {
            return;
        }
        final int base = Theme.getColor(Theme.key_windowBackgroundWhite);
        final boolean dark = NekoGlass.isDark(base | 0xFF000000);
        final float a = Math.min(1f, alpha);

        // shadow, clipped out of the card so it never darkens the translucent fill
        cardPath.rewind();
        cardPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.save();
        if (Build.VERSION.SDK_INT >= 26) {
            canvas.clipOutPath(cardPath);
        } else {
            canvas.clipPath(cardPath, Region.Op.DIFFERENCE);
        }
        shadowPaint.setColor(dark ? 0xFF000000 : 0xFFFFFFFF);
        shadowPaint.setShadowLayer(dp(7), 0, dp(2), Color.argb((int) ((dark ? 0x70 : 0x24) * a), 0, 0, 0));
        canvas.drawRoundRect(rect, radius, radius, shadowPaint);
        canvas.restore();

        fillPaint.setShader(null);
        fillPaint.setColor(Theme.multAlpha(base | 0xFF000000, (dark ? 0.56f : 0.64f) * a));
        canvas.drawRoundRect(rect, radius, radius, fillPaint);
        if (tint != 0) {
            fillPaint.setColor(Theme.multAlpha(tint, a));
            canvas.drawRoundRect(rect, radius, radius, fillPaint);
        }
        NekoGlass.drawSheen(canvas, rect, radius, dark, a);
        NekoGlass.drawRim(canvas, rect, radius, dark, a * (dark ? 0.85f : 1f));
    }

    /** Spacing + card painting under every DialogCell of a dialogs list. */
    public static final class Decoration extends RecyclerView.ItemDecoration {

        @Override
        public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            if (view instanceof DialogCell) {
                ((DialogCell) view).glassCard = true;
                outRect.set(dp(SIDE_DP), dp(GAP_HALF_DP), dp(SIDE_DP), dp(GAP_HALF_DP));
            } else {
                outRect.setEmpty();
            }
        }

        @Override
        public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            final float r = radius();
            final int accent = Theme.getColor(Theme.key_chats_actionBackground);
            for (int i = 0, n = parent.getChildCount(); i < n; i++) {
                final View child = parent.getChildAt(i);
                if (!(child instanceof DialogCell) || child.getVisibility() != View.VISIBLE) {
                    continue;
                }
                final DialogCell cell = (DialogCell) child;
                if (!cell.glassCard) {
                    continue;
                }
                final float top = cell.getGlassClipTop();
                if (top >= child.getHeight() - 1) {
                    continue;
                }
                final float x = child.getX() + cell.getTranslationX();
                final float y = child.getY();
                tmp.set(x, y + top, x + child.getWidth(), y + child.getHeight());
                drawCard(c, tmp, r, child.getAlpha(), cell.isGlassChecked() ? Theme.multAlpha(accent, 0.14f) : 0);
            }
        }
    }

    /** The soft backdrop the glass cards float on: theme background with accent tinted glows. */
    public static final class Backdrop extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int lastBg, lastAccent, lastW, lastH;
        private Shader linear, radial1, radial2;

        @Override
        public void draw(@NonNull Canvas canvas) {
            final Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) {
                return;
            }
            final int bg = Theme.getColor(Theme.key_windowBackgroundGray) | 0xFF000000;
            final int accent = Theme.getColor(Theme.key_chats_actionBackground) | 0xFF000000;
            if (linear == null || bg != lastBg || accent != lastAccent || b.width() != lastW || b.height() != lastH) {
                lastBg = bg;
                lastAccent = accent;
                lastW = b.width();
                lastH = b.height();
                final boolean dark = NekoGlass.isDark(bg);
                final int top = ColorUtils.blendARGB(bg, accent, dark ? 0.16f : 0.13f);
                final int bottom = ColorUtils.blendARGB(bg, accent, dark ? 0.10f : 0.08f);
                linear = new LinearGradient(0, b.top, 0, b.bottom, new int[]{top, bg, bottom}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
                final int g1 = Theme.multAlpha(accent, dark ? 0.22f : 0.16f);
                final int g2 = Theme.multAlpha(ColorUtils.blendARGB(accent, 0xFFFF7AB8, 0.5f), dark ? 0.16f : 0.12f);
                final float w = b.width();
                radial1 = new RadialGradient(b.left + w * 0.9f, b.top + b.height() * 0.22f, w * 0.75f, g1, g1 & 0x00FFFFFF, Shader.TileMode.CLAMP);
                radial2 = new RadialGradient(b.left + w * 0.08f, b.top + b.height() * 0.72f, w * 0.8f, g2, g2 & 0x00FFFFFF, Shader.TileMode.CLAMP);
            }
            paint.setShader(linear);
            canvas.drawRect(b, paint);
            glow.setShader(radial1);
            canvas.drawRect(b, glow);
            glow.setShader(radial2);
            canvas.drawRect(b, glow);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }
}
