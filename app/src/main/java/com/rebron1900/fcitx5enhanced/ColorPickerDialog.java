package com.rebron1900.fcitx5enhanced;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * HSV 拾色器 — 经典布局。
 *
 *  ┌──────────────────────┬──────────┬──────┐
 *  │                      │  Hue 1   │ Alpha│
 *  │   2D 色盘(饱和×明度)  │  (0-180) │      │
 *  │                      ├──────────┤      │
 *  │                      │  Hue 2   │      │
 *  │                      │(180-360) │      │
 *  ├──────────────────────┴──────────┴──────┤
 *  │         ARGB: #FF454854                │
 *  ├────────────────────────────────────────┤
 *  │         [取消]           [确定]         │
 *  └────────────────────────────────────────┘
 */
public class ColorPickerDialog {

    private final Context mContext;
    private float[] mHsv = new float[]{220f / 360f, 0.7f, 0.7f};
    private int mAlpha = 255;
    private final java.util.function.IntConsumer mListener;
    private ColorFieldView mField;
    private HueSliderView mHueSlider1, mHueSlider2;
    private AlphaSliderView mAlphaSlider;
    private View mPreview;
    private TextView mArgbText;

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

        // ── 色盘 + 右侧条 ──
        LinearLayout pickerRow = new LinearLayout(mContext);
        pickerRow.setOrientation(LinearLayout.HORIZONTAL);

        int fieldSize = (int)(220 * den);
        int barW = (int)(28 * den);
        int barH = fieldSize; // hue条占满高
        int gap = (int)(4 * den);

        // 二维色盘
        mField = new ColorFieldView(mContext);
        mField.setLayoutParams(new LinearLayout.LayoutParams(fieldSize, fieldSize));
        mField.setHue(mHsv[0]);
        mField.setSV(mHsv[1], mHsv[2]);
        pickerRow.addView(mField);

        // 间隔
        View spacer1 = new View(mContext);
        spacer1.setLayoutParams(new LinearLayout.LayoutParams((int)(6*den), 0));
        pickerRow.addView(spacer1);

        // Hue 上段 (0-180°)
        LinearLayout hueCol = new LinearLayout(mContext);
        hueCol.setOrientation(LinearLayout.VERTICAL);
        mHueSlider1 = new HueSliderView(mContext, 0, 180);
        mHueSlider1.setLayoutParams(new LinearLayout.LayoutParams(barW, 0, 1));
        mHueSlider1.setHue(mHsv[0]);
        mHueSlider1.setOnHueChange(h -> { mHsv[0] = h; mField.setHue(h); mField.invalidate(); updateUi(); });
        hueCol.addView(mHueSlider1);

        // Hue 下段 (180-360°)
        mHueSlider2 = new HueSliderView(mContext, 180, 360);
        mHueSlider2.setLayoutParams(new LinearLayout.LayoutParams(barW, 0, 1));
        mHueSlider2.setHue(mHsv[0]);
        mHueSlider2.setOnHueChange(h -> { mHsv[0] = h; mField.setHue(h); mField.invalidate(); updateUi(); });
        hueCol.addView(mHueSlider2);

        pickerRow.addView(hueCol);

        // 间隔
        View spacer2 = new View(mContext);
        spacer2.setLayoutParams(new LinearLayout.LayoutParams((int)(6*den), 0));
        pickerRow.addView(spacer2);

        // Alpha 条
        mAlphaSlider = new AlphaSliderView(mContext);
        mAlphaSlider.setLayoutParams(new LinearLayout.LayoutParams(barW, fieldSize));
        mAlphaSlider.setAlpha(mAlpha);
        mAlphaSlider.setOnAlphaChange(a -> { mAlpha = a; updateUi(); });
        pickerRow.addView(mAlphaSlider);

        root.addView(pickerRow);

        // ── ARGB 文本 ──
        mArgbText = new TextView(mContext);
        mArgbText.setTextSize(14);
        mArgbText.setTextColor(0xFF333333);
        mArgbText.setGravity(Gravity.CENTER);
        mArgbText.setPadding(0, (int)(8*den), 0, (int)(8*den));
        root.addView(mArgbText);

