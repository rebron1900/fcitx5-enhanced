package com.rebron1900.fcitx5enhanced.sync;

import android.content.Context;
import android.util.Log;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.rebron1900.fcitx5enhanced.ConfigStorage;

import java.util.concurrent.TimeUnit;

/**
 * 同步调度器 — 管理 WorkManager 周期任务和即时同步。
 */
public class SyncManager {

    private static final String TAG = "Fcitx5Sync";
    private static final String WORK_NAME = "rime_webdav_sync";

    /** 根据配置注册/取消定时同步任务 */
    public static void scheduleSync(Context context) {
        WorkManager wm = WorkManager.getInstance(context);

        if (!ConfigStorage.isWebDavEnabled(context)) {
            wm.cancelUniqueWork(WORK_NAME);
            Log.i(TAG, "sync disabled, cancelled work");
            return;
        }

        int interval = ConfigStorage.getSyncInterval(context);
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                SyncWorker.class, interval, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .addTag(WORK_NAME)
                .build();

        wm.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request);

        Log.i(TAG, "sync scheduled: every " + interval + " min");
    }

    /** 立即执行一次同步（不等待定时任务） */
    public static void syncNow(Context context, SyncCallback callback) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SyncWorker.class)
                .setConstraints(constraints)
                .addTag("rime_sync_now")
                .build();

        WorkManager.getInstance(context)
                .enqueue(request);

        // 监听结果
        WorkManager.getInstance(context)
                .getWorkInfoByIdLiveData(request.getId())
                .observeForever(info -> {
                    if (info != null && info.getState().isFinished()) {
                        String result = info.getState() == WorkInfo.State.SUCCEEDED
                                ? "同步完成" : "同步失败";
                        if (callback != null) callback.onResult(result);
                    }
                });

        Log.i(TAG, "sync now triggered");
    }

    /** 初始化：应用启动时注册定时任务 */
    public static void init(Context context) {
        if (ConfigStorage.isWebDavEnabled(context)) {
            scheduleSync(context);
        }
    }

    public interface SyncCallback {
        void onResult(String result);
    }
}
