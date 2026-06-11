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
 * 三种模式：
 * <ul>
 *   <li><b>弹窗</b>（MODE_POPUP=0）：四边全描边 + 从上到下渐变</li>
 *   <li><b>键盘</b>（MODE_KEYBOARD=1）：仅上边 + 转角描边，转角→中间亮度递减，侧边渐隐，底部无</li>
 *   <li><b>按键对角</b>（MODE_DIAGONAL=2）：仅左上角↔右下角对角描边，中间淡化</li>
 * </ul>
 */
public class GlassBorderDrawable extends Drawable {

    public static final int MODE_POPUP = 0;
    public static final int MODE_KEYBOARD = 1;
    public static final int MODE_DIAGONAL = 2;

    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mRadius;
    private final float mBorderWidth;
    private final int mTopBorderColor;
    private final int mBottomBorderColor;
    private final int mMode;
    private final boolean mIsOval;
    private final int mInsetH;  // 内部水平 inset（不通过 InsetDrawable，避免影响其他层）
    private final int mInsetV;  // 内部垂直 inset

    // ── 弹窗模式 ──
    private final Path mPopupPath = new Path();
    private float mPopupY0, mPopupY1;
    private int[] mPopupColors;
    private float[] mPopupPositions;

    // ── 键盘模式（MODE_KEYBOARD） ──
    private final Path mKbTopPath = new Path();
    private final Path mKbLeftFade = new Path();
    private final Path mKbRightFade = new Path();
    private float mKbL, mKbT, mKbR;
    private float mKbFadeEndY;
    private int mKbCenterColor;

    // ── 按键对角模式（MODE_DIAGONAL） ──
    private final Path mDgFullPath = new Path();  // 全圆角矩形
    private int mDgMidColor;                       // 中间衰减色
    private int mDgBRColor;                        // 右下角色


    // ══════════════════════════════════════════
    //  构造
    // ══════════════════════════════════════════

    /** 弹窗模式（mode=MODE_POPUP） */
    public GlassBorderDrawable(int fillColor, int topBorderColor, int bottomBorderColor,
                               float cornerRadiusPx, float borderWidthPx) {
        this(fillColor, topBorderColor, bottomBorderColor,
                cornerRadiusPx, borderWidthPx, MODE_POPUP, false, 0, 0);
    }

    /** 指定模式 */
    public GlassBorderDrawable(int fillColor, int topBorderColor, int bottomBorderColor,
                               float cornerRadiusPx, float borderWidthPx, int mode) {
        this(fillColor, topBorderColor, bottomBorderColor,
                cornerRadiusPx, borderWidthPx, mode, false, 0, 0);
    }

    /** 指定模式 + 是否椭圆 */
    public GlassBorderDrawable(int fillColor, int topBorderColor, int bottomBorderColor,
                               float cornerRadiusPx, float borderWidthPx, int mode, boolean isOval) {
        this(fillColor, topBorderColor, bottomBorderColor,
                cornerRadiusPx, borderWidthPx, mode, isOval, 0, 0);
    }

    /** 指定模式 + 是否椭圆 + 内部 inset（不通过 InsetDrawable，避免影响 LayerDrawable 其他层） */
    public GlassBorderDrawable(int fillColor, int topBorderColor, int bottomBorderColor,
                               float cornerRadiusPx, float borderWidthPx, int mode, boolean isOval,
                               int insetH, int insetV) {
        mRadius = cornerRadiusPx;
        mBorderWidth = borderWidthPx;
        mTopBorderColor = topBorderColor;
        mBottomBorderColor = bottomBorderColor;
        mMode = mode;
        mIsOval = isOval;
        mInsetH = insetH;
        mInsetV = insetV;

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
        mPopupPath.reset();
        mKbTopPath.reset();
        mKbLeftFade.reset();
        mKbRightFade.reset();
        mDgFullPath.reset();

        switch (mMode) {
            case MODE_DIAGONAL:  buildDiagonal(bounds); break;
            case MODE_KEYBOARD:  buildKeyboard(bounds); break;
            default:             buildPopup(bounds);    break;
        }
    }

