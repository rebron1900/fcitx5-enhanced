package com.rebron1900.fcitx5enhanced;

import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Preedit 美化：圆角 + 靠左 + 内容宽度。
 *
 * 策略：
 * 1. 圆角背景 — 立即设置
 * 2. 宽度修正 — 用 post {} 延迟修改 WRAP_CONTENT，只在宽度确实不是 WRAP_CONTENT 时触发
 * 3. 位置修正 — 靠左约束 + leftMargin + bottomMargin
 * 4. listener 只在 width 确实需要修改时才 post requestLayout
 */
public class PreeditHelper {
    private static final String TAG = "Fcitx5Enh";

    private static View mRegisteredView;  // 跟踪 listener 注册到哪个 view

    public static void apply(View inputView, MainHook.Config c) {
        try {
            Field pf = inputView.getClass().getDeclaredField("preedit");
            pf.setAccessible(true);
            Object preeditComp = pf.get(inputView);
            Method getUi = preeditComp.getClass().getMethod("getUi");
            Object preeditUi = getUi.invoke(preeditComp);
            Method getRoot = preeditUi.getClass().getMethod("getRoot");
            final View preeditRoot = (View) getRoot.invoke(preeditUi);
            if (preeditRoot == null) return;

            // 读取主题色
            int bgColor = 0xFF303030;
            try {
                Field tf = inputView.getClass().getSuperclass().getDeclaredField("theme");
                tf.setAccessible(true);
                Object theme = tf.get(inputView);
                bgColor = (Integer) theme.getClass().getMethod("getBarColor").invoke(theme);
            } catch (Exception ignored) {}

            float den = inputView.getResources().getDisplayMetrics().density;
            float r = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, c.corner,
                    inputView.getResources().getDisplayMetrics());
            final float leftMarginPx = 12 * den;
            final float bottomMarginPx = 3 * den;

            // 子 View 清 background → 透出底层背景
            if (preeditRoot instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) preeditRoot;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View child = vg.getChildAt(i);
                    child.setBackground(null);
                }
            }

            // 圆角背景
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.RECTANGLE);
            gd.setColor(bgColor);
            gd.setCornerRadius(r);
            preeditRoot.setBackground(gd);

            // 注册一次 listener：检测宽度/约束变化时用 post {} 修正
            if (mRegisteredView != preeditRoot) {
                mRegisteredView = preeditRoot;
                preeditRoot.addOnLayoutChangeListener((v, l, t, r1, b, ol, ot, or, ob) -> {
                    ViewGroup.LayoutParams lp = v.getLayoutParams();
                    if (lp == null) return;

                    boolean needFix = (lp.width != ViewGroup.LayoutParams.WRAP_CONTENT);

                    if (needFix) {
                        v.post(() -> fixLayout(v, leftMarginPx, bottomMarginPx));
                    }
                });
                Log.i(TAG, "preedit listener registered");
            }

            // 首次修正
            preeditRoot.post(() -> fixLayout(preeditRoot, leftMarginPx, bottomMarginPx));

            Log.i(TAG, "preedit round r=" + c.corner + "dp");
        } catch (Throwable t) {
            Log.w(TAG, "preedit effects: " + t);
        }
    }

    private static void fixLayout(View v, float leftMarginPx, float bottomMarginPx) {
        try {
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp == null) return;

            // 二次检查：是否还需要修改
            if (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT) return;

            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;

            // 清除 end 约束，强制靠左
            try {
                Class<?> cls = lp.getClass();
                try { cls.getField("endToEnd").setInt(lp, -1); } catch (Exception ignored) {}
                try { cls.getField("rightToRight").setInt(lp, -1); } catch (Exception ignored) {}
                try { cls.getField("startToStart").setInt(lp, 0); } catch (Exception ignored) {}
                try { cls.getField("horizontalBias").setFloat(lp, 0f); } catch (Exception ignored) {}
            } catch (Exception ignored) {}

            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                mlp.leftMargin = (int) leftMarginPx;
                mlp.rightMargin = 0;
                mlp.bottomMargin = (int) bottomMarginPx;
            }

            v.requestLayout();
            Log.d(TAG, "preedit fixed: WRAP_CONTENT + left");
        } catch (Exception e) {
            Log.w(TAG, "preedit fixLayout: " + e);
        }
    }
}
