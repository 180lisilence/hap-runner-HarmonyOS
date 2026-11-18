package com.haprunner;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * HAP 仓库：把用户从文件管理器选中的 .hap 复制进应用私有目录，供解析 / 解包 / 运行。
 */
public class HapStore {

    private final Context ctx;
    private final File dir;

    public HapStore(Context ctx) {
        this.ctx = ctx;
        this.dir = new File(ctx.getFilesDir(), "haps");
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    public File dir() {
        return dir;
    }

    public String displayName(Uri uri) {
        String name = null;
        try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    name = c.getString(idx);
                }
            }
        } catch (Exception ignored) {
        }
        if (name == null && uri.getLastPathSegment() != null) {
            name = uri.getLastPathSegment();
        }
        if (name == null) {
            name = "unknown.hap";
        }
        if (!name.toLowerCase().endsWith(".hap")) {
            name = name + ".hap";
        }
        return name.replaceAll("[^A-Za-z0-9._\\u4e00-\\u9fa5-]", "_");
    }

    public File importUri(Uri uri) throws Exception {
        String name = displayName(uri);
        File out = new File(dir, name);
        int i = 1;
        while (out.exists()) {
            out = new File(dir, name.replace(".hap", "_" + i + ".hap"));
            i++;
        }
        InputStream src;
        if ("file".equals(uri.getScheme())) {
            src = new java.io.FileInputStream(uri.getPath());
        } else {
            src = ctx.getContentResolver().openInputStream(uri);
        }
        try (InputStream in = src; OutputStream os = new FileOutputStream(out)) {
            if (in == null) {
                throw new IllegalStateException("无法读取文件");
            }
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        }
        return out;
    }

    public File importFile(File src) throws Exception {
        File out = new File(dir, src.getName());
        int i = 1;
        while (out.exists()) {
            out = new File(dir, src.getName().replace(".hap", "_" + i + ".hap"));
            i++;
        }
        try (InputStream in = new java.io.FileInputStream(src); OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        }
        return out;
    }

    public List<File> list() {
        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".hap"));
        List<File> out = new ArrayList<>();
        if (files != null) {
            out.addAll(Arrays.asList(files));
            out.sort(new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.compare(b.lastModified(), a.lastModified());
                }
            });
        }
        return out;
    }

    public void delete(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                delete(k);
            }
        }
        f.delete();
    }
}
