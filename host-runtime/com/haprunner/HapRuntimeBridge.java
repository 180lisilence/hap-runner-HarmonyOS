package com.haprunner;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Field;

import ohos.stage.ability.adapter.StageApplication;
import ohos.stage.ability.adapter.StageApplicationDelegate;

/**
 * 运行时路径注入桥接。
 *
 * ArkUI-X 官方流程在 Application.onCreate 阶段把宿主 APK 作为模块容器装配好；
 * 本类通过反射拿到进程内唯一的 StageApplicationDelegate，在拉起外部 HAP 的 Ability 前，
 * 把外部解包目录 / 原始 hap 挂到引擎对应的路径接口上，供 StageActivity 启动 ability 使用。
 *
 * 注意：本类只在带 ArkUI-X 运行时的构建里编入（host-runtime/），
 * lite 构建不引用任何 ohos.* 类型。
 */
public class HapRuntimeBridge {

    private static final String TAG = "HAPRUNNER";
    private static StageApplicationDelegate cachedDelegate;

    private HapRuntimeBridge() {
    }

    /** 反射取 Application 内的私有 appDelegate 单例。 */
    public static StageApplicationDelegate obtainDelegate(Context ctx) {
        if (cachedDelegate != null) {
            return cachedDelegate;
        }
        try {
            Application app = (Application) ctx.getApplicationContext();
            Field f = StageApplication.class.getDeclaredField("appDelegate");
            f.setAccessible(true);
            cachedDelegate = (StageApplicationDelegate) f.get(app);
            Log.i(TAG, "bridge obtainDelegate: " + (cachedDelegate != null ? "ok" : "null"));
        } catch (Throwable t) {
            Log.e(TAG, "bridge obtainDelegate failed", t);
        }
        return cachedDelegate;
    }

    /**
     * 把一个外部 HAP 的模块挂到引擎。
     *
     * @param ctx        应用上下文
     * @param moduleDir  解包后的模块目录（filesDir/arkui-x/<module>）
     * @param moduleName 模块名（通常 entry）
     * @param hapFile    原始 hap 文件（可选，作为容器路径注入）
     * @return delegate 是否可取、注入是否调用成功
     */
    public static boolean applyExternalModule(Context ctx, File moduleDir, String moduleName, File hapFile) {
        StageApplicationDelegate d = obtainDelegate(ctx);
        if (d == null) {
            Log.e(TAG, "applyExternalModule: delegate 不可用，跳过路径注入");
            return false;
        }
        try {
            File arkRoot = new File(ctx.getFilesDir(), "arkui-x");
            Log.i(TAG, "applyExternalModule arkRoot=" + arkRoot.getAbsolutePath()
                    + " moduleDir=" + (moduleDir != null ? moduleDir.getAbsolutePath() : "null")
                    + " module=" + moduleName
                    + " hap=" + (hapFile != null ? hapFile.getAbsolutePath() : "null"));

            // 资源前缀：filesDir/arkui-x（内置默认值，外部模块资源同样落在此树内）
            d.setResourcesFilePrefixPath(arkRoot.getAbsolutePath());
            d.setFileDir(ctx.getFilesDir().getAbsolutePath());

            // 容器路径：优先原始 hap 压缩包；为空则交给目录模式
            if (hapFile != null && hapFile.exists()) {
                d.setHapPath(hapFile.getAbsolutePath());
                Log.i(TAG, "setHapPath=" + hapFile.getAbsolutePath());
            } else if (moduleDir != null) {
                d.setHapPath(moduleDir.getAbsolutePath());
                Log.i(TAG, "setHapPath(dir)=" + moduleDir.getAbsolutePath());
            }

            // 容器内模块相对前缀：arkui-x/<module>（与内置 assets 布局对齐）
            d.setAssetsFileRelativePath("arkui-x/" + moduleName);
            Log.i(TAG, "setAssetsFileRelativePath=arkui-x/" + moduleName);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "applyExternalModule failed", t);
            return false;
        }
    }
}
