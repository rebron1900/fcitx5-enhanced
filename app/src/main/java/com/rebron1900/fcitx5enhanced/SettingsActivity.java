package com.rebron1900.fcitx5enhanced;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.rebron1900.fcitx5enhanced.sync.SyncManager;

public class SettingsActivity extends Activity {

    private static final String TAG = "Fcitx5Enh";

    // Theme tab
    private SeekBar sbBlur, sbAlpha, sbCorner;
    private TextView tvBlur, tvAlpha, tvCorner;
    private Switch swVoice, swLeft, swRight, swKeyBorder;
    private boolean isDark;

    // Sync tab
    private Switch swSyncEnabled;
    private EditText etSyncUrl, etSyncUser, etSyncPass;
    private SeekBar sbInterval;
    private TextView tvIntervalVal, tvSyncStatus;
    private View btnSyncNow;

    // Tab
    private TextView tabTheme, tabSync;
    private ScrollView panelTheme, panelSync;

    private static final int[] INTERVALS = {15, 30, 60, 120, 180, 360, 720};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        isDark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        setContentView(R.layout.activity_settings);
        setTitle("Fcitx5 增强");

        // === Tab ===
        tabTheme = findViewById(R.id.tab_theme);
        tabSync = findViewById(R.id.tab_sync);
        panelTheme = findViewById(R.id.panel_theme);
        panelSync = findViewById(R.id.panel_sync);

        tabTheme.setOnClickListener(v -> switchTab(0));
        tabSync.setOnClickListener(v -> switchTab(1));

        // === Theme tab controls ===
        sbBlur = findViewById(R.id.sb_blur_radius);
        sbAlpha = findViewById(R.id.sb_bg_alpha);
        sbCorner = findViewById(R.id.sb_corner_radius);
        tvBlur = findViewById(R.id.tv_blur_val);
        tvAlpha = findViewById(R.id.tv_alpha_val);
        tvCorner = findViewById(R.id.tv_corner_val);
        swVoice = findViewById(R.id.sw_voice);
        swLeft = findViewById(R.id.sw_left_btn);
        swRight = findViewById(R.id.sw_right_btn);
        swKeyBorder = findViewById(R.id.sw_key_border);

