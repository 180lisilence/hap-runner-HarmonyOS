package com.haprunner;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.database.Cursor;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * HAP 自动发现：
 *  1) Android 11+ 且有「所有文件访问」→ 直接遍历 /sdcard（最快最全）
 *  2) Android 10 及以下 + 存储权限 → 同样直接遍历
 *  3) 其余情况 → 遍历应用可见目录 + MediaStore + 用户授权的 SAF 目录树
 */
public class HapScanner {

    public interface Listener {
        void onProgress(String msg);

        void onFound(List<HapRef> refs);

        void onPartial(List<HapRef> refs, String msg);
    }

    public static class HapRef {
        public final String name;
        public final String path;
        public final long size;
        public File file;
        public Uri uri;

        HapRef(String name, String path, long size) {
            this.name = name;
            this.path = path;
            this.size = size;
        }
    }

    private static final int MAX_FILES = 20000;
    private static final String LOG = "HAPRUNNER";
    private static long TIME_BUDGET_MS = 30000L;

    /** 深度扫描：时间预算放宽到 90 秒 */
    public void setDeepScan(boolean deep) {
        TIME_BUDGET_MS = deep ? 90000L : 30000L;
    }
    private static final int MAX_DEPTH = 16;

    private final Context ctx;

    public HapScanner(Context ctx) {
        this.ctx = ctx;
    }

