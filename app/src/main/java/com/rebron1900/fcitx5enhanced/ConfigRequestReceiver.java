package com.rebron1900.fcitx5enhanced;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;

/** 通过显式有序广播向 Hook 进程返回只读配置，绕过 Android 包可见性过滤。 */
public class ConfigRequestReceiver extends BroadcastReceiver {
    private static final String TAG = "Fcitx5Enh";
    public static final String ACTION_REQUEST =
            "com.rebron1900.fcitx5enhanced.action.REQUEST_CONFIG";
    public static final String ACTION_CALLER_IDENTITY =
            "com.rebron1900.fcitx5enhanced.action.CALLER_IDENTITY";
    public static final String EXTRA_CALLER_IDENTITY = "caller_identity";
    public static final String EXTRA_CONFIG = "config";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_REQUEST.equals(intent.getAction())) return;
        if (!isAllowedCaller(context, intent)) {
            Log.w(TAG, "config broadcast rejected: untrusted caller");
            return;
        }
        SharedPreferences preferences = context.getSharedPreferences(
                ConfigContract.PREFS_NAME, Context.MODE_PRIVATE);
        if (!preferences.contains(ConfigContract.REVISION)
                && !preferences.contains(ConfigContract.BLUR_RADIUS)) return;

        Bundle config = new Bundle();
        config.putLong(ConfigContract.REVISION, preferences.getLong(
                ConfigContract.REVISION, ConfigContract.DEFAULT_REVISION));
        config.putInt(ConfigContract.BLUR_RADIUS, preferences.getInt(
                ConfigContract.BLUR_RADIUS, ConfigContract.DEFAULT_BLUR));
        config.putInt(ConfigContract.BG_ALPHA, preferences.getInt(
                ConfigContract.BG_ALPHA, ConfigContract.DEFAULT_ALPHA));
        config.putInt(ConfigContract.KEY_ALPHA, preferences.getInt(
                ConfigContract.KEY_ALPHA, ConfigContract.DEFAULT_KEY_ALPHA));
        config.putInt(ConfigContract.CORNER_RADIUS, preferences.getInt(
                ConfigContract.CORNER_RADIUS, ConfigContract.DEFAULT_CORNER));
        config.putInt(ConfigContract.BUTTON_BOTTOM_MARGIN, preferences.getInt(
                ConfigContract.BUTTON_BOTTOM_MARGIN, ConfigContract.DEFAULT_BUTTON_BOTTOM_MARGIN));
        config.putInt(ConfigContract.BUTTON_BOTTOM_MARGIN, preferences.getInt(
                ConfigContract.BUTTON_BOTTOM_MARGIN, ConfigContract.DEFAULT_BUTTON_BOTTOM_MARGIN));
        config.putBoolean(ConfigContract.VOICE_ENABLED, preferences.getBoolean(
                ConfigContract.VOICE_ENABLED, ConfigContract.DEFAULT_VOICE));
        config.putBoolean(ConfigContract.SHOW_LEFT_BUTTON, preferences.getBoolean(
                ConfigContract.SHOW_LEFT_BUTTON, ConfigContract.DEFAULT_LEFT_BUTTON));
        config.putBoolean(ConfigContract.SHOW_RIGHT_BUTTON, preferences.getBoolean(
                ConfigContract.SHOW_RIGHT_BUTTON, ConfigContract.DEFAULT_RIGHT_BUTTON));
        config.putBoolean(ConfigContract.KEY_BORDER, preferences.getBoolean(
                ConfigContract.KEY_BORDER, ConfigContract.DEFAULT_KEY_BORDER));

        Bundle result = new Bundle();
        result.putBundle(EXTRA_CONFIG, config);
        setResultExtras(result);
        setResultCode(android.app.Activity.RESULT_OK);
    }

    /** API 35+ 使用系统记录的广播发送者；旧系统使用不可伪造的 PendingIntent creator。 */
    private boolean isAllowedCaller(Context context, Intent intent) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 35) {
                return uidHasAllowedPackage(context, getSentFromUid());
            }

            PendingIntent identity;
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                identity = intent.getParcelableExtra(EXTRA_CALLER_IDENTITY, PendingIntent.class);
            } else {
                identity = intent.getParcelableExtra(EXTRA_CALLER_IDENTITY);
            }
            return identity != null && isAllowedIdentity(
                    context, identity.getCreatorUid(), identity.getCreatorPackage());
        } catch (Throwable t) {
            // 恶意调用方可能传入错误类型的 extra，拒绝而非崩溃（接收器 exported）。
            Log.w(TAG, "config caller identity check failed, rejected: " + t.getMessage());
            return false;
        }
    }

    private static boolean isAllowedIdentity(Context context, int uid, String packageName) {
        if (!"org.fcitx.fcitx5.android".equals(packageName)
                && !"org.fcitx.fcitx5.android.fx".equals(packageName)) return false;
        return uidHasPackage(context, uid, packageName);
    }

    private static boolean uidHasAllowedPackage(Context context, int uid) {
        return uidHasPackage(context, uid, "org.fcitx.fcitx5.android")
                || uidHasPackage(context, uid, "org.fcitx.fcitx5.android.fx");
    }

    private static boolean uidHasPackage(Context context, int uid, String packageName) {
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null) return false;
        for (String candidate : packages) {
            if (packageName.equals(candidate)) return true;
        }
        return false;
    }
}
