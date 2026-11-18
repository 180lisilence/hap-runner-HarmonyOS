package com.haprunner;

import android.os.Bundle;
import android.util.Log;

import ohos.stage.ability.adapter.StageActivity;

/**
 * HAP 宿主 Activity：继承 ArkUI-X 的 StageActivity，把 Android 生命周期映射到 HAP 的 Ability。
 *
 * instanceName 规则：bundleName:moduleName:abilityName:
 * 运行时按该名字到 assets/arkui-x/<moduleName>/ 下取 modules.abc 与 resources.index 渲染。
 */
public class HapHostActivity extends StageActivity {

    private static final String TAG = "HAPRUNNER";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        String instanceName = getIntent() != null ? getIntent().getStringExtra("instanceName") : null;
        Log.i(TAG, "HapHostActivity.onCreate instanceName=" + instanceName);
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
