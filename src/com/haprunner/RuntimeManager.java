package com.haprunner;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 运行时版本注册表与按需下载缓存（lite 构建也可编入，不引用 ohos.* 类型）。
 *
 * 设计目标：APK 内只随包携带一份内置版本运行时，不再为多个 HAP 版本打包多份 SDK；
 * 导入 HAP 时识别其编译 SDK / 虚拟机版本号，本地（内置 + 已下载缓存）命中则直接运行，
 * 未命中则联网下载对应版本包，落到 filesDir/runtimes/<版本>/；同版本二次进入直接复用缓存。
 *
 * 注意：下载包内含原生库 .so 与 systemres 资源；适配层 Java 类仍来自宿主 APK，
 * 跨版本替换适配层属远期能力，当前版本映射规则以后端注册表为准。
 */
public final class RuntimeManager {

    private static final String TAG = "HAPRUNNER";
    private static final String MARKER = "runtime.info";

    /** 随 APK 内置打包的运行时版本（与 ArkUI-X SDK 26.0.0.105 对齐） */
    public static final String BUNDLED_VERSION = "26.0.0.105";

    /**
     * 运行时下载源根地址默认值；留空表示未配置下载源，下载按钮会提示先配置。
     * 约定产物：{base}/runtimes/<版本>.zip，压缩包内按 lib/、assets/systemres/ 布局。
     * 实际地址由设置页填写并持久化，运行时通过 {@link #setRepoBase(String)} 注入。
     */
    public static final String RUNTIME_REPO_BASE = "";

    private static File rootDir;
    private static String repoBase = RUNTIME_REPO_BASE;

    /** 注入设置页填写的下载源根地址 */
    public static void setRepoBase(String base) {
        repoBase = base == null ? "" : base.trim();
    }

    public static String getRepoBase() {
        return repoBase == null ? "" : repoBase;
    }

    private RuntimeManager() {
    }

    /** 在 Application / Activity 初始化时注入私有目录根，后续静态方法无需 Context。 */
    public static void init(File filesDir) {
        rootDir = new File(filesDir, "runtimes");
        if (!rootDir.exists()) {
            rootDir.mkdirs();
        }
    }

    private static File root() {
        if (rootDir == null) {
            rootDir = new File(System.getProperty("java.io.tmpdir"), "haprunner-runtimes");
        }
        return rootDir;
    }

    /** 已下载并通过 marker 校验的运行时版本列表。 */
    public static List<String> cachedVersions() {
        List<String> out = new ArrayList<>();
        File[] kids = root().listFiles();
        if (kids == null) {
            return out;
        }
        for (File k : kids) {
            if (k.isDirectory() && new File(k, MARKER).isFile()) {
                out.add(k.getName());
            }
        }
        return out;
    }

    /** 本地是否已有该版本：内置版本或已下载缓存。版本信息缺失时不拦截。 */
    public static boolean isAvailable(String required) {
        String v = normalize(required);
        if (v.isEmpty()) {
            return true;
        }
        if (BUNDLED_VERSION.equals(v)) {
            return true;
        }
        for (String c : cachedVersions()) {
            if (c.equals(v)) {
                return true;
            }
        }
        return false;
    }

    /** 运行时版本对应的本地目录。 */
    public static File runtimeDir(String version) {
        return new File(root(), normalize(version));
    }

    public interface Callback {
        void onProgress(int percent);

        void onSuccess(String version);

        void onFailure(String version, String message);
    }

    /**
     * 后台下载指定版本运行时包并解压到缓存目录；回调线程为后台线程，UI 层需自行切主线程。
     * 已存在有效 marker 时直接回调成功，不重复下载。
     */
    public static void download(String version, Callback cb) {
        final String v = normalize(version);
        if (v.isEmpty()) {
            cb.onFailure(version, "无法识别运行时版本号");
            return;
        }
        if (new File(new File(root(), v), MARKER).isFile()) {
            Log.i(TAG, "runtime cached, skip download: " + v);
            cb.onSuccess(v);
            return;
        }
        if (repoBase.isEmpty()) {
            cb.onFailure(v, "运行时下载源未配置：请在 设置 → 下载源地址 中填写");
            return;
        }
        new Thread(() -> {
            File dir = new File(root(), v);
            dir.mkdirs();
            File tmp = new File(root(), v + ".zip");
            try {
                String url = repoBase + "/runtimes/" + v + ".zip";
                Log.i(TAG, "runtime download: " + url);
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);
                int code = conn.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    cb.onFailure(v, "HTTP " + code);
                    return;
                }
                long len = conn.getContentLengthLong();
                try (InputStream in = conn.getInputStream();
                     FileOutputStream os = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    long total = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        total += n;
                        if (len > 0) {
                            cb.onProgress((int) Math.min(99, total * 100 / len));
                        }
                    }
                }
                extractZip(tmp, dir);
                writeMarker(dir, v);
                if (tmp.exists()) {
                    tmp.delete();
                }
                cb.onProgress(100);
                cb.onSuccess(v);
            } catch (Exception e) {
                Log.e(TAG, "runtime download failed: " + v, e);
                cb.onFailure(v, e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }).start();
    }

    private static void extractZip(File zip, File target) throws Exception {
        try (ZipFile z = new ZipFile(zip)) {
            for (java.util.Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                String n = e.getName();
                if (n.startsWith("/") || n.matches("^[A-Za-z]:.*")
                        || n.contains("../") || n.contains("..\\")) {
                    throw new SecurityException("运行时包条目路径越界：" + n);
                }
                File out = new File(target, n);
                if (!inside(target, out)) {
                    throw new SecurityException("运行时包条目逃逸目录：" + n);
                }
                File parent = out.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                try (InputStream in = z.getInputStream(e); FileOutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[16384];
                    int m;
                    while ((m = in.read(buf)) > 0) {
                        os.write(buf, 0, m);
                    }
                }
            }
        }
    }

    private static void writeMarker(File dir, String version) throws Exception {
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        try (FileOutputStream os = new FileOutputStream(new File(dir, MARKER))) {
            os.write((version + "\n" + stamp + "\n").getBytes("UTF-8"));
        }
    }

    private static boolean inside(File base, File target) {
        try {
            String bp = base.getCanonicalPath();
            String tp = target.getCanonicalPath();
            return tp.equals(bp) || tp.startsWith(bp + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String v = s.trim();
        if (v.startsWith("v") || v.startsWith("V")) {
            v = v.substring(1);
        }
        return v.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
