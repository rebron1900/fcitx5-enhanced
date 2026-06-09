package com.rebron1900.fcitx5enhanced;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;
import android.view.inputmethod.InputConnection;
import android.widget.Toast;

import java.lang.ref.WeakReference;

/** 振幅回调接口 */
interface AmplitudeListener {
    void onAmplitude(float normalizedAmplitude);
}

/**
 * 通过 AIDL 连接 bibi keyboard 外部语音识别服务的客户端。
 * 每次按下创建新实例，松开后清理。
 *
 * 关键设计：
 * - mConsumed 原子标记：回调发到主线程前先标记为"已消费"，防止重复提交
 * - stopSequence: 先 finishPcm → 再 stopAudio → 确保数据完整
 * - cancel(): 标记已取消 + unbind → 新按下时清除旧 session
 */
public class VoiceInputClient {

    private static final String TAG = "VoiceInput";

    private static final String DESCRIPTOR_SVC = "com.brycewg.asrkb.aidl.IExternalSpeechService";
    private static final String DESCRIPTOR_CB = "com.brycewg.asrkb.aidl.ISpeechCallback";

    // 事务码 (FIRST_CALL_TRANSACTION=1)
    private static final int TRANSACTION_startPcmSession = 7;
    private static final int TRANSACTION_writePcm = 8;
    private static final int TRANSACTION_finishPcm = 9;
    private static final int TRANSACTION_cancelSession = 3;

    // 回调事务码
    private static final int CB_onPartial = 2;
    private static final int CB_onFinal = 3;
    private static final int CB_onError = 4;
    private static final int CB_onAmplitude = 5;

    private static final int STATE_IDLE = 0;
    private static final int STATE_RECORDING = 1;

    // ── 状态 ──
    private volatile boolean mHolding;
    private volatile boolean mConsumed;  // Atomic 语义：一旦标记就不再提交文字
    private InputMethodService mService;
    private IBinder mRemote;
    private ServiceConnection mConnection;
    private boolean mBound;
    private int mSessionId = -1;
    private int mCurrentState = STATE_IDLE;
    private Thread mAudioThread;
    private AudioRecord mAudioRecord;
    private boolean mHasPcmFrame;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private WeakReference<InputConnection> mInputConnectionRef;
    private WeakReference<Runnable> mOnDoneRef;
    private AmplitudeListener mAmpListener;

    /** 设置振幅回调（UI 更新） */
    public void setAmplitudeListener(AmplitudeListener l) {
        mAmpListener = l;
    }

