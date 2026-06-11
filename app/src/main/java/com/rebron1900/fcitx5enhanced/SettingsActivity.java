package com.rebron1900.fcitx5enhanced;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

public class SettingsActivity extends Activity {

    private SeekBar sbBlur, sbAlpha, sbCorner;
    private TextView tvBlur, tvAlpha, tvCorner;
    private Switch swVoice, swLeft, swRight, swKeyBorder;
    private View colorKeyBorder, colorKbBorder;
    private SeekBar sbKeyBorderWidth, sbKbBorderWidth;
    private TextView tvKeyBorderWidth, tvKbBorderWidth;
    private boolean isDark;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        isDark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        setContentView(R.layout.activity_settings);
        setTitle("Fcitx5 增强");

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
        colorKeyBorder = findViewById(R.id.color_key_border);
        colorKbBorder = findViewById(R.id.color_kb_border);
        sbKeyBorderWidth = findViewById(R.id.sb_key_border_width);
        sbKbBorderWidth = findViewById(R.id.sb_kb_border_width);
        tvKeyBorderWidth = findViewById(R.id.tv_key_border_width);
        tvKbBorderWidth = findViewById(R.id.tv_kb_border_width);

        applyTheme();

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                if (sb == sbBlur) tvBlur.setText(progress == 0 ? "关" : progress + "");
                else if (sb == sbAlpha) tvAlpha.setText((progress * 100) / 255 + "%");
                else if (sb == sbCorner) tvCorner.setText(progress == 0 ? "关" : progress + "");
                else if (sb == sbKeyBorderWidth)
                    tvKeyBorderWidth.setText(String.format("%.1f", progress / 10f));
                else if (sb == sbKbBorderWidth)
                    tvKbBorderWidth.setText(String.format("%.1f", progress / 10f));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) { saveAndApply(); }
        };

        sbBlur.setOnSeekBarChangeListener(listener);
        sbAlpha.setOnSeekBarChangeListener(listener);
        sbCorner.setOnSeekBarChangeListener(listener);
        sbKeyBorderWidth.setOnSeekBarChangeListener(listener);
        sbKbBorderWidth.setOnSeekBarChangeListener(listener);

        View.OnClickListener switchListener = v -> saveAndApply();
        swVoice.setOnClickListener(switchListener);
        swLeft.setOnClickListener(switchListener);
        swRight.setOnClickListener(switchListener);
        swKeyBorder.setOnClickListener(switchListener);

        // 颜色预览点击 → 弹出拾色器
        colorKeyBorder.setOnClickListener(v -> pickColor(
                "按键描边颜色", getBorderColor("key_border_color_dark", 0x22FFFFFF),
                argb -> {
                    setBorderColor("key_border_color_dark", "key_border_color_light", argb);
                    colorKeyBorder.setBackgroundColor(argb);
                    saveAndApply();
                }));
        colorKbBorder.setOnClickListener(v -> pickColor(
                "键盘轮廓颜色", getBorderColor("kb_border_color_dark", 0x33FFFFFF),
                argb -> {
                    setBorderColor("kb_border_color_dark", "kb_border_color_light", argb);
                    colorKbBorder.setBackgroundColor(argb);
                    saveAndApply();
                }));

        loadSettings();
    }

    private int getBorderColor(String key, int def) {
        return getPreferences(MODE_PRIVATE).getInt(key, def);
    }

    private void setBorderColor(String keyDark, String keyLight, int argb) {
        getPreferences(MODE_PRIVATE).edit()
                .putInt(keyDark, argb)
                .putInt(keyLight, argb)
                .commit();
    }

    private void pickColor(String title, int initial, java.util.function.IntConsumer onResult) {
        new ColorPickerDialog(this, initial, onResult).show();
    }

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
                v.setBackgroundColor(pageBg);
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
                    id == R.id.card_key_border || id == R.id.card_kb_border) {
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
                                || id == R.id.tv_corner_val
                                || id == R.id.tv_key_border_width
                                || id == R.id.tv_kb_border_width) {
                            tv.setTextColor(accent);
                        } else {
                            tv.setTextColor(tp);
                        }
                    }
                }
            } else if (child instanceof TextView) {
                ((TextView) child).setTextColor(ts);
            }
        }
    }

    private float dp(int dp) { return dp * getResources().getDisplayMetrics().density; }

    // ══════════════════════════════════════════
    //  持久化：本地 SP ← → 跨进程同步
    // ══════════════════════════════════════════

    private void loadSettings() {
        SharedPreferences sp = getPreferences(MODE_PRIVATE);
        sbBlur.setProgress(sp.getInt("blur_radius", 100));
        sbAlpha.setProgress(sp.getInt("bg_alpha", 60));
        sbCorner.setProgress(sp.getInt("corner_radius", 20));
        swVoice.setChecked(sp.getBoolean("voice_enabled", true));
        swLeft.setChecked(sp.getBoolean("show_left_button", true));
        swRight.setChecked(sp.getBoolean("show_right_button", true));
        swKeyBorder.setChecked(sp.getBoolean("key_border", true));
        sbKeyBorderWidth.setProgress(sp.getInt("key_border_width", 8));
        sbKbBorderWidth.setProgress(sp.getInt("kb_border_width", 10));
        colorKeyBorder.setBackgroundColor(
                sp.getInt("key_border_color_dark", 0x22FFFFFF));
        colorKbBorder.setBackgroundColor(
                sp.getInt("kb_border_color_dark", 0x33FFFFFF));
        updateLabels();
    }

    private void updateLabels() {
        tvBlur.setText(sbBlur.getProgress() == 0 ? "关" : String.valueOf(sbBlur.getProgress()));
        tvAlpha.setText((sbAlpha.getProgress() * 100) / 255 + "%");
        tvCorner.setText(sbCorner.getProgress() == 0 ? "关" : String.valueOf(sbCorner.getProgress()));
        tvKeyBorderWidth.setText(String.format("%.1f", sbKeyBorderWidth.getProgress() / 10f));
        tvKbBorderWidth.setText(String.format("%.1f", sbKbBorderWidth.getProgress() / 10f));
    }

    private void saveAndApply() {
        android.util.Log.i("Fcitx5Enh", "Settings saving");

        float keyBw = sbKeyBorderWidth.getProgress() / 10f;
        float kbBw = sbKbBorderWidth.getProgress() / 10f;
        int keyCol = getBorderColor("key_border_color_dark", 0x22FFFFFF);
        int kbCol = getBorderColor("kb_border_color_dark", 0x33FFFFFF);

        // 1. 本地持久化
        getPreferences(MODE_PRIVATE).edit()
                .putInt("blur_radius", sbBlur.getProgress())
                .putInt("bg_alpha", sbAlpha.getProgress())
                .putInt("corner_radius", sbCorner.getProgress())
                .putBoolean("voice_enabled", swVoice.isChecked())
                .putBoolean("show_left_button", swLeft.isChecked())
                .putBoolean("show_right_button", swRight.isChecked())
                .putBoolean("key_border", swKeyBorder.isChecked())
                .putInt("key_border_color_dark", keyCol)
                .putInt("key_border_color_light", keyCol)
                .putInt("key_border_width", sbKeyBorderWidth.getProgress())
                .putInt("kb_border_color_dark", kbCol)
                .putInt("kb_border_color_light", kbCol)
                .putInt("kb_border_width", sbKbBorderWidth.getProgress())
                .commit();

        // 2. ContentProvider
        try {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put("show_left_button", swLeft.isChecked());
            cv.put("show_right_button", swRight.isChecked());
            cv.put("voice_enabled", swVoice.isChecked());
            cv.put("key_border", swKeyBorder.isChecked());
            cv.put("blur_radius", sbBlur.getProgress());
            cv.put("bg_alpha", sbAlpha.getProgress());
            cv.put("corner_radius", sbCorner.getProgress());
            cv.put("key_border_color_dark", keyCol);
            cv.put("key_border_color_light", keyCol);
            cv.put("key_border_width", sbKeyBorderWidth.getProgress());
            cv.put("kb_border_color_dark", kbCol);
            cv.put("kb_border_color_light", kbCol);
            cv.put("kb_border_width", sbKbBorderWidth.getProgress());
            android.net.Uri uri = android.net.Uri.parse("content://com.rebron1900.fcitx5enhanced.config");
            getContentResolver().update(uri, cv, null, null);
        } catch (Exception e) {
            android.util.Log.w("Fcitx5Enh", "ConfigProvider write failed: " + e);
        }

        // 3. Broadcast
        android.content.Intent intent = new android.content.Intent("com.rebron1900.fcitx5enhanced.UI_UPDATE");
        intent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        intent.putExtra("show_left_button", swLeft.isChecked());
        intent.putExtra("show_right_button", swRight.isChecked());
        intent.putExtra("voice_enabled", swVoice.isChecked());
        intent.putExtra("key_border", swKeyBorder.isChecked());
        intent.putExtra("blur_radius", sbBlur.getProgress());
        intent.putExtra("bg_alpha", sbAlpha.getProgress());
        intent.putExtra("corner_radius", sbCorner.getProgress());
        intent.putExtra("key_border_color_dark", keyCol);
        intent.putExtra("key_border_color_light", keyCol);
        intent.putExtra("key_border_width", sbKeyBorderWidth.getProgress());
        intent.putExtra("kb_border_color_dark", kbCol);
        intent.putExtra("kb_border_color_light", kbCol);
        intent.putExtra("kb_border_width", sbKbBorderWidth.getProgress());
        try { sendBroadcast(intent); } catch (Exception ignored) {}
    }
}
