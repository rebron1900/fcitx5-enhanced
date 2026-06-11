package com.rebron1900.fcitx5enhanced;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * HSV 色轮拾色器对话框。
 * 顶部：色相环 + 饱和度/明度三角
 * 底部：Hue / Saturation / Brightness / Alpha 滑动条
 * 点击确定返回选中色值。
 */
public class ColorPickerDialog {

    private final Context mContext;
    private int mInitialColor;
    private float[] mHsv = new float[3];
    private int mAlpha = 255;
    private java.util.function.IntConsumer mListener;

    public ColorPickerDialog(Context context, int initialColor, java.util.function.IntConsumer listener) {
        mContext = context;
        mInitialColor = initialColor;
        Color.colorToHSV(initialColor, mHsv);
        mAlpha = Color.alpha(initialColor);
        mListener = listener;
    }

    public void show() {
        float den = mContext.getResources().getDisplayMetrics().density;
        int pad = (int)(16 * den);

        LinearLayout root = new LinearLayout(mContext);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        // 色环 + SV 三角预览
        final WheelView wheel = new WheelView(mContext);
        wheel.setLayoutParams(new LinearLayout.LayoutParams(
                (int)(260 * den), (int)(260 * den)));
        wheel.setHsv(mHsv);
        root.addView(wheel);

        // 预览方块
        final View preview = new View(mContext);
        int preH = (int)(40 * den);
        preview.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, preH));
        preview.setBackgroundColor(mInitialColor);
        root.addView(preview);

        // ── 滑动条 ──
        root.addView(makeSlider(mContext, "色相", 0, 360, (int) mHsv[0], den,
                v -> {
                    mHsv[0] = v;
                    wheel.setHsv(mHsv);
                    wheel.invalidate();
                    preview.setBackgroundColor(Color.HSVToColor(mAlpha, mHsv));
                }));
        root.addView(makeSlider(mContext, "饱和度", 0, 100, (int) (mHsv[1] * 100), den,
                v -> {
                    mHsv[1] = v / 100f;
                    wheel.setHsv(mHsv);
                    wheel.invalidate();
                    preview.setBackgroundColor(Color.HSVToColor(mAlpha, mHsv));
                }));
        root.addView(makeSlider(mContext, "明度", 0, 100, (int) (mHsv[2] * 100), den,
                v -> {
                    mHsv[2] = v / 100f;
                    wheel.setHsv(mHsv);
                    wheel.invalidate();
                    preview.setBackgroundColor(Color.HSVToColor(mAlpha, mHsv));
                }));
        root.addView(makeSlider(mContext, "透明度", 0, 255, mAlpha, den,
                v -> {
                    mAlpha = v;
                    preview.setBackgroundColor(Color.HSVToColor(mAlpha, mHsv));
                }));

        new AlertDialog.Builder(mContext)
                .setTitle("选择颜色")
                .setView(root)
                .setPositiveButton("确定", (d, w) -> {
                    if (mListener != null)
                        mListener.accept(Color.HSVToColor(mAlpha, mHsv));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static View makeSlider(Context ctx, String label, int min, int max, int cur,
                                    float den, java.util.function.IntConsumer onChange) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, (int)(6*den), 0, (int)(6*den));
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(13);
        tv.setTextColor(0xFFCCCCCC);
        tv.setWidth((int)(60 * den));
        row.addView(tv);

        final TextView valTv = new TextView(ctx);
        valTv.setText(String.valueOf(cur));
        valTv.setTextSize(13);
        valTv.setTextColor(0xFFFFFFFF);
        valTv.setWidth((int)(40 * den));
        valTv.setGravity(Gravity.END);
        row.addView(valTv);

        SeekBar sb = new SeekBar(ctx);
        sb.setMax(max - min);
        sb.setProgress(cur - min);
        sb.setLayoutParams(new LinearLayout.LayoutParams(
                0, (int)(30 * den), 1));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int p, boolean f) {
                int v = p + min;
                valTv.setText(String.valueOf(v));
                onChange.accept(v);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        row.addView(sb);
        return row;
    }

    /** 色环 + SV 三角 */
    private static class WheelView extends View {
        private final Paint mRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTriPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mTriPath = new Path();
        private float[] mHsv = new float[]{0, 1, 1};
        private float mRingR, mTriR;
        private float mCx, mCy;

        public WheelView(Context ctx) {
            super(ctx);
            mRingPaint.setStyle(Paint.Style.STROKE);
            mRingPaint.setStrokeCap(Paint.Cap.BUTT);
            mDotPaint.setStyle(Paint.Style.FILL);
            mDotPaint.setColor(0xFFFFFFFF);
        }

        void setHsv(float[] hsv) { mHsv = hsv.clone(); }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            mCx = w / 2f;
            mCy = h / 2f;
            mRingR = Math.min(mCx, mCy) - 4;
            mTriR = mRingR * 0.75f;
            mRingPaint.setStrokeWidth(mRingR * 0.22f);
            // 三角：倒立等边三角形，边长 = triR * sqrt(3)
            float s = (float)(mTriR * 1.732);
            float halfS = s / 2f;
            float triCx = mCx;
            float triCy = mCy + mRingR * 0.04f;
            mTriPath.reset();
            mTriPath.moveTo(triCx, triCy - mTriR);
            mTriPath.lineTo(triCx - halfS, triCy + mTriR * 0.5f);
            mTriPath.lineTo(triCx + halfS, triCy + mTriR * 0.5f);
            mTriPath.close();
        }

        @Override
        protected void onDraw(Canvas c) {
            // 色环
            for (int i = 0; i < 360; i++) {
                float start = i;
                float sweep = 2;
                float rOut = mRingR + mRingPaint.getStrokeWidth() / 2f;
                float rIn = mRingR - mRingPaint.getStrokeWidth() / 2f;
                RectF outer = new RectF(mCx - rOut, mCy - rOut, mCx + rOut, mCy + rOut);
                RectF inner = new RectF(mCx - rIn, mCy - rIn, mCx + rIn, mCy + rIn);
                Path arc = new Path();
                arc.arcTo(outer, start, sweep);
                arc.arcTo(inner, start + sweep, -sweep);
                arc.close();
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.HSVToColor(255, new float[]{i, 1f, 1f}));
                c.drawPath(arc, p);
            }

            // SV 三角填充
            int baseHue = (int) mHsv[0];
            float[] p0 = new float[]{mCx, mCy - mTriR};           // 顶点
            float s = (float)(mTriR * 1.732);
            float halfS = s / 2f;
            float triCy = mCy + mRingR * 0.04f;
            float[] p1 = new float[]{mCx - halfS, triCy + mTriR * 0.5f}; // 左下
            float[] p2 = new float[]{mCx + halfS, triCy + mTriR * 0.5f}; // 右下

            // 三个角分别着纯色/黑色/白色
            int c0 = Color.HSVToColor(255, new float[]{baseHue, 1f, 1f});
            int c1 = Color.BLACK;
            int c2 = Color.WHITE;

            // 用三条边渐变填充实现 SV 三角
            // 简单方法：画三个渐变三角拼起来
            // 从顶点到底边渐变（c0→混合）
            Paint triGrad = new Paint();
            triGrad.setShader(new LinearGradient(
                    p0[0], p0[1], (p1[0]+p2[0])/2f, (p1[1]+p2[1])/2f,
                    c0, Color.WHITE, Shader.TileMode.CLAMP));
            c.save();
            c.clipPath(mTriPath);
            c.drawRect(mCx - mTriR, mCy - mTriR, mCx + mTriR, mCy + mTriR, triGrad);
            // 从左到右叠加黑色
            Paint blackGrad = new Paint();
            blackGrad.setShader(new LinearGradient(
                    p1[0], p1[1], p2[0], p2[1],
                    Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP));
            c.drawRect(mCx - mTriR, mCy - mTriR, mCx + mTriR, mCy + mTriR, blackGrad);
            c.restore();

            // 当前 SV 位置圆点
            float svX = p0[0] + (p1[0] - p0[0]) * mHsv[2] + (p2[0] - p1[0]) * mHsv[1] * mHsv[2] * 0.5f;
            float svY = p0[1] + (p1[1] - p0[1]) * mHsv[2] + (p2[1] - p1[1]) * mHsv[1] * mHsv[2] * 0.5f;
            // 简化：用比例映射到三角
            float sRatio = mHsv[1]; // saturation
            float vRatio = mHsv[2]; // value
            float tX = p0[0] + (p1[0] - p0[0]) * (1 - vRatio) + (p2[0] - p1[0]) * sRatio * vRatio;
            float tY = p0[1] + (p1[1] - p0[1]) * (1 - vRatio) + (p2[1] - p1[1]) * sRatio * vRatio;
            mDotPaint.setColor(0xFFFFFFFF);
            c.drawCircle(tX, tY, 6, mDotPaint);
            mDotPaint.setStyle(Paint.Style.STROKE);
            mDotPaint.setStrokeWidth(2);
            mDotPaint.setColor(0xFF000000);
            c.drawCircle(tX, tY, 6, mDotPaint);
            mDotPaint.setStyle(Paint.Style.FILL);
        }
    }
}
