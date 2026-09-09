package com.rebron1900.fcitx5enhanced;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** 底部角按钮：IME 切换、剪贴板历史、语音波形。 */
public class ExtraButtonsHelper {
    private static final String TAG = "Fcitx5Enh";

    private static boolean buttonsInitialized;

    public static void add(View inputView, MainHook.Config c, MainHook.ThemeInfo ti) {
        try {
            Method getKv = inputView.getClass().getMethod("getKeyboardView");
            final ViewGroup keyboardView = (ViewGroup) getKv.invoke(inputView);

            if (buttonsInitialized) {
                View ime = keyboardView.findViewWithTag("frosted_btn_ime");
                View clip = keyboardView.findViewWithTag("frosted_btn_clipboard");
                View wave = keyboardView.findViewWithTag("frosted_btn_voice");
                String pkg = inputView.getContext().getPackageName();
                boolean isOriginal = "org.fcitx.fcitx5.android".equals(pkg);
                // 需要补齐的缺失按钮（键盘重建时 fcitx5 可能只保留了部分按钮）
                boolean needIme = c.leftBtn && ime == null;
                boolean needClip = c.rightBtn && clip == null;
                boolean needWave = c.voice && !isOriginal && wave == null;
                boolean allGone = ime == null && clip == null && wave == null;

                if (!allGone && !needIme && !needClip && !needWave) {
                    // 按钮齐全：同步可见性和主题颜色，避免主题切换后图标保持旧颜色。
                    updateExistingButtonAppearance(inputView, ime, clip, wave, ti);
                    updateExistingButtonPositions(inputView, ime, clip, wave, c);
                    if (ime != null) ime.setVisibility(c.leftBtn ? View.VISIBLE : View.GONE);
                    if (clip != null) clip.setVisibility(c.rightBtn ? View.VISIBLE : View.GONE);
                    if (wave != null) {
                        if (!c.voice) cancelVoiceSession(wave);
                        wave.setVisibility(c.voice ? View.VISIBLE : View.GONE);
                    }
                    Log.i(TAG, "toggle btns L=" + c.leftBtn + " R=" + c.rightBtn + " V=" + c.voice);
                    return;
                }
                // 有缺失或残留：清掉全部旧按钮后重建（避免重复 add）
                for (String tag : new String[]{"frosted_btn_ime", "frosted_btn_clipboard", "frosted_btn_voice"}) {
                    View b = keyboardView.findViewWithTag(tag);
                    if (b != null) {
                        if ("frosted_btn_voice".equals(tag)) cancelVoiceSession(b);
                        keyboardView.removeView(b);
                    }
                }
                buttonsInitialized = false;
            }

            final Resources res = inputView.getResources();
            Context ctx = inputView.getContext();
            final float den = res.getDisplayMetrics().density;
            final int bs = (int) (30 * den + .5f);
            final int sideMargin = Math.round(26 * den);
            final int bottomMargin = Math.round(c.buttonBottomMargin * den);

            int keyFg = ti.altKeyTextColor != 0 ? ti.altKeyTextColor : 0xFF888888;
            int accentBg = ti.accentColor != 0 ? ti.accentColor : 0xFF07C160;
            int keyBgColor = ti.keyBgColor != 0 ? ti.keyBgColor : 0xFFF0F0F0;
            final int accentColor = accentBg;

            final int topExtra = Math.round(-10 * den);

            // ── 左: IME 切换 ──
            if (c.leftBtn) {
                final ImageView ime = new ImageView(ctx);
                ime.setTag("frosted_btn_ime");
                ime.setContentDescription("切换输入法");
                ime.setBackground(null);
                ime.setPadding(0, 0, 0, 0);
                ime.setImageDrawable(SvgIcons.ime(den, keyFg, 30));
                ime.setScaleType(ImageView.ScaleType.FIT_CENTER);
                ime.setClickable(true);
                ime.setOnClickListener(v -> {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                    showImePopup(inputView, ime);
                });
                keyboardView.addView(ime, new ViewGroup.LayoutParams(bs, bs));
                ime.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                    v.getLayoutParams().width = bs;
                    v.getLayoutParams().height = bs;
                    v.setX(sideMargin);
                    v.setY(keyboardView.getHeight() - bs - bottomMargin - topExtra);
                });
            }

