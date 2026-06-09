package com.rebron1900.fcitx5enhanced;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * Fcitx5 增强 — 配置 ContentProvider
 *
 * 跨进程设置读写（hook 进程 ← IPC → 设置页进程）。
 * 存储：模块私有目录下的 enhanced_config.json（不依赖 SharedPreferences）。
 */
public class ConfigContentProvider extends ContentProvider {

    public static final Uri CONTENT_URI =
            Uri.parse("content://com.rebron1900.fcitx5enhanced.config/config");

    private static final String[] COLUMNS = {
        "blur_radius", "bg_alpha", "corner_radius",
        "voice_enabled", "show_left_button", "show_right_button"
    };

    @Override
    public boolean onCreate() { return true; }

    private File getConfigFile() {
        return new File(getContext().getFilesDir(), "enhanced_config.json");
    }

    private JSONObject readJson() {
        JSONObject json = new JSONObject();
        try {
            File f = getConfigFile();
            if (f.exists()) {
                BufferedReader br = new BufferedReader(new FileReader(f));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                json = new JSONObject(sb.toString());
            }
        } catch (Exception ignored) {}
        return json;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        JSONObject json = readJson();
        int blur = json.optInt("blur_radius", 100);
        int alpha = json.optInt("bg_alpha", 60);
        int corner = json.optInt("corner_radius", 20);
        boolean V = json.optBoolean("voice_enabled", true);
        boolean L = json.optBoolean("show_left_button", true);
        boolean R = json.optBoolean("show_right_button", true);
        android.util.Log.i("Fcitx5Enh", "CP query from file: L=" + L + " R=" + R + " V=" + V);
        MatrixCursor c = new MatrixCursor(COLUMNS);
        c.addRow(new Object[]{blur, alpha, corner, V, L, R});
        return c;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        android.util.Log.i("Fcitx5Enh", "CP update called keys=" + values.keySet());
        JSONObject json = readJson();
        try {
            if (values.containsKey("blur_radius"))
                json.put("blur_radius", values.getAsInteger("blur_radius"));
            if (values.containsKey("bg_alpha"))
                json.put("bg_alpha", values.getAsInteger("bg_alpha"));
            if (values.containsKey("corner_radius"))
                json.put("corner_radius", values.getAsInteger("corner_radius"));
            if (values.containsKey("voice_enabled")) {
                boolean v = values.getAsBoolean("voice_enabled");
                android.util.Log.i("Fcitx5Enh", "CP update: voice_enabled=" + v);
                json.put("voice_enabled", v);
            }
            if (values.containsKey("show_left_button")) {
                boolean L = values.getAsBoolean("show_left_button");
                android.util.Log.i("Fcitx5Enh", "CP update: show_left_button=" + L);
                json.put("show_left_button", L);
            }
            if (values.containsKey("show_right_button")) {
                boolean R = values.getAsBoolean("show_right_button");
                android.util.Log.i("Fcitx5Enh", "CP update: show_right_button=" + R);
                json.put("show_right_button", R);
            }
            FileWriter fw = new FileWriter(getConfigFile());
            fw.write(json.toString(2));
            fw.close();
            android.util.Log.i("Fcitx5Enh", "CP update: file written OK");
        } catch (Exception e) {
            android.util.Log.e("Fcitx5Enh", "CP update file error: " + e);
        }
        return 1;
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.dir/vnd.fcitx5enhanced.config"; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
}
