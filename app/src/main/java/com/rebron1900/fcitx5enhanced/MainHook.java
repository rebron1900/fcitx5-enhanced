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
    }

    private Config cfg = new Config();
    private boolean receiverRegistered;
    private android.content.BroadcastReceiver mReceiver;
    private View mCurrentInputView;

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
            Log.i(TAG, "read from fcitx5 SP: L=" + cfg.leftBtn + " R=" + cfg.rightBtn + " V=" + cfg.voice);
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

        FrostedGlassHelper.apply(inputView, cfg);
        roundToolbarTop(inputView);
        PreeditHelper.apply(inputView, cfg);
        ExtraButtonsHelper.add(inputView, cfg);
        KeyEffectsHelper.apply(inputView, cfg);

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
                        Log.i(TAG, "BROADCAST payload: L=" + bL + " R=" + bR + " V=" + bV);

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
                                .commit();
                        } catch (Throwable t) {
                            Log.w(TAG, "save to fcitx5 SP failed: " + t);
                        }

                        cfg.leftBtn = bL; cfg.rightBtn = bR; cfg.voice = bV;
                        cfg.blur = bB; cfg.alpha = bA; cfg.corner = bC; cfg.toolbar = bC;
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
    //  Module lifecycle
    // ══════════════════════════════════════════

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
