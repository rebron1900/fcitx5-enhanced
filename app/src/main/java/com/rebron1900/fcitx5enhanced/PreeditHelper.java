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
 * 5. 单手模式下跳过 layout fix，保留 fxliang 自己的 padding 对齐
 */
public class PreeditHelper {
    private static final String TAG = "Fcitx5Enh";

    private static View mRegisteredView;  // 跟踪 listener 注册到哪个 view

    public static void apply(View inputView, MainHook.Config c) {
        try {
            // 查找 preedit root：InputView 的直接子 View，包含 TextView（preedit 文本区域）
            // 先尝试反射（靓企鹅），再遍历子 View（原版 R8 混淆后）
            View preeditRoot = findPreeditRoot(inputView);
            if (preeditRoot == null) {
                Log.w(TAG, "preedit root not found");
                return;
            }

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

    /**
     * 检测是否处于单手模式。
     * 单手模式下 fxliang 通过 setPadding 对齐 preedit，我们不应覆盖。
     */
    private static boolean isOneHandedMode(View inputView) {
        try {
            Field f = inputView.getClass().getDeclaredField("isOneHanded");
            f.setAccessible(true);
            return f.getBoolean(inputView);
        } catch (Exception ignored) {}
        return false;
    }

    private static void fixLayout(View v, float leftMarginPx, float bottomMarginPx) {
        try {
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp == null) return;

            // 二次检查：是否还需要修改
            if (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT) return;

            // 单手模式下跳过 layout fix，保留 fxliang 自己的 padding 对齐
            // 只设置圆角背景，不改约束和 margin
            View inputView = findInputView(v);
            if (inputView != null && isOneHandedMode(inputView)) {
                Log.d(TAG, "preedit skip fixLayout in one-hand mode");
                return;
            }

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

    /** 从 preeditRoot 向上找 InputView */
    private static View findInputView(View v) {
        View parent = v;
        while (parent != null) {
            if (parent.getClass().getName().contains("InputView")) {
                return parent;
            }
            if (parent.getParent() instanceof View) {
                parent = (View) parent.getParent();
            } else {
                break;
            }
        }
        return null;
    }

    /** 查找 preedit root — 先反射，再遍历子 View */
    private static View findPreeditRoot(View inputView) {
        // 方式1: 反射（靓企鹅未混淆）
        try {
            Field pf = inputView.getClass().getDeclaredField("preedit");
            pf.setAccessible(true);
            Object preeditComp = pf.get(inputView);
            Method getUi = preeditComp.getClass().getMethod("getUi");
            Object preeditUi = getUi.invoke(preeditComp);
            Method getRoot = preeditUi.getClass().getMethod("getRoot");
            View root = (View) getRoot.invoke(preeditUi);
            if (root != null) {
                Log.i(TAG, "preedit root via reflection");
                return root;
            }
        } catch (Exception ignored) {}

        // 方式2: 遍历 InputView 子 View，找包含 TextView 的 ViewGroup
        // preedit root 是 InputView 的直接子 View，在 keyboardView 之上
        if (inputView instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) inputView;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                // preedit root 是一个 ViewGroup，内部有 TextView
                if (child instanceof ViewGroup && child != vg.getChildAt(vg.getChildCount() - 1)) {
                    ViewGroup candidate = (ViewGroup) child;
                    if (containsTextView(candidate)) {
                        Log.i(TAG, "preedit root via child scan: index=" + i);
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    /** 递归检查 ViewGroup 是否包含 TextView */
    private static boolean containsTextView(ViewGroup vg) {
        for (int i = 0; i < vg.getChildCount(); i++) {
            View child = vg.getChildAt(i);
            if (child instanceof android.widget.TextView) return true;
            if (child instanceof ViewGroup && containsTextView((ViewGroup) child)) return true;
        }
        return false;
    }
}
