package com.rebron1900.fcitx5enhanced.sync;

import android.content.Context;
import android.util.Log;

import com.rebron1900.fcitx5enhanced.ConfigStorage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * WebDAV 双向同步 — 读写 RIME sync 目录与 WebDAV 服务器。
 *
 * 同步策略：Last-write-wins + 冲突备份
 * - WebDAV 更新 → 下载覆盖本地（本地旧文件备份为 .bak）
 * - 本地更新 → 上传覆盖 WebDAV
 * - 时间戳接近（±30s）→ 跳过
 */
public class WebDavSyncHelper {

    private static final String TAG = "Fcitx5Sync";
    private static final long CONFLICT_THRESHOLD_MS = 30_000; // 30秒

    private final OkHttpClient client;
    private final String baseUrl;
    private final String credentials;
    private final File localDir;

    public WebDavSyncHelper(Context context) {
        this.baseUrl = ensureTrailingSlash(ConfigStorage.getWebDavUrl(context));
        this.credentials = Credentials.basic(
                ConfigStorage.getWebDavUser(context),
                ConfigStorage.getWebDavPass(context));
        this.localDir = ConfigStorage.getRimeSyncDir(context);

        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(false)  // WebDAV 重定向需手动处理
                .build();
    }

    /** 执行一次完整同步，返回人类可读的结果描述。 */
    public String sync() {
        Log.i(TAG, "sync start: local=" + localDir.getAbsolutePath() + " remote=" + baseUrl);
        int downloaded = 0, uploaded = 0, skipped = 0, conflicts = 0;

        try {
            // 1. 获取远端文件列表
            Map<String, Long> remoteFiles = listRemote();
            Log.i(TAG, "remote files: " + remoteFiles.size());

            // 2. 获取本地文件列表
            Map<String, Long> localFiles = listLocal();
            Log.i(TAG, "local files: " + localFiles.size());

            // 3. 处理远端文件
            for (Map.Entry<String, Long> entry : remoteFiles.entrySet()) {
                String name = entry.getKey();
                long remoteTime = entry.getValue();

                if (localFiles.containsKey(name)) {
                    long localTime = localFiles.get(name);
                    long diff = remoteTime - localTime;

                    if (Math.abs(diff) < CONFLICT_THRESHOLD_MS) {
                        // 时间戳接近，跳过
                        skipped++;
                    } else if (diff > 0) {
                        // 远端更新 → 下载覆盖
                        backupLocal(name);
                        if (downloadFile(name)) downloaded++;
                    } else {
                        // 本地更新 → 上传
                        if (uploadFile(name)) uploaded++;
                    }
                } else {
                    // 远端有、本地无 → 下载
                    if (downloadFile(name)) downloaded++;
                }
            }

            // 4. 处理本地独有的文件
            for (String name : localFiles.keySet()) {
                if (!remoteFiles.containsKey(name)) {
                    if (uploadFile(name)) uploaded++;
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "sync failed", e);
            return "同步失败: " + e.getMessage();
        }

        String result = String.format(Locale.getDefault(),
                "同步完成: 下载 %d, 上传 %d, 跳过 %d", downloaded, uploaded, skipped);
        Log.i(TAG, result);
        return result;
    }

    // ══════════════════════════════════════════
    //  WebDAV 操作
    // ══════════════════════════════════════════

    /** PROPFIND 获取远端文件列表 {name → lastModified} */
    private Map<String, Long> listRemote() throws IOException {
        Map<String, Long> result = new HashMap<>();

        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<D:propfind xmlns:D=\"DAV:\">"
                + "  <D:allprop/>"
                + "</D:propfind>";

        Request request = new Request.Builder()
                .url(baseUrl)
                .method("PROPFIND", RequestBody.create(body, MediaType.parse("application/xml")))
                .header("Authorization", credentials)
                .header("Depth", "1")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() && response.code() != 207) {
                throw new IOException("PROPFIND failed: " + response.code());
            }
            String xml = response.body() != null ? response.body().string() : "";
            parsePropfindResponse(xml, result);
        }

