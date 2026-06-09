package com.rebron1900.fcitx5enhanced;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/**
 * 波形线 View。
 * 空闲时：一条水平细线
 * 录音时：根据振幅上下波动，正弦波形
 * 无音频输入时也有基础呼吸脉动
 * 触摸：按下开始录音，松开结束
 */
public class WaveformLineView extends View {

    private Paint mPaint;
    private Path mPath;
    private float mAmplitude = 0f;
    private float mTarget = 0f;
    private int mIdleColor, mRecColor, mCurColor;
    private float mPhase = 0f;
    private boolean mRecording = false;
    private OnTouchListener mListener;

    private static final int SEGS = 40;
    private float mDensity;

    public WaveformLineView(Context context) {
        super(context);
        mDensity = getResources().getDisplayMetrics().density;
        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setStrokeJoin(Paint.Join.ROUND);
        mPath = new Path();
        mIdleColor = 0x88CCCCCC;
        mRecColor = 0xFF4F6BF6;
        mCurColor = mIdleColor;
    }

    public void setIdleColor(int c) { mIdleColor = c; mCurColor = mRecording ? mRecColor : mIdleColor; }
    public void setRecordingColor(int c) { mRecColor = c; mCurColor = mRecording ? mRecColor : mIdleColor; }

    public void setRecording(boolean r) {
        mRecording = r;
        mCurColor = r ? mRecColor : mIdleColor;
        if (!r) mTarget = 0f;
        invalidate();
    }

    public void setAmplitude(float a) {
        if (a < 0) a = 0;
        if (a > 1) a = 1;
        mTarget = a;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 平滑振幅过渡
        mAmplitude += (mTarget - mAmplitude) * 0.25f;
        if (Math.abs(mAmplitude - mTarget) < 0.002f) mAmplitude = mTarget;

        mPhase += 0.12f;
        if (mPhase > 200f) mPhase = 0;

        float pad = 6 * mDensity;
        float lineW = w - pad * 2;
        float cy = h / 2f;
        float strokeW = Math.max(1.5f, 2 * mDensity);

        mPaint.setStrokeWidth(strokeW);

        // 渐变遮罩：中间实色 → 两端渐隐到透明
        int r = Color.red(mCurColor), g = Color.green(mCurColor), b = Color.blue(mCurColor);
        int a = Color.alpha(mCurColor);
        int edgeColor = Color.argb(0, r, g, b);
        int centerColor = Color.argb(a, r, g, b);
        mPaint.setShader(new LinearGradient(
                pad, 0, w - pad, 0,
                new int[]{edgeColor, centerColor, centerColor, edgeColor},
                new float[]{0f, 0.2f, 0.8f, 1f},
                Shader.TileMode.CLAMP
        ));

        if (!mRecording) {
            // 空闲：水平线
            c.drawLine(pad, cy, w - pad, cy, mPaint);
            return;
        }

        // 有效振幅：取 max(振幅, 0.05) 确保最低可见波动（呼吸感）
        float amp = Math.max(mAmplitude, 0.05f);
        float maxH = h * 0.48f;                // 波形最大幅度（原 0.38，放大到近半高）

        // 用 lineTo 画简单正弦波（稳定可靠）
        mPath.reset();
        float segW = lineW / SEGS;
        boolean first = true;

        for (int i = 0; i <= SEGS; i++) {
            float t = (float) i / SEGS;
            // 三个频率叠加，产生丰富波浪感
            float wave = (float) (
                Math.sin(t * Math.PI * 6 + mPhase) * 0.5 +
                Math.sin(t * Math.PI * 14 + mPhase * 1.3) * 0.3 +
                Math.sin(t * Math.PI * 28 + mPhase * 0.7) * 0.2
            ) * amp;

            float x = pad + i * segW;
            float y = cy - wave * maxH;

            if (first) { mPath.moveTo(x, y); first = false; }
            else       { mPath.lineTo(x, y); }
        }

        // 画主波形线
        c.drawPath(mPath, mPaint);

        // 持续刷新动画
        postInvalidateOnAnimation();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return mListener != null && mListener.onTouch(this, e);
    }

    @Override
    public void setOnTouchListener(OnTouchListener l) { mListener = l; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int dw = (int)(160 * mDensity + 0.5f);
        int dh = (int)(32 * mDensity + 0.5f);
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);
        if (MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED) w = dw;
        if (MeasureSpec.getMode(hSpec) == MeasureSpec.UNSPECIFIED) h = dh;
        setMeasuredDimension(w, h);
    }
}
