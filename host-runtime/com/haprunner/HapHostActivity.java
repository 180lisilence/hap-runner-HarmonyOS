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
        if (moduleName == null || moduleName.isEmpty()) {
            moduleName = "entry";
        }

        Log.i(TAG, "HapHostActivity.onCreate instanceName=" + instanceName
                + " bundle=" + bundleName + " module=" + moduleName
                + " bundlePath=" + bundlePath + " hapPath=" + hapPath);

        // 先注入外部模块路径，再交给 StageActivity 拉起 Ability
        File moduleDir = bundlePath != null ? new File(bundlePath) : null;
        File hapFile = hapPath != null ? new File(hapPath) : null;
        boolean injected = HapRuntimeBridge.applyExternalModule(this, moduleDir, moduleName, hapFile);
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
