package com.rebron1900.fcitx5enhanced;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Path;
import android.os.Build;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.widget.ImageView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** 键盘毛玻璃 + 圆角裁剪。 */
public class FrostedGlassHelper {
    private static final String TAG = "Fcitx5Enh";

    public static void apply(View inputView, MainHook.Config c) {
        applyFrostedGlass(inputView, c);
        applyRoundedCorners(inputView, c);
    }

    // ══════════════════════════════════════════
    //  毛玻璃 — ViewRootImpl.createBackgroundBlurDrawable()
    // ══════════════════════════════════════════
    private static void applyFrostedGlass(View inputView, MainHook.Config c) {
        try {
            Field bgField = inputView.getClass().getDeclaredField("customBackground");
            bgField.setAccessible(true);
            ImageView bg = (ImageView) bgField.get(inputView);

            // 读取当前主题判断 dark/light + 获取按键底色
            boolean isDark = false;
            int keyBgColor = 0;
            try {
                Field themeField = inputView.getClass().getSuperclass()
                        .getDeclaredField("theme");
                themeField.setAccessible(true);
                Object theme = themeField.get(inputView);
                Method isDarkM = theme.getClass().getMethod("isDark");
                isDark = (Boolean) isDarkM.invoke(theme);
                Method getKeyBg = theme.getClass().getMethod("getKeyBackgroundColor");
                keyBgColor = (Integer) getKeyBg.invoke(theme);
            } catch (Exception ignored) {}

            Object viewRootImpl = null;
            try {
                Method getVri = View.class.getMethod("getViewRootImpl");
                viewRootImpl = getVri.invoke(inputView);
            } catch (Exception ignored) {
                viewRootImpl = inputView.getRootView().getParent();
            }

            if (viewRootImpl != null && c.blur > 0) {
                Method createBlur = viewRootImpl.getClass()
                        .getDeclaredMethod("createBackgroundBlurDrawable");
                Object blurDrawable = createBlur.invoke(viewRootImpl);

                if (blurDrawable != null) {
                    blurDrawable.getClass().getMethod("setBlurRadius", Integer.TYPE)
                            .invoke(blurDrawable, c.blur);
                    blurDrawable.getClass().getMethod("setColor", Integer.TYPE)
                            .invoke(blurDrawable, Color.TRANSPARENT);

                    bg.setBackground((Drawable) blurDrawable);

                    // tint 位图：四角填色遮 BlurDrawable 矩形模糊
                    int alpha = c.alpha;
                    int w = Math.max(1, bg.getWidth());
                    int h = Math.max(1, bg.getHeight());
                    Bitmap tint = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    Canvas cnv = new Canvas(tint);

                    int topColor, bottomColor;
                    if (keyBgColor != 0) {
                        // 从主题按键底色推导渐变
                        int baseR = Color.red(keyBgColor);
                        int baseG = Color.green(keyBgColor);
                        int baseB = Color.blue(keyBgColor);
                        topColor = Color.argb(alpha, baseR, baseG, baseB);
                        bottomColor = Color.argb(Math.min(255, alpha + 20),
                                Math.max(0, baseR - 15),
                                Math.max(0, baseG - 15),
                                Math.max(0, baseB - 15));
                    } else if (isDark) {
                        topColor = Color.argb(alpha, 30, 35, 50);
                        bottomColor = Color.argb(alpha, 20, 25, 40);
                    } else {
                        topColor = Color.argb(alpha, 245, 248, 255);
                        bottomColor = Color.argb(alpha, 225, 230, 245);
                    }

                    cnv.drawColor(bottomColor);

                    if (c.corner > 0) {
                        float cr = TypedValue.applyDimension(
                                TypedValue.COMPLEX_UNIT_DIP, c.corner,
                                inputView.getResources().getDisplayMetrics());
                        Path cornerPath = new Path();
                        cornerPath.addRoundRect(0, 0, w, h, cr, cr, Path.Direction.CW);
                        cnv.save();
                        cnv.clipPath(cornerPath);
                        GradientDrawable gt = new GradientDrawable(
                                GradientDrawable.Orientation.TOP_BOTTOM,
                                new int[]{topColor, bottomColor}
                        );
                        gt.setBounds(0, 0, w, h);
                        gt.draw(cnv);
                        cnv.restore();
                    } else {
                        GradientDrawable gt = new GradientDrawable(
                                GradientDrawable.Orientation.TOP_BOTTOM,
                                new int[]{topColor, bottomColor}
                        );
                        gt.setBounds(0, 0, w, h);
                        gt.draw(cnv);
                    }
                    // 回收旧 Bitmap
                    Drawable oldBg = bg.getDrawable();
                    if (oldBg instanceof android.graphics.drawable.BitmapDrawable) {
                        Bitmap oldBmp = ((android.graphics.drawable.BitmapDrawable) oldBg).getBitmap();
                        if (oldBmp != null && !oldBmp.isRecycled()) {
                            oldBmp.recycle();
                        }
                    }

                    bg.setImageBitmap(tint);
                    bg.setScaleType(ImageView.ScaleType.FIT_XY);
                    bg.setImageAlpha(255);

                    Log.i(TAG, "✅ REAL blur=" + c.blur + " alpha=" + alpha + " dark=" + isDark);
                } else {
                    Log.w(TAG, "createBackgroundBlurDrawable returned null");
                    fallback(bg, inputView, isDark, c);
                }
            } else {
                Log.w(TAG, "viewRootImpl=" + viewRootImpl + " blur=" + c.blur);
                fallback(bg, inputView, isDark, c);
            }
        } catch (Throwable t) {
            Log.w(TAG, "frosted glass failed: " + t);
        }
    }

