package com.rebron1900.fcitx5enhanced;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * HSV 拾色器。
 * 左：二维色盘（横向=饱和度，纵向=明度），
 * 右：竖向 Hue 条 + Alpha 条，
 * 底：预览 + 取消/确定。
 */
public class ColorPickerDialog {

    private final Context mContext;
    private float[] mHsv = new float[]{0, 1, 1};
    private int mAlpha = 255;
    private final java.util.function.IntConsumer mListener;
    private ColorFieldView mField;
    private SliderView mHueSlider;
    private SliderView mAlphaSlider;
    private View mPreview;

    public ColorPickerDialog(Context context, int initialColor, java.util.function.IntConsumer listener) {
        mContext = context;
        Color.colorToHSV(initialColor, mHsv);
        mAlpha = Color.alpha(initialColor);
        mListener = listener;
    }

    public void show() {
        float den = mContext.getResources().getDisplayMetrics().density;
        int pad = (int)(16 * den);

        LinearLayout root = new LinearLayout(mContext);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, (int)(12*den), pad, pad);

        // ── 色盘 + 右侧滑动条 ──
        LinearLayout pickerRow = new LinearLayout(mContext);
        pickerRow.setOrientation(LinearLayout.HORIZONTAL);
        pickerRow.setGravity(Gravity.CENTER_VERTICAL);
        int pickerSize = (int)(240 * den);

        // 二维色盘
        mField = new ColorFieldView(mContext);
        mField.setLayoutParams(new LinearLayout.LayoutParams(pickerSize, pickerSize));
        mField.setHue(mHsv[0]);
        mField.setSV(mHsv[1], mHsv[2]);
        pickerRow.addView(mField);

        // 右侧 Hue + 透明度 滑动条
        LinearLayout sliderCol = new LinearLayout(mContext);
        sliderCol.setOrientation(LinearLayout.VERTICAL);
        int sliderW = (int)(36 * den);
        int sliderH = (int)(pickerSize * 0.45f);
        int gap = (int)(8 * den);

        // Hue 条
        LinearLayout hueRow = new LinearLayout(mContext);
        hueRow.setOrientation(LinearLayout.VERTICAL);
        hueRow.setPadding((int)(8*den), 0, (int)(8*den), 0);
        mHueSlider = new SliderView(mContext, true);
        mHueSlider.setLayoutParams(new LinearLayout.LayoutParams(sliderW, sliderH));
        mHueSlider.setColors(new int[]{
            0xFFFF0000, 0xFFFFFF00, 0xFF00FF00,
            0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000
        });
        mHueSlider.setProgress(mHsv[0] / 360f);
        mHueSlider.setOnProgressChange(p -> {
            mHsv[0] = p * 360f;
            mField.setHue(mHsv[0]);
            mField.invalidate();
            updatePreview();
        });
        hueRow.addView(mHueSlider);
        TextView hueLabel = new TextView(mContext);
        hueLabel.setText("H");
        hueLabel.setTextSize(11);
        hueLabel.setTextColor(0xFF888888);
        hueLabel.setGravity(Gravity.CENTER);
        hueLabel.setLayoutParams(new LinearLayout.LayoutParams(sliderW, (int)(20*den)));
        hueRow.addView(hueLabel);
        sliderCol.addView(hueRow);

        // Alpha 条
        LinearLayout alphaRow = new LinearLayout(mContext);
        alphaRow.setOrientation(LinearLayout.VERTICAL);
        alphaRow.setPadding((int)(8*den), 0, (int)(8*den), 0);
        mAlphaSlider = new SliderView(mContext, false);
        mAlphaSlider.setLayoutParams(new LinearLayout.LayoutParams(sliderW, sliderH));
        mAlphaSlider.setProgress(mAlpha / 255f);
        mAlphaSlider.setOnProgressChange(p -> {
            mAlpha = (int)(p * 255f);
            updatePreview();
        });
        alphaRow.addView(mAlphaSlider);
        TextView alphaLabel = new TextView(mContext);
        alphaLabel.setText("A");
        alphaLabel.setTextSize(11);
        alphaLabel.setTextColor(0xFF888888);
        alphaLabel.setGravity(Gravity.CENTER);
        alphaLabel.setLayoutParams(new LinearLayout.LayoutParams(sliderW, (int)(20*den)));
        alphaRow.addView(alphaLabel);
        sliderCol.addView(alphaRow);

