package com.rebron1900.fcitx5enhanced;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

/** 按键 + 弹窗 半透明磨砂效果。 */
public class KeyEffectsHelper {
    private static final String TAG = "Fcitx5Enh";

    private static ViewTreeObserver.OnGlobalLayoutListener mKeyLayoutListener;
    private static ViewTreeObserver.OnGlobalLayoutListener mPopupLayoutListener;
    private static int mPopupAlpha = 120;

    /** 保存每个按键的原始 foreground（press highlight），防止重绘时嵌套叠加。 */
    private static final WeakHashMap<View, Drawable> sOriginalForegrounds = new WeakHashMap<>();

    public static void apply(View inputView, MainHook.Config c, boolean isDark) {
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
            mKeyLayoutListener = () -> {
                makeKeysTranslucent(wmView, keyAlpha);
                if (c.keyBorder) addKeyBorders(wmView, c, isDark);
            };
            wmView.getViewTreeObserver().addOnGlobalLayoutListener(mKeyLayoutListener);

            // 初始应用按键描边
            if (c.keyBorder) addKeyBorders(wmView, c, isDark);

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

    // ══════════════════════════════════════════
    //  按键玻璃描边 — 每个按键顶部+转角
    // ══════════════════════════════════════════

    /** 遍历键盘视图树，给每个按键的 appearanceView 加玻璃描边。 */
    private static void addKeyBorders(ViewGroup root, MainHook.Config c, boolean isDark) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            try {
                View appView = findAppearanceView(child);
                if (appView != null) {
                    applyKeyGlassBorder(appView, c, isDark);
                }
            } catch (Exception ignored) {}
            if (child instanceof ViewGroup) {
                addKeyBorders((ViewGroup) child, c, isDark);
            }
        }
    }

    /** 从 appearanceView 的背景读取 layer insets，圆角从 fcitx5 主题配置读。 */
    private static class KeyBgInfo {
        float radius;
        int hInset;
        int vInset;
        KeyBgInfo(float r, int h, int v) { radius = r; hInset = h; vInset = v; }
    }

    /** 从 background LayerDrawable 解析 insets，从 SharedPreferences 读 key_radius。 */
    private static KeyBgInfo parseKeyBg(Drawable bg, Context ctx, float den) {
        int hInset = 0, vInset = 0;
        float r;

        // 圆角从 fcitx5 主题配置读
        int kr = 4;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(
                    "org.fcitx.fcitx5.android.fx_preferences", Context.MODE_PRIVATE);
            kr = sp.getInt("key_radius", 4);
            if (kr < 0) kr = 0;
            if (kr > 48) kr = 48;
        } catch (Exception ignored) {}
        r = Math.max(kr * den, 2f * den);

        // insets 从 LayerDrawable 的 layer 实际渲染 inset 读
        if (bg instanceof android.graphics.drawable.LayerDrawable) {
            android.graphics.drawable.LayerDrawable ld = (android.graphics.drawable.LayerDrawable) bg;
            for (int i = 0; i < ld.getNumberOfLayers(); i++) {
                int inL = ld.getLayerInsetLeft(i);
                int inT = ld.getLayerInsetTop(i);
                int inR = ld.getLayerInsetRight(i);
                int inB = ld.getLayerInsetBottom(i);
                if (inL == inR && inT == inB) {
                    hInset = Math.max(hInset, inL);
                    vInset = Math.max(vInset, inT);
                }
            }
        }
        return new KeyBgInfo(r, hInset, vInset);
    }

    /** 给单个按键套上 InsetDrawable + GlassBorderDrawable 作为 foreground，保留原有 press highlight。
     *  用 WeakHashMap 缓存原始 foreground，避免每次重绘层叠嵌套（"循环描边" bug）。 */
    private static void applyKeyGlassBorder(View keyView, MainHook.Config c, boolean isDark) {
        try {
            float den = keyView.getResources().getDisplayMetrics().density;

            int borderTop, borderBottom;
            float borderWidthPx;
            if (isDark) {
                borderTop = 0x22FFFFFF;      // 暗色：极淡白
                borderBottom = 0x22FFFFFF;
                borderWidthPx = 0.8f * den;   // 0.8dp
            } else {
                borderTop = 0x66FFFFFF;       // 亮色：半透白
                borderBottom = 0x66FFFFFF;
                borderWidthPx = 0.8f * den;
            }

            // 从 background LayerDrawable 解析 insets，圆角从主题配置读
            KeyBgInfo info = parseKeyBg(keyView.getBackground(), keyView.getContext(), den);

            GlassBorderDrawable gb = new GlassBorderDrawable(
                    0, borderTop, borderBottom, info.radius, borderWidthPx,
                    GlassBorderDrawable.MODE_DIAGONAL);

            Drawable glassFg;
            if (info.hInset > 0 || info.vInset > 0) {
                glassFg = new InsetDrawable(gb, info.hInset, info.vInset, info.hInset, info.vInset);
            } else {
                glassFg = gb;
            }

            // 用缓存的原始 foreground（仅 press highlight），避免嵌套叠加
            Drawable originalFg = sOriginalForegrounds.get(keyView);
            if (originalFg == null) {
                originalFg = keyView.getForeground();
                sOriginalForegrounds.put(keyView, originalFg);
            }

            if (originalFg != null) {
                android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                        new Drawable[]{glassFg, originalFg});
                keyView.setForeground(ld);
            } else {
                keyView.setForeground(glassFg);
            }
        } catch (Throwable t) {
            Log.w(TAG, "key glass border: " + t.getMessage());
        }
    }
}
