package com.rebron1900.fcitx5enhanced;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/**
 * 波形线 View，基于 jaygoo/widget/wlv/WaveLineView 设计重写。
 *
 * 性能优化：
 * - Paint、Path 在构造函数中创建，不在 onDraw 中分配
 * - LinearGradient 和 mask Paint 在尺寸变化时缓存
 * - 只在录音态调用 postInvalidateOnAnimation
 */
public class WaveformLineView extends View {

    // ── 预分配的 Paint/Path（不在 onDraw 中创建） ──
    private final Paint mLinePaint;
    private final Paint mLayerPaint;       // 录音态多层波形共用
    private final Paint mMaskPaint;        // 透明度遮罩
    private final Path mPath;

    private int mIdleColor, mRecColor, mCurColor;

    // ── 缓存的 Shader（尺寸变化时重建） ──
    private LinearGradient mIdleShader;
    private LinearGradient mMaskShader;
    private boolean mShaderDirty = true;

    // ── WaveLineView 风格参数 ──
    private int mSamplingSize = 64;
    private int mSensibility = 10;
    private float mVolume = 0f;
    private int mTargetVolume = 0;
    private float mPerVolume;

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

        mLayerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mLayerPaint.setStyle(Paint.Style.STROKE);
        mLayerPaint.setStrokeCap(Paint.Cap.ROUND);
        mLayerPaint.setStrokeJoin(Paint.Join.ROUND);

        mMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mMaskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));

        mPath = new Path();

        mIdleColor = 0x88CCCCCC;
        mRecColor = 0xFF4F6BF6;
        mCurColor = mIdleColor;
        mPerVolume = mSensibility * 0.35f;
    }

    public void setSamplingSize(int sz) {
        mSamplingSize = Math.max(8, Math.min(128, sz));
    }

    public void setSensibility(int s) {
        mSensibility = Math.max(1, Math.min(10, s));
        mPerVolume = mSensibility * 0.35f;
    }

    public int getSensibility() { return mSensibility; }

    public void setIdleColor(int c) {
        mIdleColor = c;
        mCurColor = mRecording ? mRecColor : mIdleColor;
        mShaderDirty = true;
    }

    public void setRecordingColor(int c) {
        mRecColor = c;
        mCurColor = mRecording ? mRecColor : mIdleColor;
        mShaderDirty = true;
    }

    public void setRecording(boolean r) {
        mRecording = r;
        mCurColor = r ? mRecColor : mIdleColor;
        mShaderDirty = true;
        if (!r) {
            mTargetVolume = 0;
        }
        invalidate();
    }

    /** 从 AIDL 服务接收归一化振幅 0~1 */
    public void setAmplitude(float a) {
        if (a < 0) a = 0;
        if (a > 1) a = 1;
        int vol = Math.min(100, (int)(a * 120f));
        if (vol < 3) vol = 0;
        mTargetVolume = vol;
        if (!mRecording) return;  // 非录音态不触发重绘
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mShaderDirty = true;
    }

    /** 尺寸或颜色变化时重建缓存的 Shader */
    private void ensureShaders(int w, int h) {
        if (!mShaderDirty) return;
        mShaderDirty = false;

        float pad = 6 * mDensity;
        int r = Color.red(mCurColor), g = Color.green(mCurColor), b = Color.blue(mCurColor);
        int a = Color.alpha(mCurColor);

        int edgeColor = Color.argb(0, r, g, b);
        int centerColor = Color.argb(a, r, g, b);
        mIdleShader = new LinearGradient(
                pad, 0, w - pad, 0,
                new int[]{edgeColor, centerColor, centerColor, edgeColor},
                new float[]{0f, 0.2f, 0.8f, 1f},
                Shader.TileMode.CLAMP
        );

        mMaskShader = new LinearGradient(
                0, 0, w, 0,
                new int[]{0x00000000, 0xFFFFFFFF, 0xFFFFFFFF, 0x00000000},
                new float[]{0f, 0.15f, 0.85f, 1f},
                Shader.TileMode.CLAMP
        );
        mMaskPaint.setShader(mMaskShader);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        softerChangeVolume();
        ensureShaders(w, h);

        float pad = 6 * mDensity;
        float lineW = w - pad * 2;
        float cy = h / 2f;
        float strokeW = Math.max(1.5f, 2 * mDensity);

        if (!mRecording) {
            // 空闲：水平直线
            mPath.reset();
            mPath.moveTo(pad, cy);
            mPath.lineTo(w - pad, cy);
            mLinePaint.setStrokeWidth(strokeW);
            mLinePaint.setShader(mIdleShader);
            c.drawPath(mPath, mLinePaint);
            return;
        }

        // ── 录音态 ──
        float volScale = Math.min(1.0f, mVolume * 0.02f);
        float maxAmp = h * 0.45f;

        mPhase += 0.025f;
        if (mPhase > 200f) mPhase = 0;
        float offset = mPhase;

        float segW = lineW / mSamplingSize;

        float[] pathFuncs = {1.0f, 0.9f, -0.8f, -1.0f};
        float[] layerWidths = {strokeW * 0.8f, strokeW * 0.6f, strokeW * 0.5f, strokeW * 0.4f};

        int lr = Color.red(mRecColor), lg = Color.green(mRecColor), lb = Color.blue(mRecColor);

        // 预计算波形值
        float[] waveY = new float[mSamplingSize + 1];
        for (int i = 0; i <= mSamplingSize; i++) {
            float t = (float) i / mSamplingSize;
            double mapX = (t - 0.5) * 4.0;
            double sinVal = Math.sin(Math.PI * mapX - offset * Math.PI);
            double rec = 4.0 / (4.0 + mapX * mapX * mapX * mapX);
            waveY[i] = (float) (sinVal * rec * maxAmp);
        }

        // 用 saveLayer + DST_IN 透明度遮罩实现边缘渐隐
        int fadeSave = c.saveLayer(0, 0, w, h, null);
        for (int layer = 0; layer < 4; layer++) {
            mPath.reset();
            for (int i = 0; i <= mSamplingSize; i++) {
                float x = pad + i * segW;
                float y = cy + waveY[i] * pathFuncs[layer] * volScale;
                if (i == 0) mPath.moveTo(x, y);
                else        mPath.lineTo(x, y);
            }
            mLayerPaint.setStrokeWidth(layerWidths[layer]);
            mLayerPaint.setColor(Color.argb(255, lr, lg, lb));
            mLayerPaint.setShader(null);
            c.drawPath(mPath, mLayerPaint);
        }
        c.drawRect(0, 0, w, h, mMaskPaint);
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
