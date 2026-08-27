package com.rebron1900.fcitx5enhanced;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

/**
 * 跨进程配置共享 Provider。
 *
 * 模块进程负责持久化，目标输入法进程只通过 query/ContentObserver 访问。
 * Provider 必须 exported，调用方白名单负责避免其他应用篡改配置。
 */
public class ConfigProvider extends ContentProvider {
    private static final String TAG = "Fcitx5Enh";
    private static final String[] COLUMNS = {
            ConfigContract.REVISION,
            ConfigContract.SHOW_LEFT_BUTTON,
            ConfigContract.SHOW_RIGHT_BUTTON,
            ConfigContract.VOICE_ENABLED,
            ConfigContract.KEY_BORDER,
            ConfigContract.BLUR_RADIUS,
            ConfigContract.BG_ALPHA,
            ConfigContract.KEY_ALPHA,
            ConfigContract.CORNER_RADIUS
    };

    @Override
    public boolean onCreate() {
        if (getContext() == null) return false;
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            try {
                android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
                    @Override
                    public void onReceive(android.content.Context context,
                                          android.content.Intent intent) {
                        if (!android.content.Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) return;
                        synchronized (ConfigProvider.this) {
                            migrateCredentialProtectedPreferences();
                            migrateLegacyActivityPreferences();
                        }
                        context.getContentResolver().notifyChange(ConfigContract.CONTENT_URI, null);
                    }
                };
                android.content.IntentFilter filter = new android.content.IntentFilter(
                        android.content.Intent.ACTION_USER_UNLOCKED);
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    getContext().registerReceiver(receiver, filter,
                            android.content.Context.RECEIVER_NOT_EXPORTED);
                } else {
                    getContext().registerReceiver(receiver, filter);
                }
            } catch (Throwable t) {
                Log.w(TAG, "user unlock receiver registration failed", t);
            }
        }
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.fcitx5enhanced.config";
    }

    /** 仅允许模块 SettingsActivity 写入。 */
    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        requireValidUri(uri);
        if (!isModuleCaller()) {
            throw new SecurityException("config update is only available to the module");
        }
        if (values == null || values.size() == 0) return 0;

        try {
            // Provider 可能同时收到多个设置页写入，revision 与配置必须在同一临界区递增。
            synchronized (this) {
                SharedPreferences sp = preferences();
                SharedPreferences.Editor editor = sp.edit();
                putBoolean(editor, values, ConfigContract.SHOW_LEFT_BUTTON);
                putBoolean(editor, values, ConfigContract.SHOW_RIGHT_BUTTON);
                putBoolean(editor, values, ConfigContract.VOICE_ENABLED);
                putBoolean(editor, values, ConfigContract.KEY_BORDER);
                putInt(editor, values, ConfigContract.BLUR_RADIUS);
                putInt(editor, values, ConfigContract.BG_ALPHA);
                putInt(editor, values, ConfigContract.KEY_ALPHA);
                putInt(editor, values, ConfigContract.CORNER_RADIUS);

                long revision = ConfigContract.nextRevision(
                        sp.getLong(ConfigContract.REVISION, ConfigContract.DEFAULT_REVISION));
                editor.putLong(ConfigContract.REVISION, revision);
                if (!editor.commit()) {
                    Log.w(TAG, "ConfigProvider.write commit returned false");
                    return 0;
                }
                getContext().getContentResolver().notifyChange(ConfigContract.CONTENT_URI, null);
                Log.i(TAG, "ConfigProvider.write config rev=" + revision);
                return 1;
            }
        } catch (Exception e) {
            Log.w(TAG, "ConfigProvider.write failed", e);
            return 0;
        }
    }

    /** 目标输入法进程读取单行、整型布尔值 Cursor。 */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        requireValidUri(uri);
        if (!isAllowedReader()) {
            throw new SecurityException("config query is not available to this caller");
        }

        // 与 update 使用同一把锁，避免 revision 和各配置字段来自不同写入。
        synchronized (this) {
            SharedPreferences sp = preferences();
            MatrixCursor cursor = new MatrixCursor(COLUMNS);
            cursor.addRow(new Object[]{
                    sp.getLong(ConfigContract.REVISION, ConfigContract.DEFAULT_REVISION),
                    sp.getBoolean(ConfigContract.SHOW_LEFT_BUTTON, ConfigContract.DEFAULT_LEFT_BUTTON) ? 1 : 0,
                    sp.getBoolean(ConfigContract.SHOW_RIGHT_BUTTON, ConfigContract.DEFAULT_RIGHT_BUTTON) ? 1 : 0,
                    sp.getBoolean(ConfigContract.VOICE_ENABLED, ConfigContract.DEFAULT_VOICE) ? 1 : 0,
                    sp.getBoolean(ConfigContract.KEY_BORDER, ConfigContract.DEFAULT_KEY_BORDER) ? 1 : 0,
                    sp.getInt(ConfigContract.BLUR_RADIUS, ConfigContract.DEFAULT_BLUR),
                    sp.getInt(ConfigContract.BG_ALPHA, ConfigContract.DEFAULT_ALPHA),
                    sp.getInt(ConfigContract.KEY_ALPHA, ConfigContract.DEFAULT_KEY_ALPHA),
                    sp.getInt(ConfigContract.CORNER_RADIUS, ConfigContract.DEFAULT_CORNER)
            });
            return cursor;
        }
    }

    private SharedPreferences preferences() {
        migrateCredentialProtectedPreferences();
        migrateLegacyActivityPreferences();
        return rawPreferences();
    }

    private SharedPreferences rawPreferences() {
        return getContext().getSharedPreferences(
                ConfigContract.PREFS_NAME, android.content.Context.MODE_PRIVATE);
    }

    /** 将 Direct Boot 旧配置迁回 LSPosed 远程偏好使用的凭据保护存储。 */
    private synchronized void migrateCredentialProtectedPreferences() {
        if (android.os.Build.VERSION.SDK_INT < 24
                || !((android.os.UserManager) getContext().getSystemService(
                        android.content.Context.USER_SERVICE)).isUserUnlocked()) return;

        SharedPreferences target = rawPreferences();
        if (isInitialized(target)) return;
        SharedPreferences source = getContext().createDeviceProtectedStorageContext()
                .getSharedPreferences(ConfigContract.PREFS_NAME,
                        android.content.Context.MODE_PRIVATE);
        if (!isInitialized(source)) return;

        if (copyConfig(source, target)) {
            Log.i(TAG, "migrated device protected config");
        }
    }

    /**
     * 旧版设置页使用 Activity.getPreferences()；Provider 首次访问时就迁移，
     * 避免用户升级后必须先打开设置页才能恢复已有配置。
     */
    private synchronized void migrateLegacyActivityPreferences() {
        if (android.os.Build.VERSION.SDK_INT >= 24
                && !((android.os.UserManager) getContext().getSystemService(
                        android.content.Context.USER_SERVICE)).isUserUnlocked()) return;
        SharedPreferences target = rawPreferences();
        // revision 也作为统一配置已初始化标记；设置页 fallback 可能只写了增量字段。
        if (target.contains(ConfigContract.BLUR_RADIUS)
                || target.contains(ConfigContract.REVISION)) return;

        // Android Activity.getLocalClassName() 在不同实现中可能保留或省略开头的点。
        String[] legacyNames = {
                ".SettingsActivity",
                "SettingsActivity",
                "com.rebron1900.fcitx5enhanced.SettingsActivity"
        };
        for (String name : legacyNames) {
            SharedPreferences legacy = getContext().getSharedPreferences(
                    name, android.content.Context.MODE_PRIVATE);
            if (!legacy.contains(ConfigContract.BLUR_RADIUS)) continue;

            boolean migrated = target.edit()
                    .putInt(ConfigContract.BLUR_RADIUS,
                            legacy.getInt(ConfigContract.BLUR_RADIUS, ConfigContract.DEFAULT_BLUR))
                    .putInt(ConfigContract.BG_ALPHA,
                            legacy.getInt(ConfigContract.BG_ALPHA, ConfigContract.DEFAULT_ALPHA))
                    .putInt(ConfigContract.KEY_ALPHA,
                            legacy.getInt(ConfigContract.KEY_ALPHA, ConfigContract.DEFAULT_KEY_ALPHA))
                    .putInt(ConfigContract.CORNER_RADIUS,
                            legacy.getInt(ConfigContract.CORNER_RADIUS, ConfigContract.DEFAULT_CORNER))
                    .putBoolean(ConfigContract.VOICE_ENABLED,
                            legacy.getBoolean(ConfigContract.VOICE_ENABLED, ConfigContract.DEFAULT_VOICE))
                    .putBoolean(ConfigContract.SHOW_LEFT_BUTTON,
                            legacy.getBoolean(ConfigContract.SHOW_LEFT_BUTTON,
                                    ConfigContract.DEFAULT_LEFT_BUTTON))
                    .putBoolean(ConfigContract.SHOW_RIGHT_BUTTON,
                            legacy.getBoolean(ConfigContract.SHOW_RIGHT_BUTTON,
                                    ConfigContract.DEFAULT_RIGHT_BUTTON))
                    .putBoolean(ConfigContract.KEY_BORDER,
                            legacy.getBoolean(ConfigContract.KEY_BORDER,
                                    ConfigContract.DEFAULT_KEY_BORDER))
                    .putLong(ConfigContract.REVISION,
                            ConfigContract.nextRevision(target.getLong(
                                    ConfigContract.REVISION, ConfigContract.DEFAULT_REVISION)))
                    .commit();
            if (migrated) Log.i(TAG, "migrated legacy activity preferences");
            return;
        }
    }

    private static boolean isInitialized(SharedPreferences preferences) {
        return preferences.contains(ConfigContract.BLUR_RADIUS)
                || preferences.contains(ConfigContract.REVISION);
    }

    private static boolean copyConfig(SharedPreferences source, SharedPreferences target) {
        return target.edit()
                .putLong(ConfigContract.REVISION,
                        source.getLong(ConfigContract.REVISION, ConfigContract.DEFAULT_REVISION))
                .putInt(ConfigContract.BLUR_RADIUS,
                        source.getInt(ConfigContract.BLUR_RADIUS, ConfigContract.DEFAULT_BLUR))
                .putInt(ConfigContract.BG_ALPHA,
                        source.getInt(ConfigContract.BG_ALPHA, ConfigContract.DEFAULT_ALPHA))
                .putInt(ConfigContract.KEY_ALPHA,
                        source.getInt(ConfigContract.KEY_ALPHA, ConfigContract.DEFAULT_KEY_ALPHA))
                .putInt(ConfigContract.CORNER_RADIUS,
                        source.getInt(ConfigContract.CORNER_RADIUS, ConfigContract.DEFAULT_CORNER))
                .putBoolean(ConfigContract.VOICE_ENABLED,
                        source.getBoolean(ConfigContract.VOICE_ENABLED, ConfigContract.DEFAULT_VOICE))
                .putBoolean(ConfigContract.SHOW_LEFT_BUTTON,
                        source.getBoolean(ConfigContract.SHOW_LEFT_BUTTON,
                                ConfigContract.DEFAULT_LEFT_BUTTON))
                .putBoolean(ConfigContract.SHOW_RIGHT_BUTTON,
                        source.getBoolean(ConfigContract.SHOW_RIGHT_BUTTON,
                                ConfigContract.DEFAULT_RIGHT_BUTTON))
                .putBoolean(ConfigContract.KEY_BORDER,
                        source.getBoolean(ConfigContract.KEY_BORDER,
                                ConfigContract.DEFAULT_KEY_BORDER))
                .commit();
    }

    private void requireValidUri(Uri uri) {
        if (!ConfigContract.CONTENT_URI.equals(uri)) {
            throw new IllegalArgumentException("unsupported config URI: " + uri);
        }
    }

    private boolean isModuleCaller() {
        int uid = android.os.Binder.getCallingUid();
        return uid == android.os.Process.myUid()
                || uidHasPackage(uid, getContext().getPackageName());
    }

    private boolean isAllowedReader() {
        int uid = android.os.Binder.getCallingUid();
        return uid == android.os.Process.myUid()
                || uidHasPackage(uid, "org.fcitx.fcitx5.android")
                || uidHasPackage(uid, "org.fcitx.fcitx5.android.fx");
    }

    private boolean uidHasPackage(int uid, String packageName) {
        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        if (packages == null) return false;
        for (String pkg : packages) {
            if (packageName.equals(pkg)) return true;
        }
        return false;
    }

    private static void putBoolean(SharedPreferences.Editor editor, ContentValues values, String key) {
        if (!values.containsKey(key)) return;
        Object value = values.get(key);
        if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof Number) {
            editor.putBoolean(key, ((Number) value).intValue() != 0);
        } else {
            editor.putBoolean(key, Boolean.parseBoolean(String.valueOf(value)));
        }
    }

    private static void putInt(SharedPreferences.Editor editor, ContentValues values, String key) {
        if (!values.containsKey(key)) return;
        Object value = values.get(key);
        if (value instanceof Number) {
            editor.putInt(key, ((Number) value).intValue());
        } else {
            editor.putInt(key, Integer.parseInt(String.valueOf(value)));
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
}
