package com.rebron1900.fcitx5enhanced;

import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

/** Fcitx5 Frosted Glass — 毛玻璃键盘美观插件入口。 */
public class MainHook extends XposedModule {

    private static final String TAG = "Fcitx5Enh";
    private static final String PKG = "org.fcitx.fcitx5.android.fx";
    private static final String CLS_SVC = "org.fcitx.fcitx5.android.input.FcitxInputMethodService";
    private static final String CLS_IV = "org.fcitx.fcitx5.android.input.InputView";

    /** 配置快照，传递给各 Helper */
    public static class Config {
        public int blur = 100;
        public int alpha = 60;
        public int corner = 20;
        public int toolbar = 20;
        public boolean voice = true;
        public boolean leftBtn = true;
        public boolean rightBtn = true;
        public boolean keyBorder = true;
    }

    private Config cfg = new Config();
    private boolean receiverRegistered;
    private android.content.BroadcastReceiver mReceiver;
    private View mCurrentInputView;
    private android.content.SharedPreferences.OnSharedPreferenceChangeListener mThemePrefListener;
    private boolean mConfigObserved;

    // ══════════════════════════════════════════
    //  Hook 入口
    // ══════════════════════════════════════════

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!PKG.equals(param.getPackageName())) return;
        Log.i(TAG, "init");

        try {
            Class<?> svc = Class.forName(CLS_SVC, true, param.getClassLoader());
            Method setIv = svc.getMethod("setInputView", View.class);

            hook(setIv).intercept(chain -> {
                View v = (View) chain.getArgs().get(0);
                chain.proceed();

                if (v != null && CLS_IV.equals(v.getClass().getName())) {
                    readConfig(v);
                    if (!receiverRegistered) registerReapplyReceiver(v);
                    registerThemePrefListener(v);
                    registerConfigObserver(v);
                    mCurrentInputView = v;
                    View fv = v;
                    v.post(() -> applyAllEffects(fv));
                }
                return null;
            });

            Log.i(TAG, "hook installed");
        } catch (Throwable t) {
            Log.e(TAG, "hook failed", t);
        }
    }

    // ══════════════════════════════════════════
    //  Config
    // ══════════════════════════════════════════

    private void readConfig(View anyView) {
        try {
            SharedPreferences sp = anyView.getContext()
                    .getSharedPreferences("fcitx5_enhanced_config", android.content.Context.MODE_PRIVATE);
            cfg.blur = sp.getInt("blur_radius", 100);
            cfg.alpha = sp.getInt("bg_alpha", 60);
            cfg.corner = sp.getInt("corner_radius", 20);
            cfg.toolbar = cfg.corner;
            cfg.voice = sp.getBoolean("voice_enabled", true);
            cfg.leftBtn = sp.getBoolean("show_left_button", true);
            cfg.rightBtn = sp.getBoolean("show_right_button", true);
            cfg.keyBorder = sp.getBoolean("key_border", true);
            Log.i(TAG, "read from fcitx5 SP: L=" + cfg.leftBtn + " R=" + cfg.rightBtn + " V=" + cfg.voice + " K=" + cfg.keyBorder);
        } catch (Throwable t) {
            Log.w(TAG, "readConfig SP failed: " + t);
            cfg = new Config();
        }
    }

    // ══════════════════════════════════════════
    //  Apply all visual effects
    // ══════════════════════════════════════════

    private void applyAllEffects(View inputView) {
        readConfig(inputView);
        Log.i(TAG, "applyAllEffects start");

        // 检测暗色主题
        boolean isDark = false;
        try {
            java.lang.reflect.Field tf = inputView.getClass().getSuperclass()
                    .getDeclaredField("theme");
            tf.setAccessible(true);
            Object theme = tf.get(inputView);
            java.lang.reflect.Method isDarkM = theme.getClass().getMethod("isDark");
            isDark = (Boolean) isDarkM.invoke(theme);
        } catch (Exception ignored) {}

        FrostedGlassHelper.apply(inputView, cfg);
        roundToolbarTop(inputView);
        PreeditHelper.apply(inputView, cfg);
        ExtraButtonsHelper.add(inputView, cfg);
        KeyEffectsHelper.apply(inputView, cfg, isDark);

        Log.i(TAG, "applyAllEffects done");
    }

    // ══════════════════════════════════════════
    //  工具栏圆角
    // ══════════════════════════════════════════

    private void roundToolbarTop(View inputView) {
        try {
            if (cfg.toolbar <= 0) return;

            Field f = inputView.getClass().getDeclaredField("kawaiiBar");
            f.setAccessible(true);
            Object bar = f.get(inputView);
            Method gv = bar.getClass().getMethod("getView");
            View toolbar = (View) gv.invoke(bar);

            roundToolbarTopWithRetry(inputView, toolbar, 0);
        } catch (Throwable t) {
            Log.w(TAG, "toolbar round failed: " + t);
        }
    }

    private void roundToolbarTopWithRetry(View inputView, View toolbar, int attempt) {
        try {
            if (attempt > 5) {
                Log.w(TAG, "toolbar retry exhausted, skip");
                return;
            }
            if (toolbar.getWidth() <= 0 || toolbar.getHeight() <= 0) {
                toolbar.post(() -> roundToolbarTopWithRetry(inputView, toolbar, attempt + 1));
                return;
            }

            float R = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, cfg.toolbar,
                    inputView.getResources().getDisplayMetrics());

            GradientDrawable gd = new GradientDrawable();
            gd.setCornerRadii(new float[]{R, R, R, R, 0, 0, 0, 0});
            gd.setColor(Color.TRANSPARENT);
            toolbar.setBackground(gd);

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

            Log.i(TAG, "toolbar round r=" + cfg.toolbar + "dp");
        } catch (Throwable t) {
            Log.w(TAG, "toolbar round failed: " + t);
        }
    }

    // ══════════════════════════════════════════
    //  Broadcast receiver for re-apply
    // ══════════════════════════════════════════

    private void registerReapplyReceiver(View v) {
        try {
            android.content.IntentFilter filter = new android.content.IntentFilter("com.rebron1900.fcitx5enhanced.UI_UPDATE");
            android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context, android.content.Intent intent) {
                    if (intent.hasExtra("show_left_button")) {
                        boolean bL = intent.getBooleanExtra("show_left_button", true);
                        boolean bR = intent.getBooleanExtra("show_right_button", true);
                        boolean bV = intent.getBooleanExtra("voice_enabled", true);
                        int bB = intent.getIntExtra("blur_radius", 100);
                        int bA = intent.getIntExtra("bg_alpha", 60);
                        int bC = intent.getIntExtra("corner_radius", 20);
                        boolean bK = intent.getBooleanExtra("key_border", true);
                        Log.i(TAG, "BROADCAST payload: L=" + bL + " R=" + bR + " V=" + bV + " K=" + bK);

                        try {
                            SharedPreferences sp = context
                                    .getSharedPreferences("fcitx5_enhanced_config", android.content.Context.MODE_PRIVATE);
                            sp.edit()
                                .putBoolean("show_left_button", bL)
                                .putBoolean("show_right_button", bR)
                                .putBoolean("voice_enabled", bV)
                                .putInt("blur_radius", bB)
                                .putInt("bg_alpha", bA)
                                .putInt("corner_radius", bC)
                                .putBoolean("key_border", bK)
                                .commit();
                        } catch (Throwable t) {
                            Log.w(TAG, "save to fcitx5 SP failed: " + t);
                        }

                        cfg.leftBtn = bL; cfg.rightBtn = bR; cfg.voice = bV;
                        cfg.blur = bB; cfg.alpha = bA; cfg.corner = bC; cfg.toolbar = bC; cfg.keyBorder = bK;
                        View curView = mCurrentInputView;
                        if (curView != null) curView.post(() -> applyAllEffects(curView));
                    } else {
                        Log.w(TAG, "BROADCAST without extras, reading from SP");
                        readConfig(v);
                        View curView2 = mCurrentInputView;
                        if (curView2 != null) curView2.post(() -> applyAllEffects(curView2));
                    }
                }
            };
            mReceiver = r;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.getContext().registerReceiver(r, filter, android.content.Context.RECEIVER_EXPORTED);
            } else {
                v.getContext().registerReceiver(r, filter);
            }
            v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) {}
                @Override public void onViewDetachedFromWindow(View v) {
                    try { v.getContext().unregisterReceiver(mReceiver); } catch (Exception ignored) {}
                    receiverRegistered = false;
                    mReceiver = null;
                    v.removeOnAttachStateChangeListener(this);
                }
            });
            receiverRegistered = true;
            Log.i(TAG, "re-apply receiver registered");
        } catch (Throwable t) {
            Log.w(TAG, "re-apply receiver failed: " + t);
        }
    }

    // ══════════════════════════════════════════
    //  Theme preference change listener
    // ══════════════════════════════════════════

    /** 监听 fcitx5 主题配置变化（key_radius 等），自动重绘按键描边。 */
    private void registerThemePrefListener(View anyView) {
        try {
            if (mThemePrefListener != null) return; // 只注册一次
            android.content.SharedPreferences sp =
                android.preference.PreferenceManager.getDefaultSharedPreferences(
                    anyView.getContext());
            mThemePrefListener = (sp_, key) -> {
                try {
                    if ("key_radius".equals(key) || "special_key_oval_shape".equals(key)) {
                        Log.i(TAG, key + " changed, re-applying key borders");
                        View cv = mCurrentInputView;
                        if (cv != null) cv.post(() -> {
                            readConfig(cv);
                            // 只重载按键描边，不必全量 apply
                            boolean isDark = false;
                            try {
                                java.lang.reflect.Field tf = cv.getClass().getSuperclass()
                                        .getDeclaredField("theme");
                                tf.setAccessible(true);
                                Object theme = tf.get(cv);
                                isDark = (Boolean) theme.getClass().getMethod("isDark").invoke(theme);
                            } catch (Exception ignored) {}
                            KeyEffectsHelper.apply(cv, cfg, isDark);
                        });
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "theme pref listener: " + t.getMessage());
                }
            };
            sp.registerOnSharedPreferenceChangeListener(mThemePrefListener);
            Log.i(TAG, "theme pref listener registered");
        } catch (Throwable t) {
            Log.w(TAG, "register theme pref listener failed: " + t);
        }
    }

    // ══════════════════════════════════════════
    //  ConfigProvider ContentObserver
    // ══════════════════════════════════════════

    /** 监听 ConfigProvider 变化（SettingsActivity 写入时触发）。 */
    private void registerConfigObserver(View anyView) {
        if (mConfigObserved) return;
        try {
            android.net.Uri uri = android.net.Uri.parse("content://com.rebron1900.fcitx5enhanced.config");
            anyView.getContext().getContentResolver().registerContentObserver(
                    uri, false, new android.database.ContentObserver(null) {
                        @Override
                        public void onChange(boolean selfChange) {
                            View cv = mCurrentInputView;
                            if (cv != null) reapplyFromProvider(cv);
                        }
                    });
            mConfigObserved = true;
            Log.i(TAG, "config observer registered");
        } catch (Throwable t) {
            Log.w(TAG, "config observer failed: " + t);
        }
    }

    /** 从 ConfigProvider 读取配置并应用。 */
    private void reapplyFromProvider(View anyView) {
        try {
            android.net.Uri uri = android.net.Uri.parse("content://com.rebron1900.fcitx5enhanced.config");
            android.database.Cursor c = anyView.getContext()
                    .getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                try {
                    cfg.leftBtn = c.getInt(c.getColumnIndexOrThrow("show_left_button")) != 0;
                    cfg.rightBtn = c.getInt(c.getColumnIndexOrThrow("show_right_button")) != 0;
                    cfg.voice = c.getInt(c.getColumnIndexOrThrow("voice_enabled")) != 0;
                    cfg.keyBorder = c.getInt(c.getColumnIndexOrThrow("key_border")) != 0;
                    cfg.blur = c.getInt(c.getColumnIndexOrThrow("blur_radius"));
                    cfg.alpha = c.getInt(c.getColumnIndexOrThrow("bg_alpha"));
                    cfg.corner = c.getInt(c.getColumnIndexOrThrow("corner_radius"));
                    cfg.toolbar = cfg.corner;
                    Log.i(TAG, "reapply from provider: keyBorder=" + cfg.keyBorder);
                } finally {
                    c.close();
                }
            }
            View cv = mCurrentInputView;
            if (cv != null) cv.post(() -> applyAllEffects(cv));
        } catch (Throwable t) {
            Log.w(TAG, "reapplyFromProvider failed: " + t);
        }
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam p) {
        Log.i(TAG, "loaded in " + p.getProcessName());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam p) {
        if (PKG.equals(p.getPackageName())) {
            Log.i(TAG, "pkg_loaded " + p.getPackageName());
        }
    }
}
