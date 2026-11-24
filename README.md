# HAP Runner for HarmonyOS

> 个人学习项目，与 HarmonyOS / OpenHarmony 官方无关。
> 一个 Android 侧宿主：在安卓手机上选择、解析、解包并运行 HarmonyOS 的 `.hap` 应用，页面经 ArkUI-X 运行时渲染。

- 包名：`com.haprunner`
- 版本：`1.1.0`（versionCode 2）
- 兼容：minSdk 21 / targetSdk 31，arm64-v8a

## 特性

- **选择 / 扫描 HAP**：手动挑选 `.hap` 文件，或扫描本地存储与已授权目录树；支持从文件管理器「打开方式」直接送入。
- **解析 HAP**：读取 `module.json`，判断模型、兼容级别、主 EntryAbility，生成实例名与摘要，附带文件 SHA-256。
- **解包装配**：HAP 先解包到私有隔离目录 `arkui-x/ext/<包名-模块-短哈希>/`，运行前同步到引擎默认模块树 `arkui-x/<moduleName>/`；APK 不内置任何示例模块。
- **运行时探测**：检测方舟引擎 `.so`、Java 适配层、宿主 Activity 是否齐备；缺失时给出精确缺项提示。
- **命令行一键打包**：`aapt2` → `javac` → `d8` → 资源/原生库/assets 拼装 → `zipalign` → `apksigner`，无需 Android Studio。
- **两种构建形态**：不带运行时（仅解析/解包）；带 ArkUI-X 运行时（可真正渲染 ArkTS 页面）。

## 目录结构

```
├─ src/com/haprunner/                宿主 Java 源码
│  ├─ MainActivity.java              主界面、权限申请、进度回调与运行入口
│  ├─ HapScanner.java               扫描本地存储 / 目录树下的 .hap，URI 树持久化
│  ├─ HapPackage.java                HAP 解析：模型、兼容判定、主 ability、摘要、校验和
│  ├─ HapStore.java                  HAP 导入、应用目录存放、列表与删除
│  └─ RuntimeProbe.java              ArkUI-X 运行时齐备性探测与缺项提示
├─ host-runtime/com/haprunner/        运行时齐备时一并编入的宿主类
│  ├─ HapApp.java                    Application 挂载
│  ├─ HapHostActivity.java           ArkUI-X 宿主 Activity（独立进程 :host）
│  ├─ HapRuntimeBridge.java           反射桥接引擎路径接口
│  └─ ModuleSync.java                 外部隔离目录同步到引擎默认模块树
├─ arkuix-template/.arkui-x/         ArkUI-X 工程配置模板
├─ res/                              启动图标与 strings
├─ AndroidManifest.xml               清单：INTERNET / 网络状态 / 存储权限
├─ build.ps1                         命令行打包脚本
└─ shot_host.png / shot_runner.png / shot4.png   界面截图
```

## 构建环境

| 组件 | 说明 |
| --- | --- |
| Android SDK | build-tools 35.0.0、platforms android-35 |
| JDK | 21（`javac --release 8` 编译） |
| ArkUI-X SDK | 可选；提供 `arkui_android_adapter.jar`、`libarkui_android.so`、`systemres` 与插件 jar/so |
| adb | 用于 `-Install` 装机与 `-Launch` 拉起 |

## 构建

```powershell
# 普通构建（不含 ArkUI-X 运行时，只能解析 / 解包）
.\build.ps1

# 带 ArkUI-X 运行时构建，并安装到已连接设备
.\build.ps1 -ArkuiXSdk "<ArkUI-X SDK 根目录>" -Install

# 探测本机 ArkUI-X 运行时是否齐备
.\build.ps1 -Probe

# 构建后拉起应用
.\build.ps1 -Launch
```

参数：

| 参数 | 说明 |
| --- | --- |
| `-ArkuiXSdk <path>` | ArkUI-X SDK 根目录；留空则自动探测常见安装路径 |
| `-MusicProject <path>` | 关联示例工程根目录（仅缺失时补启动图标，不再打包内置 HAP）；默认探测 `E:\2026music` |
| `-Install` | 构建完成后 `adb install -r` 装机 |
| `-Launch` | 装机后拉起主 Activity |
| `-Probe` | 只探测运行时，不构建 |

产物：`dist\HapRunner-1.1.0-arkui-x.apk`（带运行时）或 `dist\HapRunner-1.1.0-lite.apk`（精简版）。

## 使用

1. 打开「HAP 运行器」→ 选择 `.hap` 文件（或授权目录后扫描）。
2. 在列表中选中目标 HAP，查看解析摘要。
3. 点「运行」：运行时齐备时由 `HapHostActivity` 拉起 ArkTS 页面；缺失时按提示补齐 ArkUI-X 运行时。

## 已知限制

- ArkUI-X 下子组件的 `aboutToAppear` / `onAppear` 不回调，数据加载需放在入口页生命周期并通过 AppStorage 订阅。
- 明文 HTTP 依赖清单中的 `usesCleartextTraffic="true"`；网络权限必须声明在本宿主清单（HAP 内的权限声明对 Android 无效）。
- 当前仅验证 arm64-v8a 设备。

## 关联项目

- [heimayun-music-self](https://github.com/180lisilence/heimayun-music-self)：由本运行器加载的 HarmonyOS 音乐应用示例。

## 截图

![宿主界面](shot_host.png)

![运行器界面](shot_runner.png)
