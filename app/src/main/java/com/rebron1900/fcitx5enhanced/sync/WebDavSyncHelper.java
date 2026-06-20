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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 *
 * 性能优化：
 * - 并发上传/下载（3线程）
 * - 增量扫描（缓存文件列表+时间戳）
 */
public class WebDavSyncHelper {

    private static final String TAG = "Fcitx5Sync";
    private static final long CONFLICT_THRESHOLD_MS = 30_000;
    private static final int CONCURRENT_UPLOADS = 3;

    private static final StringBuilder logBuffer = new StringBuilder();
    private static final int MAX_LOG_LEN = 8000;

    // 增量扫描缓存
    private static Map<String, Long> sCachedLocalFiles = new HashMap<>();
    private static long sLastScanTime = 0;

    public static String getLog() { return logBuffer.toString(); }
    public static void clearLog() { logBuffer.setLength(0); }

    private static void appendLog(String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String line = ts + " " + msg;
        Log.i(TAG, msg);
        synchronized (logBuffer) {
            logBuffer.append(line).append("\n");
            if (logBuffer.length() > MAX_LOG_LEN) {
                logBuffer.delete(0, logBuffer.length() - MAX_LOG_LEN);
            }
        }
    }

    private final OkHttpClient client;
    private final String baseUrl;
    private final String credentials;
    private final File localDir;
    private final ExecutorService executor;
    private long clockOffsetMs = 0; // 服务器时间 - 本地时间

    public WebDavSyncHelper(Context context) {
        this.baseUrl = ensureTrailingSlash(ConfigStorage.getWebDavUrl(context));
        this.credentials = Credentials.basic(
                ConfigStorage.getWebDavUser(context),
                ConfigStorage.getWebDavPass(context));
        this.localDir = ConfigStorage.getRimeSyncDir(context);
        this.executor = Executors.newFixedThreadPool(CONCURRENT_UPLOADS);

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(false);

        this.client = builder.build();
    }

    /** 同步结果 */
    public static class SyncResult {
        public final int downloaded;
        public final int uploaded;
        public final int skipped;
        public final int failed;
        public final String error;

        public SyncResult(int downloaded, int uploaded, int skipped, int failed, String error) {
            this.downloaded = downloaded;
            this.uploaded = uploaded;
            this.skipped = skipped;
            this.failed = failed;
            this.error = error;
        }

        public boolean isSuccess() { return error == null; }

        public String toToastString() {
            if (error != null) return "同步失败: " + error;
            if (downloaded == 0 && uploaded == 0) return "无变更";
            StringBuilder sb = new StringBuilder();
            if (uploaded > 0) sb.append("↑").append(uploaded);
            if (downloaded > 0) {
                if (sb.length() > 0) sb.append(" ");
                sb.append("↓").append(downloaded);
            }
            if (failed > 0) sb.append(" (").append(failed).append("失败)");
            return sb.toString();
        }
    }

