# HAP 批量测试台账（2026-11-18）

## 一、站点勘察（https://sydxky.cn/）

- 应用总数 590（搜索 API 分页，每页 20）；元数据接口未登录可读：
  - `GET /api/search.php?action=search&page=<n>` → results + pagination
  - `GET /api/search.php?action=filters` → 分类/筛选项
- **下载受登录鉴权保护**：
  - `GET /download.php?id=<app>&prepare=1` → `401 Unauthorized`（纯文本）
  - `GET /download.php?id=<app>` → 301 后落到登录表单（`login.php?redirect=download.php%3Fid%3D...`）
  - 文件直链在 CDN `downloadlb1.sydxky.cn/files/uploads/<pkg>/<带时间戳文件名>.hap`，目录列举 403；文件名由 prepare 接口返回，无法猜测。
- 自助注册：`login.php?register=1`，字段 username/nickname/password/confirm/email/**email_code（6 位邮箱验证码）**/密保问题答案；邮箱验证码需可收信邮箱或接码平台。
- 候选清单（手机、>0B、<30MB、按体积升序前 20）：`analysis/candidates.csv`。

## 二、本地/模拟器已有样本静态台账

| 样本 | 模型 | bundleName | module | ability | abc | so/abi | 备注 |
|---|---|---|---|---|---|---|---|
| test-fa.hap | fa(config.json) | com.demo.fa | entry | com.demo.fa.MainAbility | 无 | 无 | FA 已被运行器拦截 |
| test-other.hap | stage | com.demo.other(伪改) | entry | EntryAbility | 1 | 无 | abc 内固化原包名，路由黑屏（#17 残留） |
| test-so.hap | stage | com.heima.cloudmusic | entry | EntryAbility | 1 | 2（arm64-v8a, armeabi-v7a） | 占位 so 释放验证通过 |
| HeimaMusic debug | stage | com.heima.cloudmusic | entry | EntryAbility | 1 | 无 | 模拟器转译层实测首页/播放/暂停/切歌通过 |
| HeimaMusic release | stage | com.heima.cloudmusic | entry | EntryAbility | 1 | 无 | 未单独复测 |
| GTA_V_v1.0.1.hap | stage | com.shuyuan.harmonyosgtav | entry | **双 Ability**：EntryAbility / GraphicsFrameProbeAbility | 1 | 3（arm64-v8a：libgraphics_frame_surface / libc++_shared / libharmony_probe 226MB） | API26，权限 VIBRATE/GYROSCOPE，未拉起 |

## 三、代码复核发现的潜在缺陷（待新样本实测确认）

- **A. 主入口 Ability 未按 mainElement 选择**（HapPackage.primaryAbility）：当前取 `abilities.get(0)`；规范上应以 `module.mainElement` 为准在 abilities 中匹配，匹配不到再回退第 0 个。abilities 顺序与 mainElement 不一致时会拉起错误 Ability（instanceName 用错能力名 → 黑屏/找不到 Ability）。GTA_V 第 0 个恰好是 mainElement，侥幸正确。
- **B. 多模块 / HSP 依赖未处理**：解包只按 `moduleName` 落单模块目录；`module.dependencies` 未解析。多 hap 依赖场景未覆盖。
- **C. so 释放仅按 arm64/armv7 目录**：现有 APK 只编 arm64-v8a；armeabi-v7a 占位 so 能否被 houdini/x86_64 转译层加载未验证。
- **D. HarmonyOS API 档位比较逻辑无效**：compatVerdict 中 `minApi < HARMONY_API_BASE(60000000)` 恒为假，HarmonyOS 编码版本高低被跳过；目前靠引擎运行时固定版本兜底。
- **E. 解压条目未做路径穿越校验**：`new File(out, ze.getName())` 未拦截 `../`。属加固项。
- **F. abc 探测**：`endsWith("ets/modules.abc")`，多模块包可能在 `<module>/ets/modules.abc`，endsWith 可覆盖；待样本确认。

## 四、阻塞（站点渠道）

站点样本下载需要登录态；当前无账号/邮箱验证码渠道。已放弃该渠道，改用 Gitee 公开仓 `openharmony/applications_hap`（Apache-2.0，raw 直链无登录）。

## 五、本轮 15 个公开样本实测（2026-11-18，模拟器 MuMu，静音）

样本清单与结构：`analysis/sample-matrix.csv`；上机判定：`analysis/test-results.csv`；静态扫描：`analysis/scan-report.csv`。

基准确认：HeimaMusic-new（format13 abc）首页完整渲染，运行器+引擎链路正常。

| 判定 | 数量 | 表现 |
|---|---|---|
| ABILITY-OK | 2 | ability 类实例化成功（MobileDataSettings、Note）；页面仍可能因依赖系统服务黑屏 |
| ABILITY-NULL | 3 | abilityInfo nullptr，ability 查找落空，黑屏（Calc/Clock/Music） |
| ABILITY-JS-MISSING | 3 | 进入 Ability 实例化但 `Not found Ability.js`，abc 内能力类未注册（FilePicker/Security/CalendarData） |
| EXT-ONLY | 7 | 纯 ExtensionAbility（后台服务/输入法/数据共享/系统UI），无页面 Ability，不应拉起页面 |