    public static boolean canScanAll(Context ctx) {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    public List<HapRef> scan(Listener listener) {
        long start = System.currentTimeMillis();
        List<HapRef> out = new ArrayList<>();
        // 去重键：文件名 + 大小，避免同一份 HAP 在多处出现时刷屏
        Set<String> seen = new HashSet<>();
        this.partialAt = 0;
        this.truncated = false;
        this.visited = 0;
        this.listenerRef = listener;
        this.startedAt = start;

        if (canScanAll(ctx)) {
            for (File vol : volumes()) {
                Log.i(LOG, "scan volume: " + vol.getAbsolutePath());
                String[] first = {"Download", "Documents", "Bluetooth", "DCIM", "Pictures", "Movies",
                        "WeiXin", "Tencent", "Android/media", "Android/data/com.tencent.mm",
                        "Android/data/com.tencent.mobileqq"};
                for (String d : first) {
                    File f = new File(vol, d);
                    if (f.exists()) {
                        walk(f, out, seen, 0, listener);
                    }
                }
                walk(vol, out, seen, 0, listener);
            }
        } else {
            // 降级：常见的公开目录逐个尝试（受限系统上会静默失败）
            File[] roots = {
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            };
            for (File r : roots) {
                walk(r, out, seen, 0, listener);
            }
        }

        // MediaStore 作为补充源（无论是否有全盘权限都跑一次，能覆盖一些怪路径）
        scanMediaStore(out, seen);

        // 用户通过 SAF 授权过的目录树
        for (Uri tree : savedTrees()) {
            scanTree(tree, out, seen);
        }

        // 最后补上应用内已导入的副本，保证列表不会“空”
        File own = new File(ctx.getFilesDir(), "haps");
        walk(own, out, seen, 0, listener);

        long cost = System.currentTimeMillis() - start;
        if (listener != null) {
            listener.onFound(out);
            listener.onPartial(out, "扫描完成：" + out.size() + " 个，用时 " + (cost / 1000.0) + "s"
                    + (truncated ? "（已达时间上限，结果可能不全）" : ""));
            Log.i(LOG, "scan done: found=" + out.size() + " visited=" + visited + " nullDirs=" + nullDirs
                    + " cost=" + cost + "ms truncated=" + truncated);
        }
        return out;
    }

    private long partialAt = 0;
    private long startedAt = 0;
    private boolean truncated = false;
    private int visited = 0;
    private int nullDirs = 0;

    public int getVisited() { return visited; }

    public int getNullDirs() { return nullDirs; }

    public boolean isTruncated() { return truncated; }
    private Listener listenerRef;

    private boolean timeUp() {
        if (truncated) {
            return true;
        }
        if (System.currentTimeMillis() - startedAt > TIME_BUDGET_MS) {
            truncated = true;
        }
        return truncated;
    }

    private void notifyPartial(List<HapRef> out) {
        long now = System.currentTimeMillis();
        if (listenerRef != null && now - partialAt > 400) {
            partialAt = now;
            listenerRef.onPartial(out, "已发现 " + out.size() + " 个，扫描中…");
        }
    }

    // --------------------------------------------------------------- 目录遍历

    /** 所有可访问的存储卷（主存储 + SD 卡/多用户卷） */
    private List<File> volumes() {
        List<File> list = new ArrayList<>();
        File primary = Environment.getExternalStorageDirectory();
        if (primary != null) {
            list.add(primary);
        }
        try {
            File[] dirs = ctx.getExternalFilesDirs(null);
            if (dirs != null) {
                for (File d : dirs) {
                    if (d == null) {
                        continue;
                    }
                    String p = d.getAbsolutePath();
                    int idx = p.indexOf("/Android/");
                    if (idx > 0) {
                        File vol = new File(p.substring(0, idx));
                        boolean dup = false;
                        for (File e : list) {
                            if (e.getAbsolutePath().equals(vol.getAbsolutePath())) {
                                dup = true;
                            }
                        }
                        if (!dup && vol.canRead()) {
                            list.add(vol);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return list;
    }

    private void walk(File dir, List<HapRef> out, Set<String> seen, int depth, Listener l) {
        if (dir == null || depth > MAX_DEPTH || out.size() >= MAX_FILES || timeUp()) {
            return;
        }
        File[] kids;
        try {
            kids = dir.listFiles();
        } catch (Throwable t) {
            return;
        }
        if (kids == null) {
            nullDirs++;
            if (nullDirs <= 12) {
                Log.i(LOG, "listFiles null: " + dir.getAbsolutePath());
            }
            return;
        }
        for (File f : kids) {
            if (out.size() >= MAX_FILES) {
                return;
            }
            String n = f.getName();
            if (n.startsWith(".")) {
                continue;
            }
            visited++;
            if (f.isDirectory()) {
                walk(f, out, seen, depth + 1, l);
            } else if (n.toLowerCase().endsWith(".hap")) {
                String path = f.getAbsolutePath();
                String key = path;
                if (seen.add(key)) {
                    HapRef r = new HapRef(n, path, f.length());
                    r.file = f;
                    out.add(r);
                    notifyPartial(out);
                    if (l != null) {
                        l.onProgress("已发现 " + out.size() + " 个 HAP…");
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- MediaStore

    private void scanMediaStore(List<HapRef> out, Set<String> seen) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(
                    android.provider.MediaStore.Files.getContentUri("external"),
                    new String[]{"_id", "_display_name", "_size", "_data"},
                    null, null, null);
            if (c != null) {
                Log.i(LOG, "mediastore rows=" + c.getCount());
                while (c.moveToNext()) {
                    String name = c.getString(1);
                    long size = c.getLong(2);
                    String path = c.getString(3);
                    if (name == null || !name.toLowerCase().endsWith(".hap")) {
                        continue;
                    }
                    String key = (path != null ? path : "media:" + name);
                    if (!seen.add(key)) {
                        continue;
                    }
                    HapRef r = new HapRef(name, path != null ? path : "media:" + name, size);
                    if (path != null) {
                        r.file = new File(path);
                    }
                    r.uri = Uri.withAppendedPath(
                            android.provider.MediaStore.Files.getContentUri("external"),
                            c.getString(0));
                    out.add(r);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    // ------------------------------------------------------------ SAF 目录树

    public void addTree(Uri treeUri) {
        Set<String> s = new HashSet<>();
        for (Uri u : savedTrees()) {
            s.add(u.toString());
        }
        s.add(treeUri.toString());
        prefs().edit().putStringSet("trees", s).apply();
    }

    public List<Uri> savedTrees() {
        List<Uri> list = new ArrayList<>();
        for (String s : prefs().getStringSet("trees", new HashSet<String>())) {
            list.add(Uri.parse(s));
        }
        return list;
    }

    private void scanTree(Uri tree, List<HapRef> out, Set<String> seen) {
        String rootId;
        try {
            rootId = DocumentsContract.getTreeDocumentId(tree);
        } catch (Throwable t) {
            return;
        }
        walkTree(tree, rootId, "", out, seen, 0);
    }

    private void walkTree(Uri tree, String docId, String prefix, List<HapRef> out, Set<String> seen, int depth) {
        if (depth > MAX_DEPTH || out.size() >= MAX_FILES) {
            return;
        }
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(children,
                    new String[]{
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE,
                            DocumentsContract.Document.COLUMN_SIZE},
                    null, null, null);
            if (c == null) {
                return;
            }
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                long size = c.isNull(3) ? 0 : c.getLong(3);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    walkTree(tree, id, prefix + name + "/", out, seen, depth + 1);
                } else if (name != null && name.toLowerCase().endsWith(".hap")) {
                    Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    if (seen.add(doc.toString())) {
                        HapRef r = new HapRef(name, prefix + name, size);
                        r.uri = doc;
                        out.add(r);
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences("hap_scanner", Context.MODE_PRIVATE);
    }
}
