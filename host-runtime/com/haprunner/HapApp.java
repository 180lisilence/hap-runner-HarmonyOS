package com.haprunner;

import ohos.stage.ability.adapter.StageApplication;

/**
 * ArkUI-X 宿主 Application（仅在带运行时构建时编入）。
 * 负责初始化 ArkUI-X 的资源路径与引擎，必须在 AndroidManifest 的 <application android:name> 中声明。
 */
public class HapApp extends StageApplication {

    @Override
    public void onCreate() {
        // 引擎初始化前先把内置模块树补齐到 files 目录，避免 abc/清单缺失导致 Ability 无法创建
        BakedBundleBootstrap.ensureBakedEntry(this);
        super.onCreate();
    }
}
