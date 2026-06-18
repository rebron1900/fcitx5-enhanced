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
    public static void writeConfigToFile(Context context, int blur, int alpha, int corner,
                                          boolean voice, boolean leftBtn, boolean rightBtn, boolean keyBorder) {
        try {
            JSONObject json = new JSONObject();
            json.put("blur_radius", blur);
            json.put("bg_alpha", alpha);
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
}
