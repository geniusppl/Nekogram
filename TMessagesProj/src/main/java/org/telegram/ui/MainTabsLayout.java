package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.ViewConfiguration;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import android.util.Log;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedLinearLayout;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.glass.GlassTabView;

import tw.nekomimi.nekogram.glass.FlClashLens;
import tw.nekomimi.nekogram.glass.JellyGlassIndicator;
import tw.nekomimi.nekogram.glass.NekoGlass;

import java.util.HashSet;
import java.util.Set;

import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.ListAnimator;
import me.vkryl.android.util.ClickHelper;

@SuppressLint("ViewConstructor")
public class MainTabsLayout extends AnimatedLinearLayout {

    private final Theme.ResourcesProvider resourcesProvider;

    public MainTabsLayout(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
    }

    private static final float[] PASS_TEXT_SIZES_DP = {12f, 12f, 10f};
    private static final int[] PASS_PADDINGS_DP = {16, 8, 4};

    private int maxWidthPx;

    public void setMaxWidth(int maxWidthPx) {
        if (this.maxWidthPx != maxWidthPx) {
            this.maxWidthPx = maxWidthPx;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        final int height = MeasureSpec.getSize(heightMeasureSpec);
        final int tabHeight = height - getPaddingTop() - getPaddingBottom();

        if (maxWidthPx > 0 && width > maxWidthPx) {
            width = maxWidthPx;
        }

        final int maxTotalWidthForTabs = width - getPaddingLeft() - getPaddingRight();
        final int minTotalWidthForTabs = Math.min(dp(320), maxTotalWidthForTabs);

        int chosenPass = PASS_TEXT_SIZES_DP.length - 1;
        float lastMeasuredTextSize = -1;
        for (int pass = 0; pass < PASS_TEXT_SIZES_DP.length; pass++) {
            if (PASS_TEXT_SIZES_DP[pass] != lastMeasuredTextSize) {
                measureTabTexts(PASS_TEXT_SIZES_DP[pass]);
                lastMeasuredTextSize = PASS_TEXT_SIZES_DP[pass];
            }
            final int padding = dp(PASS_PADDINGS_DP[pass]);
            float total = 0;
            for (int a = 0, N = getChildCount(); a < N; a++) {
                if (!isViewVisible(getChildAt(a))) continue;
                final float withMargin = tabsTextWidth[a] + padding * 2;
                total += withMargin;
            }
            final boolean fits = total <= maxTotalWidthForTabs;
            if (fits || pass == PASS_TEXT_SIZES_DP.length - 1) {
                chosenPass = pass;
                break;
            }
        }

        applyPassTextSize(chosenPass);

        final int tabPadding = dp(PASS_PADDINGS_DP[chosenPass]);
        final int maxTabTextWidthIfEq = (maxTotalWidthForTabs / Math.max(1, visibleChildCount)) - tabPadding * 2;

        float totalWidth = 0;
        int totalWeight = 0;
        for (int a = 0, N = getChildCount(); a < N; a++) {
            final View child = getChildAt(a);
            if (!isViewVisible(child)) {
                tabsTextWidth[a] = tabsTextWidthWithMargin[a] = 0;
                tabsWeight[a] = 0;
                continue;
            }

            tabsTextWidthWithMargin[a] = tabsTextWidth[a] + tabPadding * 2;
            tabsWeight[a] = tabsTextWidthWithMargin[a] > (maxTabTextWidthIfEq + tabPadding * 2) ? 0 : 1;

            totalWidth += tabsTextWidthWithMargin[a];
            totalWeight += tabsWeight[a];
        }

        if (totalWeight == 0) {
            for (int a = 0, N = getChildCount(); a < N; a++) {
                tabsWeight[a] = isViewVisible(getChildAt(a)) ? 1 : 0;
            }
            totalWeight = visibleChildCount;
        }

        if (totalWidth > maxTotalWidthForTabs) {
            final float m = maxTotalWidthForTabs / totalWidth;
            for (int a = 0, N = getChildCount(); a < N; a++) {
                tabsTextWidthWithMargin[a] *= m;
            }
        } else if (totalWidth < minTotalWidthForTabs) {
            final float growW = minTotalWidthForTabs - totalWidth;
            final float growP = growW / totalWeight;

            for (int a = 0, N = getChildCount(); a < N; a++) {
                tabsTextWidthWithMargin[a] += growP * tabsWeight[a];
            }
        }

        int l = 0;
        for (int a = 0, N = getChildCount(); a < N; a++) {
            if (!isViewVisible(getChildAt(a))) {
                continue;
            }

            tabsWidth[a] = Math.round(tabsTextWidthWithMargin[a]);
            tabsLeftPos[a] = l;
            l += tabsWidth[a];
        }
        setMeasuredDimension(l + getPaddingLeft() + getPaddingRight(), height);
        for (int a = 0, N = getChildCount(); a < N; a++) {
            final View child = getChildAt(a);
            child.measure(
                MeasureSpec.makeMeasureSpec(tabsWidth[a], MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(tabHeight, MeasureSpec.EXACTLY));
        }

        calculateTotalSizesAfterMeasure();
    }

    public interface Tab {
        float measureTextWidth();
        default float measureTextWidth(float textSizeDp) { return measureTextWidth(); }
        default void setTextSizeDp(float textSizeDp) {}
    }



    // fills tabsTextWidth[] and return visible child count;

    private float[] tabsTextWidth;
    private float[] tabsTextWidthWithMargin;
    private int[] tabsWeight;
    private int[] tabsWidth;

    private int[] tabsLeftPos;


    private int visibleChildCount;
    private int biggestTabTextWidth;

    private void measureTabTexts(float textSizeDp) {
        final int childCount = getChildCount();
        if (tabsTextWidth == null || tabsTextWidth.length < childCount) {
            tabsTextWidth = new float[childCount];
            tabsTextWidthWithMargin = new float[childCount];
            tabsWeight = new int[childCount];
            tabsLeftPos = new int[childCount];
            tabsWidth = new int[childCount];
        }

        float maxTabWidthF = 0;
        int index = 0;

        for (int a = 0; a < childCount; a++) {
            final View child = getChildAt(a);
            if (!isViewVisible(child)) {
                tabsTextWidth[a] = -1;
                continue;
            }

            final float tabWidth;
            if (child instanceof MainTabsLayout.Tab) {
                tabWidth = ((MainTabsLayout.Tab) child).measureTextWidth(textSizeDp);
            } else {
                tabWidth = 0;
            }

            tabsTextWidth[a] = tabWidth;
            maxTabWidthF = Math.max(maxTabWidthF, tabWidth);
            index++;
        }

        biggestTabTextWidth = (int) Math.ceil(maxTabWidthF);
        visibleChildCount = index;
    }

    private void applyPassTextSize(int pass) {
        final float textSizeDp = PASS_TEXT_SIZES_DP[pass];
        for (int a = 0, N = getChildCount(); a < N; a++) {
            final View child = getChildAt(a);
            if (child instanceof MainTabsLayout.Tab) {
                ((MainTabsLayout.Tab) child).setTextSizeDp(textSizeDp);
            }
        }
    }

    @Override
    protected void setChildVisibilityFactor(View view, float factor) {
        final float s = lerp(0.7f, 1f, factor);
        view.setAlpha(factor);
        view.setScaleX(s);
        view.setScaleY(s);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        checkVisualWidth();
    }

    @Override
    protected void onItemsChanged() {
        super.onItemsChanged();
        checkVisualWidth();
    }

    private void checkVisualWidth() {
        for (int a = 0, N = getEntriesCount(); a < N; a++) {
            final ListAnimator.Entry<Holder> entry = getEntry(a);
            final float width = entry.getRectF().width();
            ((GlassTabView) entry.item.view).setVisualWidth(width);
        }
    }








    public void setTabSelected(View tab, boolean animated) {
        for (int a = 0, N = getChildCount(); a < N; a++) {
            final View child = getChildAt(a);
            if (child instanceof GlassTabView) {
                ((GlassTabView) child).setSelected(child == tab, animated);
            }
        }
    }

    private View findSelectedTab() {
        for (int a = 0, N = getChildCount(); a < N; a++) {
            final View child = getChildAt(a);
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }

            if (child instanceof GlassTabView) {
                if (((GlassTabView) child).isTabSelected()) {
                    return child;
                }
            }
        }
        return null;
    }

    private final Runnable restoreDrawSelector = () -> setSkipDrawSelector(false);

    private boolean drawCustomSelector;
    private void setSkipDrawSelector(boolean skipDrawSelector) {
        drawCustomSelector = skipDrawSelector;
        if (drawCustomSelector) {
            selectorPaint.setColor(Theme.multAlpha(Theme.getColor(Theme.key_glass_tabSelected, resourcesProvider), 0.09f));
        }
        for (int a = 0, N = getChildCount(); a < N; a++) {
            final View child = getChildAt(a);
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }

            if (child instanceof GlassTabView) {
                ((GlassTabView) child).setSkipDrawSelector(skipDrawSelector || jelly != null);
            }
        }
        invalidate();
    }







    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        if (jelly != null && drawJelly(canvas)) {
            return;
        }
        if (drawCustomSelector) {
            final float x = animatedLongSelectedViewCenterX + animatedLongSelectedViewOffsetX;
            final float sWidth = getInterpolatedWidthByX(x, this);
            final float sHeight = getHeight() - getPaddingTop() - getPaddingBottom();

            canvas.drawRoundRect(
                    x - sWidth / 2f, (getHeight() - sHeight) / 2f,
                    x + sWidth / 2f, (getHeight() + sHeight) / 2f,
                    sHeight / 2f, sHeight / 2f, selectorPaint);
        }

