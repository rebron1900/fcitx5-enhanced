package com.rebron1900.fcitx5enhanced;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.UserManager;
import android.util.Log;

/** 用户解锁后将 Direct Boot 旧配置迁回 LSPosed 远程偏好使用的凭据保护存储。 */
public class ConfigMigrationReceiver extends BroadcastReceiver {
    private static final String TAG = "Fcitx5Enh";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (android.os.Build.VERSION.SDK_INT < 24) return;
        String action = intent != null ? intent.getAction() : null;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_USER_UNLOCKED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;

        // BOOT_COMPLETED 通常晚于解锁；USER_UNLOCKED 负责锁屏启动后的闭环；
        // MY_PACKAGE_REPLACED 若发生在锁屏阶段则等待后续 USER_UNLOCKED。
        UserManager userManager = context.getSystemService(UserManager.class);
        if (userManager == null || !userManager.isUserUnlocked()) return;

        SharedPreferences target = context.getSharedPreferences(
                ConfigContract.PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences source = context.createDeviceProtectedStorageContext()
                .getSharedPreferences(ConfigContract.PREFS_NAME, Context.MODE_PRIVATE);
        boolean migrated = false;
        if (!isInitialized(target) && isInitialized(source)) {
            SharedPreferences.Editor editor = target.edit();
            ConfigContract.copyToEditor(source, editor);
            migrated = editor.commit();
        }
        if (migrated) Log.i(TAG, "migrated device protected config");
        // 无论是否需要迁移，都唤醒锁屏阶段已启动的输入法重新拉取凭据配置。
        try {
            context.getContentResolver().notifyChange(ConfigContract.CONTENT_URI, null);
        } catch (Throwable t) {
            Log.w(TAG, "post-migration config notify failed", t);
        }
    }

    private static boolean isInitialized(SharedPreferences preferences) {
        return preferences.contains(ConfigContract.REVISION)
                || preferences.contains(ConfigContract.BLUR_RADIUS);
    }
}
