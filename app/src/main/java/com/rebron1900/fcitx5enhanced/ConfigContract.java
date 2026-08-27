package com.rebron1900.fcitx5enhanced;

import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

/**
 * 配置 IPC 契约。
 *
 * 配置只由模块自己的 SharedPreferences 持久化；fcitx5 进程通过
 * {@link ConfigProvider} 查询和观察这份配置，避免跨 UID 直接读写文件。
 */
public final class ConfigContract {
    public static final String MODULE_PACKAGE = "com.rebron1900.fcitx5enhanced";
    public static final String AUTHORITY = "com.rebron1900.fcitx5enhanced.config";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/config");
    public static final String PREFS_NAME = "fcitx5_enhanced_config";
    /** 每次配置写入递增；用于跨进程通知去重和补偿 Provider 晚启动。 */
    public static final String REVISION = "config_revision";
    public static final long DEFAULT_REVISION = 0L;

    public static final String SHOW_LEFT_BUTTON = "show_left_button";
    public static final String SHOW_RIGHT_BUTTON = "show_right_button";
    public static final String VOICE_ENABLED = "voice_enabled";
    public static final String KEY_BORDER = "key_border";
    public static final String BLUR_RADIUS = "blur_radius";
    public static final String BG_ALPHA = "bg_alpha";
    public static final String KEY_ALPHA = "key_alpha";
    public static final String CORNER_RADIUS = "corner_radius";

    public static final int DEFAULT_BLUR = 100;
    public static final int DEFAULT_ALPHA = 60;
    public static final int DEFAULT_KEY_ALPHA = 140;
    public static final int DEFAULT_CORNER = 20;
    public static final boolean DEFAULT_VOICE = true;
    public static final boolean DEFAULT_LEFT_BUTTON = true;
    public static final boolean DEFAULT_RIGHT_BUTTON = true;
    public static final boolean DEFAULT_KEY_BORDER = true;

    private ConfigContract() {}

    public static ContentValues toValues(MainHook.Config config) {
        ContentValues values = new ContentValues();
        values.put(BLUR_RADIUS, config.blur);
        values.put(BG_ALPHA, config.alpha);
        values.put(KEY_ALPHA, config.keyAlpha);
        values.put(CORNER_RADIUS, config.corner);
        values.put(VOICE_ENABLED, config.voice);
        values.put(SHOW_LEFT_BUTTON, config.leftBtn);
        values.put(SHOW_RIGHT_BUTTON, config.rightBtn);
        values.put(KEY_BORDER, config.keyBorder);
        return values;
    }

    /** 只提交本页实际变化的字段，避免旧设置页快照覆盖其他并发修改。 */
    public static ContentValues toChangedValues(MainHook.Config current, MainHook.Config previous) {
        if (previous == null) return toValues(current);
        ContentValues values = new ContentValues();
        if (current.blur != previous.blur) values.put(BLUR_RADIUS, current.blur);
        if (current.alpha != previous.alpha) values.put(BG_ALPHA, current.alpha);
        if (current.keyAlpha != previous.keyAlpha) values.put(KEY_ALPHA, current.keyAlpha);
        if (current.corner != previous.corner) values.put(CORNER_RADIUS, current.corner);
        if (current.voice != previous.voice) values.put(VOICE_ENABLED, current.voice);
        if (current.leftBtn != previous.leftBtn) values.put(SHOW_LEFT_BUTTON, current.leftBtn);
        if (current.rightBtn != previous.rightBtn) values.put(SHOW_RIGHT_BUTTON, current.rightBtn);
        if (current.keyBorder != previous.keyBorder) values.put(KEY_BORDER, current.keyBorder);
        return values;
    }

    public static MainHook.Config fromCursor(Cursor cursor) {
        MainHook.Config config = new MainHook.Config();
        int revisionColumn = cursor.getColumnIndex(REVISION);
        if (revisionColumn >= 0 && !cursor.isNull(revisionColumn)) {
            config.revision = cursor.getLong(revisionColumn);
        }
        config.leftBtn = cursor.getInt(cursor.getColumnIndexOrThrow(SHOW_LEFT_BUTTON)) != 0;
        config.rightBtn = cursor.getInt(cursor.getColumnIndexOrThrow(SHOW_RIGHT_BUTTON)) != 0;
        config.voice = cursor.getInt(cursor.getColumnIndexOrThrow(VOICE_ENABLED)) != 0;
        config.keyBorder = cursor.getInt(cursor.getColumnIndexOrThrow(KEY_BORDER)) != 0;
        config.blur = cursor.getInt(cursor.getColumnIndexOrThrow(BLUR_RADIUS));
        config.alpha = cursor.getInt(cursor.getColumnIndexOrThrow(BG_ALPHA));
        config.keyAlpha = cursor.getInt(cursor.getColumnIndexOrThrow(KEY_ALPHA));
        config.corner = cursor.getInt(cursor.getColumnIndexOrThrow(CORNER_RADIUS));
        sanitize(config);
        return config;
    }

    /** 防止损坏配置或恶意 Provider 调用传入异常尺寸/透明度。 */
    public static void sanitize(MainHook.Config config) {
        config.blur = clamp(config.blur, 0, 100);
        config.alpha = clamp(config.alpha, 0, 255);
        config.keyAlpha = clamp(config.keyAlpha, 0, 255);
        config.corner = clamp(config.corner, 0, 48);
        config.toolbar = config.corner;
    }

    /** 递增 revision，避免 Long.MAX_VALUE 溢出为负数。 */
    public static long nextRevision(long revision) {
        return revision == Long.MAX_VALUE ? 1L : revision + 1L;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
