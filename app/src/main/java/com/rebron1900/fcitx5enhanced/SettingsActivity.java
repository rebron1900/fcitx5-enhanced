package com.rebron1900.fcitx5enhanced;

import android.app.Activity;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

public class SettingsActivity extends Activity {

    private static final String TAG = "Fcitx5Enh";

    // Theme tab
    private SeekBar sbBlur, sbAlpha, sbKeyAlpha, sbCorner, sbButtonBottomMargin;
    private TextView tvBlur, tvAlpha, tvKeyAlpha, tvCorner, tvButtonBottomMargin;
    private Switch swVoice, swLeft, swRight, swKeyBorder;
    /** 最近一次已提交的界面快照；只提交实际变化字段，避免旧 Activity 覆盖并发修改。 */
    private MainHook.Config mSavedConfig;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        setTitle(R.string.settings_title);

        sbBlur = findViewById(R.id.sb_blur_radius);
        sbAlpha = findViewById(R.id.sb_bg_alpha);
        sbKeyAlpha = findViewById(R.id.sb_key_alpha);
        sbCorner = findViewById(R.id.sb_corner_radius);
        sbButtonBottomMargin = findViewById(R.id.sb_button_bottom_margin);
        tvButtonBottomMargin = findViewById(R.id.tv_button_bottom_margin_val);
        tvBlur = findViewById(R.id.tv_blur_val);
        tvAlpha = findViewById(R.id.tv_alpha_val);
        tvKeyAlpha = findViewById(R.id.tv_key_alpha_val);
        tvCorner = findViewById(R.id.tv_corner_val);
        swVoice = findViewById(R.id.sw_voice);
        swLeft = findViewById(R.id.sw_left_btn);
        swRight = findViewById(R.id.sw_right_btn);
        swKeyBorder = findViewById(R.id.sw_key_border);

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) updateLabels();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) { saveAndApply(); }
        };

        sbBlur.setOnSeekBarChangeListener(listener);
        sbAlpha.setOnSeekBarChangeListener(listener);
        sbKeyAlpha.setOnSeekBarChangeListener(listener);
        sbCorner.setOnSeekBarChangeListener(listener);
        sbButtonBottomMargin.setOnSeekBarChangeListener(listener);

        View.OnClickListener switchListener = v -> saveAndApply();
        swVoice.setOnClickListener(switchListener);
        swLeft.setOnClickListener(switchListener);
        swRight.setOnClickListener(switchListener);
        swKeyBorder.setOnClickListener(switchListener);

        // 配置必须始终可编辑：模块可先于目标输入法安装，且包可见性/分支包名
        // 不应影响用户保存偏好。运行时仅在支持语音的目标输入法中显示语音控件。
        loadSettings();
    }

    // ══════════════════════════════════════════
    //  持久化：模块 SharedPreferences + ConfigProvider
    // ══════════════════════════════════════════

    private SharedPreferences getConfigPreferences() {
        return getSharedPreferences(ConfigContract.PREFS_NAME, MODE_PRIVATE);
    }

    private void loadSettings() {
        SharedPreferences sp = getConfigPreferences();
        migrateLegacySettings(sp);

        sbBlur.setProgress(clamp(sp.getInt(ConfigContract.BLUR_RADIUS,
                ConfigContract.DEFAULT_BLUR), 0, 100));
        sbAlpha.setProgress(clamp(sp.getInt(ConfigContract.BG_ALPHA,
                ConfigContract.DEFAULT_ALPHA), 0, 255));
        sbKeyAlpha.setProgress(clamp(sp.getInt(ConfigContract.KEY_ALPHA,
                ConfigContract.DEFAULT_KEY_ALPHA), 0, 255));
        sbCorner.setProgress(clamp(sp.getInt(ConfigContract.CORNER_RADIUS,
                ConfigContract.DEFAULT_CORNER), 0, 48));
        sbButtonBottomMargin.setProgress(clamp(sp.getInt(ConfigContract.BUTTON_BOTTOM_MARGIN,
                ConfigContract.DEFAULT_BUTTON_BOTTOM_MARGIN), 0, 100));
        swVoice.setChecked(sp.getBoolean(ConfigContract.VOICE_ENABLED, ConfigContract.DEFAULT_VOICE));
        swLeft.setChecked(sp.getBoolean(ConfigContract.SHOW_LEFT_BUTTON, ConfigContract.DEFAULT_LEFT_BUTTON));
        swRight.setChecked(sp.getBoolean(ConfigContract.SHOW_RIGHT_BUTTON, ConfigContract.DEFAULT_RIGHT_BUTTON));
        swKeyBorder.setChecked(sp.getBoolean(ConfigContract.KEY_BORDER, ConfigContract.DEFAULT_KEY_BORDER));
        updateLabels();
        mSavedConfig = captureConfig();
    }

    /** 将旧版 Activity 私有 SP 迁移到新的统一配置 SP。 */
    private void migrateLegacySettings(SharedPreferences target) {
        // revision 也作为统一配置已初始化标记；增量 fallback 可能尚未写入 blur。
        if (target.contains(ConfigContract.BLUR_RADIUS)
                || target.contains(ConfigContract.REVISION)) return;
        SharedPreferences legacy = getPreferences(MODE_PRIVATE);
        if (!legacy.contains("blur_radius")) return;

        target.edit()
                .putLong(ConfigContract.REVISION,
                        ConfigContract.nextRevision(ConfigContract.DEFAULT_REVISION))
                .putInt(ConfigContract.BLUR_RADIUS, legacy.getInt("blur_radius", ConfigContract.DEFAULT_BLUR))
                .putInt(ConfigContract.BG_ALPHA, legacy.getInt("bg_alpha", ConfigContract.DEFAULT_ALPHA))
                .putInt(ConfigContract.KEY_ALPHA, legacy.getInt("key_alpha", ConfigContract.DEFAULT_KEY_ALPHA))
                .putInt(ConfigContract.CORNER_RADIUS, legacy.getInt("corner_radius", ConfigContract.DEFAULT_CORNER))
                .putBoolean(ConfigContract.VOICE_ENABLED, legacy.getBoolean("voice_enabled", ConfigContract.DEFAULT_VOICE))
                .putBoolean(ConfigContract.SHOW_LEFT_BUTTON, legacy.getBoolean("show_left_button", ConfigContract.DEFAULT_LEFT_BUTTON))
                .putBoolean(ConfigContract.SHOW_RIGHT_BUTTON, legacy.getBoolean("show_right_button", ConfigContract.DEFAULT_RIGHT_BUTTON))
                .putBoolean(ConfigContract.KEY_BORDER, legacy.getBoolean("key_border", ConfigContract.DEFAULT_KEY_BORDER))
                .commit();
        Log.i(TAG, "migrated legacy settings");
    }

    private void updateLabels() {
        tvBlur.setText(getString(R.string.settings_blur_value_format, sbBlur.getProgress()));
        tvAlpha.setText(getString(R.string.settings_opacity_value_format,
                toPercent(sbAlpha.getProgress())));
        tvKeyAlpha.setText(getString(R.string.settings_opacity_value_format,
                toPercent(sbKeyAlpha.getProgress())));
        tvCorner.setText(getString(R.string.settings_corner_value_format, sbCorner.getProgress()));
        tvButtonBottomMargin.setText(getString(R.string.settings_button_bottom_margin_value_format,
                sbButtonBottomMargin.getProgress()));
    }

    /** 内部透明度使用 0–255；界面统一显示四舍五入后的 0–100%。 */
    private static int toPercent(int alpha) {
        return Math.round(alpha * 100f / 255f);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void saveAndApply() {
        MainHook.Config config = captureConfig();
        ContentValues values = ConfigContract.toChangedValues(config, mSavedConfig);
        if (values.size() == 0) return;

        Log.i(TAG, "Settings saving: L=" + config.leftBtn
                + " R=" + config.rightBtn + " V=" + config.voice);

        boolean saved = false;
        try {
            int updated = getContentResolver().update(
                    ConfigContract.CONTENT_URI, values, null, null);
            saved = updated > 0;
        } catch (Exception e) {
            Log.w(TAG, "ConfigProvider update failed, writing local fallback", e);
        }
        if (!saved) saved = writeLocalFallback(values);
        if (saved) mSavedConfig = config;
    }

    private MainHook.Config captureConfig() {
        MainHook.Config config = new MainHook.Config();
        config.blur = sbBlur.getProgress();
        config.alpha = sbAlpha.getProgress();
        config.keyAlpha = sbKeyAlpha.getProgress();
        config.corner = sbCorner.getProgress();
        config.buttonBottomMargin = sbButtonBottomMargin.getProgress();
        config.toolbar = config.corner;
        config.voice = swVoice.isChecked();
        config.leftBtn = swLeft.isChecked();
        config.rightBtn = swRight.isChecked();
        config.keyBorder = swKeyBorder.isChecked();
        return config;
    }

    private boolean writeLocalFallback(ContentValues values) {
        SharedPreferences preferences = getConfigPreferences();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putLong(ConfigContract.REVISION,
                ConfigContract.nextRevision(preferences.getLong(
                        ConfigContract.REVISION, ConfigContract.DEFAULT_REVISION)));
        Object blur = values.get(ConfigContract.BLUR_RADIUS);
        Object alpha = values.get(ConfigContract.BG_ALPHA);
        Object keyAlpha = values.get(ConfigContract.KEY_ALPHA);
        Object corner = values.get(ConfigContract.CORNER_RADIUS);
        Object buttonBottomMargin = values.get(ConfigContract.BUTTON_BOTTOM_MARGIN);
        if (blur instanceof Integer) editor.putInt(ConfigContract.BLUR_RADIUS, (Integer) blur);
        if (alpha instanceof Integer) editor.putInt(ConfigContract.BG_ALPHA, (Integer) alpha);
        if (keyAlpha instanceof Integer) editor.putInt(ConfigContract.KEY_ALPHA, (Integer) keyAlpha);
        if (corner instanceof Integer) editor.putInt(ConfigContract.CORNER_RADIUS, (Integer) corner);
        if (buttonBottomMargin instanceof Integer) editor.putInt(ConfigContract.BUTTON_BOTTOM_MARGIN, (Integer) buttonBottomMargin);
        if (values.containsKey(ConfigContract.VOICE_ENABLED)) {
            editor.putBoolean(ConfigContract.VOICE_ENABLED, values.getAsBoolean(ConfigContract.VOICE_ENABLED));
        }
        if (values.containsKey(ConfigContract.SHOW_LEFT_BUTTON)) {
            editor.putBoolean(ConfigContract.SHOW_LEFT_BUTTON, values.getAsBoolean(ConfigContract.SHOW_LEFT_BUTTON));
        }
        if (values.containsKey(ConfigContract.SHOW_RIGHT_BUTTON)) {
            editor.putBoolean(ConfigContract.SHOW_RIGHT_BUTTON, values.getAsBoolean(ConfigContract.SHOW_RIGHT_BUTTON));
        }
        if (values.containsKey(ConfigContract.KEY_BORDER)) {
            editor.putBoolean(ConfigContract.KEY_BORDER, values.getAsBoolean(ConfigContract.KEY_BORDER));
        }
        boolean committed = editor.commit();
        if (committed) {
            // Provider 暂不可用时仍尽量唤醒已注册的目标进程 Observer；
            // 下一次 onWindowShown 还会通过 revision 主动拉取兜底。
            try {
                getContentResolver().notifyChange(ConfigContract.CONTENT_URI, null);
            } catch (Exception e) {
                Log.w(TAG, "local config notify failed", e);
            }
        }
        return committed;
    }
}
