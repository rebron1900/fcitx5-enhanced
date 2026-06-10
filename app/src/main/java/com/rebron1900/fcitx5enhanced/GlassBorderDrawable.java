package com.rebron1900.fcitx5enhanced;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * 玻璃质感渐变背景 Drawable。
 * 支持两种渐变模式：
 * - 弹窗：从上到下渐变描边（顶部亮→底部暗）
 * - 键盘：上边缘整条高光（左上+右上亮→中间暗→底部暗）
 */
public class GlassBorderDrawable extends Drawable {

    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final float mRadius;
    private final float mBorderWidth;
    private final int mTopBorderColor;
    private final int mBottomBorderColor;
    private final boolean mDiagonalGlow;

    // 弹窗：从上到下渐变
    public GlassBorderDrawable(int fillColor, int topBorderColor, int bottomBorderColor,
                               float cornerRadiusPx, float borderWidthPx) {
        this(fillColor, topBorderColor, bottomBorderColor,
                cornerRadiusPx, borderWidthPx, false);
    }

    // 键盘：对角高光
    public GlassBorderDrawable(int fillColor, int topBorderColor, int bottomBorderColor,
                               float cornerRadiusPx, float borderWidthPx, boolean diagonalGlow) {
        mRadius = cornerRadiusPx;
        mBorderWidth = borderWidthPx;
        mTopBorderColor = topBorderColor;
        mBottomBorderColor = bottomBorderColor;
        mDiagonalGlow = diagonalGlow;

        mFillPaint.setColor(fillColor);
        mFillPaint.setStyle(Paint.Style.FILL);

        mBorderPaint.setStyle(Paint.Style.STROKE);
        mBorderPaint.setStrokeWidth(borderWidthPx);
        mBorderPaint.setAntiAlias(true);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        mPath.reset();
        // 靠内描边：path 缩进整条描边宽度，保证 stroke 完全在 view 内部
        float inset = mBorderWidth;
        mPath.addRoundRect(
                bounds.left + inset, bounds.top + inset,
                bounds.right - inset, bounds.bottom - inset,
                mRadius, mRadius, Path.Direction.CW);

        if (mDiagonalGlow) {
            // === 上边缘整条高光（左上+右上玻璃描边） ===
            // 顶部亮→底部暗，高光覆盖整条上边
            float height = bounds.bottom - bounds.top;
            float extend = height * 0.25f;
            float cx = (bounds.left + bounds.right) / 2f;
            mBorderPaint.setShader(new LinearGradient(
                    cx, bounds.top - extend,
                    cx, bounds.bottom + extend,
                    new int[]{mTopBorderColor, mTopBorderColor, mBottomBorderColor, mBottomBorderColor},
                    new float[]{0f, 0.2f, 0.8f, 1f},
                    Shader.TileMode.CLAMP));
        } else {
            // === 弹窗模式：从上到下渐变 ===
            float height = bounds.bottom - bounds.top;
            float extend = height * 0.4f;
            float[] positions = {0f, 0.25f, 0.75f, 1f};
            int[] colors = {mTopBorderColor, mTopBorderColor, mBottomBorderColor, mBottomBorderColor};
            mBorderPaint.setShader(new LinearGradient(
                    0, bounds.top - extend, 0, bounds.bottom + extend,
                    colors, positions,
                    Shader.TileMode.CLAMP));
        }
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.drawPath(mPath, mFillPaint);
        canvas.drawPath(mPath, mBorderPaint);
    }

    @Override
    public void getOutline(Outline outline) {
        outline.setRoundRect(getBounds(), mRadius);
    }

    @Override
    public void setAlpha(int alpha) {
        mFillPaint.setAlpha(alpha);
        mBorderPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        mFillPaint.setColorFilter(colorFilter);
        mBorderPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return android.graphics.PixelFormat.TRANSLUCENT;
    }
}
