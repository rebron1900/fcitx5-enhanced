package com.rebron1900.fcitx5enhanced;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.os.Bundle;
import android.graphics.Path;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.util.TypedValue;
import android.graphics.RectF;
import android.graphics.Picture;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputConnection;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.caverock.androidsvg.SVG;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * Fcitx5 Frosted Glass — 毛玻璃键盘美化
 *
 * Hook 入口: FcitxInputMethodService.setInputView(View)
 * 配置读取: ContentProvider IPC (ConfigContentProvider)
 * 毛玻璃: 噪声纹理 + 半透明渐变 + RenderEffect blur
 * 键盘圆角: ViewOutlineProvider on windowManager.view + customBackground
 * 工具栏圆角: 透明 + clipToOutline
 */
public class MainHook extends XposedModule {

    private static final String TAG = "Fcitx5Enh";
    private static final String PKG = "org.fcitx.fcitx5.android.fx";
    private static final String CLS_SVC = "org.fcitx.fcitx5.android.input.FcitxInputMethodService";
    private static final String CLS_IV = "org.fcitx.fcitx5.android.input.InputView";

    private int cfgBlur = 100, cfgAlpha = 60, cfgCorner = 20, cfgToolbar = 20;
    private boolean cfgVoice = true, cfgLeftBtn = true, cfgRightBtn = true;

