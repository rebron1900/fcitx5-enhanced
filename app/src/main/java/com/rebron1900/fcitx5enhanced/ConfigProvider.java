package com.rebron1900.fcitx5enhanced;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

/**
 * 跨进程配置共享 Provider。
 * <p>
 * SettingsActivity（插件进程）写入 → fcitx5 进程通过 ContentObserver 感知变化 → 读取。
 */
public class ConfigProvider extends ContentProvider {

    private static final String TAG = "Fcitx5Enh";
    private static final String SP_NAME = "fcitx5_enhanced_config_provider";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.fcitx5enhanced.config";
    }

    /** SettingsActivity 写入配置。 */
    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        Log.i(TAG, "ConfigProvider.write config");
        try {
            SharedPreferences sp = getContext()
                    .getSharedPreferences(SP_NAME, android.content.Context.MODE_PRIVATE);
            SharedPreferences.Editor ed = sp.edit();
            writeBool(ed, values, "show_left_button", true);
            writeBool(ed, values, "show_right_button", true);
            writeBool(ed, values, "voice_enabled", true);
            writeBool(ed, values, "key_border", true);
            writeInt(ed, values, "key_border_color_dark", 0x22FFFFFF);
            writeInt(ed, values, "key_border_color_light", 0x66FFFFFF);
            writeInt(ed, values, "key_border_width", 8);
            writeInt(ed, values, "kb_border_color_dark", 0x33FFFFFF);
            writeInt(ed, values, "kb_border_color_light", 0x99FFFFFF);
            writeInt(ed, values, "kb_border_width", 10);
            writeInt(ed, values, "blur_radius", 100);
            writeInt(ed, values, "bg_alpha", 60);
            writeInt(ed, values, "corner_radius", 20);
            ed.commit();
            // 通知观察者
            getContext().getContentResolver().notifyChange(uri, null);
            return 1;
        } catch (Exception e) {
            Log.w(TAG, "ConfigProvider.write failed: " + e);
            return 0;
        }
    }

    /** MainHook 读取配置。返回单行 Cursor。 */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        try {
            SharedPreferences sp = getContext()
                    .getSharedPreferences(SP_NAME, android.content.Context.MODE_PRIVATE);
            MatrixCursor c = new MatrixCursor(new String[]{
                    "show_left_button", "show_right_button", "voice_enabled",
                    "key_border", "key_border_color_dark", "key_border_color_light",
                    "key_border_width",
                    "kb_border_color_dark", "kb_border_color_light", "kb_border_width",
                    "blur_radius", "bg_alpha", "corner_radius"
            });
            c.addRow(new Object[]{
                    sp.getBoolean("show_left_button", true),
                    sp.getBoolean("show_right_button", true),
                    sp.getBoolean("voice_enabled", true),
                    sp.getBoolean("key_border", true),
                    sp.getInt("key_border_color_dark", 0x22FFFFFF),
                    sp.getInt("key_border_color_light", 0x66FFFFFF),
                    sp.getInt("key_border_width", 8),
                    sp.getInt("kb_border_color_dark", 0x33FFFFFF),
                    sp.getInt("kb_border_color_light", 0x99FFFFFF),
                    sp.getInt("kb_border_width", 10),
                    sp.getInt("blur_radius", 100),
                    sp.getInt("bg_alpha", 60),
                    sp.getInt("corner_radius", 20)
            });
            return c;
        } catch (Exception e) {
            Log.w(TAG, "ConfigProvider.read failed: " + e);
            return null;
        }
    }

    // helper
    private void writeBool(SharedPreferences.Editor ed, ContentValues v, String key, boolean def) {
        if (v.containsKey(key)) ed.putBoolean(key, v.getAsBoolean(key));
        else ed.putBoolean(key, def);
    }
    private void writeInt(SharedPreferences.Editor ed, ContentValues v, String key, int def) {
        if (v.containsKey(key)) ed.putInt(key, v.getAsInteger(key));
        else ed.putInt(key, def);
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
}
