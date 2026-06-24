package com.rebron1900.fcitx5enhanced;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * 配置文件存储 — 使用 fcitx5 的 externalFilesDir（无需特殊权限）。
 * 
 * 路径：/sdcard/Android/data/{fcitx5包名}/files/fcitx5_enhanced_config.json
 * 
 * 兼容：
 * - LSPosed：SP + 文件双写，MainHook 优先读 SP
 * - NPatch：SettingsActivity 通过 createPackageContext 写文件，MainHook 直接读文件
 */
public class ConfigStorage {
    
    private static final String TAG = "Fcitx5Enh";
    private static final String CONFIG_FILE = "fcitx5_enhanced_config.json";
    
    /** 获取配置文件（通过 Context 的 externalFilesDir） */
    public static File getConfigFile(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            dir = context.getFilesDir();
        }
        return new File(dir, CONFIG_FILE);
    }
    
    /** 写入配置到文件 */
    public static void writeConfigToFile(Context context, int blur, int alpha, int keyAlpha, int corner,
                                          boolean voice, boolean leftBtn, boolean rightBtn, boolean keyBorder) {
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
            FileWriter writer = new FileWriter(file);
            writer.write(json.toString());
            writer.close();
            Log.i(TAG, "Config written to: " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "writeConfigToFile failed: " + e);
        }
    }
    
    /** 从文件读取配置 */
    public static MainHook.Config readConfigFromFile(Context context) {
        MainHook.Config cfg = new MainHook.Config();
        File file = getConfigFile(context);
        
        if (!file.exists()) {
            Log.d(TAG, "Config file not found: " + file.getAbsolutePath());
            return cfg;
        }
        
        try {
            FileReader reader = new FileReader(file);
            BufferedReader br = new BufferedReader(reader);
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            br.close();
            
            JSONObject json = new JSONObject(sb.toString());
            cfg.blur = json.optInt("blur_radius", 100);
            cfg.alpha = json.optInt("bg_alpha", 60);
            cfg.keyAlpha = json.optInt("key_alpha", 140);
            cfg.corner = json.optInt("corner_radius", 20);
            cfg.toolbar = cfg.corner;
            cfg.voice = json.optBoolean("voice_enabled", true);
            cfg.leftBtn = json.optBoolean("show_left_button", true);
            cfg.rightBtn = json.optBoolean("show_right_button", true);
            cfg.keyBorder = json.optBoolean("key_border", true);
            Log.i(TAG, "Config read from file: L=" + cfg.leftBtn + " R=" + cfg.rightBtn);
        } catch (Exception e) {
            Log.w(TAG, "readConfigFromFile failed: " + e);
        }
        
        return cfg;
    }
    
    /** 检查配置文件是否存在 */
    public static boolean configFileExists(Context context) {
        return getConfigFile(context).exists();
    }

    // ══════════════════════════════════════════
    //  WebDAV 同步配置
    // ══════════════════════════════════════════

    private static final String SYNC_PREFS = "fcitx5_webdav_sync";
    private static final String DEFAULT_WEBDAV_URL = "";
    private static final String DEFAULT_WEBDAV_USER = "";
    private static final String DEFAULT_WEBDAV_PASS = "";
    private static final int DEFAULT_INTERVAL = 30; // 分钟

    // sync 目录选项
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

    // ══════════════════════════════════════════
    //  Sync 目录选择
    // ══════════════════════════════════════════

    public static String[] getSyncDirLabels() {
        return SYNC_DIR_LABELS;
    }

    public static int getSyncDirIndex(Context context) {
        return getSyncPrefs(context).getInt("sync_dir_index", 0);
    }

    public static void setSyncDirIndex(Context context, int index) {
        getSyncPrefs(context).edit().putInt("sync_dir_index", index).apply();
    }

    /** 获取 RIME sync 目录 — 根据用户选择的索引返回对应路径 */
    public static File getRimeSyncDir(Context context) {
        int idx = getSyncDirIndex(context);
        if (idx < 0 || idx >= SYNC_DIR_PATHS.length) idx = 0;
        File dir = new File(SYNC_DIR_PATHS[idx]);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static android.content.SharedPreferences getSyncPrefs(Context context) {
        return context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE);
    }
}