        applyTheme();

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                if (sb == sbBlur) tvBlur.setText(progress == 0 ? "关" : progress + "");
                else if (sb == sbAlpha) tvAlpha.setText((progress * 100) / 255 + "%");
                else if (sb == sbCorner) tvCorner.setText(progress == 0 ? "关" : progress + "");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) { saveAndApply(); }
        };

        sbBlur.setOnSeekBarChangeListener(listener);
        sbAlpha.setOnSeekBarChangeListener(listener);
        sbCorner.setOnSeekBarChangeListener(listener);

        View.OnClickListener switchListener = v -> saveAndApply();
        swVoice.setOnClickListener(switchListener);
        swLeft.setOnClickListener(switchListener);
        swRight.setOnClickListener(switchListener);
        swKeyBorder.setOnClickListener(switchListener);

        // 原版无 RECORD_AUDIO 权限，禁用语音开关
        String pkg = getPackageName();
        if ("org.fcitx.fcitx5.android".equals(pkg)) {
            swVoice.setEnabled(false);
            swVoice.setChecked(false);
            swVoice.setAlpha(0.4f);
        }

        // === Sync tab controls ===
        swSyncEnabled = findViewById(R.id.sw_sync_enabled);
        etSyncUrl = findViewById(R.id.et_sync_url);
        etSyncUser = findViewById(R.id.et_sync_user);
        etSyncPass = findViewById(R.id.et_sync_pass);
        sbInterval = findViewById(R.id.sb_interval);
        tvIntervalVal = findViewById(R.id.tv_interval_val);
        tvSyncStatus = findViewById(R.id.tv_sync_status);
        btnSyncNow = findViewById(R.id.btn_sync_now);

        setupSyncTab();

        loadSettings();

        // 初始化 SyncManager（注册定时任务）
        SyncManager.init(this);
    }

    // ══════════════════════════════════════════
    //  Tab 切换
    // ══════════════════════════════════════════

    private void switchTab(int index) {
        boolean isTheme = (index == 0);
        panelTheme.setVisibility(isTheme ? View.VISIBLE : View.GONE);
        panelSync.setVisibility(isTheme ? View.GONE : View.VISIBLE);

        int accent = 0xFF07C160;
        int gray = 0xFF999999;
        tabTheme.setTextColor(isTheme ? accent : gray);
        tabTheme.setTypeface(null, isTheme ? Typeface.BOLD : Typeface.NORMAL);
        tabSync.setTextColor(isTheme ? gray : accent);
        tabSync.setTypeface(null, isTheme ? Typeface.NORMAL : Typeface.BOLD);
    }

    // ══════════════════════════════════════════
    //  Sync tab setup
    // ══════════════════════════════════════════

    private void setupSyncTab() {
        // 同步间隔 SeekBar
        sbInterval.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                tvIntervalVal.setText(formatInterval(INTERVALS[progress]));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) { saveSyncConfig(); }
        });

        // 同步开关
        swSyncEnabled.setOnClickListener(v -> {
            saveSyncConfig();
            SyncManager.scheduleSync(this);
        });

        // 立即同步
        btnSyncNow.setOnClickListener(v -> {
            tvSyncStatus.setText("同步中...");
            btnSyncNow.setEnabled(false);
            SyncManager.syncNow(this, result -> {
                runOnUiThread(() -> {
                    tvSyncStatus.setText(result);
                    btnSyncNow.setEnabled(true);
                });
            });
        });
    }

    private void loadSyncSettings() {
        swSyncEnabled.setChecked(ConfigStorage.isWebDavEnabled(this));
        etSyncUrl.setText(ConfigStorage.getWebDavUrl(this));
        etSyncUser.setText(ConfigStorage.getWebDavUser(this));
        etSyncPass.setText(ConfigStorage.getWebDavPass(this));

        int interval = ConfigStorage.getSyncInterval(this);
        int idx = 1; // default 30min
        for (int i = 0; i < INTERVALS.length; i++) {
            if (INTERVALS[i] == interval) { idx = i; break; }
        }
        sbInterval.setProgress(idx);
        tvIntervalVal.setText(formatInterval(interval));
    }

    private void saveSyncConfig() {
        boolean enabled = swSyncEnabled.isChecked();
        String url = etSyncUrl.getText().toString().trim();
        String user = etSyncUser.getText().toString().trim();
        String pass = etSyncPass.getText().toString().trim();
        int interval = INTERVALS[sbInterval.getProgress()];

        ConfigStorage.saveWebDavConfig(this, enabled, url, user, pass, interval);
        Log.i(TAG, "sync config saved: enabled=" + enabled + " url=" + url + " interval=" + interval);
    }

    private static String formatInterval(int minutes) {
        if (minutes < 60) return minutes + "分钟";
        if (minutes < 1440) return (minutes / 60) + "小时";
        return (minutes / 1440) + "天";
    }

    // ══════════════════════════════════════════
    //  Theme (原有逻辑)
    // ══════════════════════════════════════════

    private void applyTheme() {
        int cardBg = isDark ? 0xFF1E1E1E : 0xFFFFFFFF;
        int pageBg = isDark ? 0xFF0D0D0D : 0xFFF0F0F0;
        int textPrimary = isDark ? 0xFFE0E0E0 : 0xFF333333;
        int textSecondary = isDark ? 0xFF888888 : 0xFF999999;
        int accent = 0xFF07C160;

        View content = findViewById(android.R.id.content);
        if (content instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) content;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View v = vg.getChildAt(i);
                if (v instanceof ScrollView) {
                    ViewGroup svg = (ViewGroup) v;
                    for (int j = 0; j < svg.getChildCount(); j++) {
                        Object sv = svg.getChildAt(j);
                        if (sv instanceof LinearLayout) {
                            styleContainer((LinearLayout) sv, cardBg, textPrimary, textSecondary, accent);
                        }
                    }
                }
            }
        }
    }

    private void styleContainer(LinearLayout container, int cardBg, int tp, int ts, int accent) {
        for (int k = 0; k < container.getChildCount(); k++) {
            View item = container.getChildAt(k);
            if (item instanceof LinearLayout && item.getId() != android.R.id.content) {
                int id = item.getId();
                if (id == R.id.card_blur || id == R.id.card_alpha ||
                    id == R.id.card_corner || id == R.id.card_buttons ||
                    id == R.id.card_key_border ||
                    id == R.id.card_sync_enable || id == R.id.card_sync_url ||
                    id == R.id.card_sync_auth || id == R.id.card_sync_interval) {
                    styleCard((LinearLayout) item, cardBg, tp, ts, accent);
                }
            } else if (item instanceof TextView) {
                ((TextView) item).setTextColor(ts);
            }
        }
    }

    private void styleCard(LinearLayout card, int bg, int tp, int ts, int accent) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(12));
        gd.setColor(bg);
        card.setBackground(gd);
        card.setElevation(dp(1));
        for (int i = 0; i < card.getChildCount(); i++) {
            View child = card.getChildAt(i);
            if (child instanceof LinearLayout) {
                LinearLayout row = (LinearLayout) child;
                for (int j = 0; j < row.getChildCount(); j++) {
                    View v = row.getChildAt(j);
                    if (v instanceof Switch) continue;
                    if (v instanceof TextView) {
                        TextView tv = (TextView) v;
                        int id = tv.getId();
                        if (id == R.id.tv_blur_val || id == R.id.tv_alpha_val
                                || id == R.id.tv_corner_val || id == R.id.tv_interval_val) {
                            tv.setTextColor(accent);
                        } else {
                            tv.setTextColor(tp);
                        }
                    }
                }
            } else if (child instanceof TextView) {
                ((TextView) child).setTextColor(ts);
            } else if (child instanceof EditText) {
                ((EditText) child).setTextColor(tp);
            }
        }
    }

    private float dp(int dp) { return dp * getResources().getDisplayMetrics().density; }

    // ══════════════════════════════════════════
    //  持久化：本地 SP + fcitx5 文件双写
    // ══════════════════════════════════════════

    private android.content.Context getFcitx5Context() {
        for (String pkg : new String[]{"org.fcitx.fcitx5.android.fx", "org.fcitx.fcitx5.android"}) {
            try {
                android.content.Context ctx = createPackageContext(pkg,
                        android.content.Context.CONTEXT_IGNORE_SECURITY);
                Log.i(TAG, "getFcitx5Context: success via " + pkg);
                return ctx;
            } catch (Exception e) {
                Log.w(TAG, "getFcitx5Context: failed for " + pkg + ": " + e);
            }
        }
        return null;
    }

    private void loadSettings() {
        SharedPreferences sp = getPreferences(MODE_PRIVATE);

        if (sp.getAll().size() > 0) {
            sbBlur.setProgress(sp.getInt("blur_radius", 100));
            sbAlpha.setProgress(sp.getInt("bg_alpha", 60));
            sbCorner.setProgress(sp.getInt("corner_radius", 20));
            swVoice.setChecked(sp.getBoolean("voice_enabled", true));
            swLeft.setChecked(sp.getBoolean("show_left_button", true));
            swRight.setChecked(sp.getBoolean("show_right_button", true));
            swKeyBorder.setChecked(sp.getBoolean("key_border", true));
        } else {
            android.content.Context fcitxCtx = getFcitx5Context();
            if (fcitxCtx != null && ConfigStorage.configFileExists(fcitxCtx)) {
                MainHook.Config cfg = ConfigStorage.readConfigFromFile(fcitxCtx);
                sbBlur.setProgress(cfg.blur);
                sbAlpha.setProgress(cfg.alpha);
                sbCorner.setProgress(cfg.corner);
                swVoice.setChecked(cfg.voice);
                swLeft.setChecked(cfg.leftBtn);
                swRight.setChecked(cfg.rightBtn);
                swKeyBorder.setChecked(cfg.keyBorder);
            }
        }
        updateLabels();
        loadSyncSettings();
    }

    private void updateLabels() {
        tvBlur.setText(sbBlur.getProgress() == 0 ? "关" : String.valueOf(sbBlur.getProgress()));
        tvAlpha.setText((sbAlpha.getProgress() * 100) / 255 + "%");
        tvCorner.setText(sbCorner.getProgress() == 0 ? "关" : String.valueOf(sbCorner.getProgress()));
    }

    private void saveAndApply() {
        Log.i(TAG, "Settings saving: L=" + swLeft.isChecked()
                + " R=" + swRight.isChecked() + " V=" + swVoice.isChecked());

        getPreferences(MODE_PRIVATE).edit()
                .putInt("blur_radius", sbBlur.getProgress())
                .putInt("bg_alpha", sbAlpha.getProgress())
                .putInt("corner_radius", sbCorner.getProgress())
                .putBoolean("voice_enabled", swVoice.isChecked())
                .putBoolean("show_left_button", swLeft.isChecked())
                .putBoolean("show_right_button", swRight.isChecked())
                .putBoolean("key_border", swKeyBorder.isChecked())
                .commit();

        android.content.Context fcitxCtx = getFcitx5Context();
        if (fcitxCtx != null) {
            ConfigStorage.writeConfigToFile(fcitxCtx,
                    sbBlur.getProgress(), sbAlpha.getProgress(), sbCorner.getProgress(),
                    swVoice.isChecked(), swLeft.isChecked(), swRight.isChecked(), swKeyBorder.isChecked());
        }

        android.content.Intent intent = new android.content.Intent("com.rebron1900.fcitx5enhanced.UI_UPDATE");
        intent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        intent.putExtra("show_left_button", swLeft.isChecked());
        intent.putExtra("show_right_button", swRight.isChecked());
        intent.putExtra("voice_enabled", swVoice.isChecked());
        intent.putExtra("key_border", swKeyBorder.isChecked());
        intent.putExtra("blur_radius", sbBlur.getProgress());
        intent.putExtra("bg_alpha", sbAlpha.getProgress());
        intent.putExtra("corner_radius", sbCorner.getProgress());
        try { sendBroadcast(intent); } catch (Exception ignored) {}
    }
}