    /** Tracks whether we've registered the re-apply broadcast receiver */
    private boolean receiverRegistered;

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!PKG.equals(param.getPackageName())) return;
        log(Log.INFO, TAG, "init");

        try {
            Class<?> svc = Class.forName(CLS_SVC, true, param.getClassLoader());
            Method setIv = svc.getMethod("setInputView", View.class);

            hook(setIv).intercept(chain -> {
                View v = (View) chain.getArgs().get(0);
                chain.proceed();

                if (v != null && CLS_IV.equals(v.getClass().getName())) {
                    readConfig(v);
                    if (!receiverRegistered) registerReapplyReceiver(v);
                    View fv = v;
                    v.post(() -> applyAllEffects(fv));
                }
                return null;
            });

            log(Log.INFO, TAG, "hook installed");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook failed", t);
        }

        // Hook fcitx5 设置页 → 添加"小企鹅增强"入口
        // hook setPreferenceScreen(PreferenceScreen) 是 public 方法，R8 动不了
        // PreferenceScreen 作为参数直接拿到，不依赖任何 internal 字段/方法
        try {
            Class<?> pfc = Class.forName("androidx.preference.PreferenceFragmentCompat",
                    true, param.getClassLoader());
            Class<?> psCls = Class.forName("androidx.preference.PreferenceScreen",
                    true, param.getClassLoader());
            Method setPs = pfc.getMethod("setPreferenceScreen", psCls);
            hook(setPs).intercept(chain -> {
                Object fragment = chain.getThisObject();
                String clsName = fragment.getClass().getName();
                // 只对 MainFragment 处理
                if (!clsName.equals("org.fcitx.fcitx5.android.ui.main.MainFragment"))
                    return chain.proceed();

                Object screen = chain.getArg(0);
                chain.proceed(); // 原始 setPreferenceScreen

                if (screen == null) return null;
                // 用 screen 的 classLoader（fcitx5 的类加载器）
                ClassLoader targetLoader = screen.getClass().getClassLoader();
                // 查重 + 添加
                addPrefToScreen(screen, fragment, targetLoader);
                return null;
            });
        } catch (Throwable e) {
            log(Log.WARN, TAG, "settings hook setup failed: " + e);
        }

        // Popup 悬浮窗背景半透明 — 后面在 applyKeyEffects 里通过监听 popup root 实现
        // (LSPosed API 101.0.1 的 Interceptor.Chain 没有 getObject())
    }

    // ══════════════════════════════════════════
    //  Config via ContentProvider IPC
    // ══════════════════════════════════════════

    private void readConfig(View anyView) {
        boolean loaded = false;
        try {
            Uri uri = Uri.parse("content://com.rebron1900.fcitx5enhanced.config/config");
            Cursor c = anyView.getContext().getContentResolver().query(
                    uri, null, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        cfgBlur = c.getInt(c.getColumnIndexOrThrow("blur_radius"));
                        cfgAlpha = c.getInt(c.getColumnIndexOrThrow("bg_alpha"));
                        cfgCorner = c.getInt(c.getColumnIndexOrThrow("corner_radius"));
                        cfgToolbar = cfgCorner;
                        cfgVoice = c.getInt(c.getColumnIndexOrThrow("voice_enabled")) != 0;
                        cfgLeftBtn = c.getInt(c.getColumnIndexOrThrow("show_left_button")) != 0;
                        cfgRightBtn = c.getInt(c.getColumnIndexOrThrow("show_right_button")) != 0;
                        loaded = true;
                    }
                } finally { c.close(); }
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "readConfig CP failed: " + t);
        }
        // Fallback: 持久文件（模块重装后恢复）
        if (!loaded || (cfgBlur == 0 && cfgAlpha == 0)) {
            restoreFromFile();
        }
    }

    /** 从 /data/local/tmp/ 持久文件恢复配置 */
    private void restoreFromFile() {
        try {
            java.io.File f = new java.io.File("/data/local/tmp/fcitx5_enhanced_config.json");
            if (!f.exists()) {
                f = new java.io.File("/data/local/tmp/fcitx5_frosted_config.json"); // 兼容旧路径
                if (!f.exists()) return;
            }
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            org.json.JSONObject json = new org.json.JSONObject(sb.toString());
            cfgBlur = json.optInt("blur_radius", 100);
            cfgAlpha = json.optInt("bg_alpha", 60);
            cfgCorner = json.optInt("corner_radius", 20);
            cfgToolbar = json.optInt("toolbar_radius", 20);
            cfgVoice = json.optBoolean("voice_enabled", true);
            cfgLeftBtn = json.optBoolean("show_left_button", true);
            cfgRightBtn = json.optBoolean("show_right_button", true);
            log(Log.INFO, TAG, "restored from file: blur=" + cfgBlur + " alpha=" + cfgAlpha);
        } catch (Exception ignored) {}
    }

    // ══════════════════════════════════════════
    //  Apply all visual effects
    // ══════════════════════════════════════════

    private void applyAllEffects(View inputView) {
        // Re-read config each time (ContentProvider gives latest values)
        readConfig(inputView);
        applyFrostedGlass(inputView);
        applyRoundedCorners(inputView);
        roundToolbarTop(inputView);
        addExtraButtons(inputView);
        applyKeyEffects(inputView);
    }

    // ══════════════════════════════════════════
    //  毛玻璃 — ViewRootImpl.createBackgroundBlurDrawable()
    // ══════════════════════════════════════════
    //
    // WeType_UI_Enhanced 的实现方式：
    // 1. 获取 ViewRootImpl（View.getViewRootImpl()，@hide 但可反射）
    // 2. 调用 createBackgroundBlurDrawable() 返回 BlurDrawable
    // 3. BlurDrawable 会模糊它所在 View 背后的内容
    // 4. 叠加半透明着色层 = 真实毛玻璃效果
    //
    // 这跟 Window.setBackgroundBlurRadius() 是两条不同的路径，
    // HyperOS 禁用了 IME 窗口的 Window blur，但没禁 ViewRootImpl blur。

    private void applyFrostedGlass(View inputView) {
        try {
            Field bgField = inputView.getClass().getDeclaredField("customBackground");
            bgField.setAccessible(true);
            ImageView bg = (ImageView) bgField.get(inputView);

            int blurRadius = cfgBlur;

            // 读取当前主题判断 dark/light
            boolean isDark = false;
            try {
                Field themeField = inputView.getClass().getSuperclass()
                        .getDeclaredField("theme");
                themeField.setAccessible(true);
                Object theme = themeField.get(inputView);
                Method isDarkM = theme.getClass().getMethod("isDark");
                // isDark is a Kotlin property → compiled to isDark() getter
                isDark = (Boolean) isDarkM.invoke(theme);
            } catch (Exception ignored) {}

            // 1) 获取 ViewRootImpl
            Object viewRootImpl = null;
            try {
                Method getVri = View.class.getMethod("getViewRootImpl");
                viewRootImpl = getVri.invoke(inputView);
            } catch (Exception ignored) {
                viewRootImpl = inputView.getRootView().getParent();
            }

            if (viewRootImpl != null && blurRadius > 0) {
                // 2) 创建 BlurDrawable
                Method createBlur = viewRootImpl.getClass()
                        .getDeclaredMethod("createBackgroundBlurDrawable");
                Object blurDrawable = createBlur.invoke(viewRootImpl);

                if (blurDrawable != null) {
                    blurDrawable.getClass().getMethod("setBlurRadius", Integer.TYPE)
                            .invoke(blurDrawable, blurRadius);
                    blurDrawable.getClass().getMethod("setColor", Integer.TYPE)
                            .invoke(blurDrawable, Color.TRANSPARENT);

                    bg.setBackground((Drawable) blurDrawable);

                    // 3) 前景着色层 — 用边缘色填满四角，遮住 BlurDrawable 的矩形模糊
                    //    BlurDrawable 是合成层效果，View 的 Canvas 裁剪对它无效
                    //    所以不用 View 裁剪实现圆角，而是把位图四角填上渐变色
                    int alpha = cfgAlpha;
                    int w = Math.max(1, bg.getWidth());
                    int h = Math.max(1, bg.getHeight());
                    Bitmap tint = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(tint);

                    int topColor, bottomColor;
                    int baseR, baseG, baseB;  // 用于推导描边色
                    if (isDark) {
                        baseR = 30; baseG = 35; baseB = 50;
                        topColor = Color.argb(alpha, baseR, baseG, baseB);
                        bottomColor = Color.argb(alpha, 20, 25, 40);
                    } else {
                        baseR = 245; baseG = 248; baseB = 255;
                        topColor = Color.argb(alpha, baseR, baseG, baseB);
                        bottomColor = Color.argb(alpha, 225, 230, 245);
                    }

                    // a) 整张画布满铺边缘色（四角被填满，BlurDrawable 不露出）
                    c.drawColor(bottomColor);

                    // b) 裁剪圆角后在上面画渐变
                    if (cfgCorner > 0) {
                        float cr = TypedValue.applyDimension(
                                TypedValue.COMPLEX_UNIT_DIP, cfgCorner,
                                inputView.getResources().getDisplayMetrics());
                        Path cornerPath = new Path();
                        cornerPath.addRoundRect(0, 0, w, h, cr, cr, Path.Direction.CW);
                        c.save();
                        c.clipPath(cornerPath);
                        GradientDrawable gt = new GradientDrawable(
                                GradientDrawable.Orientation.TOP_BOTTOM,
                                new int[]{topColor, bottomColor}
                        );
                        gt.setBounds(0, 0, w, h);
                        gt.draw(c);
                        c.restore();

                        // c) 顶部描边 — 柔和高光线
                        float borderPx = TypedValue.applyDimension(
                                TypedValue.COMPLEX_UNIT_DIP, 1f,  // 1dp 细线
                                inputView.getResources().getDisplayMetrics());
                        int borderColor;
                        if (isDark) {
                            // 暗色：从背景 rgb(30,35,50) 大幅提亮成冷灰高光
                            borderColor = Color.argb(
                                    Math.min(alpha / 3, 55),
                                    Math.min(baseR + 55, 255),
                                    Math.min(baseG + 55, 255),
                                    Math.min(baseB + 80, 255));
                        } else {
                            // 亮色：纯白高光
                            borderColor = Color.argb(
                                    Math.min(alpha / 3, 50),
                                    255, 255, 255);
                        }
                        android.graphics.Paint bp = new android.graphics.Paint();
                        bp.setStyle(android.graphics.Paint.Style.STROKE);
                        bp.setStrokeWidth(borderPx);
                        bp.setColor(borderColor);
                        bp.setAntiAlias(true);
                        // 只画顶部 + 左右顶角弧线
                        float R = cr;
                        float i = borderPx;
                        // 顶部直线
                        c.drawLine(i + R, i, w - i - R, i, bp);
                        // 左上角弧线 (从顶部边缘 → 左边缘方向, 只画顶部90°)
                        c.drawArc(new android.graphics.RectF(
                                i, i, i + R * 2, i + R * 2), 270, -90, false, bp);
                        // 右上角弧线
                        c.drawArc(new android.graphics.RectF(
                                w - i - R * 2, i, w - i, i + R * 2), 270, 90, false, bp);
                    } else {
                        // 无圆角时直接画渐变
                        GradientDrawable gt = new GradientDrawable(
                                GradientDrawable.Orientation.TOP_BOTTOM,
                                new int[]{topColor, bottomColor}
                        );
                        gt.setBounds(0, 0, w, h);
                        gt.draw(c);
                    }
                    bg.setImageBitmap(tint);
                    // FIT_XY 确保边框不被裁剪（默认 CENTER_CROP 会裁边缘）
                    bg.setScaleType(ImageView.ScaleType.FIT_XY);
                    // per-pixel alpha 已控制透明度，ImageView 层不再叠加
                    bg.setImageAlpha(255);

                    log(Log.INFO, TAG, "✅ REAL blur=" + blurRadius
                            + " alpha=" + alpha + " dark=" + isDark);
                } else {
                    log(Log.WARN, TAG, "createBackgroundBlurDrawable returned null");
                    fallbackFrostedGlass(bg, inputView, isDark);
                }
            } else {
                log(Log.WARN, TAG, "viewRootImpl=" + viewRootImpl + " blur=" + blurRadius);
                fallbackFrostedGlass(bg, inputView, isDark);
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "frosted glass failed: " + t);
        }
    }

    /** 回退方案：静态纹理（遮罩用） */
    private void fallbackFrostedGlass(ImageView bg, View inputView, boolean isDark) {
        try {
            int alpha = cfgAlpha;
            int w = inputView.getWidth();
            int h = inputView.getHeight();
            if (w <= 0) w = 1080;
            if (h <= 0) h = 1200;

            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);

            int c1, c2;
            if (isDark) {
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
            base.draw(c);

            bg.setImageBitmap(out);
            bg.setImageAlpha(255);
            bg.setBackground(null);
        } catch (Exception ignored) {}
    }

    // ══════════════════════════════════════════
    //  键盘圆角 — 由 tint 位图填角 + keyboardView 裁剪实现
    // ══════════════════════════════════════════
    //
    // 核心思路：BlurDrawable 是合成层效果，View 的 Canvas 裁剪对它无效。
    // 所以：
    //   1. tint 位图四角用渐变色填满（遮住 BlurDrawable 的矩形模糊）
    //   2. keyboardView（键盘按键容器）用 ViewOutlineProvider 裁剪
    //      普通 View 的 Canvas 裁剪对键盘按键内容有效
    //   3. DecorView 不裁剪，窗口背景透明即可
    //
    // 注意事项：
    //   - keyboardView 的裁剪是 Canvas 级别的，圆角后的键盘按键会被正确裁切
    //   - tint 位图的圆角填色和 keyboardView 的裁剪半径应保持一致（都用 cfgCorner）

    private void applyRoundedCorners(View inputView) {
        try {
            if (cfgCorner <= 0) return;

            float r = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, cfgCorner,
                    inputView.getResources().getDisplayMetrics());

            // 1) 窗口背景透明（确保系统默认底色不干扰）
            makeWindowBackgroundTransparent(inputView);

            // 2) DecorView 背景透明（不裁剪，保持完整矩形）
            View decorView = inputView.getRootView();
            decorView.setBackgroundColor(Color.TRANSPARENT);
            decorView.setBackground(null);
            // 恢复 DecorView 的 clip（之前可能设置了 clipToOutline）
            decorView.setClipToOutline(false);
            decorView.setOutlineProvider(null);

            // 3) InputView 背景透明
            inputView.setBackgroundColor(Color.TRANSPARENT);

            // 4) keyboardView 裁剪圆角 — 不被遮挡的 View 裁剪在此用
            try {
                Field kvField = inputView.getClass().getDeclaredField("keyboardView");
                kvField.setAccessible(true);
                View kv = (View) kvField.get(inputView);
                applyOutline(kv, r);
            } catch (Exception ignored) {}

            // 5) customBackground 不裁剪（tint 位图已处理四角填色）
            try {
                Field bgField = inputView.getClass().getDeclaredField("customBackground");
                bgField.setAccessible(true);
                View bgV = (View) bgField.get(inputView);
                bgV.setClipToOutline(false);
                bgV.setOutlineProvider(null);
            } catch (Exception ignored) {}

            log(Log.INFO, TAG, "corners: r=" + cfgCorner + "dp (tint-fill + kv clip)");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "corners failed: " + t);
        }
    }

    /** 多路径获取 IME 窗口并设背景透明 */
    private void makeWindowBackgroundTransparent(View anyView) {
        // 路径 D: 解包 Context 链，反射调用 getWindow()
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
                        log(Log.INFO, TAG, "✅ window transparent (D: getWindow)");
                        return;
                    }
                } catch (NoSuchMethodException ignored) {}
                ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
            }
        } catch (Exception ignored) {}

        try {
            // 路径 A: 从 View 的 Context 链中找 InputMethodService → getWindow()
            Object ctx = anyView.getContext();
            Class<?> clz = ctx.getClass();
            while (clz != null && clz.getName().contains("fcitx") == false
                    && !android.inputmethodservice.InputMethodService.class.getName().equals(clz.getName())) {
                // Walk up to find InputMethodService
                clz = clz.getSuperclass();
            }
            // Now try all classes from fcitx's service up to InputMethodService
            clz = ctx.getClass();
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
                            log(Log.INFO, TAG, "✅ window transparent (A)");
                            return;
                        }
                    }
                } catch (NoSuchMethodException ignored) {}
                clz = clz.getSuperclass();
            }

            // 路径 B: ViewRootImpl.mWindow 反射
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
                        log(Log.INFO, TAG, "✅ window transparent (B)");
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
                log(Log.INFO, TAG, "root transparent (C)");
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    private void applyOutline(View v, float radius) {
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

    // ══════════════════════════════════════════
    //  工具栏圆角
    // ══════════════════════════════════════════

    private void roundToolbarTop(View inputView) {
        try {
            if (cfgToolbar <= 0) return;

            Field f = inputView.getClass().getDeclaredField("kawaiiBar");
            f.setAccessible(true);
            Object bar = f.get(inputView);
            Method gv = bar.getClass().getMethod("getView");
            View toolbar = (View) gv.invoke(bar);

            if (toolbar.getWidth() <= 0 || toolbar.getHeight() <= 0) {
                toolbar.post(() -> roundToolbarTop(inputView));
                return;
            }

            float R = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, cfgToolbar,
                    inputView.getResources().getDisplayMetrics());

            GradientDrawable gd = new GradientDrawable();
            gd.setCornerRadii(new float[]{R, R, R, R, 0, 0, 0, 0});
            gd.setColor(Color.TRANSPARENT);
            toolbar.setBackground(gd);

            // 裁剪父布局，防止背景漏出方角
            ViewParent parent = toolbar.getParent();
            if (parent instanceof View) {
                final float pr = R;
                ((View) parent).setClipToOutline(true);
                ((View) parent).setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        int pw = view.getWidth(), ph = view.getHeight();
                        if (pw <= 0 || ph <= 0) return;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Path p = new Path();
                            p.addRoundRect(0, 0, pw, ph + (int) pr + 1,
                                    new float[]{pr, pr, pr, pr, 0, 0, 0, 0},
                                    Path.Direction.CW);
                            outline.setConvexPath(p);
                        } else {
                            outline.setRoundRect(0, 0, pw, ph + (int) pr + 1, pr);
                        }
                    }
                });
            }

            log(Log.INFO, TAG, "toolbar round r=" + cfgToolbar + "dp");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "toolbar round failed: " + t);
        }
    }

    // ══════════════════════════════════════════
    //  底部角按钮 — IME切换 + 剪贴板
    // ══════════════════════════════════════════

    private void addExtraButtons(View inputView) {
        try {
            Method getKv = inputView.getClass().getMethod("getKeyboardView");
            final ViewGroup keyboardView = (ViewGroup) getKv.invoke(inputView);
            if (keyboardView.findViewWithTag("frosted_btn_ime") != null) return;

            final Resources res = inputView.getResources();
            Context ctx = inputView.getContext();
            final float den = res.getDisplayMetrics().density;
            final int bs = (int) (30 * den + .5f);   // 图标尺寸 30dp
            final int mr = (int) (26 * den + .5f);   // 左右边距 26dp

            // 从 theme 取工具栏图标色：ToolButton 用的是 altKeyTextColor
            int toolbarColor = 0xFF888888;
            try {
                Field tf = inputView.getClass().getSuperclass().getDeclaredField("theme");
                tf.setAccessible(true);
                Object theme = tf.get(inputView);
                toolbarColor = (Integer) theme.getClass().getMethod("getAltKeyTextColor").invoke(theme);
            } catch (Exception ignored) {}
            final int keyFg = toolbarColor;

            // ── 左: IME 切换（纯图标，无背景）──
            final int topExtra = Math.round(-10 * den); // 距离底部 16dp
            if (cfgLeftBtn) {
            final ImageView ime = new ImageView(ctx);
            ime.setTag("frosted_btn_ime");
            ime.setBackground(null);
            ime.setPadding(0,0,0,0);
            ime.setImageDrawable(svgIme(den, keyFg, 30));
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
            if (cfgRightBtn) {
            final ImageView clip = new ImageView(ctx);
            clip.setTag("frosted_btn_clipboard");
            clip.setBackground(null);
            clip.setPadding(0,0,0,0);
            clip.setImageDrawable(svgClipboard(den, keyFg, 30));
            clip.setScaleType(ImageView.ScaleType.FIT_CENTER);
            clip.setClickable(true);

            clip.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                showClipboardPopup(inputView, clip);
            });

            keyboardView.addView(clip, new ViewGroup.LayoutParams(bs, bs));
            // 绝对定位到底部右侧
            clip.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                int bw = bs;
                v.getLayoutParams().width = bs;
                v.getLayoutParams().height = bs;
                v.setX(keyboardView.getWidth() - bw - mr);
                v.setY(keyboardView.getHeight() - bs - mr - topExtra);
            });
            }

            // ── 中: 语音波形线（按住录音） ──
            if (cfgVoice) {
            final WaveformLineView waveView = new WaveformLineView(ctx);
            waveView.setTag("frosted_btn_voice");
            waveView.setIdleColor(keyFg);
            waveView.setRecordingColor(Color.argb(220, 100, 160, 255)); // 录音中亮蓝紫
            waveView.setClickable(true);
            waveView.setFocusable(false);

            final VoiceInputClient[] voiceClientRef = new VoiceInputClient[1];

            waveView.setOnTouchListener((v, ev) -> {
                switch (ev.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN: {
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                        // 取消上一个会话
                        if (voiceClientRef[0] != null) {
                            voiceClientRef[0].cancel();
                        }

                        // 获取 InputMethodService 和 InputConnection
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

                        // 启动语音识别
                        VoiceInputClient client = new VoiceInputClient();
                        voiceClientRef[0] = client;

                        // 设置振幅回调 → 驱动波形
                        client.setAmplitudeListener(amp -> {
                            // 主线程更新 UI
                            waveView.post(() -> waveView.setAmplitude(amp));
                        });

                        client.startVoiceInput(svcFinal, icFinal, () -> {
                            // 恢复空闲状态
                            waveView.post(() -> {
                                waveView.setRecording(false);
                                waveView.setAmplitude(0);
                            });
                        });

                        // 进入录音状态
                        waveView.setRecording(true);
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL: {
                        if (voiceClientRef[0] != null) {
                            voiceClientRef[0].stopVoiceInput();
                            // 不设 null：等 onDone 回调恢复后再被新按下替换
                        }
                        return true;
                    }
                }
                return false;
            });

            keyboardView.addView(waveView, new ViewGroup.LayoutParams(
                    (int)(160 * den + .5f), bs));
            waveView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                v.setX((keyboardView.getWidth() - v.getWidth()) / 2);
                v.setY(keyboardView.getHeight() - bs - mr - topExtra);
            });
            } // end if cfgVoice

            log(Log.INFO,TAG,"✅ extra buttons added");
        } catch (Throwable t) {
            log(Log.WARN,TAG,"addExtraButtons: "+t);
        }
    }

    // ══════════════════════════════════════════
    //  IME 输入法列表弹窗
    // ══════════════════════════════════════════

    private void showImePopup(View inputView, View anchor) {
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

            // 主题色 — 用 keyBackgroundColor 半透明 → 磨砂玻璃效果
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
            // 半透明：alpha ~160 让下层 BlurDrawable 透出来
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
                // 悬停高亮
                GradientDrawable itemBg = new GradientDrawable();
                itemBg.setShape(GradientDrawable.RECTANGLE);
                itemBg.setColor(bgColor);
                itemBg.setCornerRadius(dp8);
                tv.setBackground(itemBg);
                tv.setOnTouchListener((v, ev) -> {
                    if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                        GradientDrawable h = new GradientDrawable();
                        h.setShape(GradientDrawable.RECTANGLE);
                        h.setColor(fDark ? 0xFF3A3A3A : 0xFFE8E8E8);
                        h.setCornerRadius(dp8);
                        v.setBackground(h);
                    } else if (ev.getAction() == android.view.MotionEvent.ACTION_UP
                            || ev.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                        v.setBackground(itemBg);
                    }
                    return false;
                });
                tv.setOnClickListener(v -> {
                    try {
                        android.os.IBinder token = getWindowToken(svc);
                        imm.setInputMethod(token, imeId);
                    } catch (Exception e) {
                        log(Log.WARN, TAG, "IME switch: " + e);
                    }
                    if (imePopupRef[0] != null) imePopupRef[0].dismiss();
                });
                layout.addView(tv);
                if (i < imes.size() - 1) {
                    View div = new View(ctx);
                    div.setBackgroundColor(borderColor);
                    layout.addView(div, new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            (int)(0.5f*den+.5f)));
                }
            }

            // 弹窗尺寸：宽度 180dp，高度自适应内容
            int imeW_DP = 180;

            android.widget.FrameLayout outer = new android.widget.FrameLayout(ctx);
            GradientDrawable outerBg = new GradientDrawable();
            outerBg.setShape(GradientDrawable.RECTANGLE);
            outerBg.setColor(bgColor); outerBg.setCornerRadius(corner);
            outer.setBackground(outerBg);
            outer.setClipToOutline(true);
            outer.setPadding(0, 0, 0, corner);
            outer.addView(layout, new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

            int popupW = (int)(imeW_DP * den + .5f);

            PopupWindow popup = new PopupWindow(outer, popupW,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true);
            popup.setElevation(dp8);
            popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
            imePopupRef[0] = popup;

            int[] loc = new int[2];
            anchor.getLocationInWindow(loc);
            int popX = Math.max(dp8, loc[0]);
            // measure to calculate height for positioning
            outer.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(popupW,
                    android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0,
                    android.view.View.MeasureSpec.UNSPECIFIED));
            int popupH = outer.getMeasuredHeight();
            int popY = loc[1] - popupH - dp8;
            // 上方放不下 → 放在按钮下方
            if (popY < dp8) {
                popY = loc[1] + (int)(40*den+.5f) + dp8;
            }
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, popX, popY);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "IME popup: " + t);
        }
    }

    // ══════════════════════════════════════════
    //  剪贴板历史弹窗（读取 fcitx Room 数据库）
    // ══════════════════════════════════════════

    private void showClipboardPopup(View inputView, View anchor) {
        try {
            Context ctx = anchor.getContext();
            float den = ctx.getResources().getDisplayMetrics().density;
            int dp10 = (int)(10*den+.5f), dp12 = (int)(12*den+.5f), dp8 = (int)(8*den+.5f);
            int corner = (int)(12*den+.5f);

            // 主题色 — 半透明磨砂效果
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

            // 读取 fcitx Room 数据库
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
                                    android.view.inputmethod.InputConnection ic =
                                        svc.getCurrentInputConnection();
                                    if (ic != null) {
                                        ic.commitText(text, 1);
                                    }
                                } catch (Exception ex) {
                                    log(Log.WARN, TAG, "paste: " + ex);
                                }
                                if (clipPopupRef[0] != null) clipPopupRef[0].dismiss();
                            });

                            layout.addView(tv);

                            // 分隔线（非最后一条）
                            if (c.getPosition() + 1 < c.getCount()
                                    && layout.getChildCount() < 10) {
                                View div = new View(ctx);
                                div.setBackgroundColor(borderColor);
                                layout.addView(div, new android.widget.LinearLayout.LayoutParams(
                                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                        (int)(0.5f*den+.5f)));
                            }
                        } while (c.moveToNext() && layout.getChildCount() < 21);
                    }
                } catch (Exception e) {
                    log(Log.WARN, TAG, "clipboard db: " + e);
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

            // 弹窗尺寸：宽度 200dp，高度上限 200dp
            int clipW_DP = 200;
            int kbHeight2 = 0;
            try {
                Method getKv = inputView.getClass().getMethod("getKeyboardView");
                View kv = (View) getKv.invoke(inputView);
                kbHeight2 = kv.getHeight();
            } catch (Exception ignored) {}
            int clipH = Math.min(kbHeight2 <= 0 ? 200 : kbHeight2 - 32, 200);
            if (clipH < 120) clipH = 120;

            android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
            sv.addView(layout, new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

            android.widget.FrameLayout outer = new android.widget.FrameLayout(ctx);
            GradientDrawable outerBg = new GradientDrawable();
            outerBg.setShape(GradientDrawable.RECTANGLE);
            outerBg.setColor(bgColor); outerBg.setCornerRadius(corner);
            outer.setBackground(outerBg);
            outer.setClipToOutline(true);
            outer.setPadding(0, 0, 0, corner);
            outer.addView(sv, new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));

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
            // 上方放不下 → 放在按钮下方
            if (popY < dp8) {
                popY = loc[1] + (int)(40*den+.5f) + dp8;
            }
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, popX, popY);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Clipboard popup: " + t);
        }
    }

    /** 获取 IME 窗口的 Window Token（用于 switchToInputMethod） */
    private android.os.IBinder getWindowToken(Object svc) throws Exception {
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

    // ══════════════════════════════════════════
    //  按键 + 弹窗 半透明（磨砂效果）
    // ══════════════════════════════════════════

    private void applyKeyEffects(View inputView) {
        try {
            Field wf = inputView.getClass().getDeclaredField("windowManager");
            wf.setAccessible(true);
            Object wm = wf.get(inputView);
            Method gv = wm.getClass().getMethod("getView");
            final ViewGroup wmView = (ViewGroup) gv.invoke(wm);
            if (wmView == null) return;

            int keyAlpha = Math.min(cfgAlpha + 80, 200);

            // 1. 对当前已有的按键做透明度
            makeKeysTranslucent(wmView, keyAlpha);

            // 2. 监听键盘布局切换 — 每次布局变化自动重应用
            wmView.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        makeKeysTranslucent(wmView, keyAlpha);
                    }
                }
            );

            // 3. Popup 悬浮窗背景半透明
            try {
                Field pf = inputView.getClass().getDeclaredField("popup");
                pf.setAccessible(true);
                final Object popupComponent = pf.get(inputView);
                Method getRoot = popupComponent.getClass().getMethod("getRoot");
                final ViewGroup popupRoot = (ViewGroup) getRoot.invoke(popupComponent);
                if (popupRoot != null) {
                    // 全局布局监听：每次弹窗布局变化时重应用透明度
                    popupRoot.getViewTreeObserver().addOnGlobalLayoutListener(
                        new ViewTreeObserver.OnGlobalLayoutListener() {
                            @Override
                            public void onGlobalLayout() {
                                makePopupTranslucent(popupRoot);
                            }
                        }
                    );
                    // 首次应用
                    makePopupTranslucent(popupRoot);
                }
            } catch (Exception e) {
                log(Log.WARN, TAG, "popup effects: " + e.getMessage());
            }

            log(Log.INFO, TAG, "key effects: alpha=" + keyAlpha);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "applyKeyEffects: " + t.getMessage());
        }
    }

    private void makeKeysTranslucent(ViewGroup root, int alpha) {
        if (alpha > 250) return;

        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            try {
                // 需要搜父类：KeyView.appearanceView 定义在抽象 KeyView 中
                View appView = findAppearanceView(child);
                if (appView != null) {
                    Drawable bg = appView.getBackground();
                    if (bg != null) {
                        // LayerDrawable 内含 GradientDrawable
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
                    // 额外：space/return 等特殊键在 onSizeChanged 设背景
                    // 它们的背景是 InsetDrawable 内含 GradientDrawable
                    tryAgainDeeper(appView, alpha);
                }
            } catch (Exception ignored) {}

            if (child instanceof ViewGroup) {
                makeKeysTranslucent((ViewGroup) child, alpha);
            }
        }
    }

    /** 搜 class 树找 appearanceView 字段 */
    private View findAppearanceView(View v) {
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

    /** 深度遍历 Drawable 树降 alpha */
    private void tryAgainDeeper(View v, int alpha) {
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

    private void makePopupTranslucent(ViewGroup root) {
        int pa = Math.min(cfgAlpha + 80, 180);
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

    // ── Broadcast receiver for re-apply signal from SettingsActivity ──

    private void registerReapplyReceiver(View v) {
        try {
            android.content.IntentFilter filter = new android.content.IntentFilter("com.rebron1900.fcitx5enhanced.UI_UPDATE");
            android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context, android.content.Intent intent) {
                    // Re-read config from ContentProvider (SettingsActivity wrote there)
                    readConfig(v);
                    v.post(() -> applyAllEffects(v));
                    log(Log.INFO, TAG, "UI_UPDATE: re-applied");
                }
            };
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.getContext().registerReceiver(r, filter, android.content.Context.RECEIVER_EXPORTED);
            } else {
                v.getContext().registerReceiver(r, filter);
            }
            receiverRegistered = true;
            log(Log.INFO, TAG, "re-apply receiver registered");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "re-apply receiver failed: " + t);
        }
    }

    // ══════════════════════════════════════════
    //  SVG 图标 — 用 AndroidSVG 解析真实 Lucide SVG XML
    // ══════════════════════════════════════════

    /** Lucide keyboard.svg — IME 切换图标 */
    private static final String IME_SVG =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"%s\" stroke-width=\"1.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
        "<rect x=\"2\" y=\"4\" width=\"20\" height=\"16\" rx=\"2\"/>" +
        "<path d=\"M6 8h.01\"/>" +
        "<path d=\"M10 8h.01\"/>" +
        "<path d=\"M14 8h.01\"/>" +
        "<path d=\"M18 8h.01\"/>" +
        "<path d=\"M8 12h.01\"/>" +
        "<path d=\"M12 12h.01\"/>" +
        "<path d=\"M16 12h.01\"/>" +
        "<path d=\"M7 16h10\"/>" +
        "</svg>";

    /** Lucide audio-lines.svg — 语音输入图标 */
    private static final String MIC_SVG =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"%s\" stroke-width=\"1.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
        "<path d=\"M2 20v-8\"/>" +
        "<path d=\"M6 20v-4\"/>" +
        "<path d=\"M10 20V4\"/>" +
        "<path d=\"M14 20V8\"/>" +
        "<path d=\"M18 20v-6\"/>" +
        "<path d=\"M22 20v-2\"/>" +
        "</svg>";

    /** Lucide clipboard-list.svg — 剪贴板图标 */
    private static final String CLIPBOARD_SVG =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"%s\" stroke-width=\"1.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
        "<rect x=\"8\" y=\"2\" width=\"8\" height=\"4\" rx=\"1\"/>" +
        "<path d=\"M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2\"/>" +
        "<path d=\"M12 11h4\"/>" +
        "<path d=\"M12 16h4\"/>" +
        "<path d=\"M8 11h.01\"/>" +
        "<path d=\"M8 16h.01\"/>" +
        "</svg>";

    /**
     * 用 AndroidSVG 解析 SVG XML，渲染到 Bitmap 后返回 BitmapDrawable
     * @param svgTemplate SVG XML 模板（%s 被替换为 stroke 色 hex）
     * @param density     DisplayMetrics.density
     * @param color       stroke 颜色（ARGB int）
     * @param sizeDp      输出尺寸 dp
     */
    private static Drawable svgToDrawable(String svgTemplate, float density, int color, int sizeDp) {
        try {
            // 用 rgba() 格式保留 alpha，原 #%06X 会截掉透明度
            int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
            float a = Color.alpha(color) / 255.0f;
            String strokeColor = String.format("rgba(%d,%d,%d,%.2f)", r, g, b, a);
            String svgXml = String.format(svgTemplate, strokeColor);

            SVG svg = SVG.getFromString(svgXml);
            int px = Math.max(1, (int) (sizeDp * density + 0.5f));

            // 直接以目标像素尺寸渲染
            Picture picture = svg.renderToPicture();
            Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            // drawPicture 支持 RectF 自动缩放
            c.drawPicture(picture, new RectF(0, 0, px, px));
            return new BitmapDrawable(null, bmp);
        } catch (Throwable t) {
            Log.w(TAG, "SVG error: " + t);
            return null;
        }
    }

    private static Drawable svgIme(float density, int color, int sizeDp) {
        return svgToDrawable(IME_SVG, density, color, sizeDp);
    }

    private static Drawable svgMic(float density, int color, int sizeDp) {
        return svgToDrawable(MIC_SVG, density, color, sizeDp);
    }

    private static Drawable svgClipboard(float density, int color, int sizeDp) {
        return svgToDrawable(CLIPBOARD_SVG, density, color, sizeDp);
    }

    private void addPrefToScreen(Object screen, Object fragment, ClassLoader cl) {
        try {
            // 查重
            Method getCnt = screen.getClass().getMethod("getPreferenceCount");
            Method getPref = screen.getClass().getMethod("getPreference", int.class);
            int cnt = (int) getCnt.invoke(screen);
            for (int i = 0; i < cnt; i++) {
                Object p = getPref.invoke(screen, i);
                String key = (String) p.getClass().getMethod("getKey").invoke(p);
                if ("fcitx5_enhanced_entry".equals(key)) return;
            }
            // 找到最后一个 PreferenceCategory（通常是"Android"）
            Object cat = screen;
            for (int i = 0; i < cnt; i++) {
                Object p = getPref.invoke(screen, i);
                if (p.getClass().getName().contains("PreferenceCategory")) cat = p;
            }
            Context ctx = (Context) fragment.getClass().getMethod("getContext").invoke(fragment);
            Object pref = Class.forName("androidx.preference.Preference", false, cl)
                    .getConstructor(Context.class).newInstance(ctx);
            pref.getClass().getMethod("setTitle", CharSequence.class)
                    .invoke(pref, "小企鹅增强");
            pref.getClass().getMethod("setSummary", CharSequence.class)
                    .invoke(pref, "毛玻璃、圆角、底部按钮配置");
            pref.getClass().getMethod("setKey", String.class)
                    .invoke(pref, "fcitx5_enhanced_entry");

            java.lang.reflect.Proxy onClk = (java.lang.reflect.Proxy) java.lang.reflect.Proxy.newProxyInstance(
                    cl,
                    new Class[]{Class.forName("androidx.preference.Preference$OnPreferenceClickListener", false, cl)},
                    (proxy, method, args) -> {
                        Intent intent = new Intent(ctx, SettingsActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(intent);
                        return true;
                    });
            pref.getClass().getMethod("setOnPreferenceClickListener",
                    Class.forName("androidx.preference.Preference$OnPreferenceClickListener", false, cl))
                    .invoke(pref, onClk);
            cat.getClass().getMethod("addPreference",
                    Class.forName("androidx.preference.Preference", false, cl))
                    .invoke(cat, pref);
            log(Log.INFO, TAG, "✅ settings entry added to Android category");
        } catch (Throwable e) {
            log(Log.WARN, TAG, "addPrefToScreen failed: " + e);
        }
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam p) {
        log(Log.INFO, TAG, "loaded in " + p.getProcessName());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam p) {
        if (PKG.equals(p.getPackageName())) {
            log(Log.INFO, TAG, "pkg_loaded " + p.getPackageName());
        }
    }
}
