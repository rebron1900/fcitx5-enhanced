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
            WebDavSyncHelper helper = new WebDavSyncHelper(getApplicationContext());
            WebDavSyncHelper.SyncResult result = helper.sync();
            ConfigStorage.saveLastSyncResult(getApplicationContext(), result.toToastString(), System.currentTimeMillis());
            Log.i(TAG, "SyncWorker done: " + result.toToastString());

            // Toast 通知
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                android.widget.Toast.makeText(getApplicationContext(), result.toToastString(), android.widget.Toast.LENGTH_SHORT).show();
            });

            return Result.success();
        } catch (Exception e) {
            ConfigStorage.saveLastSyncResult(getApplicationContext(), "失败: " + e.getMessage(), System.currentTimeMillis());
            Log.e(TAG, "SyncWorker failed", e);
            return Result.retry();
        }
    }
}
