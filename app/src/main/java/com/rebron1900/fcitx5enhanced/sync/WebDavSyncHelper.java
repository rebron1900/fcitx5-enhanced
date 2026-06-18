package com.rebron1900.fcitx5enhanced.sync;

import android.content.Context;
import android.util.Log;

import com.rebron1900.fcitx5enhanced.ConfigStorage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

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
    private static final long CONFLICT_THRESHOLD_MS = 30_000;

    private static final StringBuilder logBuffer = new StringBuilder();
    private static final int MAX_LOG_LEN = 8000;

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
    private final String baseUrl;     // 以 / 结尾
    private final String credentials;
    private final File localDir;

    public WebDavSyncHelper(Context context) {
        this.baseUrl = ensureTrailingSlash(ConfigStorage.getWebDavUrl(context));
        this.credentials = Credentials.basic(
                ConfigStorage.getWebDavUser(context),
                ConfigStorage.getWebDavPass(context));
        this.localDir = ConfigStorage.getRimeSyncDir(context);

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(false);

        // 信任所有证书（自签证书兼容）
        try {
            X509TrustManager trustAll = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustAll}, null);
            builder.sslSocketFactory(sslContext.getSocketFactory(), trustAll);
            builder.hostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {
            Log.w(TAG, "SSL trust-all setup failed: " + e);
        }

        this.client = builder.build();
    }

    /** 执行一次完整同步。 */
    public String sync() {
        appendLog("开始同步");
        appendLog("本地: " + localDir.getAbsolutePath());
        appendLog("远端: " + baseUrl);
        int downloaded = 0, uploaded = 0, skipped = 0;

        try {
            // 1. 远端文件（递归）
            Map<String, Long> remoteFiles = listRemoteRecursive(baseUrl, "");
            appendLog("远端: " + remoteFiles.size() + " 个文件");

            // 2. 本地文件（递归）
            Map<String, Long> localFiles = new HashMap<>();
            scanDir(localDir, "", localFiles);
            appendLog("本地: " + localFiles.size() + " 个文件");

            // 3. 远端文件 → 对比本地
            for (Map.Entry<String, Long> entry : remoteFiles.entrySet()) {
                String name = entry.getKey();
                long remoteTime = entry.getValue();

                if (localFiles.containsKey(name)) {
                    long localTime = localFiles.get(name);
                    long diff = remoteTime - localTime;

                    if (Math.abs(diff) < CONFLICT_THRESHOLD_MS) {
                        skipped++;
                    } else if (diff > 0) {
                        // 远端更新 → 下载
                        backupLocal(name);
                        if (downloadFile(name)) {
                            downloaded++;
                            appendLog("↓ " + name);
                        }
                    } else {
                        // 本地更新 → 上传
                        if (uploadFile(name)) {
                            uploaded++;
                            appendLog("↑ " + name);
                        }
                    }
                } else {
                    // 远端有、本地无 → 下载
                    if (downloadFile(name)) {
                        downloaded++;
                        appendLog("↓+ " + name);
                    }
                }
            }

            // 4. 本地独有的 → 上传
            for (String name : localFiles.keySet()) {
                if (!remoteFiles.containsKey(name)) {
                    if (uploadFile(name)) {
                        uploaded++;
                        appendLog("+↑ " + name);
                    }
                }
            }

        } catch (Exception e) {
            appendLog("✗ 失败: " + e.getMessage());
            return "同步失败: " + e.getMessage();
        }

        String result = String.format(Locale.getDefault(),
                "完成: ↓%d ↑%d =%d", downloaded, uploaded, skipped);
        appendLog("✓ " + result);
        return result;
    }

    // ══════════════════════════════════════════
    //  WebDAV 操作
    // ══════════════════════════════════════════

    /**
     * 递归 PROPFIND，返回 {相对路径 → lastModified}。
     * 路径相对于 baseUrl，如 "userdb/userdb.txt"。
     */
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

        String xml;
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() && response.code() != 207) {
                throw new IOException("PROPFIND failed: " + response.code());
            }
            xml = response.body() != null ? response.body().string() : "";
        }

        // 解析每个 D:response
        String[] items = xml.split("<D:response>|<d:response>");
        for (String item : items) {
            String href = extractTag(item, "D:href", "d:href");
            if (href == null) continue;

            // 跳过目录自身（即 url 对应的 href）
            String selfHref = url.replace(baseUrl, "/");
            if (href.equals(selfHref) || href.equals(selfHref.replaceAll("/$", ""))) continue;

            // 提取文件名（href 最后一段）
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
            // 构建 URL：对路径中的每段做编码
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
                    // 405 = 已存在，忽略
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
        if (files == null) {
            appendLog("扫描目录为空: " + dir.getAbsolutePath());
            return;
        }

        for (File f : files) {
            String name = prefix.isEmpty() ? f.getName() : prefix + "/" + f.getName();
            if (f.isDirectory() && !f.getName().startsWith(".")) {
                scanDir(f, name, result);
            } else if (f.isFile() && !f.getName().startsWith(".") && !f.getName().contains(".bak-")) {
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
