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
        BakedBundleBootstrap.ensureBakedModules(this);
        super.onCreate();
    }

    /**
     * 运行器外壳不在 Application 初始化阶段自动加载/渲染内置基线 Ability；
     * 只有用户在列表里点「运行」、由 {@link HapHostActivity} 按需创建时才加载 UI。
     * 否则引擎会在启动时自动拉起内置包，把 MainActivity 外壳顶成黑屏。
     */
    @Override
    public boolean onShouldLoadUI() {
        return false;
    }
}
