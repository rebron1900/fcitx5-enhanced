package com.rebron1900.fcitx5enhanced;

import android.app.BroadcastOptions;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
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
    private static final String PKG_FX = "org.fcitx.fcitx5.android.fx";
    private static final String PKG_ORIGINAL = "org.fcitx.fcitx5.android";
    private static final String CLS_SVC = "org.fcitx.fcitx5.android.input.FcitxInputMethodService";
    /** 配置快照，传递给各 Helper */
    public static class Config {
        public int blur = 100;
        public int alpha = 60;
        public int keyAlpha = 140;  // 按键背景透明度（独立于键盘背景）
        public int corner = 20;
        public int buttonBottomMargin = 26;
        public int toolbar = 20;
        public boolean voice = true;
        public boolean leftBtn = true;
        public boolean rightBtn = true;
        public boolean keyBorder = true;
        /** Provider revision；旧版 Provider 缺少该列时保持默认 0。 */
        public long revision = ConfigContract.DEFAULT_REVISION;

        /** 快速比较配置是否相等（避免不必要的全量重绘） */
        public boolean equals(Config o) {
            return o != null
                && blur == o.blur && alpha == o.alpha && keyAlpha == o.keyAlpha
                && corner == o.corner && buttonBottomMargin == o.buttonBottomMargin && keyBorder == o.keyBorder
                && leftBtn == o.leftBtn && rightBtn == o.rightBtn && voice == o.voice;
        }
    }

    /** 主题信息快照，避免各 Helper 重复反射读 theme */
    public static class ThemeInfo {
        public boolean isDark;
        public int keyBgColor;
        public int barColor;
        public int accentColor;
        public int altKeyTextColor;
    }

    private Config cfg = new Config();
    private java.lang.ref.WeakReference<View> mCurrentInputViewRef;
    private android.content.SharedPreferences.OnSharedPreferenceChangeListener mThemePrefListener;
    private SharedPreferences mThemePreferences;
    private boolean mConfigObserved;
    private ContentResolver mConfigResolver;
    private boolean mLastConfigReadFromProvider;
    private boolean mHasTrustedConfig;
    private boolean mConfigRequestPending;
    private BroadcastReceiver mUserUnlockedReceiver;
    private Context mReceiverContext;
    private Runnable mConfigReadRetry;
    private int mConfigReadRetryAttempt;

    private static final long CONFIG_APPLY_COALESCE_MS = 50L;
    private static final long CONFIG_RETRY_INITIAL_MS = 250L;
    private static final long CONFIG_RETRY_MAX_MS = 5000L;
    private static final long EFFECTS_RETRY_DELAY_MS = 250L;
    private static final int EFFECTS_RETRY_MAX_ATTEMPTS = 5;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private ContentObserver mConfigObserver;
    private Runnable mConfigObserverRetry;
    private Runnable mPendingConfigApply;
    private Runnable mEffectsRetry;
    private boolean mConfigObserverRetryScheduled;
    private int mConfigObserverRetryAttempt;
    private int mEffectsRetryAttempt;
    private int mLifecycleGeneration;
    private boolean mDestroyed;
    private long mLastAppliedRevision = Long.MIN_VALUE;

    private enum ApplyReason {
        INPUT_VIEW,
        WINDOW_SHOWN,
        CONFIG_CHANGED,
        CONFIG_RESPONSE,
        THEME_CHANGED
    }

    /** 上次全量应用过的 InputView（WeakReference 防泄漏；view 重建时必须重新应用） */
    private static java.lang.ref.WeakReference<View> sLastAppliedViewRef = null;

    /** 上次全量应用时的配置快照（用于跳过配置未变时的重复调用） */
    private static final Config sLastAppliedCfg = new Config();
    /** Provider 短暂不可用时保留最后一个可信快照，避免回退到目标进程旧 SP。 */
    private static volatile Config sLastProviderConfig;
    private static volatile boolean sLastConfigReadFromProvider;
    /** Provider 或旧版 SP 提供了真实配置；false 时不得把构造出的默认值应用到键盘。 */
    private static volatile boolean sLastConfigReadIsTrusted;

    private static final java.util.WeakHashMap<View,
            java.lang.ref.WeakReference<android.graphics.drawable.Drawable>>
            sToolbarOriginalBackgrounds = new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<View, ViewOutlineProvider>
            sToolbarOriginalOutlineProviders = new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<View, Boolean> sToolbarOriginalClipStates =
            new java.util.WeakHashMap<>();

    // ══════════════════════════════════════════
    //  Hook 入口
    // ══════════════════════════════════════════

    @Override
    public void onPackageReady(PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (!PKG_FX.equals(pkg) && !PKG_ORIGINAL.equals(pkg)) return;
        Log.i(TAG, "init pkg=" + pkg);

        try {
            Class<?> svc = Class.forName(CLS_SVC, true, param.getClassLoader());
            Method setIv = svc.getMethod("setInputView", View.class);

            hook(setIv).intercept(chain -> {
                View v = (View) chain.getArgs().get(0);
                chain.proceed();
                mDestroyed = false;
                final int generation = ++mLifecycleGeneration;

                String viewName = v != null ? v.getClass().getName() : "null";
                Log.i(TAG, "setInputView view=" + viewName);
                if (v != null) {
                    // setInputView 的参数就是目标输入法 View，不依赖被 R8 混淆的类名。
                    mCurrentInputViewRef = new java.lang.ref.WeakReference<>(v);
                    registerThemePrefListener(v);
                    registerConfigObserver(v);
                    registerUserUnlockedReceiver(v);
                    View fv = v;
                    if (fv.getWidth() > 0 && fv.getHeight() > 0) {
                        // 即使复用同一个 InputView，也强制重建内部效果，覆盖语言/主题切换。
                        fv.post(() -> {
                            if (!isCurrentGeneration(fv, generation)) return;
                            applyAllEffects(fv, ApplyReason.INPUT_VIEW);
                        });
                    } else {
                        // post() 可能早于首次 layout，等 layout 完成后再应用。
                        fv.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                            @Override
                            public void onLayoutChange(View v, int l, int t, int r, int b,
                                                       int ol, int ot, int or, int ob) {
                                v.removeOnLayoutChangeListener(this);
                                if (!isCurrentGeneration(fv, generation)) return;
                                v.post(() -> {
                                    if (!isCurrentGeneration(fv, generation)) return;
                                    applyAllEffects(fv, ApplyReason.INPUT_VIEW);
                                });
                            }
                        });
                    }
                }
                return null;
            });

            // 键盘弹出时重读配置 + 重绘
            Method onWindowShown = svc.getMethod("onWindowShown");
            hook(onWindowShown).intercept(chain -> {
                chain.proceed();
                View cv = getCurrentInputView();
                if (cv != null) {
                    // 每次显示时重新确保 Observer 存在，并主动读取最新 revision。
                    registerConfigObserver(cv);
                    final int generation = mLifecycleGeneration;
                    cv.post(() -> {
                        if (!isCurrentGeneration(cv, generation)) return;
                        applyAllEffects(cv, ApplyReason.WINDOW_SHOWN);
                    });
                }
                return null;
            });

            try {
                Method onDestroy = svc.getMethod("onDestroy");
                hook(onDestroy).intercept(chain -> {
                    try {
                        return chain.proceed();
                    } finally {
                        mDestroyed = true;
                        ++mLifecycleGeneration;
                        unregisterConfigObserver();
                        cancelEffectsRetry();
                        KeyEffectsHelper.clear();
                        mCurrentInputViewRef = null;
                    }
                });
            } catch (Throwable t) {
                Log.w(TAG, "onDestroy hook unavailable: " + t.getMessage());
            }

            Log.i(TAG, "hook installed");
        } catch (Throwable t) {
            Log.e(TAG, "hook failed", t);
        }
    }

    // ══════════════════════════════════════════
    //  Config — Provider 读取，旧版目标进程 SP 兜底
    // ══════════════════════════════════════════

    /** 显式广播请求不受目标输入法 Manifest 包可见性声明限制。 */
    private boolean readConfig(View anyView) {
        requestConfig(anyView.getContext());
        cfg = readConfigSync(anyView);
        mLastConfigReadFromProvider = sLastConfigReadFromProvider;
        mHasTrustedConfig = sLastConfigReadIsTrusted;
        if (mLastConfigReadFromProvider) cancelConfigReadRetry();
        return mHasTrustedConfig;
    }

    private void requestConfig(Context context) {
        if (mConfigRequestPending) return;
        mConfigRequestPending = true;
        Intent request = new Intent(ConfigRequestReceiver.ACTION_REQUEST);
        request.setComponent(new ComponentName(
                ConfigContract.MODULE_PACKAGE, ConfigRequestReceiver.class.getName()));
        try {
            Intent identityIntent = new Intent(ConfigRequestReceiver.ACTION_CALLER_IDENTITY)
                    .setPackage(context.getPackageName());
            PendingIntent callerIdentity = PendingIntent.getBroadcast(context, 0, identityIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            request.putExtra(ConfigRequestReceiver.EXTRA_CALLER_IDENTITY, callerIdentity);
            BroadcastReceiver resultReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    mConfigRequestPending = false;
                    Bundle result = getResultExtras(false);
                    Bundle values = result != null
                            ? result.getBundle(ConfigRequestReceiver.EXTRA_CONFIG) : null;
                    if (values == null) {
                        if (!mHasTrustedConfig) scheduleConfigReadRetry();
                        return;
                    }
                    cfg = configFromBundle(values);
                    mHasTrustedConfig = true;
                    mLastConfigReadFromProvider = true;
                    cancelConfigReadRetry();
                    Log.i(TAG, "readConfig from explicit broadcast: rev=" + cfg.revision);
                    View current = getCurrentInputView();
                    if (current != null) applyAllEffects(current, ApplyReason.CONFIG_RESPONSE);
                }
            };
            if (android.os.Build.VERSION.SDK_INT >= 35) {
                Bundle options = BroadcastOptions.makeBasic()
                        .setShareIdentityEnabled(true)
                        .toBundle();
                context.sendOrderedBroadcast(request, null, options, resultReceiver, mMainHandler,
                        android.app.Activity.RESULT_CANCELED, null, null);
            } else {
                context.sendOrderedBroadcast(request, null, resultReceiver, mMainHandler,
                        android.app.Activity.RESULT_CANCELED, null, null);
            }
        } catch (Throwable t) {
            mConfigRequestPending = false;
            Log.w(TAG, "config broadcast request failed: " + t.getMessage());
        }
    }

    private static Config configFromBundle(Bundle values) {
        Config config = new Config();
        config.revision = values.getLong(ConfigContract.REVISION, ConfigContract.DEFAULT_REVISION);
        config.blur = values.getInt(ConfigContract.BLUR_RADIUS, ConfigContract.DEFAULT_BLUR);
        config.alpha = values.getInt(ConfigContract.BG_ALPHA, ConfigContract.DEFAULT_ALPHA);
        config.keyAlpha = values.getInt(ConfigContract.KEY_ALPHA, ConfigContract.DEFAULT_KEY_ALPHA);
        config.corner = values.getInt(ConfigContract.CORNER_RADIUS, ConfigContract.DEFAULT_CORNER);
        config.buttonBottomMargin = values.getInt(ConfigContract.BUTTON_BOTTOM_MARGIN, ConfigContract.DEFAULT_BUTTON_BOTTOM_MARGIN);
        config.voice = values.getBoolean(ConfigContract.VOICE_ENABLED, ConfigContract.DEFAULT_VOICE);
        config.leftBtn = values.getBoolean(
                ConfigContract.SHOW_LEFT_BUTTON, ConfigContract.DEFAULT_LEFT_BUTTON);
        config.rightBtn = values.getBoolean(
                ConfigContract.SHOW_RIGHT_BUTTON, ConfigContract.DEFAULT_RIGHT_BUTTON);
        config.keyBorder = values.getBoolean(
                ConfigContract.KEY_BORDER, ConfigContract.DEFAULT_KEY_BORDER);
        ConfigContract.sanitize(config);
        return config;
    }

    /** 同步读取配置（static，供外部调用）。 */
    public static Config readConfigSync(View anyView) {
        sLastConfigReadFromProvider = false;
        sLastConfigReadIsTrusted = false;
        try (android.database.Cursor cursor = anyView.getContext().getContentResolver().query(
                ConfigContract.CONTENT_URI, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                Config config = ConfigContract.fromCursor(cursor);
                Log.i(TAG, "readConfig from provider: rev=" + config.revision
                        + " blur=" + config.blur + " alpha=" + config.alpha
                        + " L=" + config.leftBtn + " R=" + config.rightBtn);
                sLastProviderConfig = copyConfig(config);
                sLastConfigReadFromProvider = true;
                sLastConfigReadIsTrusted = true;
                return config;
            }
        } catch (Throwable t) {
            Log.w(TAG, "readConfig provider failed, using legacy SP: " + t.getMessage());
        }

        // Provider 已成功读取过后，目标进程 SP 可能只是旧版残留，不能覆盖可信快照。
        Config lastProviderConfig = sLastProviderConfig;
        if (lastProviderConfig != null) {
            Log.w(TAG, "provider unavailable, using last provider snapshot");
            sLastConfigReadIsTrusted = true;
            return copyConfig(lastProviderConfig);
        }

        // 仅作为旧版本升级兜底；新版本不再写入目标进程 SP。
        Config config = new Config();
        try {
            SharedPreferences sp = anyView.getContext().getSharedPreferences(
                    ConfigContract.PREFS_NAME, android.content.Context.MODE_PRIVATE);
            sLastConfigReadIsTrusted = sp.contains(ConfigContract.REVISION)
                    || sp.contains(ConfigContract.BLUR_RADIUS);
            config.blur = sp.getInt(ConfigContract.BLUR_RADIUS, ConfigContract.DEFAULT_BLUR);
            config.alpha = sp.getInt(ConfigContract.BG_ALPHA, ConfigContract.DEFAULT_ALPHA);
            config.keyAlpha = sp.getInt(ConfigContract.KEY_ALPHA, ConfigContract.DEFAULT_KEY_ALPHA);
            config.corner = sp.getInt(ConfigContract.CORNER_RADIUS, ConfigContract.DEFAULT_CORNER);
            config.buttonBottomMargin = sp.getInt(ConfigContract.BUTTON_BOTTOM_MARGIN, ConfigContract.DEFAULT_BUTTON_BOTTOM_MARGIN);
            config.toolbar = config.corner;
            config.voice = sp.getBoolean(ConfigContract.VOICE_ENABLED, ConfigContract.DEFAULT_VOICE);
            config.leftBtn = sp.getBoolean(ConfigContract.SHOW_LEFT_BUTTON, ConfigContract.DEFAULT_LEFT_BUTTON);
            config.rightBtn = sp.getBoolean(ConfigContract.SHOW_RIGHT_BUTTON, ConfigContract.DEFAULT_RIGHT_BUTTON);
            config.keyBorder = sp.getBoolean(ConfigContract.KEY_BORDER, ConfigContract.DEFAULT_KEY_BORDER);
        } catch (Throwable t) {
            Log.w(TAG, "readConfig fallback failed: " + t.getMessage());
        }
        ConfigContract.sanitize(config);
        return config;
    }

    private static Config copyConfig(Config source) {
        Config copy = new Config();
        copy.blur = source.blur;
        copy.alpha = source.alpha;
        copy.keyAlpha = source.keyAlpha;
        copy.corner = source.corner;
        copy.toolbar = source.toolbar;
        copy.voice = source.voice;
        copy.leftBtn = source.leftBtn;
        copy.rightBtn = source.rightBtn;
        copy.keyBorder = source.keyBorder;
        copy.revision = source.revision;
        return copy;
    }

    // ══════════════════════════════════════════
    //  Apply all visual effects
    // ══════════════════════════════════════════

    private void applyAllEffects(View inputView, ApplyReason reason) {
        // 主题自身变化不影响模块配置，避免每次主题回调都同步跨进程 query。
        if (reason != ApplyReason.THEME_CHANGED && reason != ApplyReason.CONFIG_RESPONSE) {
            readConfig(inputView);
        }
        if (!mHasTrustedConfig) {
            Log.w(TAG, "applyAllEffects deferred: no trusted config available");
            return;
        }

        // 窗口显示和配置通知都按 revision/内容去重；输入法 View/主题变化仍强制重应用。
        // 这样既能补偿 Observer 丢失，也不会因重复 notify 反复重建视觉对象。
        View lastView = sLastAppliedViewRef != null ? sLastAppliedViewRef.get() : null;
        if (lastView != inputView && mEffectsRetry != null) cancelEffectsRetry();
        boolean sameView = lastView == inputView;
        boolean sameConfig = sLastAppliedCfg.equals(cfg);
        boolean sameRevision = mLastAppliedRevision == cfg.revision;
        boolean configReadIsFresh = reason == ApplyReason.THEME_CHANGED
                || mLastConfigReadFromProvider;
        if ((reason == ApplyReason.WINDOW_SHOWN || reason == ApplyReason.CONFIG_CHANGED)
                && configReadIsFresh && sameView && sameConfig && sameRevision) {
            Log.d(TAG, "applyAllEffects: config revision unchanged, skip");
            return;
        }
        Log.i(TAG, "applyAllEffects start rev=" + cfg.revision + " reason=" + reason);

        // 一次性提取主题信息，避免各 Helper 重复反射
        ThemeInfo themeInfo = new ThemeInfo();
        try {
            Field tf = findField(inputView.getClass(), "theme");
            if (tf == null) throw new NoSuchFieldException("theme");
            tf.setAccessible(true);
            Object theme = tf.get(inputView);
            themeInfo.isDark = (Boolean) theme.getClass().getMethod("isDark").invoke(theme);
            themeInfo.keyBgColor = (Integer) theme.getClass().getMethod("getKeyBackgroundColor").invoke(theme);
            themeInfo.barColor = (Integer) theme.getClass().getMethod("getBarColor").invoke(theme);
            themeInfo.accentColor = (Integer) theme.getClass().getMethod("getAccentKeyBackgroundColor").invoke(theme);
            themeInfo.altKeyTextColor = (Integer) theme.getClass().getMethod("getAltKeyTextColor").invoke(theme);
        } catch (Exception ignored) {}

        // 同一份 cfg + themeInfo 传给所有 Helper，避免重复读 SP/file 和反射
        final MainHook.Config c = cfg;
        final MainHook.ThemeInfo ti = themeInfo;

        boolean frostedApplied = FrostedGlassHelper.apply(inputView, c, ti);
        roundToolbarTop(inputView);
        PreeditHelper.apply(inputView, c, ti);
        ExtraButtonsHelper.add(inputView, c, ti);
        boolean keyEffectsApplied = KeyEffectsHelper.apply(inputView, c, ti.isDark);

        if (!frostedApplied || !keyEffectsApplied) {
            Log.w(TAG, "applyAllEffects incomplete: frosted=" + frostedApplied
                    + " keyEffects=" + keyEffectsApplied);
            scheduleEffectsRetry(inputView);
            return;
        }

        // 只有主要 Helper 完整应用后才记录快照；初始化时 View 尚未就绪时会继续重试。
        sLastAppliedViewRef = new java.lang.ref.WeakReference<>(inputView);
        sLastAppliedCfg.blur = cfg.blur;
        sLastAppliedCfg.alpha = cfg.alpha;
        sLastAppliedCfg.keyAlpha = cfg.keyAlpha;
        sLastAppliedCfg.corner = cfg.corner;
        sLastAppliedCfg.toolbar = cfg.toolbar;
        sLastAppliedCfg.voice = cfg.voice;
        sLastAppliedCfg.leftBtn = cfg.leftBtn;
        sLastAppliedCfg.rightBtn = cfg.rightBtn;
        sLastAppliedCfg.keyBorder = cfg.keyBorder;
        mLastAppliedRevision = cfg.revision;
        cancelEffectsRetry();
        Log.i(TAG, "applyAllEffects done");
    }

    // ══════════════════════════════════════════
    //  工具栏圆角
    // ══════════════════════════════════════════

    private void roundToolbarTop(View inputView) {
        try {
            View toolbar = findToolbarView(inputView);
            if (toolbar == null) {
                Log.w(TAG, "toolbar bar field not found, skip");
                return;
            }
            if (cfg.toolbar <= 0) {
                clearToolbarRounding(toolbar);
                return;
            }
            roundToolbarTopWithRetry(inputView, toolbar, 0);
        } catch (Throwable t) {
            Log.w(TAG, "toolbar round failed: " + t);
        }
    }

    private static Field findField(Class<?> start, String name) {
        for (Class<?> cls = start; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            try {
                return cls.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    private View findToolbarView(View inputView) throws Exception {
        Object bar = null;
        for (Class<?> cls = inputView.getClass(); cls != null; cls = cls.getSuperclass()) {
            for (String fieldName : new String[]{"kawaiiBar", "toolbarBar", "toolbar"}) {
                try {
                    Field f = cls.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    bar = f.get(inputView);
                    if (bar != null) break;
                } catch (NoSuchFieldException ignored) {}
            }
            if (bar != null) break;
        }
        if (bar == null) return null;
        Method gv = bar.getClass().getMethod("getView");
        Object toolbar = gv.invoke(bar);
        return toolbar instanceof View ? (View) toolbar : null;
    }

    private void clearToolbarRounding(View toolbar) {
        Object tag = toolbar.getTag(R.id.tag_toolbar_original_background);
        Drawable taggedOriginal = tag instanceof Drawable ? (Drawable) tag : null;
        toolbar.setTag(R.id.tag_toolbar_original_background, null);
        java.lang.ref.WeakReference<Drawable> originalRef =
                sToolbarOriginalBackgrounds.containsKey(toolbar)
                        ? sToolbarOriginalBackgrounds.remove(toolbar) : null;
        if (taggedOriginal != null || originalRef != null) {
            toolbar.setBackground(taggedOriginal != null
                    ? taggedOriginal : originalRef.get());
        }

        ViewParent parent = toolbar.getParent();
        if (parent instanceof View) {
            View parentView = (View) parent;
            if (sToolbarOriginalClipStates.containsKey(parentView)) {
                Boolean originalClip = sToolbarOriginalClipStates.remove(parentView);
                parentView.setClipToOutline(Boolean.TRUE.equals(originalClip));
            }
            if (sToolbarOriginalOutlineProviders.containsKey(parentView)) {
                parentView.setOutlineProvider(sToolbarOriginalOutlineProviders.remove(parentView));
            }
        }
        Log.i(TAG, "toolbar corners disabled");
    }

    private void roundToolbarTopWithRetry(View inputView, View toolbar, int attempt) {
        try {
            if (mDestroyed || inputView != getCurrentInputView()) return;
            if (cfg.toolbar <= 0) {
                clearToolbarRounding(toolbar);
                return;
            }
            if (attempt > 5) {
                Log.w(TAG, "toolbar retry exhausted, skip");
                return;
            }
            if (toolbar.getWidth() <= 0 || toolbar.getHeight() <= 0) {
                toolbar.post(() -> roundToolbarTopWithRetry(inputView, toolbar, attempt + 1));
                return;
            }

            float radiusPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, cfg.toolbar,
                    inputView.getResources().getDisplayMetrics());

            GradientDrawable gd = new GradientDrawable();
            gd.setCornerRadii(new float[]{radiusPx, radiusPx, radiusPx, radiusPx, 0, 0, 0, 0});
            gd.setColor(Color.TRANSPARENT);
            if (!sToolbarOriginalBackgrounds.containsKey(toolbar)) {
                Drawable original = toolbar.getBackground();
                toolbar.setTag(R.id.tag_toolbar_original_background, original);
                sToolbarOriginalBackgrounds.put(toolbar,
                        new java.lang.ref.WeakReference<>(original));
            }
            toolbar.setBackground(gd);

            ViewParent parent = toolbar.getParent();
            if (parent instanceof View) {
                final float pr = radiusPx;
                View parentView = (View) parent;
                if (!sToolbarOriginalClipStates.containsKey(parentView)) {
                    sToolbarOriginalClipStates.put(parentView, parentView.getClipToOutline());
                    sToolbarOriginalOutlineProviders.put(parentView, parentView.getOutlineProvider());
                }
                parentView.setClipToOutline(true);
                parentView.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        int pw = view.getWidth(), ph = view.getHeight();
                        if (pw <= 0 || ph <= 0) return;
                        // 让底部圆角落到可见区域之外，只显示上方圆角。
                        // RoundRect 是 clipToOutline 在所有目标版本都可靠支持的形状；
                        // ConvexPath 在部分 Android 12+ 设备上只作用于阴影而不会裁剪。
                        outline.setRoundRect(0, 0, pw, ph + (int) pr + 1, pr);
                    }
                });
            }

            Log.i(TAG, "toolbar round r=" + cfg.toolbar + "dp");
        } catch (Throwable t) {
            Log.w(TAG, "toolbar round failed: " + t);
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
            mThemePreferences = sp;
            mThemePrefListener = (sp_, key) -> {
                try {
                    Log.i(TAG, key + " changed, re-applying effects");
                    View cv = getCurrentInputView();
                    if (cv != null) {
                        final int generation = mLifecycleGeneration;
                        cv.post(() -> {
                            if (!isCurrentGeneration(cv, generation)) return;
                            applyAllEffects(cv, ApplyReason.THEME_CHANGED);
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

    /** 监听 ConfigProvider 变化，兼容 Provider 对目标进程可见的系统。 */
    private void registerConfigObserver(View anyView) {
        if (mConfigObserved) return;
        try {
            if (mConfigObserver == null) {
                mConfigObserver = new ContentObserver(mMainHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        // 一次拖动/连续设置可能产生多次通知，只合并成一次最新配置应用。
                        scheduleConfigApply(CONFIG_APPLY_COALESCE_MS);
                    }
                };
            }
            ContentResolver resolver = anyView.getContext().getContentResolver();
            resolver.registerContentObserver(
                    ConfigContract.CONTENT_URI, false, mConfigObserver);
            mConfigResolver = resolver;
            mConfigObserved = true;
            cancelConfigReadRetry();
            mConfigObserverRetryAttempt = 0;
            cancelConfigObserverRetry();
            Log.i(TAG, "config observer registered");
        } catch (Throwable t) {
            Log.w(TAG, "config observer failed: " + t);
            scheduleConfigObserverRetry();
        }
    }

    /** 锁屏阶段读取失败时，在系统解锁后重置退避状态并主动重新拉取。 */
    private void registerUserUnlockedReceiver(View anyView) {
        if (android.os.Build.VERSION.SDK_INT < 24 || mUserUnlockedReceiver != null) return;
        Context context = anyView.getContext().getApplicationContext();
        android.os.UserManager userManager = context.getSystemService(android.os.UserManager.class);
        if (userManager != null && userManager.isUserUnlocked()) return;

        mUserUnlockedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) return;
                mConfigReadRetryAttempt = 0;
                mConfigRequestPending = false;
                View current = getCurrentInputView();
                if (current != null) {
                    registerConfigObserver(current);
                    applyAllEffects(current, ApplyReason.CONFIG_CHANGED);
                }
                unregisterUserUnlockedReceiver();
            }
        };
        try {
            android.content.IntentFilter filter = new android.content.IntentFilter(
                    Intent.ACTION_USER_UNLOCKED);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(mUserUnlockedReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(mUserUnlockedReceiver, filter);
            }
            mReceiverContext = context;
        } catch (Throwable t) {
            mUserUnlockedReceiver = null;
            Log.w(TAG, "user unlock receiver registration failed: " + t.getMessage());
        }
    }

    private void unregisterUserUnlockedReceiver() {
        if (mUserUnlockedReceiver != null && mReceiverContext != null) {
            try {
                mReceiverContext.unregisterReceiver(mUserUnlockedReceiver);
            } catch (Throwable t) {
                Log.w(TAG, "user unlock receiver unregister failed: " + t.getMessage());
            }
        }
        mUserUnlockedReceiver = null;
        mReceiverContext = null;
    }

    /** Provider 可能因模块进程尚未启动而暂不可用，使用退避重试而不是永久失去监听。 */
    private void scheduleConfigObserverRetry() {
        if (mConfigObserved || mConfigObserverRetryScheduled) return;
        int shift = Math.min(mConfigObserverRetryAttempt, 5);
        long delay = Math.min(CONFIG_RETRY_MAX_MS,
                CONFIG_RETRY_INITIAL_MS << shift);
        mConfigObserverRetryAttempt++;
        mConfigObserverRetryScheduled = true;
        mConfigObserverRetry = () -> {
            mConfigObserverRetryScheduled = false;
            View cv = getCurrentInputView();
            if (cv != null) {
                boolean wasObserved = mConfigObserved;
                registerConfigObserver(cv);
                if (!wasObserved && mConfigObserved) scheduleConfigApply(0L);
            }
        };
        mMainHandler.postDelayed(mConfigObserverRetry, delay);
        Log.d(TAG, "config observer retry in " + delay + "ms");
    }

    private void scheduleConfigReadRetry() {
        if (mConfigReadRetry != null || mConfigReadRetryAttempt >= 5) return;
        long delay = Math.min(CONFIG_RETRY_MAX_MS,
                CONFIG_RETRY_INITIAL_MS << Math.min(mConfigReadRetryAttempt, 4));
        mConfigReadRetryAttempt++;
        mConfigReadRetry = () -> {
            mConfigReadRetry = null;
            View cv = getCurrentInputView();
            if (cv != null) applyAllEffects(cv, ApplyReason.CONFIG_CHANGED);
        };
        mMainHandler.postDelayed(mConfigReadRetry, delay);
        Log.d(TAG, "config read retry in " + delay + "ms");
    }

    private void cancelConfigReadRetry() {
        if (mConfigReadRetry != null) mMainHandler.removeCallbacks(mConfigReadRetry);
        mConfigReadRetry = null;
        mConfigReadRetryAttempt = 0;
    }

    private void cancelConfigObserverRetry() {
        if (mConfigObserverRetry != null) {
            mMainHandler.removeCallbacks(mConfigObserverRetry);
        }
        mConfigObserverRetry = null;
        mConfigObserverRetryScheduled = false;
    }

    private void unregisterConfigObserver() {
        cancelConfigObserverRetry();
        cancelConfigReadRetry();
        unregisterUserUnlockedReceiver();
        if (mPendingConfigApply != null) {
            mMainHandler.removeCallbacks(mPendingConfigApply);
            mPendingConfigApply = null;
        }
        if (mConfigObserved && mConfigResolver != null && mConfigObserver != null) {
            try {
                mConfigResolver.unregisterContentObserver(mConfigObserver);
            } catch (Throwable t) {
                Log.w(TAG, "config observer unregister failed: " + t.getMessage());
            }
        }
        mConfigResolver = null;
        mConfigObserved = false;
        mConfigRequestPending = false;
        if (mThemePreferences != null && mThemePrefListener != null) {
            try {
                mThemePreferences.unregisterOnSharedPreferenceChangeListener(mThemePrefListener);
            } catch (Throwable t) {
                Log.w(TAG, "theme listener unregister failed: " + t.getMessage());
            }
        }
        mThemePreferences = null;
        mThemePrefListener = null;
    }

    /** 合并 Provider 通知，读取时始终以最新 revision 为准。 */
    private void scheduleConfigApply(long delayMs) {
        if (mPendingConfigApply != null) return;
        mPendingConfigApply = () -> {
            mPendingConfigApply = null;
            View cv = getCurrentInputView();
            if (cv != null) applyAllEffects(cv, ApplyReason.CONFIG_CHANGED);
        };
        mMainHandler.postDelayed(mPendingConfigApply, delayMs);
    }

    private void scheduleEffectsRetry(View inputView) {
        if (mEffectsRetry != null || mEffectsRetryAttempt >= EFFECTS_RETRY_MAX_ATTEMPTS) return;
        java.lang.ref.WeakReference<View> viewRef = new java.lang.ref.WeakReference<>(inputView);
        mEffectsRetryAttempt++;
        mEffectsRetry = () -> {
            mEffectsRetry = null;
            View current = getCurrentInputView();
            View expected = viewRef.get();
            if (current != null && current == expected) {
                applyAllEffects(current, ApplyReason.INPUT_VIEW);
            }
        };
        mMainHandler.postDelayed(mEffectsRetry, EFFECTS_RETRY_DELAY_MS);
        Log.d(TAG, "effects retry in " + EFFECTS_RETRY_DELAY_MS
                + "ms (" + mEffectsRetryAttempt + "/" + EFFECTS_RETRY_MAX_ATTEMPTS + ")");
    }

    private void cancelEffectsRetry() {
        if (mEffectsRetry != null) mMainHandler.removeCallbacks(mEffectsRetry);
        mEffectsRetry = null;
        mEffectsRetryAttempt = 0;
    }

    private boolean isCurrentGeneration(View view, int generation) {
        return !mDestroyed && generation == mLifecycleGeneration && view == getCurrentInputView();
    }

    private View getCurrentInputView() {
        return mCurrentInputViewRef != null ? mCurrentInputViewRef.get() : null;
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
