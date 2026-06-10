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
 * 波形线 View，基于 jaygoo/widget/wlv/WaveLineView 设计重写。
 *
 * 振幅管线：
 *   setAmplitude(0~1) → 内部放大到 0~100 音量
 *   → softerChangeVolume() 按 perVolume(=sensibility*0.35) 每帧追赶
 *   → height * volume * 0.01 * waveFormula * pathFunc
 *
 * 可调参数：mSamplingSize（采样密度）、mSensibility（响应灵敏度 1~10）
 */
public class WaveformLineView extends View {

    private Paint mLinePaint;
    private Path mPath;
    private int mIdleColor, mRecColor, mCurColor;

    /** ── WaveLineView 风格参数 ── */
    private int mSamplingSize = 64;
    private int mSensibility = 10;           // 1~10，越大响应越快
    private float mVolume = 0f;              // 当前音量 0~100
    private int mTargetVolume = 0;           // 目标音量 0~100
    private float mPerVolume;                // = sensibility * 0.35f

    private float mPhase = 0f;
    private boolean mRecording = false;
    private OnTouchListener mListener;

    private float mDensity;

    public WaveformLineView(Context context) {
        super(context);
        mDensity = getResources().getDisplayMetrics().density;
        mLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mLinePaint.setStyle(Paint.Style.STROKE);
        mLinePaint.setStrokeCap(Paint.Cap.ROUND);
        mLinePaint.setStrokeJoin(Paint.Join.ROUND);
        mPath = new Path();
        mIdleColor = 0x88CCCCCC;
        mRecColor = 0xFF4F6BF6;
        mCurColor = mIdleColor;
        mPerVolume = mSensibility * 0.35f;
    }

    /** 设置采样点密度（默认 40） */
    public void setSamplingSize(int sz) {
        mSamplingSize = Math.max(8, Math.min(128, sz));
    }

    /** 设置响应灵敏度 1~10，默认 5。越大振幅变化越快。 */
    public void setSensibility(int s) {
        mSensibility = Math.max(1, Math.min(10, s));
        mPerVolume = mSensibility * 0.35f;
    }
    public int getSensibility() { return mSensibility; }

    public void setIdleColor(int c) { mIdleColor = c; mCurColor = mRecording ? mRecColor : mIdleColor; }
    public void setRecordingColor(int c) { mRecColor = c; mCurColor = mRecording ? mRecColor : mIdleColor; }

    public void setRecording(boolean r) {
        mRecording = r;
        mCurColor = r ? mRecColor : mIdleColor;
        if (!r) {
            mTargetVolume = 0;
        }
        invalidate();
    }