### 实测印证的结构事实
- **非 entry 模块名可被引擎识别**：Note(module=`default`)、MobileDataSettings(module=`mobiledatasettings`) 均 ability 命中；解包目录与 `arkui-x/<moduleName>` 前缀约定成立，无需按 entry 硬编码。
- **全限定 ability 名可匹配**：MobileDataSettings ability 为 `com.ohos.mobiledatasettings.MainAbility`，命中。
- 老 OpenHarmony 4.x 包（format9/12 abc）多数能力类未被当前引擎 VM 注册，属运行时字节码兼容限制，壳层路径注入无法修复；表现为 ability null 或 Ability.js not found，页面黑屏。
- `AbilityStageObj is nullptr`、`Not found AbilityStage.js`、`read file failed` 在可跑的 Heima 上同样出现，是外部注入路径的固有噪声，不致命；真正分界是 ability 是否实例化成功。

## 六、实测后确认的运行器缺陷（壳层可修）

- **G. ExtensionAbility 未解析**（HapPackage.fill 只读 `abilities`）：纯扩展包 abilities 为空，`primaryAbility()` 回退 mainElement（全限定服务名），被当成页面 Ability 拉起后落空黑屏。应解析 `extensionAbilities`，识别纯扩展包并在判定/对话框说明「后台服务/扩展能力，无页面可运行」。
- **H. 非 entry 模块名**：壳侧已按 moduleName 解包/注入，实测可行；仅需保证 instanceName 与目录一致，无需强制 entry。
- **I. deviceTypes 误判**：compatVerdict 第 67 行「不含 phone 即不支持手机」过严；`default` 是通用设备类型，应放行。含 `default` 或 `phone` 均视为可在手机运行。
- **A. mainElement 匹配**（复核成立）：primaryAbility 应按 mainElement 在 abilities 中匹配（短名/全限定名归一），匹配不到再回退第 0 个。
- **D. API 档位比较无效**：老小数字号（9/12/14）与 Android SDK_INT 直接比较无意义；仅当 minApi >= HARMONY_API_BASE 时才按 HarmonyOS 档比较。
- **E. 解压路径穿越未校验**（加固项）：解包条目需拦截 `../`。
- **B/C/F**：多模块 HSP 依赖、多 abi so、多模块 abc 探测仍待后续样本覆盖，本轮无对照样本。

## 七、壳层修复实现与设备端验证（2026-11-18 续）

改动文件（改前备份 `backup/20261118-fix/src/com/haprunner/`）：`src/com/haprunner/HapPackage.java`、`src/com/haprunner/MainActivity.java`。仅本地，未 commit、未推远程。

| 项 | 实现 | 设备端验证 |
|---|---|---|
| G 扩展识别 | fill() 解析 `extensionAbilities`；`isExtOnly()`（abilities 空且扩展非空）；verdict「后台服务/扩展能力，没有可打开的页面」；tryRun 拦截对话框；详情新增「扩展能力」行 | AudioPicker 列表 verdict 正确显示扩展判定 ✓；拦截对话框弹窗未在 UI 点出（MuMu 多虚拟屏 tap 未送达按钮，非代码缺陷），分支经代码审查在拉起前 return |
| I 设备类型放行 | deviceTypes 含 `phone` 或 `default` 即放行 | Calc_Demo 显示「可以运行」✓（此前被误判不支持手机） |
| A mainElement 匹配 | primaryAbility 按 mainElement 在 abilities 匹配，短名/全限定名取末段归一，落空回退第 0 个 | 代码审查通过；无独立对照样本 |
| D API 无效比较 | 移除 HarmonyOS 档位与 Android SDK_INT 的直接比较，老小数字号不再误拦 | 代码审查通过 |
| E 路径穿越 | 解包条目 `inside()` 校验逃出模块目录则跳过 | 代码审查通过；无恶意样本触发 |
| H 非 entry 模块 | 保持按 moduleName 解包/注入，不强制 entry | 实测 Note/MobileDataSettings 可行 |

回归：HeimaMusic-new 重新解包拉起，`LoadPage Success`、Ability onForeground，首页完整渲染（搜索栏/Banner/每日推荐/推荐歌单/底部导航），正常链路未退化 ✓。

构建产物：`dist/HapRunner-1.1.0-arkui-x.apk`（92,375,715 B），已装机 emulator-5556；javac/d8 通过。

未实证残留：
- 其余 6 个扩展包（NavigationBar/kikaInput/ScreenShot/SettingsData/SystemDialog/VolumePanel）列表 verdict 未逐一在 UI 读全，解析规则与已验证的 AudioPicker 同源。
- G 拦截对话框视觉弹窗未截到（tap 路由问题）。
- B 多模块 HSP 依赖、C 多 abi so、F 多模块 abc 探测仍无对照样本。
- fmt9/12 老 abc 在当前引擎 VM 下能力类未注册，属运行时兼容限制，壳层路径注入无法修复。
