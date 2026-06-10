package com.rebron1900.fcitx5enhanced;

import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** 按键 + 弹窗 半透明磨砂效果。 */
public class KeyEffectsHelper {
    private static final String TAG = "Fcitx5Enh";

    private static ViewTreeObserver.OnGlobalLayoutListener mKeyLayoutListener;
    private static ViewTreeObserver.OnGlobalLayoutListener mPopupLayoutListener;
    private static int mPopupAlpha = 120;

    public static void apply(View inputView, MainHook.Config c) {
        mPopupAlpha = Math.min(c.alpha + 80, 180);

        // ... rest follows
        try {
            Field wf = inputView.getClass().getDeclaredField("windowManager");
            wf.setAccessible(true);
            Object wm = wf.get(inputView);
            Method gv = wm.getClass().getMethod("getView");
            final ViewGroup wmView = (ViewGroup) gv.invoke(wm);
            if (wmView == null) return;

            int keyAlpha = Math.min(c.alpha + 80, 200);

            makeKeysTranslucent(wmView, keyAlpha);

            if (mKeyLayoutListener != null) {
                wmView.getViewTreeObserver().removeOnGlobalLayoutListener(mKeyLayoutListener);
            }
            mKeyLayoutListener = () -> makeKeysTranslucent(wmView, keyAlpha);
            wmView.getViewTreeObserver().addOnGlobalLayoutListener(mKeyLayoutListener);

            // Popup 悬浮窗背景半透明
            try {
                Field pf = inputView.getClass().getDeclaredField("popup");
                pf.setAccessible(true);
                final Object popupComponent = pf.get(inputView);
                Method getRoot = popupComponent.getClass().getMethod("getRoot");
                final ViewGroup popupRoot = (ViewGroup) getRoot.invoke(popupComponent);
                if (popupRoot != null) {
                    if (mPopupLayoutListener != null) {
                        popupRoot.getViewTreeObserver().removeOnGlobalLayoutListener(mPopupLayoutListener);
                    }
                    mPopupLayoutListener = () -> makePopupTranslucent(popupRoot);
                    popupRoot.getViewTreeObserver().addOnGlobalLayoutListener(mPopupLayoutListener);
                    makePopupTranslucent(popupRoot);
                }
            } catch (Exception e) {
                Log.w(TAG, "popup effects: " + e.getMessage());
            }

            Log.i(TAG, "key effects: alpha=" + keyAlpha);
        } catch (Throwable t) {
            Log.w(TAG, "applyKeyEffects: " + t.getMessage());
        }
    }

    private static void makeKeysTranslucent(ViewGroup root, int alpha) {
        if (alpha > 250) return;
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            try {
                View appView = findAppearanceView(child);
                if (appView != null) {
                    Drawable bg = appView.getBackground();
                    if (bg != null) {
                        if (bg instanceof android.graphics.drawable.LayerDrawable) {
                            android.graphics.drawable.LayerDrawable ld =
                                (android.graphics.drawable.LayerDrawable) bg;
                            for (int j = 0; j < ld.getNumberOfLayers(); j++) {
                                Drawable layer = ld.getDrawable(j);
                                if (layer != null) layer.setAlpha(alpha);
                            }
                        } else {
                            bg.setAlpha(alpha);
                        }
                    }
                    tryAgainDeeper(appView, alpha);
                }
            } catch (Exception ignored) {}
            if (child instanceof ViewGroup) {
                makeKeysTranslucent((ViewGroup) child, alpha);
            }
        }
    }

    private static View findAppearanceView(View v) {
        Class<?> c = v.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField("appearanceView");
                f.setAccessible(true);
                return (View) f.get(v);
            } catch (Exception ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }

    private static void tryAgainDeeper(View v, int alpha) {
        Drawable bg = v.getBackground();
        if (bg == null) return;
        if (bg instanceof android.graphics.drawable.InsetDrawable) {
            Drawable inner = ((android.graphics.drawable.InsetDrawable) bg).getDrawable();
            if (inner != null) inner.setAlpha(alpha);
        } else if (bg instanceof android.graphics.drawable.LayerDrawable) {
            android.graphics.drawable.LayerDrawable ld =
                (android.graphics.drawable.LayerDrawable) bg;
            for (int j = 0; j < ld.getNumberOfLayers(); j++) {
                Drawable layer = ld.getDrawable(j);
                if (layer != null) layer.setAlpha(alpha);
            }
        } else {
            bg.setAlpha(alpha);
        }
    }

    private static void makePopupTranslucent(ViewGroup root) {
        int pa = mPopupAlpha;
        if (pa > 240) return;
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            Drawable bg = child.getBackground();
            if (bg != null) bg.setAlpha(pa);
            if (child instanceof ViewGroup) {
                makePopupTranslucent((ViewGroup) child);
            }
        }
    }
}