    /** 从 AIDL 服务接收归一化振幅 0~1，内部放大到 0~100 */
    public void setAmplitude(float a) {
        if (a < 0) a = 0;
        if (a > 1) a = 1;
        // 放大到 0~100：小声音 200x 放大保证幅度；低于阈值则归零
        int vol = Math.min(100, (int)(a * 200f));
        if (vol < 3) vol = 0;
        mTargetVolume = vol;
        if (vol > 0 && !mRecording) {
            invalidate();
            return;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        // ── WaveLineView 风格音量平滑 ──
        softerChangeVolume();

        float pad = 6 * mDensity;
        float lineW = w - pad * 2;
        float cy = h / 2f;
        float strokeW = Math.max(1.5f, 2 * mDensity);

        mLinePaint.setStrokeWidth(strokeW);

        int r = Color.red(mCurColor), g = Color.green(mCurColor), b = Color.blue(mCurColor);
        int a = Color.alpha(mCurColor);

        // ── 渐变遮罩 ──
        int edgeColor = Color.argb(0, r, g, b);
        int centerColor = Color.argb(a, r, g, b);
        LinearGradient lineGrad = new LinearGradient(
                pad, 0, w - pad, 0,
                new int[]{edgeColor, centerColor, centerColor, edgeColor},
                new float[]{0f, 0.2f, 0.8f, 1f},
                Shader.TileMode.CLAMP
        );

        if (!mRecording) {
            // 空闲：水平直线
            mPath.reset();
            mPath.moveTo(pad, cy);
            mPath.lineTo(w - pad, cy);
            mLinePaint.setShader(lineGrad);
            mLinePaint.setStrokeWidth(strokeW);
            c.drawPath(mPath, mLinePaint);
            return;
        }

        // ── 录音态 ──
        // 音量缩放因子 0~1
        float volScale = mVolume * 0.01f;

        // 最大波形幅度：拉到视图高度 80%
        float maxAmp = h * 0.8f;

        // 波动速度：每帧 0.04，舒缓波动
        mPhase += 0.04f;
        if (mPhase > 200f) mPhase = 0;
        float offset = mPhase;

        float segW = lineW / mSamplingSize;

        // 4 层参数：主线 1.0，其他线继续加大
        float[] pathFuncs = {1.0f, 0.9f, -0.8f, -1.0f};
        float[] layerWidths = {strokeW * 0.8f, strokeW * 0.6f, strokeW * 0.5f, strokeW * 0.4f};
        int[] layerAlphas = {255, 220, 190, 150};
        int[] layerColors = new int[4];
        float[] hsv = new float[3];
        Color.colorToHSV(mRecColor, hsv);
        float baseBright = Math.max(0.5f, hsv[2]);
        float baseSat = Math.max(0.3f, hsv[1]);
        for (int i = 0; i < 4; i++) {
            float bright = Math.min(1f, baseBright + 0.2f - i * 0.15f);
            float sat = Math.max(0.15f, baseSat - i * 0.12f);
            layerColors[i] = Color.HSVToColor(layerAlphas[i],
                    new float[]{hsv[0], sat, Math.max(0.4f, bright)});
        }

        // 预计算波形值
        float[] waveY = new float[mSamplingSize + 1];
        for (int i = 0; i <= mSamplingSize; i++) {
            float t = (float) i / mSamplingSize;
            double mapX = (t - 0.5) * 4.0;
            double sinVal = Math.sin(Math.PI * mapX - offset * Math.PI);
            double rec = 4.0 / (4.0 + mapX * mapX * mapX * mapX);
            waveY[i] = (float) (sinVal * rec * maxAmp);
        }

        Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
        lp.setStyle(Paint.Style.STROKE);
        lp.setStrokeCap(Paint.Cap.ROUND);
        lp.setStrokeJoin(Paint.Join.ROUND);

        // 用 saveLayer + DST_IN 透明度遮罩实现边缘渐隐
        // 先画所有路径到一个离屏缓冲，再用渐变遮罩裁剪两端 alpha
        int fadeSave = c.saveLayer(0, 0, w, h, null);
        for (int layer = 0; layer < 4; layer++) {
            mPath.reset();
            for (int i = 0; i <= mSamplingSize; i++) {
                float x = pad + i * segW;
                float y = cy + waveY[i] * pathFuncs[layer] * volScale;
                if (i == 0) mPath.moveTo(x, y);
                else        mPath.lineTo(x, y);
            }
            lp.setStrokeWidth(layerWidths[layer]);
            lp.setColor(layerColors[layer]);
            lp.setShader(null);
            c.drawPath(mPath, lp);
        }
        // 透明度遮罩：两边 15% → 透明，中间 → 保持
        Paint maskPaint = new Paint();
        maskPaint.setXfermode(new android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.DST_IN));
        LinearGradient maskGrad = new LinearGradient(
                0, 0, w, 0,
                new int[]{0x00000000, 0xFFFFFFFF, 0xFFFFFFFF, 0x00000000},
                new float[]{0f, 0.15f, 0.85f, 1f},
                Shader.TileMode.CLAMP
        );
        maskPaint.setShader(maskGrad);
        c.drawRect(0, 0, w, h, maskPaint);
        c.restoreToCount(fadeSave);

        postInvalidateOnAnimation();
    }

    /** WaveLineView 风格音量平滑追赶 */
    private void softerChangeVolume() {
        if (mVolume < mTargetVolume - mPerVolume) {
            mVolume += mPerVolume;
        } else if (mVolume > mTargetVolume + mPerVolume) {
            mVolume = Math.max(mPerVolume * 2, mVolume - mPerVolume);
        } else {
            mVolume = mTargetVolume;
        }
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