        return result;
    }

    /** 解析 PROPFIND XML，提取文件名和修改时间 */
    private void parsePropfindResponse(String xml, Map<String, Long> result) {
        // 简单 XML 解析，避免引入 XML 库
        String[] items = xml.split("<D:response>|<d:response>");
        for (String item : items) {
            String href = extractTag(item, "D:href", "d:href");
            if (href == null || href.endsWith("/")) continue; // 跳过目录

            // 从 href 提取文件名
            String name = href.substring(href.lastIndexOf('/') + 1);
            if (name.isEmpty()) continue;

            // 提取修改时间
            String modified = extractTag(item, "D:getlastmodified", "d:getlastmodified");
            long time = parseWebDavDate(modified);
            if (time > 0) {
                result.put(name, time);
            }
        }
    }

    /** 下载单个文件到本地 */
    private boolean downloadFile(String name) {
        try {
            Request request = new Request.Builder()
                    .url(baseUrl + name)
                    .header("Authorization", credentials)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    Log.w(TAG, "download failed: " + name + " code=" + response.code());
                    return false;
                }

                File target = new File(localDir, name);
                try (InputStream is = response.body().byteStream();
                     FileOutputStream fos = new FileOutputStream(target)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                    }
                }
                Log.d(TAG, "downloaded: " + name);
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "download error: " + name, e);
            return false;
        }
    }

    /** 上传单个文件到 WebDAV */
    private boolean uploadFile(String name) {
        try {
            File file = new File(localDir, name);
            if (!file.exists()) return false;

            RequestBody body = RequestBody.create(file, MediaType.parse("application/octet-stream"));
            Request request = new Request.Builder()
                    .url(baseUrl + name)
                    .put(body)
                    .header("Authorization", credentials)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    Log.w(TAG, "upload failed: " + name + " code=" + response.code());
                    return false;
                }
                Log.d(TAG, "uploaded: " + name);
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "upload error: " + name, e);
            return false;
        }
    }

    /** 备份本地文件（冲突保护） */
    private void backupLocal(String name) {
        File original = new File(localDir, name);
        if (!original.exists()) return;

        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(new Date());
        File backup = new File(localDir, name + ".bak-" + timestamp);
        if (original.renameTo(backup)) {
            Log.i(TAG, "backup: " + name + " → " + backup.getName());
        }
    }

    // ══════════════════════════════════════════
    //  本地文件操作
    // ══════════════════════════════════════════

    /** 列出本地文件 {name → lastModified} */
    private Map<String, Long> listLocal() {
        Map<String, Long> result = new HashMap<>();
        File[] files = localDir.listFiles();
        if (files == null) return result;

        for (File f : files) {
            if (f.isFile() && !f.getName().startsWith(".") && !f.getName().contains(".bak-")) {
                result.put(f.getName(), f.lastModified());
            }
        }
        return result;
    }

    // ══════════════════════════════════════════
    //  工具方法
    // ══════════════════════════════════════════

    private static String extractTag(String xml, String... tags) {
        for (String tag : tags) {
            int start = xml.indexOf("<" + tag + ">");
            if (start < 0) continue;
            start += tag.length() + 2;
            int end = xml.indexOf("</" + tag + ">", start);
            if (end < 0) continue;
            return xml.substring(start, end).trim();
        }
        return null;
    }

    /** 解析 WebDAV 日期格式 (RFC 1123) */
    private static long parseWebDavDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return 0;
        try {
            // "Tue, 16 Jun 2026 14:30:00 GMT"
            SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH);
            Date date = sdf.parse(dateStr);
            return date != null ? date.getTime() : 0;
        } catch (Exception e) {
            Log.w(TAG, "parse date failed: " + dateStr);
            return 0;
        }
    }

    private static String ensureTrailingSlash(String url) {
        return url.endsWith("/") ? url : url + "/";
    }
}
