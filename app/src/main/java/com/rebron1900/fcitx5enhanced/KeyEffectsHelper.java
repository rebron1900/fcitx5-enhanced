package com.rebron1900.fcitx5enhanced;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

/** 按键半透明磨砂效果 + 描边。 */
public class KeyEffectsHelper {
    private static final String TAG = "Fcitx5Enh";

    private static ViewTreeObserver.OnGlobalLayoutListener mKeyLayoutListener;
    private static ViewGroup mAttachedView;

    /** 保存每个按键的原始 foreground（press highlight），防止重绘时嵌套叠加。 */
    private static final WeakHashMap<View, Drawable> sOriginalForegrounds = new WeakHashMap<>();

    /** 标记已设置描边的 view，避免重复设置 */
    private static final WeakHashMap<View, Boolean> sBorderedViews = new WeakHashMap<>();

    /** 缓存 appearanceView 反射结果（View→View），miss 不缓存（反射失败本身就很快） */
    private static final WeakHashMap<View, View> sAppearanceCache = new WeakHashMap<>();

    /** 缓存资源 ID，避免每次 getIdentifier 查找 */
    private static int sIdReturn = -1;
    private static int sIdSwitch = -1;
    private static boolean sIdResolved = false;

    /** 标记 listener 中是否正在执行，防止重入 */
    private static boolean sApplying = false;

    /** 每次 apply 时从 SP 读取的主题参数（不缓存，保证实时性） */
    private static int sKeyRadius = 4;
    private static boolean sSpecialKeyOval = false;

    public static void apply(View inputView, MainHook.Config c, boolean isDark) {
        try {
            Field wf = inputView.getClass().getDeclaredField("windowManager");
            wf.setAccessible(true);
            Object wm = wf.get(inputView);
            Method gv = wm.getClass().getMethod("getView");
            final ViewGroup wmView = (ViewGroup) gv.invoke(wm);
            if (wmView == null) return;

            int keyAlpha = c.keyAlpha;  // 使用独立的按键透明度配置

            // 移除旧 listener
            removeOldListener();

            // 清除旧缓存（主题切换后 view 全换了，旧标记无效）
            sBorderedViews.clear();
            sOriginalForegrounds.clear();
            sAppearanceCache.clear();

            // 初次应用 — 只做一次，不靠 listener
            makeKeysTranslucent(wmView, keyAlpha);
            if (c.keyBorder) {
                addKeyBorders(wmView, c, isDark);
            } else {
                removeKeyBorders(wmView);
            }

            // listener 处理新增按键（如切换中英文、切换键盘布局时新出现的按键）
            mKeyLayoutListener = () -> {
                if (sApplying) return;  // 防重入
                sApplying = true;
                try {
                    // 重新读取配置（lambda 不能捕获旧 cfg 引用）
                    MainHook.Config fresh = MainHook.readConfigSync(inputView);
                    // 应用透明度（切换中英文后新建的按键需要重新设置）
                    makeKeysTranslucent(wmView, fresh.keyAlpha);
                    if (fresh.keyBorder) {
                        addKeyBorders(wmView, fresh, isDark);
                    }
                } finally {
                    sApplying = false;
                }
            };
            mAttachedView = wmView;
            wmView.getViewTreeObserver().addOnGlobalLayoutListener(mKeyLayoutListener);

            // 解析资源 ID（只做一次）
            if (!sIdResolved) {
                sIdResolved = true;
                String pkg = inputView.getContext().getPackageName();
                android.content.res.Resources res = inputView.getContext().getResources();
                sIdReturn = res.getIdentifier("button_return", "id", pkg);
                sIdSwitch = res.getIdentifier("button_layout_switch", "id", pkg);
            }

            // 每次 apply 都读 SP（保证药丸设置实时生效）
            // 动态获取包名，兼容原版和靓企鹅
            try {
                String pkg = inputView.getContext().getPackageName();
                SharedPreferences sp = inputView.getContext().getSharedPreferences(
                        pkg + "_preferences", Context.MODE_PRIVATE);
                sKeyRadius = sp.getInt("key_radius", 4);
                sSpecialKeyOval = sp.getBoolean("special_key_oval_shape", false);
            } catch (Exception ignored) {}

            Log.i(TAG, "key effects: alpha=" + keyAlpha);
        } catch (Throwable t) {
            Log.w(TAG, "applyKeyEffects: " + t.getMessage());
        }
    }

