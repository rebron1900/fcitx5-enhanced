package com.rebron1900.fcitx5enhanced.sync;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * SAF (Storage Access Framework) 实现 — 通过 ContentResolver + DocumentsContract 访问。
 *
 * 用 DocumentsContract 直接操作 document tree，避免 DocumentFile.listFiles() 的性能问题。
 */
public class SafLocalAccess implements LocalFileAccess {

    private final Context context;
    private final Uri treeUri;

    public SafLocalAccess(Context context, Uri treeUri) {
        this.context = context.getApplicationContext();
        this.treeUri = treeUri;
    }

    @Override
    public Map<String, Long> scanAll() {
        Map<String, Long> result = new HashMap<>();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        scanChildren(childrenUri, "", result);
        return result;
    }

    private void scanChildren(Uri childrenUri, String prefix, Map<String, Long> result) {
        ContentResolver cr = context.getContentResolver();
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        try (android.database.Cursor cursor = cr.query(childrenUri, projection, null, null, null)) {
            if (cursor == null) return;
            while (cursor.moveToNext()) {
                String docId = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                long lastMod = cursor.getLong(3);

                String relPath = prefix.isEmpty() ? name : prefix + "/" + name;

                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    Uri subChildren = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
                    scanChildren(subChildren, relPath, result);
                } else if (!name.contains(".bak-")) {
                    result.put(relPath, lastMod);
                }
            }
        }
    }

    @Override
    public InputStream openRead(String relPath) throws Exception {
        Uri fileUri = findDocumentUri(relPath);
        if (fileUri == null) throw new java.io.FileNotFoundException("Not found: " + relPath);
        return context.getContentResolver().openInputStream(fileUri);
    }

    @Override
    public OutputStream openWrite(String relPath) throws Exception {
        // 拆分路径：确保父目录存在，然后创建/覆盖文件
        String[] parts = relPath.split("/");
        Uri parentUri = rootDocUri();

        // 逐级创建/查找目录
        for (int i = 0; i < parts.length - 1; i++) {
            parentUri = ensureChildDir(parentUri, parts[i]);
        }

        String fileName = parts[parts.length - 1];
        Uri existing = findChildByName(parentUri, fileName);

        ContentResolver cr = context.getContentResolver();
        if (existing != null) {
            // 覆盖已有文件
            return cr.openOutputStream(existing, "wt");
        } else {
            // 创建新文件
            Uri newFile = DocumentsContract.createDocument(
                    cr, parentUri, "application/octet-stream", fileName);
            if (newFile == null) throw new java.io.IOException("Failed to create: " + relPath);
            return cr.openOutputStream(newFile);
        }
    }

    @Override
    public boolean exists(String relPath) {
        return findDocumentUri(relPath) != null;
    }

    @Override
    public boolean backup(String relPath) {
        Uri fileUri = findDocumentUri(relPath);
        if (fileUri == null) return true;
        String ts = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(new Date());
        String newName = relPath.substring(relPath.lastIndexOf('/') + 1) + ".bak-" + ts;
        try {
            DocumentsContract.renameDocument(context.getContentResolver(), fileUri, newName);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getDisplayPath() {
        return treeUri.getPath();
    }

    // ══════════════════════════════════════════
    //  内部工具
    // ══════════════════════════════════════════

    /** 将 tree URI 转为对应的 document URI（DocumentsContract API 需要 document URI） */
    private Uri rootDocUri() {
        String treeDocId = DocumentsContract.getTreeDocumentId(treeUri);
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId);
    }

    /** 按相对路径查找 document URI，返回 null 如果不存在 */
    private Uri findDocumentUri(String relPath) {
        String[] parts = relPath.split("/");
        Uri current = rootDocUri();

        for (String part : parts) {
            Uri child = findChildByName(current, part);
            if (child == null) return null;
            current = child;
        }
        return current;
    }

    /** 在 parent 下查找名为 name 的子项 */
    private Uri findChildByName(Uri parent, String name) {
        ContentResolver cr = context.getContentResolver();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                parent, DocumentsContract.getDocumentId(parent));
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        try (android.database.Cursor cursor = cr.query(childrenUri, projection, null, null, null)) {
            if (cursor == null) return null;
            while (cursor.moveToNext()) {
                if (name.equals(cursor.getString(1))) {
                    return DocumentsContract.buildDocumentUriUsingTree(
                            treeUri, cursor.getString(0));
                }
            }
        }
        return null;
    }

    /** 确保子目录存在，返回其 URI */
    private Uri ensureChildDir(Uri parent, String dirName) {
        Uri existing = findChildByName(parent, dirName);
        if (existing != null) return existing;

        try {
            Uri created = DocumentsContract.createDocument(
                    context.getContentResolver(), parent,
                    DocumentsContract.Document.MIME_TYPE_DIR, dirName);
            if (created != null) return created;
        } catch (Exception ignored) {}
        return parent;
    }
}