    private void buildPopup(Rect bounds) {
        float inset = mBorderWidth;
        mPopupPath.addRoundRect(
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

    private void buildKeyboard(Rect bounds) {
        // path 在视图边界（inset=0），描边中心骑在 clip 线上，
        // clip 切掉外半，不留下任何黑底缝隙
        float inset = 0f;
        mKbL = bounds.left + inset;
        mKbT = bounds.top + inset;
        mKbR = bounds.right - inset;

        // 侧边渐隐段长度 ≈ 1.2× 圆角半径，至少 20px
        float fadePx = Math.max(mRadius * 1.2f, 20f);
        float fadeEndY = mKbT + mRadius + fadePx;
        mKbFadeEndY = fadeEndY;

        // 上轮廓：左弧 → 上边 → 右弧
        mKbTopPath.moveTo(mKbL, mKbT + mRadius);
        mKbTopPath.arcTo(mKbL, mKbT,
                mKbL + 2 * mRadius, mKbT + 2 * mRadius,
                180, 90, false);
        mKbTopPath.lineTo(mKbR - mRadius, mKbT);
        mKbTopPath.arcTo(mKbR - 2 * mRadius, mKbT,
                mKbR, mKbT + 2 * mRadius,
                270, 90, false);

        // 侧边渐隐段
        mKbLeftFade.moveTo(mKbL, mKbT + mRadius);
        mKbLeftFade.lineTo(mKbL, fadeEndY);
        mKbRightFade.moveTo(mKbR, mKbT + mRadius);
        mKbRightFade.lineTo(mKbR, fadeEndY);

        // 中心衰减色（转角 40% 亮度）
        int a = Color.alpha(mTopBorderColor);
        mKbCenterColor = Color.argb((int) (a * 0.4f),
                Color.red(mTopBorderColor),
                Color.green(mTopBorderColor),
                Color.blue(mTopBorderColor));
    }

    private void buildDiagonal(Rect bounds) {
        // 全圆角矩形，inset = borderWidth + 内部 inset（hMargin/vMargin）
        float insetH = mBorderWidth + mInsetH;
        float insetV = mBorderWidth + mInsetV;
        if (mIsOval) {
            mDgFullPath.addOval(
                    bounds.left + insetH, bounds.top + insetV,
                    bounds.right - insetH, bounds.bottom - insetV,
                    Path.Direction.CW);
        } else {
            mDgFullPath.addRoundRect(
                    bounds.left + insetH, bounds.top + insetV,
                    bounds.right - insetH, bounds.bottom - insetV,
                    mRadius, mRadius, Path.Direction.CW);
        }

        // 中间衰减色（15%透明度），右下角（50%透明度）
        int a = Color.alpha(mTopBorderColor);
        mDgMidColor = Color.argb((int) (a * 0.15f),
                Color.red(mTopBorderColor),
                Color.green(mTopBorderColor),
                Color.blue(mTopBorderColor));
        mDgBRColor = Color.argb((int) (a * 0.50f),
                Color.red(mTopBorderColor),
                Color.green(mTopBorderColor),
                Color.blue(mTopBorderColor));
    }


    // ══════════════════════════════════════════
    //  Draw
    // ══════════════════════════════════════════

    @Override
    public void draw(Canvas canvas) {
        if (getBounds().isEmpty()) return;

        switch (mMode) {
            case MODE_DIAGONAL:  drawDiagonalMode(canvas); break;
            case MODE_KEYBOARD:  drawKeyboardMode(canvas); break;
            default:             drawPopupMode(canvas);    break;
        }
    }

    private void drawPopupMode(Canvas canvas) {
        mBorderPaint.setShader(new LinearGradient(
                0, mPopupY0, 0, mPopupY1,
                mPopupColors, mPopupPositions,
                Shader.TileMode.CLAMP));
        canvas.drawPath(mPopupPath, mFillPaint);
        canvas.drawPath(mPopupPath, mBorderPaint);
    }

    private void drawKeyboardMode(Canvas canvas) {
        // 描边骑在 clip 线上（inset=0），stroke 翻倍，clip 切外半
        float savedW = mBorderPaint.getStrokeWidth();
        mBorderPaint.setStrokeWidth(savedW * 2f);

        float w = mKbR - mKbL;
        float arcFrac = (w > 0) ? Math.min(mRadius / w, 0.45f) : 0.1f;

        // Pass 1：上轮廓，水平渐变（转角→中间渐弱）
        mBorderPaint.setShader(new LinearGradient(
                mKbL, mKbT, mKbR, mKbT,
                new int[]{
                        mTopBorderColor, mTopBorderColor,
                        mKbCenterColor,
                        mTopBorderColor, mTopBorderColor
                },
                new float[]{0f, arcFrac, 0.5f, 1f - arcFrac, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawPath(mKbTopPath, mBorderPaint);

        // Pass 2：左侧渐隐
        mBorderPaint.setShader(new LinearGradient(
                0, mKbT + mRadius,
                0, mKbFadeEndY,
                mTopBorderColor, Color.TRANSPARENT,
                Shader.TileMode.CLAMP));
        canvas.drawPath(mKbLeftFade, mBorderPaint);

        // Pass 3：右侧渐隐
        canvas.drawPath(mKbRightFade, mBorderPaint);

        mBorderPaint.setStrokeWidth(savedW);
    }

    private void drawDiagonalMode(Canvas canvas) {
        // 内描边：inset=borderWidth，用正常 stroke 宽度（不翻倍）
        // 对角线渐变：左上角(亮) → 中间(极淡) → 右下角(中亮)
        Rect b = getBounds();
        mBorderPaint.setShader(new LinearGradient(
                b.left, b.top, b.right, b.bottom,
                new int[]{mTopBorderColor, mTopBorderColor, mDgMidColor, mDgBRColor, mDgBRColor},
                new float[]{0f, 0.15f, 0.50f, 0.85f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawPath(mDgFullPath, mBorderPaint);
        canvas.drawPath(mDgFullPath, mFillPaint);

        mBorderPaint.setShader(null);
    }


    // ══════════════════════════════════════════
    //  Drawable 协议
    // ══════════════════════════════════════════

    @Override
    public void getOutline(Outline outline) {
        if (mIsOval) {
            outline.setOval(getBounds());
        } else {
            outline.setRoundRect(getBounds(), mRadius);
        }
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