        // ── 预览 ──
        mPreview = new View(mContext);
        mPreview.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int)(8*den)));
        root.addView(mPreview);

        // ── 按钮 ──
        LinearLayout btnRow = new LinearLayout(mContext);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, (int)(10*den), 0, 0);

        TextView cancelBtn = new TextView(mContext);
        cancelBtn.setText("取消");
        cancelBtn.setTextSize(15);
        cancelBtn.setTextColor(0xFF666666);
        cancelBtn.setGravity(Gravity.CENTER);
        cancelBtn.setBackgroundColor(0xFFF5F5F5);
        int btnR = (int)(8*den);
        android.graphics.drawable.GradientDrawable cancelBg = new android.graphics.drawable.GradientDrawable();
        cancelBg.setCornerRadius(btnR);
        cancelBg.setColor(0xFFF5F5F5);
        cancelBtn.setBackground(cancelBg);
        cancelBtn.setPadding(0, (int)(12*den), 0, (int)(12*den));
        cancelBtn.setLayoutParams(new LinearLayout.LayoutParams(0, (int)(46*den), 1));
        cancelBtn.setOnClickListener(v -> {}); // dismiss

        View btnSpacer = new View(mContext);
        btnSpacer.setLayoutParams(new LinearLayout.LayoutParams((int)(12*den), 0));

        TextView okBtn = new TextView(mContext);
        okBtn.setText("确定");
        okBtn.setTextSize(15);
        okBtn.setTextColor(0xFFFFFFFF);
        okBtn.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable okBg = new android.graphics.drawable.GradientDrawable();
        okBg.setCornerRadius(btnR);
        okBg.setColor(0xFF07C160);
        okBtn.setBackground(okBg);
        okBtn.setPadding(0, (int)(12*den), 0, (int)(12*den));
        okBtn.setLayoutParams(new LinearLayout.LayoutParams(0, (int)(46*den), 1));
        okBtn.setOnClickListener(v -> {
            if (mListener != null)
                mListener.accept(Color.HSVToColor(mAlpha, mHsv));
        });

        btnRow.addView(cancelBtn);
        btnRow.addView(btnSpacer);
        btnRow.addView(okBtn);
        root.addView(btnRow);

        updateUi();

        new AlertDialog.Builder(mContext)
                .setTitle("选择颜色")
                .setView(root)
                .setPositiveButton(null, null)
                .setNegativeButton(null, null)
                .show();
    }

    private void updateUi() {
        int color = Color.HSVToColor(mAlpha, mHsv);
        if (mPreview != null) mPreview.setBackgroundColor(color);
        if (mArgbText != null) mArgbText.setText(String.format("ARGB: #%08X", color));
    }

    // ══════════════════════════════════════════
    //  二维色盘：X=饱和度, Y=明度
    // ══════════════════════════════════════════

    private static class ColorFieldView extends View {
        private float mHue;
        private float mSat = 0.7f, mVal = 0.7f;
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
            int pure = Color.HSVToColor(255, new float[]{mHue, 1f, 1f});

            Paint p = new Paint();
            // 黑→白 纵向渐变
            p.setShader(new LinearGradient(0, 0, 0, h,
                    Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            // 白→纯色 横向渐变
            p.setShader(new LinearGradient(0, 0, w, 0,
                    Color.WHITE, pure, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);

            // 选中点
            float dx = mSat * w;
            float dy = (1f - mVal) * h;
            mDotPaint.setColor(0xFFFFFFFF);
            c.drawCircle(dx, dy, 7, mDotPaint);
            mDotPaint.setStyle(Paint.Style.STROKE);
            mDotPaint.setStrokeWidth(2);
            mDotPaint.setColor(0xFF000000);
            c.drawCircle(dx, dy, 7, mDotPaint);
            mDotPaint.setStyle(Paint.Style.FILL);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int act = e.getAction();
            if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_MOVE) {
                mSat = Math.max(0, Math.min(1, e.getX() / getWidth()));
                mVal = Math.max(0, Math.min(1, 1f - e.getY() / getHeight()));
                invalidate();
                return true;
            }
            return false;
        }
    }

    // ══════════════════════════════════════════
    //  Hue 滑块
    // ══════════════════════════════════════════

    private static class HueSliderView extends View {
        private final int mStartDeg, mEndDeg;
        private float mHue;
        private final Paint mPointerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private java.util.function.Consumer<Float> mListener;

        HueSliderView(Context ctx, int startDeg, int endDeg) {
            super(ctx);
            mStartDeg = startDeg;
            mEndDeg = endDeg;
        }

        void setHue(float h) { mHue = h; invalidate(); }
        void setOnHueChange(java.util.function.Consumer<Float> l) { mListener = l; }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            Paint p = new Paint();

            // hue 渐变
            int steps = 30;
            float stepH = (float) h / steps;
            for (int i = 0; i < steps; i++) {
                float frac1 = (float) i / steps;
                float frac2 = (float) (i + 1) / steps;
                int deg1 = mStartDeg + (int)((mEndDeg - mStartDeg) * frac1);
                int deg2 = mStartDeg + (int)((mEndDeg - mStartDeg) * frac2);
                int c1 = Color.HSVToColor(255, new float[]{deg1, 1f, 1f});
                int c2 = Color.HSVToColor(255, new float[]{deg2, 1f, 1f});
                p.setShader(new LinearGradient(0, i * stepH, 0, (i + 1) * stepH,
                        c1, c2, Shader.TileMode.CLAMP));
                c.drawRect(0, i * stepH, w, (i + 1) * stepH, p);
            }

            // 指针
            float pos = (mHue - mStartDeg) / (mEndDeg - mStartDeg) * h;
            pos = Math.max(0, Math.min(h, pos));
            mPointerPaint.setColor(0xFFFFFFFF);
            mPointerPaint.setShadowLayer(4, 0, 0, 0x80000000);
            c.drawCircle(w / 2f, pos, 9, mPointerPaint);
            mPointerPaint.setShadowLayer(0, 0, 0, 0);
            mPointerPaint.setStyle(Paint.Style.STROKE);
            mPointerPaint.setStrokeWidth(2);
            mPointerPaint.setColor(0xFF333333);
            c.drawCircle(w / 2f, pos, 9, mPointerPaint);
            mPointerPaint.setStyle(Paint.Style.FILL);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int act = e.getAction();
            if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_MOVE) {
                float fracy = Math.max(0, Math.min(1, e.getY() / getHeight()));
                mHue = mStartDeg + (mEndDeg - mStartDeg) * fracy;
                invalidate();
                if (mListener != null) mListener.accept(mHue);
                return true;
            }
            return false;
        }
    }

    // ══════════════════════════════════════════
    //  Alpha 滑块
    // ══════════════════════════════════════════

    private static class AlphaSliderView extends View {
        private int mAlpha = 255;
        private final Paint mPointerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private java.util.function.Consumer<Integer> mListener;

        AlphaSliderView(Context ctx) { super(ctx); }

        void setAlpha(int a) { mAlpha = a; invalidate(); }
        void setOnAlphaChange(java.util.function.Consumer<Integer> l) { mListener = l; }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;

            // 棋盘底纹
            Paint checkP = new Paint();
            int cell = (int)(10 * getResources().getDisplayMetrics().density);
            for (int row = 0; row < h; row += cell) {
                for (int col = 0; col < w; col += cell) {
                    checkP.setColor((((row / cell) + (col / cell)) % 2 == 0)
                            ? 0xFFCCCCCC : 0xFFFFFFFF);
                    c.drawRect(col, row, Math.min(col + cell, w), Math.min(row + cell, h), checkP);
                }
            }

            // 黑白渐变蒙层
            Paint p = new Paint();
            p.setShader(new LinearGradient(0, 0, 0, h,
                    Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);

            // 指针
            float pos = (1f - mAlpha / 255f) * h;
            mPointerPaint.setColor(0xFFFFFFFF);
            mPointerPaint.setShadowLayer(4, 0, 0, 0x80000000);
            c.drawCircle(w / 2f, pos, 9, mPointerPaint);
            mPointerPaint.setShadowLayer(0, 0, 0, 0);
            mPointerPaint.setStyle(Paint.Style.STROKE);
            mPointerPaint.setStrokeWidth(2);
            mPointerPaint.setColor(0xFF333333);
            c.drawCircle(w / 2f, pos, 9, mPointerPaint);
            mPointerPaint.setStyle(Paint.Style.FILL);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int act = e.getAction();
            if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_MOVE) {
                float fracy = Math.max(0, Math.min(1, e.getY() / getHeight()));
                mAlpha = Math.max(0, Math.min(255, Math.round((1f - fracy) * 255f)));
                invalidate();
                if (mListener != null) mListener.accept(mAlpha);
                return true;
            }
            return false;
        }
    }
}