    private static void fallback(ImageView bg, View inputView, boolean isDark, MainHook.Config c) {
        try {
            int alpha = c.alpha;
            int w = inputView.getWidth();
            int h = inputView.getHeight();
            if (w <= 0 || h <= 0) {
                DisplayMetrics dm = inputView.getResources().getDisplayMetrics();
                w = dm.widthPixels;
                h = (int) (dm.heightPixels * 0.4f);
            }

            // 尝试读主题按键底色
            int keyBgColor = 0;
            try {
                Field themeField = inputView.getClass().getSuperclass()
                        .getDeclaredField("theme");
                themeField.setAccessible(true);
                Object theme = themeField.get(inputView);
                Method getKeyBg = theme.getClass().getMethod("getKeyBackgroundColor");
                keyBgColor = (Integer) getKeyBg.invoke(theme);
            } catch (Exception ignored) {}

            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas cnv = new Canvas(out);

            int c1, c2;
            if (keyBgColor != 0) {
                int br = Color.red(keyBgColor), bg_ = Color.green(keyBgColor), bb = Color.blue(keyBgColor);
                c1 = Color.argb(alpha, br, bg_, bb);
                c2 = Color.argb(Math.min(255, alpha + 20),
                        Math.max(0, br - 15), Math.max(0, bg_ - 15), Math.max(0, bb - 15));
            } else if (isDark) {
                c1 = Color.argb(alpha, 30, 35, 50);
                c2 = Color.argb(alpha, 20, 25, 40);
            } else {
                c1 = Color.argb(alpha, 245, 248, 255);
                c2 = Color.argb(alpha, 225, 230, 245);
            }

            GradientDrawable base = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{c1, c2}
            );
            base.setBounds(0, 0, w, h);
            base.draw(cnv);

            // 回收旧 Bitmap
            Drawable oldBg = bg.getDrawable();
            if (oldBg instanceof android.graphics.drawable.BitmapDrawable) {
                Bitmap oldBmp = ((android.graphics.drawable.BitmapDrawable) oldBg).getBitmap();
                if (oldBmp != null && !oldBmp.isRecycled()) {
                    oldBmp.recycle();
                }
            }

            bg.setImageBitmap(out);
            bg.setImageAlpha(255);
            bg.setBackground(null);
        } catch (Exception ignored) {}
    }

    // ══════════════════════════════════════════
    //  键盘圆角 — tint 位图填角 + keyboardView 裁剪
    // ══════════════════════════════════════════

    private static void applyRoundedCorners(View inputView, MainHook.Config c) {
        try {
            if (c.corner <= 0) return;

            float r = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, c.corner,
                    inputView.getResources().getDisplayMetrics());

            makeWindowTransparent(inputView);

            View decorView = inputView.getRootView();
            decorView.setBackgroundColor(Color.TRANSPARENT);
            decorView.setBackground(null);
            decorView.setClipToOutline(false);
            decorView.setOutlineProvider(null);

            inputView.setBackgroundColor(Color.TRANSPARENT);

            try {
                Field kvField = inputView.getClass().getDeclaredField("keyboardView");
                kvField.setAccessible(true);
                View kv = (View) kvField.get(inputView);
                applyOutline(kv, r);
                // 渐变描边（前景叠加）——暗色亮色描边
                addGradientBorder(kv, inputView, c);
            } catch (Exception ignored) {}

            try {
                Field bgField = inputView.getClass().getDeclaredField("customBackground");
                bgField.setAccessible(true);
                View bgV = (View) bgField.get(inputView);
                bgV.setClipToOutline(false);
                bgV.setOutlineProvider(null);
            } catch (Exception ignored) {}

            Log.i(TAG, "corners: r=" + c.corner + "dp (tint-fill + kv clip)");
        } catch (Throwable t) {
            Log.w(TAG, "corners failed: " + t);
        }
    }

    /** 多路径获取 IME 窗口并设背景透明 */
    private static void makeWindowTransparent(View anyView) {
        // 路径 D: ContextWrapper 链中找 getWindow()
        try {
            android.content.Context ctx = anyView.getContext();
            while (ctx instanceof android.content.ContextWrapper) {
                try {
                    Method gw = ctx.getClass().getMethod("getWindow");
                    Object winObj = gw.invoke(ctx);
                    if (winObj instanceof Window) {
                        Window w = (Window) winObj;
                        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                        w.getDecorView().setBackgroundColor(Color.TRANSPARENT);
                        Log.i(TAG, "✅ window transparent (D: getWindow)");
                        return;
                    }
                } catch (NoSuchMethodException ignored) {}
                ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
            }
        } catch (Exception ignored) {}

        try {
            Object ctx = anyView.getContext();
            Class<?> clz = ctx.getClass();
            while (clz != null) {
                try {
                    java.lang.reflect.Method gw = clz.getDeclaredMethod("getWindow");
                    gw.setAccessible(true);
                    Object softInputWin = gw.invoke(ctx);
                    if (softInputWin != null) {
                        Method getWin = softInputWin.getClass().getMethod("getWindow");
                        Window window = (Window) getWin.invoke(softInputWin);
                        if (window != null) {
                            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                            window.getDecorView().setBackgroundColor(Color.TRANSPARENT);
                            Log.i(TAG, "✅ window transparent (A)");
                            return;
                        }
                    }
                } catch (NoSuchMethodException ignored) {}
                clz = clz.getSuperclass();
            }

            // 路径 B: ViewRootImpl.mWindow
            try {
                View root = anyView.getRootView();
                Object vri = root.getParent();
                if (vri != null) {
                    java.lang.reflect.Field f = vri.getClass().getDeclaredField("mWindow");
                    f.setAccessible(true);
                    Window w = (Window) f.get(vri);
                    if (w != null) {
                        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                        w.getDecorView().setBackgroundColor(Color.TRANSPARENT);
                        Log.i(TAG, "✅ window transparent (B)");
                        return;
                    }
                }
            } catch (Exception ignored) {}

            // 路径 C: 暴力设所有可见层透明
            try {
                View root = anyView.getRootView();
                root.setBackgroundColor(Color.TRANSPARENT);
                if (root.getParent() instanceof View) {
                    ((View) root.getParent()).setBackgroundColor(Color.TRANSPARENT);
                }
                Log.i(TAG, "root transparent (C)");
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    private static void applyOutline(View v, float radius) {
        v.setClipToOutline(true);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                int w = view.getWidth(), h = view.getHeight();
                if (w <= 0 || h <= 0) return;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Path path = new Path();
                    path.addRoundRect(0, 0, w, h, radius, radius, Path.Direction.CW);
                    outline.setConvexPath(path);
                } else {
                    outline.setRoundRect(0, 0, w, h, radius);
                }
            }
        });
    }

    /** 键盘渐变描边 — View.setForeground + GlassBorderDrawable */
    private static void addGradientBorder(View keyboardView, View inputView, MainHook.Config c) {
        try {
            boolean isDark = false;
            try {
                java.lang.reflect.Field themeField = inputView.getClass().getSuperclass()
                        .getDeclaredField("theme");
                themeField.setAccessible(true);
                Object theme = themeField.get(inputView);
                java.lang.reflect.Method isDarkM = theme.getClass().getMethod("isDark");
                isDark = (Boolean) isDarkM.invoke(theme);
            } catch (Exception ignored) {}

            int borderTop, borderBottom;
            float den = inputView.getResources().getDisplayMetrics().density;
            float borderWidthPx;
            if (isDark) {
                // 暗色：白 0.20→TRANSPARENT→0.20，描边 1dp（进一步淡化）
                borderTop = 0x33FFFFFF;
                borderBottom = 0x33FFFFFF;
                borderWidthPx = 1f * den;
            } else {
                // 亮色：白 0.6→TRANSPARENT→0.6，描边 1dp
                borderTop = 0x99FFFFFF;
                borderBottom = 0x99FFFFFF;
                borderWidthPx = 1f * den;
            }
            float radius = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, c.corner,
                    inputView.getResources().getDisplayMetrics());

            GlassBorderDrawable gb = new GlassBorderDrawable(
                    0, borderTop, borderBottom, radius, borderWidthPx,
                    GlassBorderDrawable.MODE_KEYBOARD);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                keyboardView.setForeground(gb);
                Log.i(TAG, "keyboard gradient border: dark=" + isDark);
            }
        } catch (Throwable t) {
            Log.w(TAG, "gradient border failed: " + t);
        }
    }
}
