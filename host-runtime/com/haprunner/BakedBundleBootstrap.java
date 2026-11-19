package com.haprunner;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 内置基准包落盘自举。
 *
 * 引擎 StageApplication 的资源复制只会把 resources/ 落到 files 目录，
 * 不会把 ets/modules.abc、module.json、pack.info 等模块清单写进 files。
 * 外部 HAP 运行时按 filesDir/arkui-x/<module> 路径读取这些条目，缺失时
 * 会报 invalid script buffer、Ability 无法创建，页面只渲染出部分布局。
 *
 * 本类在 Application.onCreate 阶段（引擎初始化前）把 APK assets 里的
 * arkui-x/entry 整棵树补齐到 files/arkui-x/entry；已落盘则幂等跳过。
 */
public final class BakedBundleBootstrap {

    private static final String TAG = "HAPRUNNER";
    private static final String ASSET_ROOT = "arkui-x/entry";
    private static final String ABC_REL = "ets/modules.abc";

    private BakedBundleBootstrap() {
    }

    /** 确保内置模块树在 files 目录完整落盘；任何失败都只记日志，不阻断启动。 */
    public static void ensureBakedEntry(Context ctx) {
        try {
            File entryDir = new File(ctx.getFilesDir(), "arkui-x/entry");
            File abc = new File(entryDir, ABC_REL);
            if (abc.isFile() && abc.length() > 0) {
                return; // 已完整落盘，无需重复复制
            }
            AssetManager am = ctx.getAssets();
            copyTree(am, ASSET_ROOT, entryDir);
            Log.i(TAG, "baked entry copied to files, abcExists=" + abc.exists()
                    + " abcSize=" + abc.length());
        } catch (Throwable t) {
            Log.w(TAG, "bootstrap baked entry failed: " + t.getMessage());
        }
    }

    private static void copyTree(AssetManager am, String assetPath, File targetDir) throws Exception {
        String[] kids = am.list(assetPath);
        if (kids == null) {
            return;
        }
        targetDir.mkdirs();
        for (String name : kids) {
            String childAsset = assetPath + "/" + name;
            File target = new File(targetDir, name);
            String[] sub = am.list(childAsset);
            if (sub != null && sub.length > 0) {
                copyTree(am, childAsset, target);
            } else {
                File parent = target.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                try (InputStream in = am.open(childAsset);
                     OutputStream os = new FileOutputStream(target)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                    }
                }
            }
        }
    }
}
