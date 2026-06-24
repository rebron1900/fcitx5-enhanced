package com.rebron1900.fcitx5enhanced.sync;

import android.content.Context;
import android.net.Uri;

import com.rebron1900.fcitx5enhanced.ConfigStorage;

import java.io.File;

/**
 * 工厂 — 根据配置创建合适的 LocalFileAccess。
 */
public class LocalFileAccessFactory {

    public static LocalFileAccess create(Context context) {
        // 优先 SAF URI（用户通过文件选择器授权的目录）
        Uri saUri = ConfigStorage.getSyncDirUri(context);
        if (saUri != null) {
            return new SafLocalAccess(context, saUri);
        }
        // 回退到直接文件路径
        File dir = ConfigStorage.getRimeSyncDir(context);
        return new FileLocalAccess(dir);
    }
}
