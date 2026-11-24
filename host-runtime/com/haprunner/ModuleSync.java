package com.haprunner;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 外部 HAP 同步到引擎默认模块树。
 *
 * ArkUI-X 引擎的 stage asset provider 固定从 filesDir/arkui-x/<moduleName> 读取模块，
 * 只把 setAssetsFileRelativePath 当作附加前缀，外部包解包到 arkui-x/ext/<key> 后
 * 引擎仍按模块名命中默认树。这里在拉起 Ability 之前，把外部解包目录整体同步覆盖到
 * arkui-x/entry，并用 .sync_source 记录来源，避免同一包重复复制。
 *
 * 通用容器不再内置任何基线 HAP（build.ps1 不再打包示例模块），APK assets 里没有可回填的
 * 旧模块树，files 目录的 entry 即本次外部模块的唯一来源。
 */
public final class ModuleSync {

    private static final String TAG = "HAPRUNNER";
    private static final String MARKER = ".sync_source";

    private ModuleSync() {
    }

    public static File syncToEntry(Context ctx, File moduleDir, String key) {
        File arkRoot = new File(ctx.getFilesDir(), "arkui-x");
        File entry = new File(arkRoot, "entry");
        if (moduleDir == null || !moduleDir.isDirectory()) {
            return entry;
        }
        File marker = new File(entry, MARKER);
        // 同来源且 abc 体积一致才跳过复制；不同包/不同构建的 key 不同，必然整树重铺
        // （86MB 级包实测 0.2~0.5s）。
        boolean sameSource = marker.isFile() && key != null && key.equals(readMarker(marker))
                && sizeOf(new File(entry, "ets/modules.abc")) == sizeOf(new File(moduleDir, "ets/modules.abc"));
        if (sameSource && new File(entry, "ets/modules.abc").exists()) {
            Log.i(TAG, "entry 已是 " + key + " 的最新内容，跳过复制");
            return entry;
        }

        long start = System.currentTimeMillis();
        Log.i(TAG, "同步外部模块到 entry: " + moduleDir.getName() + " -> " + entry.getAbsolutePath());
        deleteTree(entry);
        entry.mkdirs();
        try {
            copyTree(moduleDir, entry);
            writeMarker(marker, key);
            Log.i(TAG, "同步完成 用时 " + (System.currentTimeMillis() - start) + " ms");
        } catch (Throwable t) {
            Log.e(TAG, "同步外部模块失败", t);
        }
        return entry;
    }

    private static long sizeOf(File f) {
        return f != null && f.isFile() ? f.length() : -1L;
    }

    private static String readMarker(File marker) {
        try (InputStream in = new FileInputStream(marker)) {
            byte[] buf = new byte[256];
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n, "UTF-8").trim() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static void writeMarker(File marker, String key) {
        try (OutputStream out = new FileOutputStream(marker)) {
            out.write((key == null ? "" : key).getBytes("UTF-8"));
        } catch (Throwable t) {
            Log.w(TAG, "写同步标记失败: " + t.getMessage());
        }
    }

    static void copyTree(File from, File to) throws Exception {
        if (from.isDirectory()) {
            if (!to.exists() && !to.mkdirs()) {
                throw new Exception("无法创建目录: " + to.getAbsolutePath());
            }
            String[] names = from.list();
            if (names == null) {
                return;
            }
            for (String name : names) {
                copyTree(new File(from, name), new File(to, name));
            }
            return;
        }
        File parent = to.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new Exception("无法创建目录: " + parent.getAbsolutePath());
        }
        try (InputStream in = new FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteTree(k);
                }
            }
        }
        f.delete();
    }
}
