package com.rebron1900.fcitx5enhanced;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** 底部角按钮：IME 切换、剪贴板历史、语音波形。 */
public class ExtraButtonsHelper {
    private static final String TAG = "Fcitx5Enh";

    private static boolean buttonsInitialized;

    public static void add(View inputView, MainHook.Config c) {
        try {
            Method getKv = inputView.getClass().getMethod("getKeyboardView");
            final ViewGroup keyboardView = (ViewGroup) getKv.invoke(inputView);

            if (buttonsInitialized) {
                View ime = keyboardView.findViewWithTag("frosted_btn_ime");
                View clip = keyboardView.findViewWithTag("frosted_btn_clipboard");
                View wave = keyboardView.findViewWithTag("frosted_btn_voice");
                if (ime == null && clip == null && wave == null) {
                    buttonsInitialized = false;
                } else {
                    if (ime != null) ime.setVisibility(c.leftBtn ? View.VISIBLE : View.GONE);
                    if (clip != null) clip.setVisibility(c.rightBtn ? View.VISIBLE : View.GONE);
                    if (wave != null) wave.setVisibility(c.voice ? View.VISIBLE : View.GONE);
                    Log.i(TAG, "toggle btns L=" + c.leftBtn + " R=" + c.rightBtn + " V=" + c.voice);
                    return;
                }
            }

            final Resources res = inputView.getResources();
            Context ctx = inputView.getContext();
            final float den = res.getDisplayMetrics().density;
            final int bs = (int) (30 * den + .5f);
            final int mr = (int) (26 * den + .5f);

            int keyFg = 0xFF888888;
            int accentBg = 0xFF07C160;
            try {
                Field tf = inputView.getClass().getSuperclass().getDeclaredField("theme");
                tf.setAccessible(true);
                Object theme = tf.get(inputView);
                keyFg = (Integer) theme.getClass().getMethod("getAltKeyTextColor").invoke(theme);
                accentBg = (Integer) theme.getClass().getMethod("getAccentKeyBackgroundColor").invoke(theme);
            } catch (Exception ignored) {}
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
                    v.setX(mr);
                    v.setY(keyboardView.getHeight() - bs - mr - topExtra);
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
                    v.setX(keyboardView.getWidth() - bs - mr);
                    v.setY(keyboardView.getHeight() - bs - mr - topExtra);
                });
            }

            // ── 中: 语音波形线 ──
            if (c.voice) {
                final WaveformLineView waveView = new WaveformLineView(ctx);
                waveView.setTag("frosted_btn_voice");
                waveView.setContentDescription("语音输入");
                waveView.setIdleColor(keyFg);
                waveView.setRecordingColor(accentColor);
                waveView.setClickable(true);
                waveView.setFocusable(false);

                final VoiceInputClient[] voiceClientRef = new VoiceInputClient[1];

                waveView.setOnTouchListener((v, ev) -> {
                    switch (ev.getAction()) {
                        case MotionEvent.ACTION_DOWN: {
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                            if (voiceClientRef[0] != null) voiceClientRef[0].cancel();

                            android.inputmethodservice.InputMethodService svc = null;
                            InputConnection ic = null;
                            try {
                                Field sf = inputView.getClass().getSuperclass()
                                        .getDeclaredField("service");
                                sf.setAccessible(true);
                                svc = (android.inputmethodservice.InputMethodService) sf.get(inputView);
                                ic = svc.getCurrentInputConnection();
                            } catch (Exception ignored) {}
                            final android.inputmethodservice.InputMethodService svcFinal = svc;
                            final InputConnection icFinal = ic;

                            VoiceInputClient client = new VoiceInputClient();
                            voiceClientRef[0] = client;
                            client.setAmplitudeListener(amp ->
                                    waveView.post(() -> waveView.setAmplitude(amp)));
                            client.startVoiceInput(svcFinal, icFinal, () ->
                                    waveView.post(() -> {
                                        waveView.setRecording(false);
                                        waveView.setAmplitude(0);
                                    }));
                            waveView.setRecording(true);
                            return true;
                        }
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL: {
                            if (voiceClientRef[0] != null) voiceClientRef[0].stopVoiceInput();
                            return true;
                        }
                    }
                    return false;
                });

                keyboardView.addView(waveView, new ViewGroup.LayoutParams(
                        (int) (160 * den + .5f), bs));
                waveView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                    v.setX((keyboardView.getWidth() - v.getWidth()) / 2);
                    v.setY(keyboardView.getHeight() - bs - mr - topExtra);
                });
            }

            buttonsInitialized = true;
            Log.i(TAG, "✅ extra buttons added");
        } catch (Throwable t) {
            Log.w(TAG, "addExtraButtons: " + t);
        }
    }

    // ══════════════════════════════════════════
    //  IME 输入法列表弹窗
    // ══════════════════════════════════════════

    private static void showImePopup(View inputView, View anchor) {
        try {
            Field sf = inputView.getClass().getSuperclass().getDeclaredField("service");
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
                Field tf = inputView.getClass().getSuperclass().getDeclaredField("theme");
                tf.setAccessible(true);
                Object theme = tf.get(inputView);
                darkRef[0] = (Boolean) theme.getClass().getMethod("isDark").invoke(theme);
                keyBgRead = (Integer) theme.getClass().getMethod("getKeyBackgroundColor").invoke(theme);
                fgColor = darkRef[0] ? 0xFFDDDDDD : 0xFF333333;
                borderColor = darkRef[0] ? 0xFF555555 : 0xFFCCCCCC;
            } catch (Exception e) {
                fgColor = 0xFF333333; borderColor = 0xFFCCCCCC;
            }
            bgColor = Color.argb(160,
                    Color.red(keyBgRead), Color.green(keyBgRead), Color.blue(keyBgRead));
            final boolean fDark = darkRef[0];

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
                tv.setOnTouchListener((v, ev) -> {
                    if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                        GradientDrawable h = new GradientDrawable();
                        h.setShape(GradientDrawable.RECTANGLE);
                        h.setColor(fDark ? 0xFF3A3A3A : 0xFFE8E8E8);
                        h.setCornerRadius(dp8);
                        v.setBackground(h);
                    } else if (ev.getAction() == MotionEvent.ACTION_UP
                            || ev.getAction() == MotionEvent.ACTION_CANCEL) {
                        v.setBackground(itemBg);
                    }
                    return false;
                });
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
            outerBg.setColor(bgColor); outerBg.setCornerRadius(corner);
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
        try {
            Context ctx = anchor.getContext();
            float den = ctx.getResources().getDisplayMetrics().density;
            int dp10 = (int)(10*den+.5f), dp12 = (int)(12*den+.5f), dp8 = (int)(8*den+.5f);
            int corner = (int)(12*den+.5f);

            int bgColor, fgColor, borderColor, dimColor;
            final boolean[] darkRef2 = {false};
            int keyBgRead2 = 0xFFF0F0F0;
            try {
                Field tf = inputView.getClass().getSuperclass().getDeclaredField("theme");
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
            bgColor = Color.argb(160,
                    Color.red(keyBgRead2), Color.green(keyBgRead2), Color.blue(keyBgRead2));

            LinearLayout layout = new LinearLayout(ctx);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(dp8, dp8, dp8, dp8);

            String dbPath = ctx.getApplicationInfo().dataDir + "/databases/clbdb";
            java.io.File dbFile = new java.io.File(dbPath);
            final PopupWindow[] clipPopupRef = new PopupWindow[1];
            boolean hasData = false;

            if (dbFile.exists()) {
                SQLiteDatabase db = null;
                Cursor c = null;
                try {
                    db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY);
                    c = db.rawQuery(
                        "SELECT text, pinned, timestamp FROM clipboard WHERE deleted=0 ORDER BY pinned DESC, timestamp DESC LIMIT 10",
                        null);

                    if (c != null && c.moveToFirst()) {
                        hasData = true;
                        do {
                            final String text = c.getString(0);

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
                                    Field sf = inputView.getClass().getSuperclass()
                                            .getDeclaredField("service");
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

                            if (c.getPosition() + 1 < c.getCount()
                                    && layout.getChildCount() < 10) {
                                View div = new View(ctx);
                                div.setBackgroundColor(borderColor);
                                layout.addView(div, new LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        (int)(0.5f*den+.5f)));
                            }
                        } while (c.moveToNext() && layout.getChildCount() < 21);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "clipboard db: " + e);
                } finally {
                    if (c != null) c.close();
                    if (db != null) db.close();
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
            int clipH = Math.min(kbHeight2 <= 0 ? 200 : kbHeight2 - 32, 200);
            if (clipH < 120) clipH = 120;

            ScrollView sv = new ScrollView(ctx);
            sv.addView(layout, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            FrameLayout outer = new FrameLayout(ctx);
            GradientDrawable outerBg = new GradientDrawable();
            outerBg.setShape(GradientDrawable.RECTANGLE);
            outerBg.setColor(bgColor); outerBg.setCornerRadius(corner);
            outer.setBackground(outerBg);
            outer.setClipToOutline(true);
            outer.setPadding(0, 0, 0, corner);
            outer.addView(sv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            int popupW2 = (int)(clipW_DP * den + .5f);
            int popupH2 = (int)(clipH * den + .5f);

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