    /**
     * 开始语音识别会话。
     */
    public void startVoiceInput(InputMethodService service, InputConnection ic, Runnable onDone) {
        mService = service;
        mHolding = true;
        mConsumed = false;
        mInputConnectionRef = new WeakReference<>(ic);
        mOnDoneRef = new WeakReference<>(onDone);

        final ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                if (!mHolding || mConsumed) {
                    Log.d(TAG, "onServiceConnected: already cancelled, unbinding");
                    doUnbind();
                    return;
                }
                try {
                    if (binder == null) throw new IllegalStateException("no binder");
                    mRemote = binder;

                    final Binder cbBinder = new Binder() {
                        @Override
                        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                            try {
                                if (mConsumed) {
                                    // 已取消：回应 success 但不做任何事
                                    if (reply != null) reply.writeNoException();
                                    return true;
                                }
                                switch (code) {
                                    case CB_onPartial: {
                                        data.enforceInterface(DESCRIPTOR_CB);
                                        data.readInt();
                                        String text = data.readString();
                                        if (text == null) text = "";
                                        final String t = text;
                                        mMainHandler.post(() -> {
                                            if (mConsumed) return;
                                            InputConnection icL = mInputConnectionRef != null
                                                    ? mInputConnectionRef.get() : null;
                                            if (icL != null) icL.setComposingText(t, 1);
                                        });
                                        if (reply != null) reply.writeNoException();
                                        return true;
                                    }
                                    case CB_onFinal: {
                                        data.enforceInterface(DESCRIPTOR_CB);
                                        data.readInt();
                                        String text = data.readString();
                                        if (text == null) text = "";
                                        final String t = text;
                                        // 在 Binder 线程立即标记 mConsumed，防第二个回调（AI 修正版）竞争
                                        if (mConsumed) return true;
                                        mConsumed = true;
                                        mMainHandler.post(() -> {
                                            InputConnection icL = mInputConnectionRef != null
                                                    ? mInputConnectionRef.get() : null;
                                            if (icL != null) {
                                                icL.finishComposingText();
                                                icL.commitText(t, 1);
                                            }
                                            Runnable done = mOnDoneRef != null ? mOnDoneRef.get() : null;
                                            if (done != null) done.run();
                                        });
                                        doUnbind();
                                        if (reply != null) reply.writeNoException();
                                        return true;
                                    }
                                    case CB_onError: {
                                        data.enforceInterface(DESCRIPTOR_CB);
                                        data.readInt();
                                        int errCode = data.readInt();
                                        String msg = data.readString();
                                        Log.w(TAG, "ASR error: " + errCode + " " + msg);
                                        showToast("语音识别错误: " + (msg != null ? msg : "code=" + errCode));
                                        if (mConsumed) return true;
                                        mConsumed = true;
                                        mMainHandler.post(() -> {
                                            Runnable done = mOnDoneRef != null ? mOnDoneRef.get() : null;
                                            if (done != null) done.run();
                                        });
                                        doUnbind();
                                        if (reply != null) reply.writeNoException();
                                        return true;
                                    }
                                    case CB_onAmplitude: {
                                        data.enforceInterface(DESCRIPTOR_CB);
                                        data.readInt();
                                        data.readFloat();
                                        if (reply != null) reply.writeNoException();
                                        return true;
                                    }
                                    case IBinder.INTERFACE_TRANSACTION: {
                                        if (reply != null) reply.writeString(DESCRIPTOR_CB);
                                        return true;
                                    }
                                    default:
                                        return super.onTransact(code, data, reply, flags);
                                }
                            } catch (Throwable t) {
                                Log.w(TAG, "callback transact failed", t);
                                return false;
                            }
                        }
                    };

                    Parcel data = Parcel.obtain();
                    Parcel reply = Parcel.obtain();
                    int sid = -999;
                    try {
                        data.writeInterfaceToken(DESCRIPTOR_SVC);
                        // presence=1, 带 SpeechConfig（使用已配置的 ASR 引擎）
                        data.writeInt(1);
                        data.writeString(null);  // vendorId=null → app 设置
                        data.writeInt(1);        // streamingPreferred=true
                        data.writeInt(-1);       // punctuationEnabled=null
                        data.writeInt(-1);       // autoStopOnSilence=null
                        data.writeString(null);  // sessionTag=null
                        data.writeStrongBinder(cbBinder);
                        binder.transact(TRANSACTION_startPcmSession, data, reply, 0);
                        reply.readException();
                        sid = reply.readInt();
                    } finally {
                        try { data.recycle(); } catch (Throwable ignored) {}
                        try { reply.recycle(); } catch (Throwable ignored) {}
                    }

                    if (sid <= 0) {
                        Log.w(TAG, "startPcmSession returned " + sid);
                        showToast("bibi 会话启动失败 (code=" + sid + ")");
                        doUnbind();
                    } else {
                        mSessionId = sid;
                        mCurrentState = STATE_RECORDING;
                        startAudioStreaming();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "bind/start failed", t);
                    showToast("无法连接 bibi keyboard 服务");
                    doUnbind();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                doUnbind();
            }
        };
        mConnection = conn;

        ComponentName[] candidates = new ComponentName[]{
                new ComponentName("com.brycewg.asrkb.pro",
                        "com.brycewg.asrkb.api.ExternalSpeechService"),
                new ComponentName("com.brycewg.asrkb",
                        "com.brycewg.asrkb.api.ExternalSpeechService")
        };

        boolean anyBound = false;
        for (ComponentName c : candidates) {
            Intent intent = new Intent().setComponent(c);
            try {
                anyBound = service.bindService(intent, conn, Context.BIND_AUTO_CREATE);
                if (anyBound) break;
            } catch (Throwable t) {
                Log.d(TAG, "bind attempt failed: " + c.getPackageName(), t);
            }
        }
        mBound = anyBound;

        if (!anyBound) {
            showToast("未找到 bibi keyboard");
            fireOnDone();
            doUnbind();
        }
    }

    /**
     * 停止语音识别（松开按钮时调用）。
     * 先 finishPcm 告诉服务端处理音频 → 再停录音线程。
     */
    public void stopVoiceInput() {
        if (!mHolding || mConsumed) return;
        mHolding = false;

        if (mCurrentState == STATE_IDLE) {
            Log.d(TAG, "stopVoiceInput: still binding, cancel");
            fireOnDone();
            doUnbind();
            return;
        }

        // 关键顺序同原版：先停录音 → 再 finishPcm（已在 finishSession 内部处理）
        if (mHasPcmFrame) {
            finishSession();
        } else {
            cancelSession();
            stopAudioStreaming();
        }
    }

    /**
     * 强制取消当前会话（新按下时调用旧 client 的 cancel）。
     * 设置 mConsumed 后所有回调都不会提交文字。
     */
    public void cancel() {
        if (mConsumed) return;
        mConsumed = true;
        mHolding = false;
        doUnbind();
        stopAudioStreaming();
        fireOnDone();
    }

    // ── 音频流 ──

    private void startAudioStreaming() {
        if (mService.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "缺少 RECORD_AUDIO 权限");
            showToast("需要麦克风权限");
            fireOnDone();
            doUnbind();
            return;
        }

        mAudioThread = new Thread(() -> {
            int sr = 16000;
            int ch = AudioFormat.CHANNEL_IN_MONO;
            int fmt = AudioFormat.ENCODING_PCM_16BIT;
            int minBuf = AudioRecord.getMinBufferSize(sr, ch, fmt);
            int chunkBytes = (sr * 200 / 1000) * 2;
            int bufSize = Math.max(minBuf, chunkBytes * 2);

            AudioRecord rec;
            try {
                rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        sr, ch, fmt, bufSize);
                rec.startRecording();
            } catch (Throwable t) {
                Log.w(TAG, "AudioRecord failed", t);
                try {
                    rec = new AudioRecord(MediaRecorder.AudioSource.MIC,
                            sr, ch, fmt, bufSize);
                    rec.startRecording();
                } catch (Throwable e) {
                    Log.e(TAG, "AudioRecord both failed", e);
                    showToast("录音启动失败");
                    fireOnDone();
                    doUnbind();
                    return;
                }
            }
            mAudioRecord = rec;

            byte[] chunk = new byte[chunkBytes];
            boolean notified = false;
            long silentChunks = 0;

            while (!Thread.currentThread().isInterrupted()
                    && mSessionId > 0 && mRemote != null && !mConsumed) {

                if (!mHolding) break;  // 松手立刻退

                int n;
                try {
                    n = rec.read(chunk, 0, chunk.length);
                } catch (Throwable t) {
                    break;
                }
                if (n < 0) break;
                if (n == 0) {
                    try { Thread.sleep(10); } catch (InterruptedException e) { break; }
                    continue;
                }
                if (!notified) {
                    notified = true;
                    Log.d(TAG, "audio streaming started");
                }
                // 计算 RMS 振幅并回调
                writePcmFrame(chunk, n, sr, 1);
                if (mAmpListener != null) {
                    float amp = computeRmsAmplitude(chunk, n);
                    mAmpListener.onAmplitude(amp);
                }
                silentChunks = 0;
            }
        });
        mAudioThread.setDaemon(true);
        mAudioThread.start();
    }

    private void stopAudioStreaming() {
        if (mAudioThread != null) {
            mAudioThread.interrupt();
            mAudioThread = null;
        }
        if (mAudioRecord != null) {
            try { mAudioRecord.stop(); } catch (Throwable ignored) {}
            try { mAudioRecord.release(); } catch (Throwable ignored) {}
            mAudioRecord = null;
        }
    }

    private void writePcmFrame(byte[] buf, int len, int sr, int ch) {
        if (mRemote == null || mSessionId <= 0) return;
        if (len > 0) mHasPcmFrame = true;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR_SVC);
            data.writeInt(mSessionId);
            if (len == buf.length) data.writeByteArray(buf);
            else {
                byte[] copy = new byte[len];
                System.arraycopy(buf, 0, copy, 0, len);
                data.writeByteArray(copy);
            }
            data.writeInt(sr);
            data.writeInt(ch);
            mRemote.transact(TRANSACTION_writePcm, data, reply, 0);
            reply.readException();
        } catch (Throwable t) {
            Log.w(TAG, "writePcm failed", t);
        } finally {
            try { data.recycle(); } catch (Throwable ignored) {}
            try { reply.recycle(); } catch (Throwable ignored) {}
        }
    }

    // ── 会话控制 ──

    /** 先停录音 → 再 finishPcm（确保 finishPcm 到达前所有 chunk 已发出） */
    private void finishSession() {
        if (mRemote == null || mSessionId <= 0) return;
        // 第一步：停录音线程（确保不再写 PCM）
        stopAudioStreaming();
        // 第二步：发 finishPcm（服务端开始处理完整音频）
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR_SVC);
            data.writeInt(mSessionId);
            mRemote.transact(TRANSACTION_finishPcm, data, reply, 0);
            reply.readException();
        } catch (Throwable t) {
            Log.w(TAG, "finishSession failed", t);
        } finally {
            try { data.recycle(); } catch (Throwable ignored) {}
            try { reply.recycle(); } catch (Throwable ignored) {}
        }
    }

    private void cancelSession() {
        if (mRemote == null || mSessionId <= 0) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR_SVC);
            data.writeInt(mSessionId);
            mRemote.transact(TRANSACTION_cancelSession, data, reply, 0);
            reply.readException();
        } catch (Throwable t) {
            Log.w(TAG, "cancelSession failed", t);
        } finally {
            try { data.recycle(); } catch (Throwable ignored) {}
            try { reply.recycle(); } catch (Throwable ignored) {}
        }
    }

    private void doUnbind() {
        try {
            if (mBound && mConnection != null && mService != null) {
                mService.unbindService(mConnection);
            }
        } catch (Throwable ignored) {}
        mBound = false;
        mConnection = null;
        mRemote = null;
        mSessionId = -1;
        mCurrentState = STATE_IDLE;
        mHasPcmFrame = false;
    }

    private void fireOnDone() {
        mMainHandler.post(() -> {
            if (mConsumed) return;
            mConsumed = true;
            Runnable done = mOnDoneRef != null ? mOnDoneRef.get() : null;
            if (done != null) done.run();
        });
    }

    private void showToast(String msg) {
        if (mService != null) {
            mMainHandler.post(() ->
                Toast.makeText(mService, msg, Toast.LENGTH_SHORT).show());
        }
    }

    /** 从 PCM 16bit 数据计算 RMS 归一化振幅 */
    private float computeRmsAmplitude(byte[] buffer, int len) {
        int samples = len / 2;
        if (samples <= 0) return 0f;
        double sumSq = 0;
        for (int i = 0; i < samples; i++) {
            short sample = (short) ((buffer[i * 2 + 1] << 8) | (buffer[i * 2] & 0xFF));
            sumSq += (double) sample * sample;
        }
        float rms = (float) Math.sqrt(sumSq / samples);
        // 归一化到 0~1（16bit max = 32768）
        float norm = rms / 32768f;
        // 环境噪声通常在 0.01~0.05，提高灵敏度
        if (norm < 0.02f) norm = 0f;
        return Math.min(1f, norm * 4f);
    }
}