    /** 执行一次完整同步。 */
    public SyncResult sync() {
        appendLog("开始同步");
        appendLog("本地: " + localDir.getAbsolutePath());
        appendLog("远端: " + baseUrl);
        int downloaded = 0, uploaded = 0, skipped = 0, failed = 0;

        try {
            // 1. 远端文件（递归）
            Map<String, Long> remoteFiles = listRemoteRecursive(baseUrl, "");
            appendLog("远端: " + remoteFiles.size() + " 个文件");

            // 2. 本地文件（增量扫描）
            Map<String, Long> localFiles = scanLocalIncremental();
            appendLog("本地: " + localFiles.size() + " 个文件");

            // 3. 收集需要上传/下载的文件
            List<String> toUpload = new ArrayList<>();
            List<String> toDownload = new ArrayList<>();

            // 远端文件 → 对比本地（补偿时钟偏差）
            for (Map.Entry<String, Long> entry : remoteFiles.entrySet()) {
                String name = entry.getKey();
                long remoteTime = entry.getValue() - clockOffsetMs;

                if (localFiles.containsKey(name)) {
                    long localTime = localFiles.get(name);
                    long diff = remoteTime - localTime;

                    if (Math.abs(diff) < CONFLICT_THRESHOLD_MS) {
                        skipped++;
                    } else if (diff > 0) {
                        toDownload.add(name);
                    } else {
                        toUpload.add(name);
                    }
                } else {
                    toDownload.add(name);
                }
            }

            // 本地独有的 → 上传
            for (String name : localFiles.keySet()) {
                if (!remoteFiles.containsKey(name)) {
                    toUpload.add(name);
                }
            }

            // 4. 并发下载
            if (!toDownload.isEmpty()) {
                appendLog("下载 " + toDownload.size() + " 个文件...");
                List<Future<Boolean>> futures = new ArrayList<>();
                for (String name : toDownload) {
                    futures.add(executor.submit(() -> {
                        backupLocal(name);
                        return downloadFile(name);
                    }));
                }
                for (Future<Boolean> f : futures) {
                    try {
                        if (f.get()) downloaded++;
                        else failed++;
                    } catch (Exception e) {
                        failed++;
                    }
                }
            }

            // 5. 并发上传
            if (!toUpload.isEmpty()) {
                appendLog("上传 " + toUpload.size() + " 个文件...");
                List<Future<Boolean>> futures = new ArrayList<>();
                for (String name : toUpload) {
                    futures.add(executor.submit(() -> uploadFile(name)));
                }
                for (Future<Boolean> f : futures) {
                    try {
                        if (f.get()) uploaded++;
                        else failed++;
                    } catch (Exception e) {
                        failed++;
                    }
                }
            }

        } catch (Exception e) {
            appendLog("✗ 失败: " + e.getMessage());
            return new SyncResult(downloaded, uploaded, skipped, failed, e.getMessage());
        } finally {
            executor.shutdown();
        }

        String result = String.format(Locale.getDefault(),
                "完成: ↓%d ↑%d =%d", downloaded, uploaded, skipped);
        appendLog("✓ " + result);
        return new SyncResult(downloaded, uploaded, skipped, failed, null);
    }

    // ══════════════════════════════════════════
    //  增量扫描
    // ══════════════════════════════════════════

    /** 增量扫描本地文件，只返回变更的文件 */
    private Map<String, Long> scanLocalIncremental() {
        Map<String, Long> current = new HashMap<>();
        scanDir(localDir, "", current);

        // 如果是首次扫描，返回全部
        if (sCachedLocalFiles.isEmpty()) {
            sCachedLocalFiles = current;
            sLastScanTime = System.currentTimeMillis();
            return current;
        }

        // 增量：只返回变更的文件
        Map<String, Long> changed = new HashMap<>();
        for (Map.Entry<String, Long> entry : current.entrySet()) {
            Long cached = sCachedLocalFiles.get(entry.getKey());
            if (cached == null || !cached.equals(entry.getValue())) {
                changed.put(entry.getKey(), entry.getValue());
            }
        }

        // 更新缓存
        sCachedLocalFiles = current;
        sLastScanTime = System.currentTimeMillis();

        return changed.isEmpty() ? current : changed;
    }

    // ══════════════════════════════════════════
    //  WebDAV 操作
    // ══════════════════════════════════════════

    /** 递归 PROPFIND，返回 {相对路径 → lastModified} */
    private Map<String, Long> listRemoteRecursive(String url, String prefix) throws IOException {
        Map<String, Long> result = new HashMap<>();

        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<D:propfind xmlns:D=\"DAV:\"><D:allprop/></D:propfind>";

        Request request = new Request.Builder()
                .url(url)
                .method("PROPFIND", RequestBody.create(body, MediaType.parse("application/xml")))
                .header("Authorization", credentials)
                .header("Depth", "1")
                .build();

        appendLog("PROPFIND " + url);

        String xml;
        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            String respBody = response.body() != null ? response.body().string() : "";
            if (code != 207 && code != 200) {
                appendLog("✗ HTTP " + code);
                throw new IOException("PROPFIND failed: " + code);
            }

            // 从 Date 头计算时钟偏差
            if (clockOffsetMs == 0) {
                String serverDate = response.header("Date");
                if (serverDate != null) {
                    long serverTime = parseWebDavDate(serverDate);
                    if (serverTime > 0) {
                        clockOffsetMs = serverTime - System.currentTimeMillis();
                        appendLog("时钟偏差: " + (clockOffsetMs > 0 ? "+" : "") + (clockOffsetMs / 1000) + "s");
                    }
                }
            }

            xml = respBody;
        }

