package com.rebron1900.fcitx5enhanced.sync;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.rebron1900.fcitx5enhanced.ConfigStorage;

/**
 * WorkManager Worker — 定时执行 WebDAV 同步。
 *
 * 由 SettingsActivity 注册，MainHook 保活。
 */
public class SyncWorker extends Worker {

    private static final String TAG = "Fcitx5Sync";

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.i(TAG, "SyncWorker started");
        try {
            WebDavSyncHelper helper = new WebDavSyncHelper(
                    getApplicationContext(),
                    LocalFileAccessFactory.create(getApplicationContext()));
            WebDavSyncHelper.SyncResult result = helper.sync();
            ConfigStorage.saveLastSyncResult(getApplicationContext(), result.toToastString(), System.currentTimeMillis());
            Log.i(TAG, "SyncWorker done: " + result.toToastString());

            // Toast 通知
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                android.widget.Toast.makeText(getApplicationContext(), result.toToastString(), android.widget.Toast.LENGTH_SHORT).show();
            });

            return Result.success();
        } catch (IllegalArgumentException e) {
            // 配置错误（URL/账号未填），不重试
            String msg = "同步失败: " + e.getMessage();
            ConfigStorage.saveLastSyncResult(getApplicationContext(), msg, System.currentTimeMillis());
            Log.e(TAG, "SyncWorker config error", e);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                android.widget.Toast.makeText(getApplicationContext(), msg, android.widget.Toast.LENGTH_LONG).show();
            });
            return Result.failure();
        } catch (Exception e) {
            ConfigStorage.saveLastSyncResult(getApplicationContext(), "失败: " + e.getMessage(), System.currentTimeMillis());
            Log.e(TAG, "SyncWorker failed", e);
            return Result.retry();
        }
    }
}
