package com.rebron1900.fcitx5enhanced.sync;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

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
            String result = helper.sync();
            Log.i(TAG, "SyncWorker done: " + result);
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "SyncWorker failed", e);
            return Result.retry();
        }
    }
}