        // 解析每个 D:response
        String[] items = xml.split("<D:response>|<d:response>");
        for (String item : items) {
            String href = extractTag(item, "D:href", "d:href");
            if (href == null) continue;

            // 跳过目录自身
            try {
                java.net.URI uri = java.net.URI.create(url);
                String selfPath = uri.getPath();
                if (href.equals(selfPath) || href.equals(selfPath.replaceAll("/$", ""))) continue;
            } catch (Exception ignored) {}

            // 提取文件名
            String trimmed = href.endsWith("/") ? href.substring(0, href.length() - 1) : href;
            String lastSegment = trimmed.substring(trimmed.lastIndexOf('/') + 1);
            if (lastSegment.isEmpty()) continue;

            String relPath = prefix.isEmpty() ? lastSegment : prefix + "/" + lastSegment;

            if (href.endsWith("/")) {
                // 子目录 → 递归
                String subUrl = ensureTrailingSlash(url) + lastSegment + "/";
                result.putAll(listRemoteRecursive(subUrl, relPath));
            } else {
                // 文件
                String modified = extractTag(item, "D:getlastmodified", "d:getlastmodified");
                long time = parseWebDavDate(modified);
                if (time > 0) {
                    result.put(relPath, time);
                }
            }
        }

        return result;
    }

    /** 下载单个文件 */
    private boolean downloadFile(String relPath) {
        try {
            String url = baseUrl + relPath.replace(" ", "%20");

            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", credentials)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    appendLog("✗ 下载失败: " + relPath + " HTTP " + response.code());
                    return false;
                }

                File target = new File(localDir, relPath);
                target.getParentFile().mkdirs();
                try (InputStream is = response.body().byteStream();
                     FileOutputStream fos = new FileOutputStream(target)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                    }
                }
                appendLog("↓ " + relPath);
                return true;
            }
        } catch (Exception e) {
            appendLog("✗ 下载异常: " + relPath + " " + e.getMessage());
            return false;
        }
    }

    /** 上传单个文件，自动创建父目录 */
    private boolean uploadFile(String relPath) {
        try {
            File file = new File(localDir, relPath);
            if (!file.exists()) {
                appendLog("✗ 本地不存在: " + relPath);
                return false;
            }

            // 确保远端父目录存在
            String parent = relPath.contains("/")
                    ? relPath.substring(0, relPath.lastIndexOf('/'))
                    : "";
            if (!parent.isEmpty()) {
                mkcolRecursive(parent);
            }

            String url = baseUrl + relPath.replace(" ", "%20");
            RequestBody body = RequestBody.create(file, MediaType.parse("application/octet-stream"));
            Request request = new Request.Builder()
                    .url(url)
                    .put(body)
                    .header("Authorization", credentials)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    appendLog("✗ 上传失败: " + relPath + " HTTP " + response.code());
                    return false;
                }
                appendLog("↑ " + relPath);
                return true;
            }
        } catch (Exception e) {
            appendLog("✗ 上传异常: " + relPath + " " + e.getMessage());
            return false;
        }
    }

    /** 递归创建远端目录（MKCOL） */
    private void mkcolRecursive(String relPath) {
        String[] parts = relPath.split("/");
        String current = "";
        for (String part : parts) {
            current = current.isEmpty() ? part : current + "/" + part;
            String dirUrl = baseUrl + current + "/";
            try {
                Request request = new Request.Builder()
                        .url(dirUrl)
                        .method("MKCOL", null)
                        .header("Authorization", credentials)
                        .build();
                try (Response resp = client.newCall(request).execute()) {
                    int code = resp.code();
                    if (code == 201) {
                        appendLog("📁 创建目录: " + current);
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    /** 备份本地文件 */
    private void backupLocal(String relPath) {
        File original = new File(localDir, relPath);
        if (!original.exists()) return;

        String ts = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(new Date());
        File backup = new File(localDir, relPath + ".bak-" + ts);
        if (original.renameTo(backup)) {
            appendLog("备份: " + relPath);
        }
    }

    // ══════════════════════════════════════════
    //  本地扫描
    // ══════════════════════════════════════════

    private void scanDir(File dir, String prefix, Map<String, Long> result) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            String name = prefix.isEmpty() ? f.getName() : prefix + "/" + f.getName();
            if (f.isDirectory()) {
                scanDir(f, name, result);
            } else if (f.isFile() && !f.getName().contains(".bak-")) {
                result.put(name, f.lastModified());
            }
        }
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

    private static long parseWebDavDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return 0;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH);
            Date date = sdf.parse(dateStr);
            return date != null ? date.getTime() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String ensureTrailingSlash(String url) {
        return url.endsWith("/") ? url : url + "/";
    }
}
