package com.rebron1900.fcitx5enhanced;

import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Preedit（拼音码显示框）美化：圆角 + 靠左 + 内容宽度。 */
public class PreeditHelper {
    private static final String TAG = "Fcitx5Enh";

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

            // 子 View 清 background → 透出底层背景
            if (preeditRoot instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) preeditRoot;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View child = vg.getChildAt(i);
                    child.setBackground(null);
                    ViewGroup.LayoutParams clp = child.getLayoutParams();
                    if (clp != null && clp.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                        clp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                        child.setLayoutParams(clp);
                    }
                }
            }

            // 圆角背景
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.RECTANGLE);
            gd.setColor(bgColor);
            gd.setCornerRadius(r);
            preeditRoot.setBackground(gd);

            // 原地修改 LayoutParams → 靠左 + 内容宽度
            ViewGroup.LayoutParams lp = preeditRoot.getLayoutParams();
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            try { lp.getClass().getField("endToEnd").setInt(lp, -1); } catch (Exception e) {}
            try { lp.getClass().getField("rightToRight").setInt(lp, -1); } catch (Exception e) {}
            try { lp.getClass().getField("horizontalBias").setFloat(lp, 0f); } catch (Exception e) {}
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                mlp.leftMargin = (int) (12 * den + .5f);
                mlp.bottomMargin = (int) (3 * den + .5f);
            }
            preeditRoot.requestLayout();

            Log.i(TAG, "preedit round r=" + c.corner + "dp left=12dp bottom=3dp");
        } catch (Throwable t) {
            Log.w(TAG, "preedit effects: " + t);
        }
    }
}