        pickerRow.addView(sliderCol);
        root.addView(pickerRow);

        // ── 预览 ──
        mPreview = new View(mContext);
        int preH = (int)(36 * den);
        mPreview.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, preH));
        updatePreview();
        root.addView(mPreview);

        // ── 按钮 ──
        LinearLayout btnRow = new LinearLayout(mContext);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, (int)(12*den), 0, 0);

        TextView cancelBtn = new TextView(mContext);
        cancelBtn.setText("取消");
        cancelBtn.setTextSize(15);
        cancelBtn.setTextColor(0xFF888888);
        cancelBtn.setGravity(Gravity.CENTER);
        cancelBtn.setBackgroundColor(0xFFF0F0F0);
        cancelBtn.setPadding(0, (int)(10*den), 0, (int)(10*den));
        cancelBtn.setLayoutParams(new LinearLayout.LayoutParams(
                0, (int)(44*den), 1));
        cancelBtn.setOnClickListener(v -> {}); // dismisses dialog

        TextView okBtn = new TextView(mContext);
        okBtn.setText("确定");
        okBtn.setTextSize(15);
        okBtn.setTextColor(0xFFFFFFFF);
        okBtn.setGravity(Gravity.CENTER);
        okBtn.setBackgroundColor(0xFF07C160);
        okBtn.setPadding(0, (int)(10*den), 0, (int)(10*den));
        okBtn.setLayoutParams(new LinearLayout.LayoutParams(
                0, (int)(44*den), 1));
        okBtn.setOnClickListener(v -> {
            if (mListener != null)
                mListener.accept(Color.HSVToColor(mAlpha, mHsv));
        });

        btnRow.addView(cancelBtn);
        btnRow.addView(okBtn);
        root.addView(btnRow);

        new AlertDialog.Builder(mContext)
                .setTitle("选择颜色")
                .setView(root)
                .setPositiveButton(null, null)
                .setNegativeButton(null, null)
                .show();
    }

    private void updatePreview() {
        if (mPreview != null)
            mPreview.setBackgroundColor(Color.HSVToColor(mAlpha, mHsv));
    }

    // ══════════════════════════════════════════
    //  二维色盘
    // ══════════════════════════════════════════

    private static class ColorFieldView extends View {
        private float mHue;
        private float mSat = 1, mVal = 1;
        private final Paint mDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ColorFieldView(Context ctx) {
            super(ctx);
            mDotPaint.setStyle(Paint.Style.FILL);
        }

        void setHue(float h) { mHue = h; }
        void setSV(float s, float v) { mSat = s; mVal = v; }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;

            // 从左(白)到右(纯色)，从上(白)到下(黑)
            int pure = Color.HSVToColor(255, new float[]{mHue, 1f, 1f});
            Paint p = new Paint();

            // Pass 1: 底部黑色渐变
            p.setShader(new LinearGradient(0, 0, 0, h,
                    Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);

            // Pass 2: 从左(白)到右(纯色)渐变，叠加 DST_IN 用混合模式
            // 更简单：先画从左到右渐变，再用从下到上的渐变剪裁
            p.setShader(new LinearGradient(0, 0, w, 0,
                    Color.WHITE, pure, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);

            // 当前选中点
            float dotX = mSat * w;
            float dotY = (1f - mVal) * h;
            mDotPaint.setColor(0xFFFFFFFF);
            c.drawCircle(dotX, dotY, 7, mDotPaint);
            mDotPaint.setStyle(Paint.Style.STROKE);
            mDotPaint.setStrokeWidth(2);
            mDotPaint.setColor(0xFF000000);
            c.drawCircle(dotX, dotY, 7, mDotPaint);
            mDotPaint.setStyle(Paint.Style.FILL);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN
                    || e.getAction() == MotionEvent.ACTION_MOVE) {
                float x = Math.max(0, Math.min(getWidth(), e.getX()));
                float y = Math.max(0, Math.min(getHeight(), e.getY()));
                mSat = x / getWidth();
                mVal = 1f - y / getHeight();
                invalidate();
                return true;
            }
            return false;
        }
    }

    // ══════════════════════════════════════════
    //  竖向滑动条
    // ══════════════════════════════════════════

    private static class SliderView extends View {
        private boolean mIsHue;
        private float mProgress = 0.5f;
        private java.util.function.Consumer<Float> mListener;
        private final Paint mPointerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int[] mColors;

        SliderView(Context ctx, boolean isHue) {
            super(ctx);
            mIsHue = isHue;
            mPointerPaint.setStyle(Paint.Style.FILL);
            mPointerPaint.setColor(0xFFFFFFFF);
        }

        void setColors(int[] colors) { mColors = colors; }
        void setProgress(float p) { mProgress = Math.max(0, Math.min(1, p)); invalidate(); }
        void setOnProgressChange(java.util.function.Consumer<Float> l) { mListener = l; }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;

            if (mIsHue && mColors != null) {
                // Hue 渐变条
                Paint p = new Paint();
                float[] positions = new float[mColors.length];
                for (int i = 0; i < mColors.length; i++)
                    positions[i] = (float) i / (mColors.length - 1);
                p.setShader(new LinearGradient(0, 0, 0, h,
                        mColors, positions, Shader.TileMode.CLAMP));
                c.drawRoundRect(4, 0, w - 4, h, 4, 4, p);
            } else {
                // Alpha 渐变条：底色从透明到不透明
                Paint p = new Paint();
                p.setShader(new LinearGradient(0, 0, 0, h,
                        Color.TRANSPARENT, Color.WHITE, Shader.TileMode.CLAMP));
                c.drawRoundRect(4, 0, w - 4, h, 4, 4, p);
                // 棋盘底纹示意透明
                Paint checkP = new Paint();
                int cell = (int)(8 * getResources().getDisplayMetrics().density);
                for (int row = 0; row < h; row += cell) {
                    for (int col = 0; col < w; col += cell) {
                        int cx = col + 4;
                        if (((row / cell) + (col / cell)) % 2 == 0)
                            checkP.setColor(0xFFCCCCCC);
                        else
                            checkP.setColor(0xFFFFFFFF);
                        c.drawRect(cx, row, Math.min(cx + cell, w - 4), Math.min(row + cell, h), checkP);
                    }
                }
                // 再蒙一层渐变
                c.drawRoundRect(4, 0, w - 4, h, 4, 4, p);
            }

            // 指针
            float py = mProgress * h;
            mPointerPaint.setShadowLayer(4, 0, 0, 0x80000000);
            c.drawCircle(w / 2f, py, 10, mPointerPaint);
            mPointerPaint.setShadowLayer(0, 0, 0, 0);
            mPointerPaint.setStyle(Paint.Style.STROKE);
            mPointerPaint.setStrokeWidth(2);
            mPointerPaint.setColor(0xFF333333);
            c.drawCircle(w / 2f, py, 10, mPointerPaint);
            mPointerPaint.setStyle(Paint.Style.FILL);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN
                    || e.getAction() == MotionEvent.ACTION_MOVE) {
                float p = Math.max(0, Math.min(1, e.getY() / getHeight()));
                mProgress = p;
                invalidate();
                if (mListener != null) mListener.accept(p);
                return true;
            }
            return false;
        }
    }
}
