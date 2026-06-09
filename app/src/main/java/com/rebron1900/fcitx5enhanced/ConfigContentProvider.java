package com.rebron1900.fcitx5enhanced;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * Fcitx5 增强 — 配置 ContentProvider
 *
 * 跨进程设置读写（hook 进程 ← IPC → 设置页进程）。
 */
public class ConfigContentProvider extends ContentProvider {

    public static final Uri CONTENT_URI =
            Uri.parse("content://com.rebron1900.fcitx5enhanced.config/config");

    private static final String SP_NAME = "fcitx5_enhanced_config";
    private static final String[] COLUMNS = {
        "blur_radius", "bg_alpha", "corner_radius",
        "voice_enabled", "show_left_button", "show_right_button"
    };

    @Override
    public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        SharedPreferences sp = getContext()
                .getSharedPreferences(SP_NAME, android.content.Context.MODE_PRIVATE);
        MatrixCursor c = new MatrixCursor(COLUMNS);
        c.addRow(new Object[]{
                sp.getInt("blur_radius", 100),
                sp.getInt("bg_alpha", 60),
                sp.getInt("corner_radius", 20),
                sp.getBoolean("voice_enabled", true),
                sp.getBoolean("show_left_button", true),
                sp.getBoolean("show_right_button", true)
        });
        return c;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        SharedPreferences.Editor editor = getContext()
                .getSharedPreferences(SP_NAME, android.content.Context.MODE_PRIVATE).edit();
        if (values.containsKey("blur_radius"))
            editor.putInt("blur_radius", values.getAsInteger("blur_radius"));
        if (values.containsKey("bg_alpha"))
            editor.putInt("bg_alpha", values.getAsInteger("bg_alpha"));
        if (values.containsKey("corner_radius"))
            editor.putInt("corner_radius", values.getAsInteger("corner_radius"));
        if (values.containsKey("voice_enabled"))
            editor.putBoolean("voice_enabled", values.getAsBoolean("voice_enabled"));
        if (values.containsKey("show_left_button"))
            editor.putBoolean("show_left_button", values.getAsBoolean("show_left_button"));
        if (values.containsKey("show_right_button"))
            editor.putBoolean("show_right_button", values.getAsBoolean("show_right_button"));
        editor.apply();
        return 1;
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.dir/vnd.fcitx5enhanced.config"; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
}
