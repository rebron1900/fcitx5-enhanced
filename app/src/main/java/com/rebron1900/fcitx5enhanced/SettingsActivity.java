package com.rebron1900.fcitx5enhanced;

import android.app.Activity;
import android.content.ContentValues;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

public class SettingsActivity extends Activity {

    private static final String CONFIG_PATH = "/data/local/tmp/fcitx5_enhanced_config.json";

    private SeekBar sbBlur, sbAlpha, sbCorner;
    private TextView tvBlur, tvAlpha, tvCorner;
    private Switch swVoice, swLeft, swRight;
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

        // Switch listeners
        View.OnClickListener switchListener = v -> saveAndApply();
        swVoice.setOnClickListener(switchListener);
        swLeft.setOnClickListener(switchListener);
        swRight.setOnClickListener(switchListener);

        loadSettings();
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
                    id == R.id.card_corner || id == R.id.card_buttons) {
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
                                || id == R.id.tv_corner_val) {
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

    private void loadSettings() {
        try {
            Cursor c = getContentResolver().query(
                    ConfigContentProvider.CONTENT_URI, null, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        sbBlur.setProgress(c.getInt(c.getColumnIndexOrThrow("blur_radius")));
                        sbAlpha.setProgress(c.getInt(c.getColumnIndexOrThrow("bg_alpha")));
                        sbCorner.setProgress(c.getInt(c.getColumnIndexOrThrow("corner_radius")));
                        swVoice.setChecked(c.getInt(c.getColumnIndexOrThrow("voice_enabled")) != 0);
                        swLeft.setChecked(c.getInt(c.getColumnIndexOrThrow("show_left_button")) != 0);
                        swRight.setChecked(c.getInt(c.getColumnIndexOrThrow("show_right_button")) != 0);
                    }
                } finally { c.close(); }
            }
        } catch (Exception ignored) {}

        restoreFromFile();
        updateLabels();
    }

    private void restoreFromFile() {
        try {
            File f = new File(CONFIG_PATH);
            if (!f.exists()) return;
            BufferedReader br = new BufferedReader(new FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            JSONObject json = new JSONObject(sb.toString());
            sbBlur.setProgress(json.optInt("blur_radius", 100));
            sbAlpha.setProgress(json.optInt("bg_alpha", 60));
            sbCorner.setProgress(json.optInt("corner_radius", 20));
            swVoice.setChecked(json.optBoolean("voice_enabled", true));
            swLeft.setChecked(json.optBoolean("show_left_button", true));
            swRight.setChecked(json.optBoolean("show_right_button", true));
        } catch (Exception ignored) {}
    }

    private void updateLabels() {
        tvBlur.setText(sbBlur.getProgress() == 0 ? "关" : String.valueOf(sbBlur.getProgress()));
        tvAlpha.setText((sbAlpha.getProgress() * 100) / 255 + "%");
        tvCorner.setText(sbCorner.getProgress() == 0 ? "关" : String.valueOf(sbCorner.getProgress()));
    }

    private void saveAndApply() {
        ContentValues cv = new ContentValues();
        cv.put("blur_radius", sbBlur.getProgress());
        cv.put("bg_alpha", sbAlpha.getProgress());
        cv.put("corner_radius", sbCorner.getProgress());
        cv.put("voice_enabled", swVoice.isChecked());
        cv.put("show_left_button", swLeft.isChecked());
        cv.put("show_right_button", swRight.isChecked());
        try {
            getContentResolver().update(ConfigContentProvider.CONTENT_URI, cv, null, null);
        } catch (Exception ignored) {}
        saveToFile();
        android.content.Intent intent = new android.content.Intent("com.rebron1900.fcitx5enhanced.UI_UPDATE");
        try { sendBroadcast(intent); } catch (Exception ignored) {}
    }

    private void saveToFile() {
        try {
            JSONObject json = new JSONObject();
            json.put("blur_radius", sbBlur.getProgress());
            json.put("bg_alpha", sbAlpha.getProgress());
            json.put("corner_radius", sbCorner.getProgress());
            json.put("voice_enabled", swVoice.isChecked());
            json.put("show_left_button", swLeft.isChecked());
            json.put("show_right_button", swRight.isChecked());
            FileWriter fw = new FileWriter(CONFIG_PATH);
            fw.write(json.toString(2));
            fw.close();
        } catch (Exception ignored) {}
    }
}