            // ── 右: 剪贴板 ──
            if (c.rightBtn) {
                final ImageView clip = new ImageView(ctx);
                clip.setTag("frosted_btn_clipboard");
                clip.setContentDescription("剪贴板历史");
                clip.setBackground(null);
                clip.setPadding(0, 0, 0, 0);
                clip.setImageDrawable(SvgIcons.clipboard(den, keyFg, 30));
                clip.setScaleType(ImageView.ScaleType.FIT_CENTER);
                clip.setClickable(true);
                clip.setOnClickListener(v -> {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                    showClipboardPopup(inputView, clip);
                });
                keyboardView.addView(clip, new ViewGroup.LayoutParams(bs, bs));
                clip.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                    v.getLayoutParams().width = bs;
                    v.getLayoutParams().height = bs;
                    v.setX(keyboardView.getWidth() - bs - sideMargin);
                    v.setY(keyboardView.getHeight() - bs - bottomMargin - topExtra);
                });
            }

            // ── 中: 语音波形线（原版无 RECORD_AUDIO 权限，跳过）──
            String pkg = inputView.getContext().getPackageName();
            boolean isOriginal = "org.fcitx.fcitx5.android".equals(pkg);
            if (c.voice && !isOriginal) {
                final WaveformLineView waveView = new WaveformLineView(ctx);
                waveView.setTag("frosted_btn_voice");
                waveView.setContentDescription("语音输入");
                waveView.setIdleColor(keyFg);
                waveView.setRecordingColor(accentColor);
                waveView.setClickable(true);
                waveView.setFocusable(false);

                final VoiceInputClient[] voiceClientRef = new VoiceInputClient[1];

                waveView.setOnTouchListener((v, ev) -> {
                    try {
                    switch (ev.getAction()) {
                        case MotionEvent.ACTION_DOWN: {
                            // 阻止父 View 抢走触摸事件（键盘滑动手势会截断长按）
                            ViewGroup parent = (ViewGroup) v.getParent();
                            if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                            if (voiceClientRef[0] != null) voiceClientRef[0].cancel();

                            android.inputmethodservice.InputMethodService svc = null;
                            InputConnection ic = null;
                            try {
                                Field sf = findField(inputView.getClass(), "service");
                                if (sf == null) throw new NoSuchFieldException("service");
                                sf.setAccessible(true);
                                svc = (android.inputmethodservice.InputMethodService) sf.get(inputView);
                                ic = svc.getCurrentInputConnection();
                            } catch (Exception ignored) {}
                            if (svc == null) {
                                Log.w(TAG, "voice: service is null");
                                return true;
                            }
                            // Hook 运行在目标输入法 UID；模块自身声明权限并不会授予它录音权限。
                            if (svc.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                                    != PackageManager.PERMISSION_GRANTED) {
                                Toast.makeText(svc, "请先授予当前输入法麦克风权限", Toast.LENGTH_SHORT).show();
                                return true;
                            }
                            final android.inputmethodservice.InputMethodService svcFinal = svc;
                            final InputConnection icFinal = ic;

                            VoiceInputClient client = new VoiceInputClient();
                            voiceClientRef[0] = client;
                            waveView.setTag(R.id.tag_voice_client, client);
                            client.setAmplitudeListener(amp -> waveView.post(() -> {
                                if (voiceClientRef[0] == client) waveView.setAmplitude(amp);
                            }));
                            client.startVoiceInput(svcFinal, icFinal, () -> waveView.post(() -> {
                                // 旧会话异步结束时不能覆盖刚启动的新会话的波形状态。
                                if (voiceClientRef[0] == client) {
                                    waveView.setRecording(false);
                                    waveView.setAmplitude(0);
                                    waveView.setTag(R.id.tag_voice_client, null);
                                    voiceClientRef[0] = null;
                                }
                            }));
                            waveView.setRecording(true);
                            return true;
                        }
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL: {
                            ViewGroup p2 = (ViewGroup) v.getParent();
                            if (p2 != null) p2.requestDisallowInterceptTouchEvent(false);
                            VoiceInputClient client = voiceClientRef[0];
                            if (client != null) {
                                if (ev.getAction() == MotionEvent.ACTION_CANCEL) {
                                    // 系统/父视图中断手势时必须丢弃音频，不能提交识别结果。
                                    voiceClientRef[0] = null;
                                    waveView.setTag(R.id.tag_voice_client, null);
                                    client.cancel();
                                    waveView.setRecording(false);
                                    waveView.setAmplitude(0);
                                } else {
                                    client.stopVoiceInput();
                                    v.performClick();
                                }
                            }
                            return true;
                        }
                    }
                    return true;  // 消费所有触摸事件，防止父 View 抢走
                    } catch (Throwable t) {
                        Log.w(TAG, "voice touch: " + t);
                        return false;
                    }
                });

                keyboardView.addView(waveView, new ViewGroup.LayoutParams(
                        (int) (160 * den + .5f), bs));
                waveView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                    v.setX((keyboardView.getWidth() - v.getWidth()) / 2);
                    v.setY(keyboardView.getHeight() - bs - bottomMargin - topExtra);
                });
                waveView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override public void onViewAttachedToWindow(View v) {}

                    @Override public void onViewDetachedFromWindow(View v) {
                        VoiceInputClient client = voiceClientRef[0];
                        voiceClientRef[0] = null;
                        waveView.setTag(R.id.tag_voice_client, null);
                        if (client != null) client.cancel();
                        waveView.setRecording(false);
                        waveView.setAmplitude(0);
                    }
                });
            }

            buttonsInitialized = true;
            Log.i(TAG, "✅ extra buttons added");
        } catch (Throwable t) {
            Log.w(TAG, "addExtraButtons: " + t);
        }
    }

    /** 停止与语音波形 View 绑定的会话，避免隐藏/重建控件后仍在录音或提交文字。 */
    private static void cancelVoiceSession(View wave) {
        Object tag = wave.getTag(R.id.tag_voice_client);
        wave.setTag(R.id.tag_voice_client, null);
        if (tag instanceof VoiceInputClient) ((VoiceInputClient) tag).cancel();
        if (wave instanceof WaveformLineView) {
            WaveformLineView waveform = (WaveformLineView) wave;
            waveform.setRecording(false);
            waveform.setAmplitude(0);
        }
    }

    private static void updateExistingButtonPositions(View inputView, View ime, View clip, View wave, MainHook.Config c) {
        View anchor = ime != null ? ime : (clip != null ? clip : wave);
        if (anchor == null || !(anchor.getParent() instanceof ViewGroup)) return;
        ViewGroup keyboardView = (ViewGroup) anchor.getParent();
        float den = inputView.getResources().getDisplayMetrics().density;
        int bs = Math.round(30 * den);
        int sideMargin = Math.round(26 * den);
        int bottomMargin = Math.round(c.buttonBottomMargin * den);
        if (ime != null) { ime.setX(sideMargin); ime.setY(keyboardView.getHeight() - bs - bottomMargin + Math.round(10 * den)); }
        if (clip != null) { clip.setX(keyboardView.getWidth() - bs - sideMargin); clip.setY(keyboardView.getHeight() - bs - bottomMargin + Math.round(10 * den)); }
        if (wave != null) { wave.setX((keyboardView.getWidth() - wave.getWidth()) / 2f); wave.setY(keyboardView.getHeight() - bs - bottomMargin + Math.round(10 * den)); }
    }

    private static void updateExistingButtonAppearance(View inputView, View ime,
                                                        View clip, View wave,
                                                        MainHook.ThemeInfo ti) {
        float den = inputView.getResources().getDisplayMetrics().density;
        int keyFg = ti.altKeyTextColor != 0 ? ti.altKeyTextColor : 0xFF888888;
        int accent = ti.accentColor != 0 ? ti.accentColor : 0xFF07C160;
        if (ime instanceof ImageView) {
            ((ImageView) ime).setImageDrawable(SvgIcons.ime(den, keyFg, 30));
        }
        if (clip instanceof ImageView) {
            ((ImageView) clip).setImageDrawable(SvgIcons.clipboard(den, keyFg, 30));
        }
        if (wave instanceof WaveformLineView) {
            WaveformLineView waveform = (WaveformLineView) wave;
            waveform.setIdleColor(keyFg);
            waveform.setRecordingColor(accent);
        }
    }

    // ══════════════════════════════════════════
    //  IME 输入法列表弹窗
    // ══════════════════════════════════════════

    private static void showImePopup(View inputView, View anchor) {
        try {
            Field sf = findField(inputView.getClass(), "service");
            if (sf == null) throw new NoSuchFieldException("service");
            sf.setAccessible(true);
            final Object svc = sf.get(inputView);

            InputMethodManager imm = (InputMethodManager)
                ((android.inputmethodservice.InputMethodService) svc)
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
            java.util.List<InputMethodInfo> imes = imm.getEnabledInputMethodList();

            Context ctx = anchor.getContext();
            float den = ctx.getResources().getDisplayMetrics().density;
            int dp10 = (int)(10*den+.5f), dp12 = (int)(12*den+.5f), dp8 = (int)(8*den+.5f);
            int corner = (int)(12*den+.5f);

            int bgColor, fgColor, borderColor;
            final boolean[] darkRef = {false};
            int keyBgRead = 0xFFF0F0F0;
            try {
                Field tf = findField(inputView.getClass(), "theme");
                if (tf == null) throw new NoSuchFieldException("theme");
                tf.setAccessible(true);
                Object theme = tf.get(inputView);
                darkRef[0] = (Boolean) theme.getClass().getMethod("isDark").invoke(theme);
                keyBgRead = (Integer) theme.getClass().getMethod("getKeyBackgroundColor").invoke(theme);
                fgColor = darkRef[0] ? 0xFFDDDDDD : 0xFF333333;
                borderColor = darkRef[0] ? 0xFF555555 : 0xFFCCCCCC;
            } catch (Exception e) {
                fgColor = 0xFF333333; borderColor = 0xFFCCCCCC;
            }
            bgColor = Color.argb(255,
                    Color.red(keyBgRead), Color.green(keyBgRead), Color.blue(keyBgRead));
            LinearLayout layout = new LinearLayout(ctx);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(dp8, dp8, dp8, dp8);

            PackageManager pm = ctx.getPackageManager();
            final PopupWindow[] imePopupRef = new PopupWindow[1];
            for (int i = 0; i < imes.size(); i++) {
                InputMethodInfo ime = imes.get(i);
                final String imeId = ime.getId();
                String label = ime.loadLabel(pm).toString();

                TextView tv = new TextView(ctx);
                tv.setText(label);
                tv.setTextSize(15);
                tv.setTextColor(fgColor);
                tv.setPadding(dp12, dp10, dp12, dp10);
                tv.setGravity(Gravity.CENTER_VERTICAL);
                tv.setMinHeight((int)(40*den+.5f));
                GradientDrawable itemBg = new GradientDrawable();
                itemBg.setShape(GradientDrawable.RECTANGLE);
                itemBg.setColor(bgColor);
                itemBg.setCornerRadius(dp8);
                tv.setBackground(itemBg);
                tv.setOnClickListener(v -> {
                    try {
                        android.os.IBinder token = getWindowToken(svc);
                        imm.setInputMethod(token, imeId);
                    } catch (Exception e) {
                        Log.w(TAG, "IME switch: " + e);
                    }
                    if (imePopupRef[0] != null) imePopupRef[0].dismiss();
                });
                layout.addView(tv);
                if (i < imes.size() - 1) {
                    View div = new View(ctx);
                    div.setBackgroundColor(borderColor);
                    layout.addView(div, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            (int)(0.5f*den+.5f)));
                }
            }

            int imeW_DP = 180;

            FrameLayout outer = new FrameLayout(ctx);
            GradientDrawable outerBg = new GradientDrawable();
            outerBg.setShape(GradientDrawable.RECTANGLE);
            outerBg.setColor(bgColor);
            outerBg.setCornerRadius(corner);
            outer.setBackground(outerBg);
            outer.setClipToOutline(true);
            outer.setPadding(0, 0, 0, corner);
            outer.addView(layout, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            int popupW = (int)(imeW_DP * den + .5f);

            PopupWindow popup = new PopupWindow(outer, popupW,
                    ViewGroup.LayoutParams.WRAP_CONTENT, true);
            popup.setElevation(dp8);
            popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
            imePopupRef[0] = popup;

            int[] loc = new int[2];
            anchor.getLocationInWindow(loc);
            int popX = Math.max(dp8, loc[0]);
            outer.measure(
                View.MeasureSpec.makeMeasureSpec(popupW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int popupH = outer.getMeasuredHeight();
            int popY = loc[1] - popupH - dp8;
            if (popY < dp8) {
                popY = loc[1] + (int)(40*den+.5f) + dp8;
            }
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, popX, popY);
        } catch (Throwable t) {
            Log.w(TAG, "IME popup: " + t);
        }
    }

    // ══════════════════════════════════════════
    //  剪贴板历史弹窗
    // ══════════════════════════════════════════

    private static void showClipboardPopup(View inputView, View anchor) {
        Context context = anchor.getContext();
        new Thread(() -> {
            java.util.List<String> entries = readClipboardEntries(context);
            if (!anchor.isAttachedToWindow()) return;
            anchor.post(() -> {
                if (anchor.isAttachedToWindow()) {
                    showClipboardPopup(inputView, anchor, entries);
                }
            });
        }, "Fcitx5Enh-Clipboard").start();
    }

    private static java.util.List<String> readClipboardEntries(Context context) {
        java.util.List<String> entries = new java.util.ArrayList<>();
        String dbPath = context.getApplicationInfo().dataDir + "/databases/clbdb";
        java.io.File dbFile = new java.io.File(dbPath);
        if (!dbFile.exists()) return entries;

        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY);
            cursor = db.rawQuery(
                    "SELECT text FROM clipboard WHERE deleted=0 "
                            + "ORDER BY pinned DESC, timestamp DESC LIMIT 10", null);
            while (cursor.moveToNext()) {
                String text = cursor.getString(0);
                if (text != null && !text.isEmpty()) entries.add(text);
            }
        } catch (Exception e) {
            Log.w(TAG, "clipboard db: " + e);
        } finally {
            if (cursor != null) cursor.close();
            if (db != null) db.close();
        }
        return entries;
    }

    private static void showClipboardPopup(View inputView, View anchor,
                                           java.util.List<String> entries) {
        try {
            Context ctx = anchor.getContext();
            float den = ctx.getResources().getDisplayMetrics().density;
            int dp10 = (int)(10*den+.5f), dp12 = (int)(12*den+.5f), dp8 = (int)(8*den+.5f);
            int corner = (int)(12*den+.5f);

            int bgColor, fgColor, borderColor, dimColor;
            final boolean[] darkRef2 = {false};
            int keyBgRead2 = 0xFFF0F0F0;
            try {
                Field tf = findField(inputView.getClass(), "theme");
                if (tf == null) throw new NoSuchFieldException("theme");
                tf.setAccessible(true);
                Object theme = tf.get(inputView);
                darkRef2[0] = (Boolean) theme.getClass().getMethod("isDark").invoke(theme);
                keyBgRead2 = (Integer) theme.getClass().getMethod("getKeyBackgroundColor").invoke(theme);
                fgColor = darkRef2[0] ? 0xFFDDDDDD : 0xFF333333;
                borderColor = darkRef2[0] ? 0xFF555555 : 0xFFCCCCCC;
                dimColor = darkRef2[0] ? 0xFF666666 : 0xFF999999;
            } catch (Exception e) {
                fgColor = 0xFF333333; borderColor = 0xFFCCCCCC; dimColor = 0xFF999999;
            }
            bgColor = Color.argb(255,
                    Color.red(keyBgRead2), Color.green(keyBgRead2), Color.blue(keyBgRead2));

            LinearLayout layout = new LinearLayout(ctx);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(dp8, dp8, dp8, dp8);

            final PopupWindow[] clipPopupRef = new PopupWindow[1];
            boolean hasData = !entries.isEmpty();
            for (int i = 0; i < entries.size() && i < 10; i++) {
                final String text = entries.get(i);
                String display = text.length() > 60 ? text.substring(0, 57) + "…" : text;
                display = display.trim();
                if (display.isEmpty()) display = "(空)";

                TextView tv = new TextView(ctx);
                tv.setText(display);
                tv.setTextSize(13);
                tv.setTextColor(fgColor);
                tv.setPadding(dp12, dp10, dp12, dp10);
                tv.setGravity(Gravity.CENTER_VERTICAL);
                tv.setMinHeight((int)(36*den+.5f));
                tv.setSingleLine(true);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.END);

                GradientDrawable itemBg = new GradientDrawable();
                itemBg.setShape(GradientDrawable.RECTANGLE);
                itemBg.setColor(bgColor);
                itemBg.setCornerRadius(dp8);
                tv.setBackground(itemBg);

                tv.setOnClickListener(v -> {
                    try {
                        Field sf = findField(inputView.getClass(), "service");
                        if (sf == null) throw new NoSuchFieldException("service");
                        sf.setAccessible(true);
                        android.inputmethodservice.InputMethodService svc =
                                (android.inputmethodservice.InputMethodService) sf.get(inputView);
                        InputConnection ic = svc.getCurrentInputConnection();
                        if (ic != null) ic.commitText(text, 1);
                    } catch (Exception ex) {
                        Log.w(TAG, "paste: " + ex);
                    }
                    if (clipPopupRef[0] != null) clipPopupRef[0].dismiss();
                });

                layout.addView(tv);
                if (i < entries.size() - 1 && i < 9) {
                    View div = new View(ctx);
                    div.setBackgroundColor(borderColor);
                    layout.addView(div, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            (int)(0.5f * den + .5f)));
                }
            }

            if (!hasData) {
                TextView empty = new TextView(ctx);
                empty.setText("暂无剪贴板记录");
                empty.setTextSize(14);
                empty.setTextColor(dimColor);
                empty.setPadding(dp12, dp10, dp12, dp10);
                empty.setGravity(Gravity.CENTER);
                empty.setMinHeight((int)(60*den+.5f));
                layout.addView(empty);
            }

            int clipW_DP = 200;
            int kbHeight2 = 0;
            try {
                Method getKv = inputView.getClass().getMethod("getKeyboardView");
                View kv = (View) getKv.invoke(inputView);
                kbHeight2 = kv.getHeight();
            } catch (Exception ignored) {}
            int defaultClipHeight = Math.round(200 * den);
            int minClipHeight = Math.round(120 * den);
            int clipH = kbHeight2 <= 0
                    ? defaultClipHeight
                    : Math.min(kbHeight2 - Math.round(32 * den), defaultClipHeight);
            if (clipH < minClipHeight) clipH = minClipHeight;

            ScrollView sv = new ScrollView(ctx);
            sv.addView(layout, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            FrameLayout outer = new FrameLayout(ctx);
            GradientDrawable outerBg2 = new GradientDrawable();
            outerBg2.setShape(GradientDrawable.RECTANGLE);
            outerBg2.setColor(bgColor);
            outerBg2.setCornerRadius(corner);
            outer.setBackground(outerBg2);
            outer.setClipToOutline(true);
            outer.setPadding(0, 0, 0, corner);
            outer.addView(sv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            int popupW2 = (int)(clipW_DP * den + .5f);
            int popupH2 = clipH;

            final PopupWindow popup = new PopupWindow(outer, popupW2, popupH2, true);
            popup.setElevation(dp8);
            popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
            clipPopupRef[0] = popup;

            int[] loc = new int[2];
            anchor.getLocationInWindow(loc);
            int popX = Math.max(dp8, anchor.getRootView().getWidth() - popupW2 - dp8);
            int popY = loc[1] - popupH2 - dp8;
            if (popY < dp8) {
                popY = loc[1] + (int)(40*den+.5f) + dp8;
            }
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, popX, popY);
        } catch (Throwable t) {
            Log.w(TAG, "Clipboard popup: " + t);
        }
    }

    private static Field findField(Class<?> start, String name) {
        for (Class<?> cls = start; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            try {
                return cls.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    private static android.os.IBinder getWindowToken(Object svc) throws Exception {
        Method gw = svc.getClass().getMethod("getWindow");
        Object softInputWin = gw.invoke(svc);
        if (softInputWin instanceof android.app.Dialog) {
            android.view.Window w = ((android.app.Dialog) softInputWin).getWindow();
            if (w != null) return w.getAttributes().token;
        }
        Method gWin = softInputWin.getClass().getMethod("getWindow");
        android.view.Window win = (android.view.Window) gWin.invoke(softInputWin);
        return win.getAttributes().token;
    }
}