        super.dispatchDraw(canvas);
    }


    final Paint selectorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final SpringAnimation scaleX = new SpringAnimation(this, DynamicAnimation.SCALE_X, 1f);
    final SpringAnimation scaleY = new SpringAnimation(this, DynamicAnimation.SCALE_Y, 1f);

    final SpringAnimation selectedTabPositionOffsetX = new SpringAnimation(this, new FloatPropertyCompat<MainTabsLayout>("selectedTabPositionOffsetX") {
        @Override
        public float getValue(MainTabsLayout object) {
            return object.animatedLongSelectedViewOffsetX;
        }

        @Override
        public void setValue(MainTabsLayout object, float value) {
            object.animatedLongSelectedViewOffsetX = value;
            object.invalidate();
        }
    });
    final SpringAnimation selectedTabPositionX = new SpringAnimation(this, new FloatPropertyCompat<MainTabsLayout>("selectedTabPositionX") {
        @Override
        public float getValue(MainTabsLayout object) {
            return object.animatedLongSelectedViewCenterX;
        }

        @Override
        public void setValue(MainTabsLayout object, float value) {
            object.animatedLongSelectedViewCenterX = value;
            object.invalidate();
        }
    });

    {
        selectedTabPositionOffsetX.setSpring(new SpringForce(1)
            .setStiffness(SpringForce.STIFFNESS_MEDIUM)
            .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY));
        scaleX.setSpring(new SpringForce(1f)
            .setStiffness(250)
            .setDampingRatio(0.25f));
        scaleY.setSpring(new SpringForce(1f)
            .setStiffness(250)
            .setDampingRatio(0.25f));
        selectedTabPositionX.setSpring(new SpringForce(1f)
            .setStiffness(SpringForce.STIFFNESS_MEDIUM)
            .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY));
    }

    private float animatedLongSelectedViewCenterX;
    private float animatedLongSelectedViewOffsetX;

    private boolean isInLongPress;
    private float lastLongSelectedViewCenterX;
    private float lastLongSelectedViewWidth;
    private View lastLongSelectedView;




    public static View findChildUnder(ViewGroup parent, float x, float y) {
        for (int i = parent.getChildCount() - 1; i >= 0; i--) {
            View child = parent.getChildAt(i);

            if (child.getVisibility() != View.VISIBLE) continue;

            if (x >= child.getLeft() && x <= child.getRight()
                    && y >= child.getTop() && y <= child.getBottom()) {
                return child;
            }
        }
        return null;
    }

    private void checkLongMove(float x_, float y, boolean start, boolean end) {
        final float x = clampXToChildrenCenters(x_, this);
        final View found = findNearestVisibleChildByX(x, this);
        if (start) {
            View selected = findSelectedTab();
            if (selected != null) {
                animatedLongSelectedViewCenterX = selected.getX() + selected.getWidth() / 2f;
                animatedLongSelectedViewOffsetX = animatedLongSelectedViewCenterX - x;
                selectedTabPositionOffsetX.animateToFinalPosition(0);
                if (selected != found && found != null) {
                    found.performClick();
                }
            }
            selectedTabPositionX.cancel();
        }

        if (!end) {
            animatedLongSelectedViewCenterX = x;
            invalidate();
        }

        if (found != null) {
            lastLongSelectedView = found;
            setTabSelected(found, true);

            if (end) {
                final float vw = found.getWidth();
                final float cx = found.getX() + vw / 2f;
                if (lastLongSelectedViewWidth != vw || lastLongSelectedViewCenterX != cx) {
                    selectedTabPositionX.animateToFinalPosition(cx);
                }
            }
        }
    }

    private final Set<View> tabsWithIgnoreClick = new HashSet<>();
    public void addTabToIgnoreClick(View v) {
        tabsWithIgnoreClick.add(v);
    }

    private final BoolAnimator animatorIsScaled = new BoolAnimator(0, (a, factor, c, g) -> {
        if (jelly != null) {
            return;
        }
        setScaleX(lerp(1, 1.019f, factor));
        setScaleY(lerp(1, 1.019f, factor));
    }, CubicBezierInterpolator.EASE_OUT_QUINT, 380);

    private final ClickHelper clickHelper = new ClickHelper(new ClickHelper.Delegate() {
        @Override
        public boolean needClickAt(View view, float x, float y) {
            lastLongSelectedView = null;
            final View found = findChildUnder(MainTabsLayout.this, x, y);
            return found != null && !tabsWithIgnoreClick.contains(found);
        }

        @Override
        public void onClickAt(View view, float x, float y) {
        }

        @Override
        public boolean needLongPress(float x, float y) {
            return true;
        }

        @Override
        public boolean needCancelTouchBySlopMove() {
            return false;
        }


        @Override
        public boolean onLongPressRequestedAt(View view, float x, float y) {
            checkPivot(view, x, y);
            isInLongPress = true;
            AndroidUtilities.cancelRunOnUIThread(restoreDrawSelector);
            setSkipDrawSelector(true);
            checkLongMove(x, y, true, false);
            invalidate();
            longTouchStart();
            return true;
        }

        @Override
        public void onLongPressMove(View view, MotionEvent e, float x, float y, float startX, float startY) {
            checkPivot(view, x, y);
            checkLongMove(x, y, false, false);
            invalidate();
        }

        @Override
        public long getLongPressDuration() {
            return ClickHelper.Delegate.super.getLongPressDuration() * 750 / 1000;
        }

        @Override
        public void onLongPressFinish(View view, float x, float y) {
            checkPivot(view, x, y);
            checkLongMove(x, y, false, true);
            isInLongPress = false;
            AndroidUtilities.runOnUIThread(restoreDrawSelector, 450);
            if (lastLongSelectedView != null) {
                lastLongSelectedView.performClick();
            }
            lastLongSelectedView = null;
            invalidate();
            longTouchEnd();
        }

        @Override
        public void onLongPressCancelled(View view, float x, float y) {
            checkPivot(view, x, y);
            checkLongMove(x, y, false, true);
            isInLongPress = false;
            AndroidUtilities.runOnUIThread(restoreDrawSelector, 450);
            lastLongSelectedView = null;
            invalidate();
            longTouchEnd();
        }

        private void longTouchStart() {
            animatorIsScaled.setValue(true, true);

            /*
            if (!scaleX.isRunning()) {
                scaleX.setStartVelocity(-0.45f);
                scaleY.setStartVelocity(-0.45f);
            }
            scaleX.animateToFinalPosition(1.012f);
            scaleY.animateToFinalPosition(1.012f);
            */
        }

        private void longTouchEnd() {
            animatorIsScaled.setValue(false, true);

            /*
            if (!scaleX.isRunning()) {
                scaleX.setStartVelocity(0.25f);
                scaleY.setStartVelocity(0.25f);
            }
            scaleX.animateToFinalPosition(1f);
            scaleY.animateToFinalPosition(1f);
            */
        }
    });

    @Override
    public void setScaleY(float scaleY) {
        super.setScaleY(scaleY);
        checkLayerType();
    }

    @Override
    public void setScaleX(float scaleX) {
        super.setScaleX(scaleX);
        checkLayerType();
    }

    private void checkLayerType() {
        if (jelly != null) {
            return;
        }
        final int layerType = Math.abs(getScaleX() - 1f) < 0.0001f && Math.abs(getScaleY() - 1f) < 0.0001f ?
            View.LAYER_TYPE_NONE : View.LAYER_TYPE_HARDWARE;

        if (getLayerType() != layerType) {
            setLayerType(layerType, null);
            invalidate();
        }
    }


    private void checkPivot(View view, float x, float y) {
        if (jelly != null) {
            return;
        }
        float w = view.getWidth();
        float h = view.getHeight();

        if (w <= 0f || h <= 0f) {
            return;
        }

        float cx = w * 0.5f;
        float cy = h * 0.5f;

        float dx = x - cx;
        float dy = y - cy;

        float halfW = w * 0.5f;
        float halfH = h * 0.5f;

        float nx = dx / halfW;
        float ny = dy / halfH;

        float r = (float) Math.sqrt(nx * nx + ny * ny);

        float pivotX;
        float pivotY;

        if (r > 1e-4f) {
            float mappedR = 1.5f * r / (r + 0.5f);

            float scale = mappedR / r;
            pivotX = cx + dx * scale;
            pivotY = cy + dy * scale;
        } else {
            pivotX = cx;
            pivotY = cy;
        }

        pivotX = lerp(cx, pivotX, 1f);
        pivotY = lerp(cy, pivotY, 3f);

        view.setPivotX(pivotX);
        view.setPivotY(pivotY);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (jelly != null && handleJellyTouch(ev)) {
            return true;
        }
        clickHelper.onTouchEvent(this, ev);
        return super.dispatchTouchEvent(ev);
    }


    private static float clampXToChildrenCenters(float x, ViewGroup parent) {
        if (parent == null || parent.getChildCount() == 0) {
            return x;
        }

        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        boolean found = false;

        for (int i = 0; i < parent.getChildCount(); i++) {
            View view = parent.getChildAt(i);
            if (view == null || view.getVisibility() != View.VISIBLE) {
                continue;
            }

            float centerX = view.getX() + view.getWidth() * 0.5f;

            if (centerX < min) min = centerX;
            if (centerX > max) max = centerX;

            found = true;
        }

        if (!found) {
            return x;
        }

        if (x < min) return min;
        if (x > max) return max;
        return x;
    }

    @Nullable
    private static View findNearestVisibleChildByX(float x, ViewGroup parent) {
        if (parent == null || parent.getChildCount() == 0) {
            return null;
        }

        View nearest = null;
        float nearestDistance = Float.MAX_VALUE;

        for (int i = 0; i < parent.getChildCount(); i++) {
            View view = parent.getChildAt(i);
            if (view == null || view.getVisibility() != View.VISIBLE) {
                continue;
            }

            float centerX = view.getX() + view.getWidth() * 0.5f;
            float distance = Math.abs(centerX - x);

            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = view;
            }
        }

        return nearest;
    }

    private static float getInterpolatedWidthByX(float x, ViewGroup parent) {
        if (parent == null || parent.getChildCount() == 0) {
            return 0f;
        }

        View left = null;
        View right = null;

        for (int i = 0; i < parent.getChildCount(); i++) {
            View view = parent.getChildAt(i);
            if (view == null || view.getVisibility() != View.VISIBLE) {
                continue;
            }

            float centerX = view.getX() + view.getWidth() * 0.5f;

            if (centerX <= x && (left == null || centerX > getCenterX(left))) {
                left = view;
            }

            if (centerX >= x && (right == null || centerX < getCenterX(right))) {
                right = view;
            }
        }

        if (left == null && right == null) {
            return 0f;
        }

        if (left == null) {
            return right.getWidth();
        }

        if (right == null) {
            return left.getWidth();
        }

        float leftX = getCenterX(left);
        float rightX = getCenterX(right);

        if (left == right || leftX == rightX) {
            return left.getWidth();
        }

        float ratio = (x - leftX) / (rightX - leftX);
        return lerp(left.getWidth(), right.getWidth(), ratio);
    }

    private static float getCenterX(View v) {
        return v.getX() + v.getWidth() * 0.5f;
    }

    // ---------------------------------------------------------------------------------------
    // Nekogram Liquid Glass: jelly glass pill indicator.
    // Drag/stretch/squash/settle behaviour ported from prism_ui NavigationDock
    // (https://github.com/chen08209/prism_ui, FlClash, GPL-3.0). Only active when the
    // Neko "Liquid Glass" switch is on; otherwise jelly == null and nothing below runs.
    // ---------------------------------------------------------------------------------------

    private final JellyGlassIndicator jelly = NekoGlass.isActive() ? new JellyGlassIndicator(this) : null;
    // FlClash's clear glass lens (prism_ui _GlassLens + _LensContent), API 33+
    private final Object flclashLens = jelly != null && Build.VERSION.SDK_INT >= 33 ? FlClashLens.create() : null;
    private Drawable lensBackdrop;
    private boolean jellyPressed;
    private final RectF jellyRect = new RectF();
    private final Path jellyPath = new Path();
    private boolean jellyDragging;
    private float jellyDownX;
    private float jellyDragX;
    private View jellyDragStartSelected;
    private View jellyHover;
    private float jellyGestureX = Float.NaN;
    private long jellyGestureTime;
    private long jellyFollowEndTime;
    private boolean jellyWasFollowing;

    public boolean hasJelly() {
        return jelly != null;
    }

    /**
     * What the lens bends: the bar's blurred backdrop over the whole view (and past it), so the
     * part of the lens that rises above the bar still has something under it.
     */
    public void setLensBackdrop(Drawable backdrop) {
        lensBackdrop = backdrop;
        if (backdrop != null) {
            backdrop.setCallback(this);
        }
    }

    public Drawable getLensBackdrop() {
        return lensBackdrop;
    }

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return who == lensBackdrop || super.verifyDrawable(who);
    }

    /** Center x of the pager position under the finger, or NaN when the pager is not dragged. */
    public void setJellyGestureX(float x) {
        if (jelly == null) {
            return;
        }
        jellyGestureX = x;
        jellyGestureTime = SystemClock.uptimeMillis();
        invalidate();
    }

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);
        if (jelly != null && child instanceof GlassTabView) {
            ((GlassTabView) child).setSkipDrawSelector(true);
        }
    }

    private float jellyPitch() {
        int count = 0;
        float sum = 0;
        for (int i = 0; i < getChildCount(); i++) {
            final View v = getChildAt(i);
            if (v.getVisibility() == View.VISIBLE && v.getWidth() > 0) {
                sum += v.getWidth();
                count++;
            }
        }
        return count == 0 ? dp(64) : sum / count;
    }

    private boolean drawJelly(Canvas canvas) {
        final float height = getHeight() - getPaddingTop() - getPaddingBottom();
        if (height <= 0) {
            return false;
        }
        final long now = SystemClock.uptimeMillis();
        float targetX;
        int mode;
        if (jellyDragging) {
            targetX = jellyDragX;
            mode = JellyGlassIndicator.MODE_FOLLOW;
        } else if (jellyPressed && !isInLongPress && findNearestVisibleChildByX(clampXToChildrenCenters(jellyDownX, this), this) != null) {
            targetX = getCenterX(findNearestVisibleChildByX(clampXToChildrenCenters(jellyDownX, this), this));
            mode = JellyGlassIndicator.MODE_TRAVEL;
        } else if (isInLongPress) {
            targetX = animatedLongSelectedViewCenterX + animatedLongSelectedViewOffsetX;
            mode = JellyGlassIndicator.MODE_FOLLOW;
        } else if (!Float.isNaN(jellyGestureX) && now - jellyGestureTime < 400) {
            targetX = jellyGestureX;
            mode = JellyGlassIndicator.MODE_FOLLOW;
        } else {
            final View selected = findSelectedTab();
            if (selected == null) {
                return false;
            }
            targetX = getCenterX(selected);
            mode = now - jellyFollowEndTime < 700 ? JellyGlassIndicator.MODE_SETTLE : JellyGlassIndicator.MODE_TRAVEL;
        }
        if (mode == JellyGlassIndicator.MODE_FOLLOW) {
            jellyWasFollowing = true;
        } else if (jellyWasFollowing) {
            jellyWasFollowing = false;
            jellyFollowEndTime = now;
            mode = JellyGlassIndicator.MODE_SETTLE;
        }
        final float targetW = getInterpolatedWidthByX(clampXToChildrenCenters(targetX, this), this);
        if (targetW <= 0) {
            return false;
        }
        jelly.setPitch(jellyPitch());
        jelly.setTarget(targetX, targetW, mode);
        jelly.step();
        applyJellyPress();
        jelly.getRect(jellyRect, getPaddingTop() + height / 2f, height, dp(FlClashLens.LENS_GROWTH_DP));
        if (jellyRect.height() > getHeight()) {
            jellyRect.inset(0, (jellyRect.height() - getHeight()) / 2f);
        }
        final float r = jellyRect.height() / 2f;
        final float lift = jelly.getLift();
        final int accent = Theme.getColor(Theme.key_glass_tabSelected, resourcesProvider);

        // prism_ui _Lens: on glass the selection rests as a tinted platter and clears as it lifts
        if (lift < 1f) {
            NekoGlass.drawPill(canvas, jellyRect, r, accent, 0f, 1f - Math.max(0f, lift));
        }

        if (lift > 0.004f && Build.VERSION.SDK_INT >= 33 && flclashLens != null && drawFlClashLens(canvas, r, lift, accent)) {
            return true;
        }

        if (lift > 0.02f) {
            // fallback: the lifted lens magnifies what is under it (prism_ui _lensMagnify = 0.16)
            jellyPath.rewind();
            jellyPath.addRoundRect(jellyRect, r, r, Path.Direction.CW);
            canvas.save();
            canvas.clipOutPath(jellyPath);
            super.dispatchDraw(canvas);
            canvas.restore();
            canvas.save();
            canvas.clipPath(jellyPath);
            final float s = 1f + 0.16f * lift;
            canvas.scale(s, s, jellyRect.centerX(), jellyRect.centerY());
            super.dispatchDraw(canvas);
            canvas.restore();
        } else {
            super.dispatchDraw(canvas);
        }
        return true;
    }

    /**
     * prism_ui _PressTransform: a press swells the bar by min(2 * 1/8, 16dp / longest side) and a
     * pull past either end stretches it along x by pull / width * 0.5 and moves it with the pull.
     */
    private void applyJellyPress() {
        final float panelW = Math.max(1f, getWidth() - dp(DialogsActivity.MAIN_TABS_MARGIN) * 2f);
        final float growth = Math.min(JellyGlassIndicator.PRESS_GROWTH * 2f, dp(JellyGlassIndicator.MAX_PRESS_GROWTH_DP) / panelW);
        final float swell = 1f + jelly.getSwell() * growth;
        final float pull = jelly.getPull();
        final float sx = swell * (1f + Math.abs(pull) / panelW * JellyGlassIndicator.PULL_STRETCH);
        setPivotX(getWidth() / 2f);
        setPivotY(getHeight() / 2f);
        if (Math.abs(getScaleX() - sx) > 1e-4f) {
            setScaleX(sx);
        }
        if (Math.abs(getScaleY() - swell) > 1e-4f) {
            setScaleY(swell);
        }
        if (Math.abs(getTranslationX() - pull) > 0.05f) {
            setTranslationX(pull);
        }
    }

    private final FlClashLens.ContentPainter lensContentPainter = this::drawTabsMagnified;

    /** _LensTint: every destination scaled about its own center, as the lens magnifies it. */
    private void drawTabsMagnified(Canvas canvas, float magnify) {
        final long time = getDrawingTime();
        for (int i = 0, N = getChildCount(); i < N; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getWidth() <= 0) {
                continue;
            }
            canvas.save();
            canvas.scale(magnify, magnify, child.getX() + child.getWidth() / 2f, child.getY() + child.getHeight() / 2f);
            drawChild(canvas, child, time);
            canvas.restore();
        }
    }

    @androidx.annotation.RequiresApi(api = 33)
    private boolean drawFlClashLens(Canvas canvas, float r, float lift, int accent) {
        final FlClashLens lens = (FlClashLens) flclashLens;
        if (lens.isFailed() || !canvas.isHardwareAccelerated()) {
            return false;
        }
        final Drawable backdrop = lensBackdrop != null ? lensBackdrop : getBackground();
        if (backdrop == null) {
            return false;
        }
        final boolean dark = Theme.isCurrentThemeDark();
        final int w = getWidth(), h = getHeight();
        final int out = lensBackdrop != null ? dp(24) : 0;
        // _lensShadows under the clear lens, then the lens bending the bar (_GlassLens)
        lens.drawShadow(canvas, jellyRect, lift, dark);
        if (!lens.drawGlass(canvas, w, h, jellyRect, r, lift, dark, backdrop, -out, -out, w + out, h + out)) {
            return false;
        }
        // destinations outside the lens as they are
        jellyPath.rewind();
        jellyPath.addRoundRect(jellyRect, r, r, Path.Direction.CW);
        canvas.save();
        canvas.clipOutPath(jellyPath);
        super.dispatchDraw(canvas);
        canvas.restore();
        // and inside it magnified, tinted and warped by a pane of their own (_LensContent)
        if (!lens.drawContent(canvas, w, h, jellyRect, r, lift, dark, accent, lensContentPainter)) {
            canvas.save();
            canvas.clipPath(jellyPath);
            final float s = 1f + FlClashLens.LENS_MAGNIFY * lift;
            canvas.scale(s, s, jellyRect.centerX(), jellyRect.centerY());
            super.dispatchDraw(canvas);
            canvas.restore();
        }
        return true;
    }

    private void updateJellyDrag(float x) {
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (int i = 0; i < getChildCount(); i++) {
            final View v = getChildAt(i);
            if (v.getVisibility() != View.VISIBLE) continue;
            final float c = getCenterX(v);
            min = Math.min(min, c);
            max = Math.max(max, c);
        }
        if (min > max) {
            return;
        }
        final float pitch = jellyPitch();
        float cx = x;
        if (x < min) {
            cx = min + JellyGlassIndicator.rubberBand(x - min, pitch * JellyGlassIndicator.OVERDRAG);
        } else if (x > max) {
            cx = max + JellyGlassIndicator.rubberBand(x - max, pitch * JellyGlassIndicator.OVERDRAG);
        }
        final float overshoot = x - Math.max(0, Math.min(getWidth(), x));
        jelly.setPull(JellyGlassIndicator.rubberBand(overshoot, Math.min(getWidth(), getHeight()) * 7f / 32f));
        jellyDragX = cx;
        final View found = findNearestVisibleChildByX(clampXToChildrenCenters(cx, this), this);
        if (found != null && found != jellyHover) {
            jellyHover = found;
            setTabSelected(found, true);
            try {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            } catch (Exception ignore) {
            }
        }
        invalidate();
    }

    private boolean handleJellyTouch(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                jellyDragging = false;
                jellyDownX = ev.getX();
                jellyPressed = true;
                jelly.setPressed(true);
                invalidate();
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!jellyDragging) {
                    if (isInLongPress || Math.abs(ev.getX() - jellyDownX) < ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                        return false;
                    }
                    jellyDragging = true;
                    jellyDragStartSelected = findSelectedTab();
                    jellyHover = jellyDragStartSelected;
                    final MotionEvent cancel = MotionEvent.obtain(ev);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    clickHelper.onTouchEvent(this, cancel);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }
                updateJellyDrag(ev.getX());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                jellyPressed = false;
                jelly.setPressed(false);
                invalidate();
                if (!jellyDragging) {
                    return false;
                }
                jellyDragging = false;
                jelly.setPull(0);
                View target = null;
                if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
                    // carry a flick on, as prism_ui _fling does (100ms projection)
                    final float pitch = jellyPitch();
                    final float projected = jellyDragX + Math.max(-pitch, Math.min(pitch, jelly.getVelocity() * 0.1f));
                    target = findNearestVisibleChildByX(clampXToChildrenCenters(projected, this), this);
                }
                if (target != null && target != jellyDragStartSelected) {
                    setTabSelected(target, true);
                    target.performClick();
                } else if (jellyDragStartSelected != null) {
                    setTabSelected(jellyDragStartSelected, true);
                }
                jellyDragStartSelected = null;
                jellyHover = null;
                return true;
        }
        return jellyDragging;
    }
}
