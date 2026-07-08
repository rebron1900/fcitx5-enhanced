package com.rebron1900.fcitx5enhanced;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

/**
 * 跨进程配置共享 Provider。
 * <p>
 * SettingsActivity（插件进程）写入 → fcitx5 进程通过 ContentObserver 感知变化。
 * 存储统一使用 {@link ConfigManager} 的主 SP。
 */
public class ConfigProvider extends ContentProvider {

    private static final String TAG = "Fcitx5Enh";

    @Override
    public boolean onCreate() { return true; }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.fcitx5enhanced.config";
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        Log.i(TAG, "ConfigProvider.write config");
        try {
            ConfigManager.Config current = ConfigManager.read(getContext());
            ConfigManager.write(getContext(),
                    values.containsKey("blur_radius") ? values.getAsInteger("blur_radius") : current.blur,
                    values.containsKey("bg_alpha") ? values.getAsInteger("bg_alpha") : current.alpha,
                    values.containsKey("key_alpha") ? values.getAsInteger("key_alpha") : current.keyAlpha,
                    values.containsKey("corner_radius") ? values.getAsInteger("corner_radius") : current.corner,
                    values.containsKey("voice_enabled") ? values.getAsBoolean("voice_enabled") : current.voice,
                    values.containsKey("show_left_button") ? values.getAsBoolean("show_left_button") : current.leftBtn,
                    values.containsKey("show_right_button") ? values.getAsBoolean("show_right_button") : current.rightBtn,
                    values.containsKey("key_border") ? values.getAsBoolean("key_border") : current.keyBorder
            );
            getContext().getContentResolver().notifyChange(uri, null);
            return 1;
        } catch (Exception e) {
            Log.w(TAG, "ConfigProvider.write failed: " + e);
            return 0;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        try {
            ConfigManager.Config cfg = ConfigManager.read(getContext());
            MatrixCursor c = new MatrixCursor(new String[]{
                    "show_left_button", "show_right_button", "voice_enabled",
                    "key_border", "blur_radius", "bg_alpha", "key_alpha", "corner_radius"
            });
            c.addRow(new Object[]{
                    cfg.leftBtn ? 1 : 0,
                    cfg.rightBtn ? 1 : 0,
                    cfg.voice ? 1 : 0,
                    cfg.keyBorder ? 1 : 0,
                    cfg.blur, cfg.alpha, cfg.keyAlpha, cfg.corner
            });
            return c;
        } catch (Exception e) {
            Log.w(TAG, "ConfigProvider.read failed: " + e);
            return null;
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
}
