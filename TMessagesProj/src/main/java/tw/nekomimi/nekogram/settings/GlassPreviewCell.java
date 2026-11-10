package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import org.telegram.ui.ActionBar.Theme;

import tw.nekomimi.nekogram.glass.GlassDialogCards;

/**
 * Live preview for the Liquid Glass sliders: a colourful patterned backdrop with one sample chat
 * card drawn exactly like the chat list cards (current corner radius + glass transparency).
 */
@SuppressLint("ViewConstructor")
public class GlassPreviewCell extends View {

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stripePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint contentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frame = new RectF();
    private final RectF card = new RectF();
    private final RectF tmp = new RectF();
    private int lastW = -1, lastH = -1;
    private Shader linear, blob1, blob2, blob3;

    public GlassPreviewCell(Context context) {
        super(context);
        stripePaint.setStrokeWidth(dpf2(10));
        stripePaint.setColor(0x33FFFFFF);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(dp(132), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth(), h = getHeight();
        frame.set(dp(16), dp(12), w - dp(16), h - dp(12));
        if (frame.width() <= 0) {
            return;
        }
        if (w != lastW || h != lastH) {
            lastW = w;
            lastH = h;
            linear = new LinearGradient(frame.left, frame.top, frame.right, frame.bottom,
                new int[]{0xFF6A5CFF, 0xFF00B3FF, 0xFF2BD99F, 0xFFFFC542, 0xFFFF5C8A},
                null, Shader.TileMode.CLAMP);
            blob1 = new RadialGradient(frame.left + frame.width() * 0.2f, frame.top + frame.height() * 0.3f, dp(70), 0xCCFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            blob2 = new RadialGradient(frame.left + frame.width() * 0.75f, frame.bottom, dp(90), 0xCC7A1CFF, 0x007A1CFF, Shader.TileMode.CLAMP);
            blob3 = new RadialGradient(frame.right, frame.top, dp(60), 0xAAFF3D3D, 0x00FF3D3D, Shader.TileMode.CLAMP);
        }
        final float fr = dp(18);
        canvas.save();
        tmp.set(frame);
        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(tmp, fr, fr, android.graphics.Path.Direction.CW);
        canvas.clipPath(clip);
        bgPaint.setShader(linear);
        canvas.drawRect(frame, bgPaint);
        blobPaint.setShader(blob1);
        canvas.drawRect(frame, blobPaint);
        blobPaint.setShader(blob2);
        canvas.drawRect(frame, blobPaint);
        blobPaint.setShader(blob3);
        canvas.drawRect(frame, blobPaint);
        // diagonal stripes so transparency is easy to judge
        for (float x = frame.left - frame.height(); x < frame.right; x += dp(28)) {
            canvas.drawLine(x, frame.bottom, x + frame.height(), frame.top, stripePaint);
        }

        // sample chat card, same geometry as the chat list (12dp sides, 72dp tall)
        final float cardH = dp(72);
        card.set(frame.left + dp(12), frame.centerY() - cardH / 2f, frame.right - dp(12), frame.centerY() + cardH / 2f);
        GlassDialogCards.drawCard(canvas, card, GlassDialogCards.radius(), 1f, 0);

        // avatar + name + message placeholders
        final boolean dark = Theme.isCurrentThemeDark();
        contentPaint.setColor(Theme.getColor(Theme.key_chats_actionBackground));
        canvas.drawCircle(card.left + dp(10) + dp(27), card.centerY(), dp(27), contentPaint);
        final int text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final float tx = card.left + dp(76);
        contentPaint.setColor(text);
        tmp.set(tx, card.top + dp(18), Math.min(card.right - dp(60), tx + dp(110)), card.top + dp(30));
        canvas.drawRoundRect(tmp, dp(6), dp(6), contentPaint);
        contentPaint.setColor(Theme.multAlpha(text, dark ? 0.45f : 0.4f));
        tmp.set(tx, card.top + dp(42), Math.max(tx + dp(20), card.right - dp(20)), card.top + dp(52));
        canvas.drawRoundRect(tmp, dp(5), dp(5), contentPaint);
        contentPaint.setColor(Theme.multAlpha(text, 0.35f));
        tmp.set(card.right - dp(48), card.top + dp(20), card.right - dp(16), card.top + dp(28));
        canvas.drawRoundRect(tmp, dp(4), dp(4), contentPaint);
        canvas.restore();
    }
}
