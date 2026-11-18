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
        sb.append(hasEngine() ? "✓ " : "✗ ").append("渲染引擎（libarkui_android.so）\n");
        sb.append(hasAdapter() ? "✓ " : "✗ ").append("系统适配层（ohos.stage.ability.adapter.*）\n");
        sb.append(hasHost() ? "✓ " : "✗ ").append("宿主页面（HapHostActivity）\n");
        return sb.toString();
    }

    public static String missingHint() {
        return "当前是精简版，缺少运行应用所需的环境，只能查看和解包。\n\n"
                + "如需运行：在打包机上接入 ArkUI-X SDK，用 build.ps1 带 -ArkuiXSdk 参数重新构建完整版；"
                + "也可以自行编译运行时，或用已带运行时的第三方包替换原生库与适配层后重新打包。\n\n"
                + "说明：.hap 不能像普通 APK 那样直接安装，必须由本运行器内置运行环境才能渲染页面。";
    }
}
