package com.rebron1900.fcitx5enhanced;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.View;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 配置管理中心 — 单例静态类。
 *
 * 核心设计：
 * - AtomicInteger 版本计数器，每次配置变更递增
 * - WeakHashMap<View, Integer> 记录每个 InputView 上次应用的版本
 * - shouldApply(View) 检查版本号，O(1) 决策是否需重新应用效果
 * - View 销毁 → WeakHashMap 自动清理 → 无内存泄漏
 */
public class ConfigManager {
    private static final String TAG = "Fcitx5Enh";
    private static final String SP_NAME = "fcitx5_enhanced_config";

    /** 版本计数器 — 每次配置变更递增 */
    private static final AtomicInteger sVersion = new AtomicInteger(0);

    /**
     * 每个 View 上次应用的版本号。
     * key 是 InputView 对象（WeakReference），View GC 时自动移除 entry。
     * value 是 Integer（自动装箱，小对象无泄漏风险）。
     */
    private static final Map<View, Integer> sLastApplied =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ConfigManager() {}

    /** 当前配置版本号 */
    public static int getVersion() {
        return sVersion.get();
    }

    /** 递增版本号（配置变更通道调用） */
    public static void bumpVersion() {
        sVersion.incrementAndGet();
    }

    /**
     * 检查指定 View 是否需要重新应用效果。
     * 如果 View 上次应用的版本等于当前版本 → false（跳过）
     * 否则 → 记录当前版本并返回 true（需要应用）
     */
    public static synchronized boolean shouldApply(View view) {
        int curVer = sVersion.get();
        Integer lastVer = sLastApplied.get(view);
        if (lastVer != null && lastVer == curVer) {
            return false;
        }
        sLastApplied.put(view, curVer);
        return true;
    }

    /**
     * 重置 View 的上次应用版本（强制下次 apply 执行）。
     * 用于 InputView 重建检测：发现新 View 时调用。
     */
    public static void resetForView(View view) {
        sLastApplied.remove(view);
    }

    /** 清除所有 View 的 apply 记录（进程级重置） */
    public static void resetAll() {
        sLastApplied.clear();
        sVersion.incrementAndGet();
    }

    // ══════════════════════════════════════════
    //  Config 数据记录（不可变快照）
    // ══════════════════════════════════════════

    /** 不可变配置快照 — 共享安全，无需防御性拷贝 */
    public static class Config {
        public final int blur;
        public final int alpha;
        public final int keyAlpha;
        public final int corner;
        public final int toolbar;
        public final boolean voice;
        public final boolean leftBtn;
        public final boolean rightBtn;
        public final boolean keyBorder;

        public Config(int blur, int alpha, int keyAlpha, int corner,
                      boolean voice, boolean leftBtn, boolean rightBtn, boolean keyBorder) {
            this.blur = blur;
            this.alpha = alpha;
            this.keyAlpha = keyAlpha;
            this.corner = corner;
            this.toolbar = corner;
            this.voice = voice;
            this.leftBtn = leftBtn;
            this.rightBtn = rightBtn;
            this.keyBorder = keyBorder;
        }
    }

    // ══════════════════════════════════════════
    //  读写 SP
    // ══════════════════════════════════════════

    public static Config read(Context context) {
        SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        return new Config(
                sp.getInt("blur_radius", 100),
                sp.getInt("bg_alpha", 60),
                sp.getInt("key_alpha", 140),
                sp.getInt("corner_radius", 20),
                sp.getBoolean("voice_enabled", true),
                sp.getBoolean("show_left_button", true),
                sp.getBoolean("show_right_button", true),
                sp.getBoolean("key_border", true)
        );
    }

    public static void write(Context context, Config cfg) {
        context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt("blur_radius", cfg.blur)
                .putInt("bg_alpha", cfg.alpha)
                .putInt("key_alpha", cfg.keyAlpha)
                .putInt("corner_radius", cfg.corner)
                .putBoolean("voice_enabled", cfg.voice)
                .putBoolean("show_left_button", cfg.leftBtn)
                .putBoolean("show_right_button", cfg.rightBtn)
                .putBoolean("key_border", cfg.keyBorder)
                .commit();
        bumpVersion();
    }

    public static void write(Context context, int blur, int alpha, int keyAlpha, int corner,
                              boolean voice, boolean leftBtn, boolean rightBtn, boolean keyBorder) {
        write(context, new Config(blur, alpha, keyAlpha, corner,
                voice, leftBtn, rightBtn, keyBorder));
    }
}
