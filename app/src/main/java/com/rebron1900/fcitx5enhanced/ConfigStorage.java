package com.rebron1900.fcitx5enhanced;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * 配置文件存储 — fcitx5 的 externalFilesDir JSON 文件。
 * <p>
 * ⚠️ 已废弃：作为 NPatch 兼容备选通道保留。
 * 主读写请使用 {@link ConfigManager}。
 */
@Deprecated
public class ConfigStorage {

    private static final String TAG = "Fcitx5Enh";
    private static final String CONFIG_FILE = "fcitx5_enhanced_config.json";

    public static File getConfigFile(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) dir = context.getFilesDir();
        return new File(dir, CONFIG_FILE);
    }

    public static boolean configFileExists(Context context) {
        return getConfigFile(context).exists();
    }

    /** 写 JSON 文件（NPatch 备选通道，ConfigManager.write 已写主 SP） */
    public static void writeConfigToFile(Context context, int blur, int alpha, int keyAlpha,
                                          int corner, boolean voice, boolean leftBtn,
                                          boolean rightBtn, boolean keyBorder) {
        try {
            JSONObject json = new JSONObject();
            json.put("blur_radius", blur);
            json.put("bg_alpha", alpha);
            json.put("key_alpha", keyAlpha);
            json.put("corner_radius", corner);
            json.put("voice_enabled", voice);
            json.put("show_left_button", leftBtn);
            json.put("show_right_button", rightBtn);
            json.put("key_border", keyBorder);
            File file = getConfigFile(context);
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(json.toString());
            }
            Log.i(TAG, "Config written to: " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "writeConfigToFile failed: " + e);
        }
    }

    /** 从 JSON 文件读取配置（仅备选；主要读入口用 ConfigManager.read） */
    public static ConfigManager.Config readConfigFromFile(Context context) {
        ConfigManager.Config def = new ConfigManager.Config(100, 60, 140, 20, true, true, true, true);
        File file = getConfigFile(context);
        if (!file.exists()) return def;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            JSONObject json = new JSONObject(sb.toString());
            return new ConfigManager.Config(
                    json.optInt("blur_radius", 100),
                    json.optInt("bg_alpha", 60),
                    json.optInt("key_alpha", 140),
                    json.optInt("corner_radius", 20),
                    json.optBoolean("voice_enabled", true),
                    json.optBoolean("show_left_button", true),
                    json.optBoolean("show_right_button", true),
                    json.optBoolean("key_border", true)
            );
        } catch (Exception e) {
            Log.w(TAG, "readConfigFromFile failed: " + e);
            return def;
        }
    }

    // ══════════════════════════════════════════
    //  WebDAV 同步配置
    // ══════════════════════════════════════════

    private static final String SYNC_PREFS = "fcitx5_webdav_sync";
    private static final String DEFAULT_WEBDAV_URL = "";
    private static final String DEFAULT_WEBDAV_USER = "";
    private static final String DEFAULT_WEBDAV_PASS = "";
    private static final int DEFAULT_INTERVAL = 30;

    private static final String[] SYNC_DIR_PATHS = {
        "/sdcard/Android/data/org.fcitx.fcitx5.android.fx/files/data/rime/sync",
        "/sdcard/Android/data/org.fcitx.fcitx5.android/files/data/rime/sync"
    };
    private static final String[] SYNC_DIR_LABELS = {
        "Fcitx5 (.fx)",
        "Fcitx5 (原版)"
    };

    public static boolean isWebDavEnabled(Context context) {
        return getSyncPrefs(context).getBoolean("webdav_enabled", false);
    }
    public static String getWebDavUrl(Context context) {
        return getSyncPrefs(context).getString("webdav_url", DEFAULT_WEBDAV_URL);
    }
    public static String getWebDavUser(Context context) {
        return getSyncPrefs(context).getString("webdav_user", DEFAULT_WEBDAV_USER);
    }
    public static String getWebDavPass(Context context) {
        return getSyncPrefs(context).getString("webdav_pass", DEFAULT_WEBDAV_PASS);
    }
    public static int getSyncInterval(Context context) {
        return getSyncPrefs(context).getInt("sync_interval", DEFAULT_INTERVAL);
    }
    public static void saveWebDavConfig(Context context, boolean enabled, String url,
                                         String user, String pass, int interval) {
        getSyncPrefs(context).edit()
                .putBoolean("webdav_enabled", enabled)
                .putString("webdav_url", url)
                .putString("webdav_user", user)
                .putString("webdav_pass", pass)
                .putInt("sync_interval", interval)
                .apply();
    }
    public static long getLastSyncTime(Context context) {
        return getSyncPrefs(context).getLong("last_sync_time", 0);
    }
    public static String getLastSyncResult(Context context) {
        return getSyncPrefs(context).getString("last_sync_result", "尚未同步");
    }
    public static void saveLastSyncResult(Context context, String result, long time) {
        getSyncPrefs(context).edit()
                .putLong("last_sync_time", time)
                .putString("last_sync_result", result)
                .apply();
    }

    public static String[] getSyncDirLabels() { return SYNC_DIR_LABELS; }

    public static int getSyncDirIndex(Context context) {
        return getSyncPrefs(context).getInt("sync_dir_index", 0);
    }
    public static void setSyncDirIndex(Context context, int index) {
        getSyncPrefs(context).edit().putInt("sync_dir_index", index).apply();
    }

    public static Uri getSyncDirUri(Context context) {
        String uriStr = getSyncPrefs(context).getString("sync_dir_uri", null);
        return uriStr != null ? Uri.parse(uriStr) : null;
    }
    public static void setSyncDirUri(Context context, Uri uri) {
        getSyncPrefs(context).edit().putString("sync_dir_uri", uri.toString()).apply();
    }
    public static void clearSyncDirUri(Context context) {
        getSyncPrefs(context).edit().remove("sync_dir_uri").apply();
    }

    public static File getRimeSyncDir(Context context) {
        int idx = getSyncDirIndex(context);
        if (idx < 0 || idx >= SYNC_DIR_PATHS.length) idx = 0;
        File dir = new File(SYNC_DIR_PATHS[idx]);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static SharedPreferences getSyncPrefs(Context context) {
        return context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE);
    }
}
