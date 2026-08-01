package me.huidoudour.apksign.signer;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import java.io.File;
import java.util.List;

/**
 * 尽力把分享/选择得到的 content Uri 解析为真实文件路径，
 * 用于"签名结果存回原目录"。解析不出返回 null（调用方回退到下载目录）。
 */
public class UriPathResolver {

    /** 自有文件管理器 FileProvider 的 authority */
    private static final String FILE_MANAGER_AUTHORITY = "me.huidoudour.file.manager.fileprovider";

    public static File resolve(Context context, Uri uri) {
        if (uri == null) return null;
        if ("file".equals(uri.getScheme())) {
            File f = new File(uri.getPath() != null ? uri.getPath() : "");
            return f.isFile() ? f : null;
        }
        if (!"content".equals(uri.getScheme())) return null;

        long expectedSize = querySize(context, uri);

        // 0. 自有 FileManager 的 FileProvider URI（优先使用自己 App）
        File fmFile = resolveFileManagerUri(uri);
        if (fmFile != null) {
            File f = validate(fmFile, expectedSize);
            if (f != null) return f;
        }

        // 1. SAF 文档 Uri（系统文件选择器 / 大部分文件管理器）
        try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                String docId = DocumentsContract.getDocumentId(uri);
                String authority = uri.getAuthority();
                if ("com.android.externalstorage.documents".equals(authority)) {
                    String[] split = docId.split(":", 2);
                    String rel = split.length > 1 ? split[1] : "";
                    File base = "primary".equalsIgnoreCase(split[0])
                            ? Environment.getExternalStorageDirectory()
                            : new File("/storage/" + split[0]);
                    File f = validate(new File(base, rel), expectedSize);
                    if (f != null) return f;
                }
                if ("com.android.providers.downloads.documents".equals(authority)
                        && docId.startsWith("raw:")) {
                    File f = validate(new File(docId.substring(4)), expectedSize);
                    if (f != null) return f;
                }
            }
        } catch (Exception ignored) {
        }

        // 2. MediaStore 的 _data 列
        try (Cursor c = context.getContentResolver().query(
                uri, new String[]{"_data"}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String path = c.getString(0);
                if (path != null) {
                    File f = validate(new File(path), expectedSize);
                    if (f != null) return f;
                }
            }
        } catch (Exception ignored) {
        }

        // 3. FileProvider 常见形态：Uri 路径里直接嵌着 /storage/... 绝对路径
        String path = uri.getPath();
        if (path != null) {
            int idx = path.indexOf("/storage/");
            if (idx >= 0) {
                File f = validate(new File(path.substring(idx)), expectedSize);
                if (f != null) return f;
            }
        }

        // 4. FileProvider 相对根形态：content://xxx/external_files/Download/a.apk
        //    尝试把首段之后的部分拼到外部存储根目录
        List<String> segments = uri.getPathSegments();
        if (segments != null && segments.size() > 1) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < segments.size(); i++) {
                if (sb.length() > 0) sb.append('/');
                sb.append(segments.get(i));
            }
            File f = validate(new File(Environment.getExternalStorageDirectory(), sb.toString()),
                    expectedSize);
            if (f != null) return f;
        }
        return null;
    }

    /** 候选文件必须真实存在，且大小与 Uri 报告的一致（防止同名误判） */
    private static File validate(File file, long expectedSize) {
        if (!file.isFile()) return null;
        if (expectedSize > 0 && file.length() != expectedSize) return null;
        return file;
    }

    /**
     * 解析自有 FileManager 的 FileProvider URI 为真实文件路径。
     * FileManager 的 file_paths.xml 定义了 external、root、files 等路径映射。
     */
    private static File resolveFileManagerUri(Uri uri) {
        if (!FILE_MANAGER_AUTHORITY.equals(uri.getAuthority())) return null;
        List<String> segments = uri.getPathSegments();
        if (segments == null || segments.isEmpty()) return null;

        String rootName = segments.get(0);
        // 构建相对路径（去掉首段 rootName）
        StringBuilder relPath = new StringBuilder();
        for (int i = 1; i < segments.size(); i++) {
            if (relPath.length() > 0) relPath.append('/');
            relPath.append(segments.get(i));
        }
        String rel = relPath.toString();
        if (rel.isEmpty()) return null;

        switch (rootName) {
            case "external":
                return new File(Environment.getExternalStorageDirectory(), rel);
            case "root":
                return new File("/" + rel);
            default:
                return null;
        }
    }

    private static long querySize(Context context, Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0) return c.getLong(idx);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }
}
