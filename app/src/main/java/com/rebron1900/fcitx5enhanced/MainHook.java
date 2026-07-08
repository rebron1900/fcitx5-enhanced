package com.rebron1900.fcitx5enhanced;

import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

/** Fcitx5 Frosted Glass — 毛玻璃键盘美观插件入口。 */
public class MainHook extends XposedModule {

    private static final String TAG = "Fcitx5Enh";
    private static final String PKG_FX = "org.fcitx.fcitx5.android.fx";
    private static final String PKG_ORIGINAL = "org.fcitx.fcitx5.android";
    private static final String CLS_SVC = "org.fcitx.fcitx5.android.input.FcitxInputMethodService";
    private static final String CLS_IV = "org.fcitx.fcitx5.android.input.InputView";

    /** 当前运行的包名（用于判断是原版还是靓企鹅） */
    private String mRunningPkg;

    /** 主题信息快照，避免各 Helper 重复反射读 theme */
    public static class ThemeInfo {
        public boolean isDark;
        public int keyBgColor;
        public int barColor;
        public int accentColor;
        public int altKeyTextColor;
    }

    private WeakReference<View> mCurrentInputViewRef;
    private boolean receiverRegistered;
    private android.content.BroadcastReceiver mReceiver;
    private SharedPreferences.OnSharedPreferenceChangeListener mThemePrefListener;
    private android.database.ContentObserver mConfigObserver;
    private boolean mConfigObserved;

    private View getCurrentInputView() {
        return mCurrentInputViewRef != null ? mCurrentInputViewRef.get() : null;
    }

    // ══════════════════════════════════════════
    //  Hook 入口
    // ══════════════════════════════════════════

    @Override
    public void onPackageReady(PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (!PKG_FX.equals(pkg) && !PKG_ORIGINAL.equals(pkg)) return;
        mRunningPkg = pkg;
        Log.i(TAG, "init pkg=" + pkg);

        try {
            Class<?> svc = Class.forName(CLS_SVC, true, param.getClassLoader());
            Method setIv = svc.getMethod("setInputView", View.class);

            hook(setIv).intercept(chain -> {
                View v = (View) chain.getArgs().get(0);
                chain.proceed();

                String viewName = v != null ? v.getClass().getName() : "null";
                Log.i(TAG, "setInputView view=" + viewName);
                if (v != null && CLS_IV.equals(v.getClass().getName())) {
                    View oldView = getCurrentInputView();
                    if (v != oldView) {
                        ConfigManager.resetForView(v);
                    }
                    mCurrentInputViewRef = new WeakReference<>(v);
                    if (!receiverRegistered) registerReapplyReceiver(v);
                    registerThemePrefListener(v);
                    registerConfigObserver(v);
                    // 用 LayoutChangeListener 确保 view 已 layout 完再 apply
                    // post() 可能在 layout 之前执行，导致磨砂玻璃等效果失效
                    v.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                        @Override
                        public void onLayoutChange(View v, int l, int t, int r, int b,
                                                   int ol, int ot, int or, int ob) {
                            v.removeOnLayoutChangeListener(this);
                            v.post(() -> applyAllEffects(v));
                        }
                    });
                }
                return null;
            });

            // 键盘弹出时检查是否需要应用效果 + 检查定时同步
            Method onWindowShown = svc.getMethod("onWindowShown");
            hook(onWindowShown).intercept(chain -> {
                chain.proceed();
                View cv = getCurrentInputView();
                if (cv != null) {
                    cv.post(() -> {
                        if (ConfigManager.shouldApply(cv)) {
                            applyAllEffects(cv);
                        }
                    });
                    checkAndRunSync(cv.getContext());
                }
                return null;
            });

