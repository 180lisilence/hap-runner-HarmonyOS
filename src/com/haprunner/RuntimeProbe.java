package com.haprunner;

import java.io.File;

/**
 * ArkUI-X 运行时探测：HAP 里的 abc（ArkTS 字节码）必须有方舟运行时 + ArkUI 引擎才能执行。
 * 这两样由 ArkUI-X SDK 提供，随 APK 打进 lib/ 与 classes 里：
 *   1. lib/arm64-v8a/libarkui_android.so、libhilog.so（以及 plugins 下的组件/接口 .so）
 *   2. ohos.stage.ability.adapter.StageApplication / StageActivity（Java 适配层）
 *   3. assets/arkui-x/systemres（框架资源）
 */
public final class RuntimeProbe {

    public static final String ADAPTER_ACTIVITY = "ohos.stage.ability.adapter.StageActivity";
    public static final String ADAPTER_APP = "ohos.stage.ability.adapter.StageApplication";
    public static final String HOST_ACTIVITY = "com.haprunner.HapHostActivity";
    private static final String ENGINE_LIB = "arkui_android";

    private RuntimeProbe() {
    }

    public static boolean hasAdapter() {
        try {
            Class.forName(ADAPTER_ACTIVITY);
            Class.forName(ADAPTER_APP);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasEngine() {
        try {
            System.loadLibrary(ENGINE_LIB);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 运行时 + 宿主 Activity 都就绪，才能真正把任意 HAP 拉起来 */
    public static boolean isReady() {
        boolean host = false;
        try {
            Class.forName(HOST_ACTIVITY);
            host = true;
        } catch (Throwable ignored) {
        }
        return hasAdapter() && hasEngine() && host;
    }

    public static boolean hasHost() {
        try {
            Class.forName(HOST_ACTIVITY);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(hasEngine() ? "✓ " : "✗ ").append("lib").append(ENGINE_LIB).append(".so（ArkUI 引擎 + 方舟运行时）\n");
        sb.append(hasAdapter() ? "✓ " : "✗ ").append("ohos.stage.ability.adapter.*（Android 适配层）\n");
        sb.append(hasHost() ? "✓ " : "✗ ").append("HapHostActivity（HAP 宿主 Activity）\n");
        return sb.toString();
    }

    public static String missingHint() {
        return "当前 APK 里没有 ArkUI-X 运行时，HAP 无法被“执行”。\n\n"
                + "补齐方式（任选其一）：\n"
                + "1) DevEco Studio → SDK Manager 安装 ArkUI-X SDK（需登录华为账号），\n"
                + "   再执行 tools/arkuix/setup.ps1 -ArkuiXSdk <sdk路径> 重新打包；\n"
                + "2) 用 ArkUI-X 源码（gitee.com/arkui-x）自行编译 Android 运行时；\n"
                + "3) 使用已含运行时的第三方发行版，把 lib/ 与 adapter jar 塞进本工程重建。\n\n"
                + "说明：HAP 不能像 APK 一样直接安装执行——Android 没有 ArkUI/方舟运行时，\n"
                + "必须由宿主 App 内嵌 ArkUI-X 才能渲染 ArkTS 页面。";
    }
}
