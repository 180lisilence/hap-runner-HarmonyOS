package com.haprunner;

import android.os.Bundle;
import android.util.Log;

import java.io.File;

import ohos.stage.ability.adapter.StageActivity;

/**
 * HAP 宿主 Activity：继承 ArkUI-X 的 StageActivity，把 Android 生命周期映射到 HAP 的 Ability。
 *
 * instanceName 规则：bundleName:moduleName:abilityName:
 * 启动前先通过 {@link HapRuntimeBridge} 把外部模块目录/ hap 注入引擎路径，再 setInstanceName。
 */
public class HapHostActivity extends StageActivity {

    private static final String TAG = "HAPRUNNER";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        String instanceName = getIntent() != null ? getIntent().getStringExtra("instanceName") : null;
        String bundlePath = getIntent() != null ? getIntent().getStringExtra("bundlePath") : null;
        String bundleName = getIntent() != null ? getIntent().getStringExtra("bundleName") : null;
        String moduleName = getIntent() != null ? getIntent().getStringExtra("moduleName") : null;
        String hapPath = getIntent() != null ? getIntent().getStringExtra("hapPath") : null;
        String assetsRelPath = getIntent() != null ? getIntent().getStringExtra("assetsRelPath") : null;
        if (moduleName == null || moduleName.isEmpty()) {
            moduleName = "entry";
        }

        Log.i(TAG, "HapHostActivity.onCreate instanceName=" + instanceName
                + " bundle=" + bundleName + " module=" + moduleName
                + " rel=" + assetsRelPath
                + " bundlePath=" + bundlePath + " hapPath=" + hapPath);

        // 引擎固定从 filesDir/arkui-x/entry 读取页面字节码，所以先把外部解包目录
        // 同步覆盖到 entry，再注入；运行器本身仍通用（不内置任何具体应用）。
        File moduleDir = bundlePath != null ? new File(bundlePath) : null;
        if (moduleDir != null && moduleDir.isDirectory()) {
            moduleDir = ModuleSync.syncToEntry(this, moduleDir, moduleDir.getName());
            assetsRelPath = "arkui-x/entry";
            Log.i(TAG, "entry 已同步为外部模块: " + moduleDir.getAbsolutePath());
        }
        // 原始 hap 不再作为容器路径注入：目录模式更稳（部分设备上 sdcard 读取会被拒）
        boolean injected = HapRuntimeBridge.applyExternalModule(this, moduleDir, moduleName, assetsRelPath, null);
        Log.i(TAG, "external module injected=" + injected);

        if (instanceName != null && instanceName.length() > 0) {
            super.setInstanceName(instanceName);
        }
        try {
            super.onCreate(savedInstanceState);
            Log.i(TAG, "StageActivity.onCreate done, instanceId=" + getInstanceId());
        } catch (Throwable t) {
            Log.e(TAG, "StageActivity.onCreate failed", t);
            finish();
        }
    }
}
