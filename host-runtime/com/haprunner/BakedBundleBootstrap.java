package com.haprunner;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 内置模块树落盘自举（通用，不绑定具体示例应用）。
 *
 * 引擎 StageApplication 的资源复制只会把 resources/ 落到 files 目录，
 * 不会把 ets/modules.abc、module.json、pack.info 等模块清单写进 files。
 * 外部 HAP 运行时按 filesDir/arkui-x/<模块> 路径读取这些条目，缺失时
 * 会报 invalid script buffer、Ability 无法创建，页面只渲染出部分布局。
 *
 * 本类在 Application.onCreate 阶段（引擎初始化前）遍历 APK assets 里
 * arkui-x/ 下的每个模块目录（systemres 框架资源除外），整棵树幂等补齐到
 * files/arkui-x/<模块>；以该模块 ets/modules.abc 存在且非空为幂等标志，
 * 失败仅记日志不阻断启动。
 */
public final class BakedBundleBootstrap {

    private static final String TAG = "HAPRUNNER";
    private static final String ASSET_ROOT = "arkui-x";
    private static final String SYSTEM_RES = "systemres";
    private static final String ABC_REL = "ets/modules.abc";

    private BakedBundleBootstrap() {
    }

    /** 确保 assets/arkui-x 下全部内置模块树在 files 目录完整落盘；任何失败都只记日志。 */
    public static void ensureBakedModules(Context ctx) {
        AssetManager am = ctx.getAssets();
        String[] moduleDirs;
        try {
            moduleDirs = am.list(ASSET_ROOT);
        } catch (Throwable t) {
            Log.w(TAG, "bootstrap list assets failed: " + t.getMessage());
            return;
        }
        if (moduleDirs == null) {
            return;
        }
        for (String mod : moduleDirs) {
            if (SYSTEM_RES.equals(mod)) {
                continue;
            }
            String assetPath = ASSET_ROOT + "/" + mod;
            String[] kids;
            try {
                kids = am.list(assetPath);
            } catch (Throwable t) {
                continue;
            }
            if (kids == null || kids.length == 0) {
                continue; // 条目是文件而非模块目录，跳过
            }
            File targetDir = new File(ctx.getFilesDir(), assetPath);
            File abc = new File(targetDir, ABC_REL);
            if (abc.isFile() && abc.length() > 0) {
                continue; // 该模块已完整落盘，无需重复复制
            }
            try {
                copyTree(am, assetPath, targetDir);
                Log.i(TAG, "baked module '" + mod + "' copied to files, abcExists=" + abc.exists()
                        + " abcSize=" + abc.length());
            } catch (Throwable t) {
                Log.w(TAG, "bootstrap baked module '" + mod + "' failed: " + t.getMessage());
            }
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
