package com.rebron1900.fcitx5enhanced.sync;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * java.io.File 实现 — 用于直接文件系统访问。
 */
public class FileLocalAccess implements LocalFileAccess {

    private final File rootDir;

    public FileLocalAccess(File rootDir) {
        this.rootDir = rootDir;
    }

    @Override
    public Map<String, Long> scanAll() {
        Map<String, Long> result = new HashMap<>();
        scanDir(rootDir, "", result);
        return result;
    }

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

    @Override
    public InputStream openRead(String relPath) throws Exception {
        return new FileInputStream(new File(rootDir, relPath));
    }

    @Override
    public OutputStream openWrite(String relPath) throws Exception {
        File target = new File(rootDir, relPath);
        target.getParentFile().mkdirs();
        return new FileOutputStream(target);
    }

    @Override
    public boolean exists(String relPath) {
        return new File(rootDir, relPath).exists();
    }

    @Override
    public boolean backup(String relPath) {
        File original = new File(rootDir, relPath);
        if (!original.exists()) return true;
        String ts = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(new Date());
        File backup = new File(rootDir, relPath + ".bak-" + ts);
        return original.renameTo(backup);
    }

    @Override
    public String getDisplayPath() {
        return rootDir.getAbsolutePath();
    }
}
