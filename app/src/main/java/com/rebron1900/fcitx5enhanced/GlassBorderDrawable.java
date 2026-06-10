package com.rebron1900.fcitx5enhanced;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * 玻璃质感渐变描边 Drawable。
 * <p>
 * 两种模式：
 * <ul>
 *   <li><b>弹窗</b>（diagonalGlow=false）：四边全描边 + 从上到下渐变</li>
 *   <li><b>键盘</b>（diagonalGlow=true）：
 *       仅上边 + 转角描边，转角→中间亮度递减，
 *       左右两侧转角往下渐变消失（秒变），底部无描边</li>
 * </ul>
 */
public class GlassBorderDrawable extends Drawable {

    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mRadius;
    private final float mBorderWidth;
    private final int mTopBorderColor;
    private final int mBottomBorderColor;
    private final boolean mDiagonalGlow;

    // ── 弹窗模式 ──
    private final Path mPath = new Path();

    // ── 键盘模式 ──
    private final Path mTopPath = new Path();          // 上轮廓：左弧→上边→右弧
    private final Path mLeftFadePath = new Path();     // 左侧渐变段
    private final Path mRightFadePath = new Path();    // 右侧渐变段
    private float mKInsetL, mKInsetT, mKInsetR;        // inset 后坐标
    private float mKFadeEndY;                           // 侧边渐变终止 Y
    private int mKCenterColor;                          // 转角→中间衰减色

    // 弹窗模式渐变参数（onBoundsChange 缓存）
    private float mPopupY0, mPopupY1;
    private int[] mPopupColors;
    private float[] mPopupPositions;


    // ══════════════════════════════════════════
    //  构造
    // ══════════════════════════════════════════

    /** 弹窗模式 */
    public GlassBorderDrawable(int fillColor, int topBorderColor, int bottomBorderColor,
                               float cornerRadiusPx, float borderWidthPx) {
        this(fillColor, topBorderColor, bottomBorderColor,
                cornerRadiusPx, borderWidthPx, false);
    }

    /** 弹窗 / 键盘模式 */
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
        mBorderPaint.setStrokeCap(Paint.Cap.BUTT);
    }


    // ══════════════════════════════════════════
    //  Bounds
    // ══════════════════════════════════════════

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        mPath.reset();
        mTopPath.reset();
        mLeftFadePath.reset();
        mRightFadePath.reset();

        if (mDiagonalGlow) {
            // ═══ 键盘模式：仅上边 + 转角 + 侧边渐隐 ═══
            // path 在视图边界（inset=0），描边中心骑在 clip 线上，
            // clip 切掉外半，不留下任何黑底缝隙
            float inset = 0f;
            mKInsetL = bounds.left + inset;
            mKInsetT = bounds.top + inset;
            mKInsetR = bounds.right - inset;

            // 侧边渐隐段长度 ≈ 1.2× 圆角半径，至少 20px
            float fadePx = Math.max(mRadius * 1.2f, 20f);
            float fadeEndY = mKInsetT + mRadius + fadePx;
            mKFadeEndY = fadeEndY;

            // ── 上轮廓：左弧 → 上边 → 右弧 ──
            mTopPath.moveTo(mKInsetL, mKInsetT + mRadius);
            mTopPath.arcTo(mKInsetL, mKInsetT,
                    mKInsetL + 2 * mRadius, mKInsetT + 2 * mRadius,
                    180, 90, false);
            mTopPath.lineTo(mKInsetR - mRadius, mKInsetT);
            mTopPath.arcTo(mKInsetR - 2 * mRadius, mKInsetT,
                    mKInsetR, mKInsetT + 2 * mRadius,
                    270, 90, false);

            // ── 侧边渐隐段 ──
            mLeftFadePath.moveTo(mKInsetL, mKInsetT + mRadius);
            mLeftFadePath.lineTo(mKInsetL, fadeEndY);
            mRightFadePath.moveTo(mKInsetR, mKInsetT + mRadius);
            mRightFadePath.lineTo(mKInsetR, fadeEndY);

            // ── 中心衰减色（转角 40% 亮度） ──
            int a = Color.alpha(mTopBorderColor);
            mKCenterColor = Color.argb((int) (a * 0.4f),
                    Color.red(mTopBorderColor),
                    Color.green(mTopBorderColor),
                    Color.blue(mTopBorderColor));
        } else {
            // ═══ 弹窗模式：全圆角矩形 + 上下渐变（不变） ═══
            float inset = mBorderWidth;
            mPath.addRoundRect(
                    bounds.left + inset, bounds.top + inset,
                    bounds.right - inset, bounds.bottom - inset,
                    mRadius, mRadius, Path.Direction.CW);

            float h = bounds.bottom - bounds.top;
            float extend = h * 0.4f;
            mPopupY0 = bounds.top - extend;
            mPopupY1 = bounds.bottom + extend;
            mPopupColors = new int[]{
                    mTopBorderColor, mTopBorderColor,
                    mBottomBorderColor, mBottomBorderColor};
            mPopupPositions = new float[]{0f, 0.25f, 0.75f, 1f};
        }
    }


    // ══════════════════════════════════════════
    //  Draw
    // ══════════════════════════════════════════

    @Override
    public void draw(Canvas canvas) {
        if (getBounds().isEmpty()) return;

        if (mDiagonalGlow) {
            drawKeyboardMode(canvas);
        } else {
            drawPopupMode(canvas);
        }
    }

    private void drawKeyboardMode(Canvas canvas) {
        // 描边骑在 clip 线上（inset=0），clip 切掉外半。
        // 将 stroke 宽度翻倍，让可见内半 = 原始目标宽度
        float savedW = mBorderPaint.getStrokeWidth();
        mBorderPaint.setStrokeWidth(savedW * 2f);

        float w = mKInsetR - mKInsetL;
        float arcFrac = (w > 0) ? Math.min(mRadius / w, 0.45f) : 0.1f;

        // ── Pass 1：上轮廓，水平渐变 ──
        // 转角→中间亮度递减，转角最强
        mBorderPaint.setShader(new LinearGradient(
                mKInsetL, mKInsetT, mKInsetR, mKInsetT,
                new int[]{
                        mTopBorderColor, mTopBorderColor,
                        mKCenterColor,
                        mTopBorderColor, mTopBorderColor
                },
                new float[]{0f, arcFrac, 0.5f, 1f - arcFrac, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawPath(mTopPath, mBorderPaint);

        // ── Pass 2：左侧渐隐，垂直渐变 ──
        mBorderPaint.setShader(new LinearGradient(
                0, mKInsetT + mRadius,
                0, mKFadeEndY,
                mTopBorderColor, Color.TRANSPARENT,
                Shader.TileMode.CLAMP));
        canvas.drawPath(mLeftFadePath, mBorderPaint);

        // ── Pass 3：右侧渐隐 ──
        canvas.drawPath(mRightFadePath, mBorderPaint);

        mBorderPaint.setStrokeWidth(savedW);
    }

    private void drawPopupMode(Canvas canvas) {
        mBorderPaint.setShader(new LinearGradient(
                0, mPopupY0, 0, mPopupY1,
                mPopupColors, mPopupPositions,
                Shader.TileMode.CLAMP));
        canvas.drawPath(mPath, mFillPaint);
        canvas.drawPath(mPath, mBorderPaint);
    }


    // ══════════════════════════════════════════
    //  Drawable 协议
    // ══════════════════════════════════════════

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
    public void setColorFilter(ColorFilter colorFilter) {
        mFillPaint.setColorFilter(colorFilter);
        mBorderPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