            Log.i(TAG, "hook installed");
        } catch (Throwable t) {
            Log.e(TAG, "hook failed", t);
        }
    }

    // ══════════════════════════════════════════
    //  Apply all visual effects
    // ══════════════════════════════════════════

    private void applyAllEffects(View inputView) {
        // ConfigManager.shouldApply 已在调用处检查，此处不需要再检查
        ConfigManager.Config cfg = ConfigManager.read(inputView.getContext());

        Log.i(TAG, "applyAllEffects start");

        // 一次性提取主题信息，避免各 Helper 重复反射
        ThemeInfo themeInfo = new ThemeInfo();
        try {
            Field tf = inputView.getClass().getSuperclass()
                    .getDeclaredField("theme");
            tf.setAccessible(true);
            Object theme = tf.get(inputView);
            themeInfo.isDark = (Boolean) theme.getClass().getMethod("isDark").invoke(theme);
            themeInfo.keyBgColor = (Integer) theme.getClass().getMethod("getKeyBackgroundColor").invoke(theme);
            themeInfo.barColor = (Integer) theme.getClass().getMethod("getBarColor").invoke(theme);
            themeInfo.accentColor = (Integer) theme.getClass().getMethod("getAccentKeyBackgroundColor").invoke(theme);
            themeInfo.altKeyTextColor = (Integer) theme.getClass().getMethod("getAltKeyTextColor").invoke(theme);
        } catch (Exception ignored) {}

        // 同一份 cfg + themeInfo 传给所有 Helper，避免重复读 SP/file 和反射
        final ConfigManager.Config c = cfg;
        final MainHook.ThemeInfo ti = themeInfo;

        FrostedGlassHelper.apply(inputView, c, ti);
        roundToolbarTop(inputView, cfg);
        PreeditHelper.apply(inputView, c, ti);
        ExtraButtonsHelper.add(inputView, c, ti);
        KeyEffectsHelper.apply(inputView, c, ti.isDark);

        Log.i(TAG, "applyAllEffects done");
    }

    // ══════════════════════════════════════════
    //  工具栏圆角
    // ══════════════════════════════════════════

    private void roundToolbarTop(View inputView, ConfigManager.Config cfg) {
        try {
            if (cfg.toolbar <= 0) return;

            // 尝试多个字段名：靓企鹅用 kawaiiBar，原版可能用不同名字
            Object bar = null;
            for (String fieldName : new String[]{"kawaiiBar", "toolbarBar", "toolbar"}) {
                try {
                    Field f = inputView.getClass().getDeclaredField(fieldName);
                    f.setAccessible(true);
                    bar = f.get(inputView);
                    if (bar != null) break;
                } catch (NoSuchFieldException ignored) {}
            }
            if (bar == null) {
                Log.w(TAG, "toolbar bar field not found, skip");
                return;
            }
            Method gv = bar.getClass().getMethod("getView");
            View toolbar = (View) gv.invoke(bar);

            roundToolbarTopWithRetry(inputView, toolbar, 0, cfg);
        } catch (Throwable t) {
            Log.w(TAG, "toolbar round failed: " + t);
        }
    }

    private void roundToolbarTopWithRetry(View inputView, View toolbar, int attempt, ConfigManager.Config cfg) {
        try {
            if (attempt > 5) {
                Log.w(TAG, "toolbar retry exhausted, skip");
                return;
            }
            if (toolbar.getWidth() <= 0 || toolbar.getHeight() <= 0) {
                toolbar.post(() -> roundToolbarTopWithRetry(inputView, toolbar, attempt + 1, cfg));
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
                        int bKA = intent.getIntExtra("key_alpha", 140);
                        int bC = intent.getIntExtra("corner_radius", 20);
                        boolean bK = intent.getBooleanExtra("key_border", true);
                        Log.i(TAG, "BROADCAST payload: L=" + bL + " R=" + bR + " V=" + bV + " K=" + bK);

                        ConfigManager.write(context, bB, bA, bKA, bC,
                                bV, bL, bR, bK);
                        View curView = getCurrentInputView();
                        if (curView != null) curView.post(() -> applyAllEffects(curView));
                    } else {
                        Log.w(TAG, "BROADCAST without extras, reading from SP");
                        View curView2 = getCurrentInputView();
                        if (curView2 != null) curView2.post(() -> {
                            if (ConfigManager.shouldApply(curView2)) {
                                applyAllEffects(curView2);
                            }
                        });
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
                    cleanup();
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
            SharedPreferences sp =
                android.preference.PreferenceManager.getDefaultSharedPreferences(
                    anyView.getContext());
            mThemePrefListener = (sp_, key) -> {
                try {
                    if ("key_radius".equals(key) || "special_key_oval_shape".equals(key)) {
                        Log.i(TAG, key + " changed, re-applying key borders");
                        View cv = getCurrentInputView();
                        if (cv != null) cv.post(() -> {
                            ConfigManager.Config cfg = ConfigManager.read(cv.getContext());
                            // 只重载按键描边，不必全量 apply
                            boolean isDark = false;
                            try {
                                Field tf = cv.getClass().getSuperclass()
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
            mConfigObserver = new android.database.ContentObserver(null) {
                @Override
                public void onChange(boolean selfChange) {
                    ConfigManager.bumpVersion();
                    View cv = getCurrentInputView();
                    if (cv != null) cv.post(() -> applyAllEffects(cv));
                }
            };
            anyView.getContext().getContentResolver().registerContentObserver(
                    uri, false, mConfigObserver);
            mConfigObserved = true;
            Log.i(TAG, "config observer registered");
        } catch (Throwable t) {
            Log.w(TAG, "config observer failed: " + t);
        }
    }

    // ══════════════════════════════════════════
    //  Cleanup
    // ══════════════════════════════════════════

    private void cleanup() {
        try {
            if (mReceiver != null) {
                View cv = getCurrentInputView();
                if (cv != null) {
                    cv.getContext().unregisterReceiver(mReceiver);
                }
                mReceiver = null;
            }
        } catch (Exception ignored) {}
        try {
            if (mConfigObserver != null) {
                View cv = getCurrentInputView();
                if (cv != null) {
                    cv.getContext().getContentResolver().unregisterContentObserver(mConfigObserver);
                }
                mConfigObserver = null;
            }
        } catch (Exception ignored) {}
        try {
            if (mThemePrefListener != null) {
                View cv = getCurrentInputView();
                if (cv != null) {
                    SharedPreferences sp = android.preference.PreferenceManager.getDefaultSharedPreferences(cv.getContext());
                    sp.unregisterOnSharedPreferenceChangeListener(mThemePrefListener);
                }
                mThemePrefListener = null;
            }
        } catch (Exception ignored) {}
        receiverRegistered = false;
        mConfigObserved = false;
        mCurrentInputViewRef = null;
    }

    // ══════════════════════════════════════════
    //  同步检查（键盘弹出时触发）
    // ══════════════════════════════════════════

    private static long sLastSyncCheckTime = 0;

    private void checkAndRunSync(android.content.Context ctx) {
        try {
            if (!ConfigStorage.isWebDavEnabled(ctx)) {
                Log.d(TAG, "sync: webdav disabled");
                return;
            }

            long now = System.currentTimeMillis();
            int intervalMs = ConfigStorage.getSyncInterval(ctx) * 60 * 1000;

            if (now - sLastSyncCheckTime < intervalMs) {
                Log.d(TAG, "sync: interval not reached, skip");
                return;
            }
            sLastSyncCheckTime = now;

            java.io.File syncDir = ConfigStorage.getRimeSyncDir(ctx);
            if (!syncDir.exists()) {
                Log.w(TAG, "sync: dir not exist: " + syncDir.getAbsolutePath());
                return;
            }

            Log.i(TAG, "sync triggered");
            new Thread(() -> {
                com.rebron1900.fcitx5enhanced.sync.SyncManager.runSyncOnce(ctx);
                String result = com.rebron1900.fcitx5enhanced.ConfigStorage.getLastSyncResult(ctx);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    android.widget.Toast.makeText(ctx, result, android.widget.Toast.LENGTH_SHORT).show();
                });
            }, "rime-webdav-sync").start();
        } catch (Exception e) {
            Log.w(TAG, "checkAndRunSync failed: " + e);
        }
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam p) {
        Log.i(TAG, "loaded in " + p.getProcessName());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam p) {
        String pkg = p.getPackageName();
        if (PKG_FX.equals(pkg) || PKG_ORIGINAL.equals(pkg)) {
            Log.i(TAG, "pkg_loaded " + pkg);
        }
    }
}