    private static void removeOldListener() {
        if (mKeyLayoutListener != null && mAttachedView != null) {
            try {
                ViewTreeObserver vto = mAttachedView.getViewTreeObserver();
                if (vto.isAlive()) {
                    vto.removeOnGlobalLayoutListener(mKeyLayoutListener);
                }
            } catch (Exception ignored) {}
            mKeyLayoutListener = null;
            mAttachedView = null;
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

    /** 带缓存的 appearanceView 查找 */
    private static View findAppearanceView(View v) {
        View cached = sAppearanceCache.get(v);
        if (cached != null) return cached;

        Class<?> c = v.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField("appearanceView");
                f.setAccessible(true);
                View result = (View) f.get(v);
                if (result != null) {
                    sAppearanceCache.put(v, result);
                }
                return result;
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
                    if (sBorderedViews.containsKey(appView)) {
                        continue;  // 已描边，跳过（不再检查 LayerDrawable）
                    }
                    applyKeyGlassBorder(appView, c, isDark);
                }
            } catch (Exception ignored) {}
            if (child instanceof ViewGroup) {
                addKeyBorders((ViewGroup) child, c, isDark);
            }
        }
    }

    /** 移除所有按键描边（keyBorder 关闭时调用） */
    private static void removeKeyBorders(ViewGroup root) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            try {
                View appView = findAppearanceView(child);
                if (appView != null) {
                    Drawable fg = appView.getForeground();
                    if (fg instanceof android.graphics.drawable.LayerDrawable) {
                        android.graphics.drawable.LayerDrawable ld = (android.graphics.drawable.LayerDrawable) fg;
                        if (ld.getNumberOfLayers() == 2 && ld.getDrawable(0) instanceof GlassBorderDrawable) {
                            appView.setForeground(ld.getDrawable(1));
                        }
                    } else if (fg instanceof GlassBorderDrawable) {
                        appView.setForeground(null);
                    }
                }
            } catch (Exception ignored) {}
            if (child instanceof ViewGroup) {
                removeKeyBorders((ViewGroup) child);
            }
        }
    }

    /** 从 actual drawable 链解析：边距、圆角、形状 */
    private static class KeyBgInfo {
        float radius;
        int hInset;
        int vInset;
        boolean isOval;
        boolean isPill;
        KeyBgInfo(float r, int h, int v, boolean oval, boolean pill) {
            radius = r; hInset = h; vInset = v; isOval = oval; isPill = pill;
        }
    }

    /** 遍历 drawable 链提取实际圆角和形状 */
    private static KeyBgInfo parseKeyBg(Drawable bg, Context ctx, float den) {
        int hInset = 0, vInset = 0;
        float radius = 0f;
        boolean isOval = false;
        boolean isPill = false;

        Drawable d = bg;
        while (d != null) {
            if (d instanceof android.graphics.drawable.InsetDrawable) {
                android.graphics.drawable.InsetDrawable id = (android.graphics.drawable.InsetDrawable) d;
                android.graphics.Rect pad = new android.graphics.Rect();
                id.getPadding(pad);
                hInset = Math.max(hInset, pad.left);
                vInset = Math.max(vInset, pad.top);
                d = id.getDrawable();
            } else if (d instanceof android.graphics.drawable.LayerDrawable) {
                android.graphics.drawable.LayerDrawable ld = (android.graphics.drawable.LayerDrawable) d;
                int last = ld.getNumberOfLayers() - 1;
                int inL = ld.getLayerInsetLeft(last);
                int inT = ld.getLayerInsetTop(last);
                int inR = ld.getLayerInsetRight(last);
                int inB = ld.getLayerInsetBottom(last);
                if (inL == inR && inT == inB) {
                    hInset = Math.max(hInset, inL);
                    vInset = Math.max(vInset, inT);
                }
                d = ld.getDrawable(last);
            } else if (d instanceof android.graphics.drawable.GradientDrawable) {
                android.graphics.drawable.GradientDrawable gd = (android.graphics.drawable.GradientDrawable) d;
                try {
                    Field shapeField = android.graphics.drawable.GradientDrawable.class
                            .getDeclaredField("mShape");
                    shapeField.setAccessible(true);
                    int shape = shapeField.getInt(gd);
                    isOval = (shape == android.graphics.drawable.GradientDrawable.OVAL);
                } catch (Exception ignored) {}
                if (Build.VERSION.SDK_INT >= 29) {
                    float[] radii = gd.getCornerRadii();
                    if (radii != null && radii.length > 0 && radii[0] > 0) {
                        radius = radii[0];
                    }
                }
                if (radius == 0) {
                    try {
                        Field rField = android.graphics.drawable.GradientDrawable.class
                                .getDeclaredField("mRadius");
                        rField.setAccessible(true);
                        float fr = rField.getFloat(gd);
                        if (fr > 0) radius = fr;
                    } catch (Exception ignored) {}
                }
                if (radius >= 10000f) {
                    isPill = true;
                    isOval = false;
                    radius = 0f;
                }
                break;
            } else {
                break;
            }
        }
        // 兜底：用缓存的 key_radius（仅当 drawable 链解析不到时）
        if (radius == 0 && !isOval) {
            int kr = sKeyRadius;
            if (kr < 0) kr = 0;
            if (kr > 48) kr = 48;
            radius = Math.max(kr * den, 2f * den);
        }

        return new KeyBgInfo(radius, hInset, vInset, isOval, isPill);
    }

    /** 给单个按键套上描边 foreground */
    private static void applyKeyGlassBorder(View keyView, MainHook.Config c, boolean isDark) {
        try {
            float den = keyView.getResources().getDisplayMetrics().density;

            int borderTop, borderBottom;
            float borderWidthPx;
            if (isDark) {
                borderTop = 0x33FFFFFF;
                borderBottom = 0x33FFFFFF;
                borderWidthPx = 0.8f * den;
            } else {
                borderTop = 0x88FFFFFF;
                borderBottom = 0x88FFFFFF;
                borderWidthPx = 0.8f * den;
            }

            int hMargin = 0, vMargin = 0;
            View outer = null;
            try {
                outer = (View) keyView.getParent();
                if (outer != null) {
                    boolean foundH = false, foundV = false;
                    Class<?> cls = outer.getClass();
                    while (cls != null && cls != Object.class && (!foundH || !foundV)) {
                        if (!foundH) {
                            try {
                                Field hm = cls.getDeclaredField("hMargin");
                                hm.setAccessible(true);
                                hMargin = hm.getInt(outer);
                                foundH = true;
                            } catch (NoSuchFieldException ignored) {}
                        }
                        if (!foundV) {
                            try {
                                Field vm = cls.getDeclaredField("vMargin");
                                vm.setAccessible(true);
                                vMargin = vm.getInt(outer);
                                foundV = true;
                            } catch (NoSuchFieldException ignored) {}
                        }
                        if (!foundH || !foundV) cls = cls.getSuperclass();
                        else break;
                    }
                }
            } catch (Exception ignored) {}

            KeyBgInfo info = parseKeyBg(keyView.getBackground(), keyView.getContext(), den);

            // 药丸检测：用缓存的资源 ID 和 SP 值
            if (!info.isPill && !info.isOval && outer != null && sIdResolved && sSpecialKeyOval) {
                try {
                    Object tag = outer.getTag();
                    if (tag instanceof Integer) {
                        int vid = (Integer) tag;
                        if (vid == sIdReturn || vid == sIdSwitch) {
                            info.isPill = true;
                            info.radius = Math.min(keyView.getWidth(), keyView.getHeight()) * 0.5f;
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (info.isPill && info.radius <= 0) {
                info.radius = Math.min(keyView.getWidth(), keyView.getHeight()) * 0.5f;
            }

            int useH = Math.max(hMargin, info.hInset);
            int useV = Math.max(vMargin, info.vInset);

            GlassBorderDrawable gb;
            if (info.isOval) {
                gb = new GlassBorderDrawable(
                        0, borderTop, borderBottom, info.radius, borderWidthPx * 1.8f,
                        GlassBorderDrawable.MODE_DIAGONAL, true, useH, useV);
            } else if (info.isPill) {
                gb = new GlassBorderDrawable(
                        0, borderTop, borderBottom, info.radius, borderWidthPx * 1.8f,
                        GlassBorderDrawable.MODE_DIAGONAL, false, useH, useV);
            } else {
                gb = new GlassBorderDrawable(
                        0, borderTop, borderBottom, info.radius, borderWidthPx,
                        GlassBorderDrawable.MODE_DIAGONAL, false, useH, useV);
            }

            Drawable originalFg = sOriginalForegrounds.get(keyView);
            if (originalFg == null) {
                Drawable currentFg = keyView.getForeground();
                // 如果当前 foreground 是上次 apply 的 LayerDrawable(GlassBorder + 原始)，解包
                if (currentFg instanceof android.graphics.drawable.LayerDrawable) {
                    android.graphics.drawable.LayerDrawable ld = (android.graphics.drawable.LayerDrawable) currentFg;
                    if (ld.getNumberOfLayers() == 2 && ld.getDrawable(0) instanceof GlassBorderDrawable) {
                        originalFg = ld.getDrawable(1);
                    } else {
                        originalFg = currentFg;
                    }
                } else {
                    originalFg = currentFg;
                }
                sOriginalForegrounds.put(keyView, originalFg);
            }

            if (originalFg != null) {
                android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                        new Drawable[]{gb, originalFg});
                keyView.setForeground(ld);
            } else {
                keyView.setForeground(gb);
            }

            sBorderedViews.put(keyView, Boolean.TRUE);
        } catch (Throwable t) {
            Log.w(TAG, "key glass border: " + t.getMessage());
        }
    }
}
