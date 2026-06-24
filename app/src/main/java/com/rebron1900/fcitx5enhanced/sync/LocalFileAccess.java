package com.rebron1900.fcitx5enhanced.sync;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

/**
 * 本地文件访问抽象 — 屏蔽 java.io.File 与 SAF DocumentFile 的差异。
 */
public interface LocalFileAccess {

    /** 递归扫描所有文件，返回 {相对路径 → lastModified} */
    Map<String, Long> scanAll();

    /** 打开文件读取流 */
    InputStream openRead(String relPath) throws Exception;

    /** 打开文件写入流（自动创建父目录） */
    OutputStream openWrite(String relPath) throws Exception;

    /** 检查文件是否存在 */
    boolean exists(String relPath);

    /** 备份文件（重命名为 .bak-timestamp） */
    boolean backup(String relPath);

    /** 返回根目录的显示路径 */
    String getDisplayPath();
}
