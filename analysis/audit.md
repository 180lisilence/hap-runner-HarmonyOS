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

## 八、A 线（检查/排雷）第一批落地（2026-11-19）

定位：HAP 检查/排雷工具线优先，运行能力白名单渐进。改前备份 `backup/20261119-a-track/`（8 个 Java + build.ps1/Manifest/.gitignore）。仅本地，未 commit、未推远程。

### 新增/改动

- **错误码体系**（新增 `src/com/haprunner/Verdict.java`）：稳定码 `OK / UNRECOGNIZED / FA_UNSUPPORTED / NO_ABC / EXTENSION_ONLY / DEVICE_MISMATCH / RUNTIME_MISSING / ABI_MISSING / BYTECODE_UNSUPPORTED / HSP_UNSUPPORTED / SECURITY_REJECTED`；每码映射支持等级（可运行 / 仅可查看·解包 / 明确不支持 / 待验证）与中文文案。`HapPackage.computeVerdict()` 按固定顺序产出 Verdict；列表 verdict、tryRun 拦截对话框、导入日志、详情判定行统一引用错误码，不再散落硬编码文案。
- **结构化字段**（`HapPackage`）：新增 `abis`（libs/<abi>/*.so 收集）、`dependencies`（module.dependencies）、`moduleType`、`signature`（signed/unsigned）、`abcVersion`；详情弹窗补 ABI、签名、依赖模块、模块类型、判定码行。扫描脚本 `analysis/scan.ps1` 同步输出 `mtype/abi/sig/dep` 列并回填 `scan-report.csv`。
- **解包安全上限**（`MainActivity.unpack`）：总大小 300MB、条目数 5000、单 so 100MB、压缩比 100（>10MB 条目触发压缩炸弹判定）；拒绝绝对路径/盘符条目；so ABI 白名单（arm64-v8a/armeabi-v7a/armeabi/x86_64），非白名单跳过；超限抛 `SecurityReject`，运行拦截归到 `SECURITY_REJECTED` 弹窗。
- **预留未接入判定**：`ABI_MISSING`、`BYTECODE_UNSUPPORTED` 码已定义，待后续按设备 ABI 与字节码对照表接入；`HSP_UNSUPPORTED` 已在 dependencies 非空时拒绝并列出缺失模块。

### 设备端验证（MuMu V2284A，Android 15，x86_64，静音）

- 构建：`build.ps1` javac/d8 通过，APK 92,379,811 B，装机成功。
- App 启动 pid 正常，运行环境三件套就绪；列表 local.hap 显示「可以运行」（OK）。
- 基准回归：直拉 `local.hap`（com.heima.cloudmusic/entry/EntryAbility），`external module injected=true`、`CreateJsAbilityInfo call`、`Ability onForeground`、`LoadPage Success`，首页完整渲染（搜索栏/Banner/每日推荐/推荐歌单/底部导航），链路未退化 ✓。
- 设备连接变更：MuMu Nx 走 adb `127.0.0.1:7555`（备选 5557），非旧的 emulator-5556；多虚拟屏 + 浮窗模式，`uiautomator` 坐标与 `input -d <displayId>` 映射不稳。

### 未实证残留

- 扩展/FA/缺字节码等非 OK 码的拦截弹窗，本轮未在 UI 逐一点出（浮窗多屏 tap 未送达）；判定分支与文案经代码审查，扩展判定沿用上一轮已实证同源逻辑。
- 安全上限（条目数/总大小/压缩炸弹/so 白名单）未用恶意样本实测，仅代码审查 + 编译通过。
- BYTECODE_UNSUPPORTED 判定尚未接字节码版本对照逻辑。
- 私有目录实为 `files/haps/`（旧台账误记 aps）；样本目录 `analysis/samples/` 已 gitignore，未入远程。

## 九、A0.3 错 ABI 规则落地（2026-11-19 续）

改前备份 `backup/20261119-a03/`（6 个 Java + build.ps1/Manifest）。仅本地，未 commit、未推远程。

**规则定稿**：错 ABI 归 `ABI_MISSING`（兼容判定），不进 `SECURITY_REJECTED`。安全拒绝只保留路径越界、压缩炸弹、超大 so 等有真实危害的项；架构不匹配是兼容性事实，且非白名单 so 在解包层已 skip 不落盘，风险已隔离。

**实现**（`HapPackage.java`）：
- 接入 `android.os.Build.SUPPORTED_ABIS`，与包内 `abis` 做忽略大小写交集判断。
- 判定位置：HSP 之后、RUNTIME_MISSING 之前；包带 so 且与本机 ABI 无交集 → `ABI_MISSING`，参数为包内 ABI 列表；包内无 so（纯 ArkTS）不触发该码。
- 解包侧 `MainActivity.unpack` 维持现状：非白名单 ABI 的 so 跳过不落盘并记日志，整包是否可运行由 `computeVerdict` 给出。

**验证**：javac/d8 通过，APK 92,379,811 B，装机 MuMu；基准 `local.hap`（无 so）直拉 `Ability onForeground`、`LoadPage Success`，未误触发 ABI 判定，正常链路未退化 ✓。

**残留**：
- `ABI_MISSING` 尚未用错 ABI 样本在设备上实测，触发样本留待 A1 安全/兼容样本清单。
- 设备 ABI 目前直读 `Build.SUPPORTED_ABIS`；C 路线模块解耦时应改为外部注入，保持判定逻辑可脱离 Android 框架。

## 十、A 线收尾：安全闭环实测 + 本地检查工具/CLI/报告（2026-11-19 续）

方向：A 线「HAP 检查/排雷工具」优先，B 线「白名单受限运行器」冻结；定位收窄为 HAP 宿主运行器/检查器。本轮全部改动仅本地，未 commit、未推远程。改前备份 `backup/20261119-a-remain/`（8 文件）。

### 10.1 代码加固与判定接线

- **A0.1 路径穿越拒绝**（`MainActivity.unpack`）：绝对路径、Windows 盘符、`../`、`..\` 条目在落盘前抛 `SecurityReject`；目录/文件条目再过 `inside()` 规范化兜底，防逃逸出模块目录。
- **A0.2 未知大小条目兜底**：`ze.getSize()` 返回 -1 时走 `measureEntry()` 流式计量解压字节数，超 300MB 总上限即拒绝，避免未知大小条目绕过限额。
- **A0.3 错 ABI 归兼容判定**（第九节已记）：非白名单 ABI 的 so 在解包层 skip 不落盘，整包结论由 `computeVerdict` 出 `ABI_MISSING`，不进 `SECURITY_REJECTED`。
- **A2 对照表外置 + 字节码判定**：`assets/compat/abi.json`（白名单 ABI）、`assets/compat/bytecode.json`（支持魔数 PANDA）；`HapPackage` 接入 `BYTECODE_UNSUPPORTED`（abc 魔数非 PANDA）。`analysis/scan.ps1` 补 `compat` 列并回填 `scan-report.csv`。判定固定顺序：UNRECOGNIZED → FA_UNSUPPORTED → NO_ABC → BYTECODE_UNSUPPORTED → EXTENSION_ONLY → DEVICE_MISMATCH → HSP_UNSUPPORTED → ABI_MISSING → RUNTIME_MISSING → OK。
- **A3 建议动作 advice**（`Verdict.java`）：新增 `advice` 字段，每个非 OK 码附「下一步怎么办」；tryRun 拦截对话框与详情弹窗均带出建议。

### 10.2 本地检查工具（A4 预言机 + C CLI 内核共用）

- `tools/hapcheck.ps1`（PowerShell 7 + .NET System.IO.Compression，与 scan.ps1 同工具链；无 Node zip 依赖）：
  - 单文件：文本/JSON 报告 + 退出码 `0 可运行 / 1 仅查看 / 2 不支持 / 3 安全拒绝`。
  - 目录批量：`-OutCsv` 出矩阵 CSV、`-OutMarkdown` 出 Markdown 矩阵；`-Json` 出机器可读 JSON。
  - 审计：条目穿越/绝对路径/盘符、条目数/总大小/单 so/压缩比上限、ABI 集合与白名单、module/config、abc 魔数(PANDA)、deviceTypes、dependencies、签名。规则与 Java `computeVerdict` 同序，属测试镜像；判定真相源仍是 Java。
- `tools/make-security-samples.py`：构造恶意/对照样本到 `analysis/samples/security/`，并写 `expected.txt` 预期码清单。
- **环境事实**：跑脚本必须用 pwsh 7（`pwsh` 7.6.4）直接 `& .\tools\hapcheck.ps1`；`powershell.exe` 为 Windows PowerShell 5.1，对 ordered 字典点赋值/if 表达式兼容差，会导致判定列静默丢失、大括号解析错乱，禁止用 5.1 跑本项目脚本。

### 10.3 A1 安全闭环：本地预言机 + 设备实测

样本（7 个，`analysis/samples/security/`）：

| 样本 | 预期码 | 本地预言机 | 设备实测（MuMu 7555） |
|---|---|---|---|
| sec-traversal-dir.hap | SECURITY_REJECTED | PASS | SecurityReject「条目路径越界：../../evil/evil.txt」(unpack:635)，evil 未落地 |
| sec-traversal-abs.hap | SECURITY_REJECTED | PASS | SecurityReject「条目路径越界：/etc/evil.txt」 |
| sec-too-many-entries.hap | SECURITY_REJECTED | PASS | SecurityReject「条目数超过上限 5000」 |
| sec-bomb.hap | SECURITY_REJECTED | PASS | SecurityReject「疑似压缩炸弹：rawfile/fat.bin」 |
| sec-bad-abi.hap | ABI_MISSING | PASS | skip unsupported abi: mips，so 不落盘；解包继续走通 |
| legal-normal.hap | OK | PASS | unpack bundle=com.ohos.filepicker/audiopicker，正常解包不误杀 |
| legal-edge.hap | OK | PASS | unpack bundle=com.test.edge，5000 条目边界内通过 |

- 本地预言机 7/7 PASS；设备侧 4 个恶意包全部拦截、2 个合法包通过、错 ABI so 被隔离。
- 设备投递方式：`am start -a VIEW -d file://…/x.hap -n com.haprunner/.MainActivity` 触发 `handleViewIntent → importUri → parse → unpack`，绕过 MuMu 多屏不稳的 UI tap。
- 实测后已清理：样本及重名副本、被拒绝包的残留解包目录、`/data/local/tmp/hapsec/`；仅保留基准 `HeimaMusic-6.0.0.hap` 与内置解包 `entry/`、框架 `systemres/`。
- 构建：javac/d8 通过，APK `dist/HapRunner-1.1.0-arkui-x.apk` 92,379,952 B，compat 两份 json 已入包；装机 MuMu 回归基准音乐首页链路未退化。

### 10.4 B 报告化离线产物

- 结构化 JSON：单文件 `hapcheck -Json`，字段对齐矩阵（type/module/mtype/abis/bytecode/apiVersion/deviceTypes/permissions/sigStatus/dep/verdictCode/supportLevel/reasons/advice）。
- 矩阵视图：`analysis/compat-matrix.md`（15 官方样本）、`analysis/security-matrix.md`（7 安全样本）、`analysis/hapcheck-matrix.csv`、`analysis/security-matrix.csv`。
- **未做（冻结/远期）**：App 内导出文本/JSON、HTML 矩阵视图；归 B 线运行器冻结范围，待检查服务化阶段再做。

### 10.5 未实证 / 残留

- `ABI_MISSING`、`BYTECODE_UNSUPPORTED` 未在设备 UI 逐一点出拦截弹窗；按既定决策功能判定码用日志断言 + 代码审查覆盖，不占脆弱设备轮次。
- 被拒绝包在抛异常前已 `out.mkdirs()` 建模块骨架目录、越界条目之前的合法条目可能先落盘；攻击载荷本身未逃逸落地，残留骨架目录实测后已清。
- 对照表 json 为可维护数据副本；Java 侧当前用内置默认常量，`HapPackage` 无 Context 不读 assets json；改对照表需三处同步（Java 常量、json、tools 脚本）。
- 设备 ABI 直读 `Build.SUPPORTED_ABIS`；解耦 CLI 时应改为外部注入。
- MuMu 浮窗多屏 tap 坐标映射不稳仍是 UI 自动化验证阻塞；真机 NTN_AN20/A2LV6R1A11001684 被占用不可用（#17 保持 in_progress）。

## 11. #38 基准包远程图片不显示 — 根因与修复（2026-11-19）

### 11.1 现象
内置基准包（com.heima.cloudmusic）首页 Banner 轮播、每日推荐、推荐歌单的远程封面空白，仅标题/计数/底部 Tab 可见；动态页固定尺寸小图（头像、44dp 歌曲封面）远程 wall 图可见。标题栏显示「远程数据·已连接服务器」。

### 11.2 已排除项（不重复验证）
- 服务端 `/swiper /dailyRecommend /recommendList /songList /momentList` 五接口与 `/music/images/wall_01..07.jpg` 本机请求均 200，图内容与尺寸正常（wall_01=1080x1920）。
- Manifest 已声明 INTERNET/ACCESS_NETWORK_STATE，`usesCleartextTraffic=true`；日志 8 条 http_exec 已发出。
- `MusicApi` 多别名解析（image/imageUrl/cover/coverUrl/pic/img/url、avatar）兼容，URL 取空已排除；URL 为服务器绝对地址，不经 joinUrl 拼接。
- 图片缓存目录 `cache/image_file_cache/` 首启后自动建成，`mkdir failed` 为首启前噪声，非根因。

### 11.3 根因
引擎 `StageApplication` 的资源复制只把 `resources/` 落到 `filesDir/arkui-x/entry`，**不落** `ets/modules.abc`、`ets/sourceMaps.map`、`module.json`、`pack.info`、`pkgContextInfo.json`。外部 HAP 运行时按 `filesDir/arkui-x/<module>` 路径读取脚本缓冲；全新装机（uninstall 清空 files）后这些条目缺失，日志报 `RunScriptBuffer failed / invalid script buffer`，Ability 无法完整创建，页面只渲染出部分布局，首页大图表现为空白。历史设备残留旧解包树，故问题未暴露；本次 uninstall 重装后复现。

### 11.4 双 ABI 试验（已回退，保留结论）
为排除 arm64 houdini 转译的图形缓冲兼容问题，曾把 `build.ps1` 改为 x86_64 + arm64-v8a 双 ABI 打包（备份 `backup/20261119-abi-x64/build.ps1`）。设备 abilist 为 `x86_64,arm64-v8a,x86`，但进程仍以 arm64 houdini 初始化，解压库却只留 x86_64，架构错配导致 abc 运行失败、整页黑屏；单 arm64 基线同样复现 `invalid script buffer`，证明黑屏非双 ABI 回归。脚本已回退单 arm64-v8a；x86_64 原生库在该模拟器转译环境下不可用，多 ABI 打包决策冻结为远期项。

### 11.5 修复
- 新增 `host-runtime/com/haprunner/BakedBundleBootstrap.java`：`ensureBakedEntry(ctx)` 在引擎初始化前把 APK `assets/arkui-x/entry` 整棵树递归复制到 `filesDir/arkui-x/entry`；以 `ets/modules.abc` 存在且非空为幂等标志，已落盘则跳过；异常仅记日志不阻断启动。
- `HapApp.onCreate` 在 `super.onCreate()` 前调用上述自举。
- 源码备份 `backup/20261119-bootstrap/`（src 与 host-runtime 全部 .java）。

### 11.6 设备验证（MuMu 15，x86_64）
- 全新链路：uninstall → install → 首启（未手动 push 任何文件）。
- 日志：`baked entry copied to files, abcExists=true abcSize=245988`；`LaunchAbility EntryAbility` 成功，无 `invalid script buffer`；5 条 http_exec 发出。
- 截图复核：Banner 山水湖、每日推荐（山水/红墙飞檐/绿帐篷）、推荐歌单（帐篷/餐食/雪山草甸）远程封面全部显示，本地图标与底部 Tab 正常。

### 11.7 残留
- 动态页本轮未逐屏复核（小图此前轮次已验证可见）；MuMu 多屏 tap 坐标映射不稳仍是 UI 自动化阻塞，#17 保持 in_progress。
- 本轮 A 线改动与 #38 修复均未 commit、未推远程。

### 11.8 解包被拒包回滚加固 + 预言机复跑（2026-11-19 续）

- **回滚加固**：`MainActivity.unpack` 在安全拒绝或解包异常时调用 `rollbackUnpack`，删除本次写出的模块骨架目录与本次新建的 `libsRoot`；解包前已存在的 so 根目录保留。改前备份 `backup/20261119-unpack-rollback/MainActivity.java`。
- **设备验证（MuMu 7555）**：`sec-traversal-abs.hap`（条目路径越界 `/etc/evil.txt`）与 `sec-bomb.hap`（疑似压缩炸弹 `rawfile/fat.bin`）均抛 `SecurityReject`；拒绝后 `files/arkui-x/` 下未留下对应模块骨架目录（仅内置 `entry`、`systemres`）。
- **ABI 样本**：`sec-bad-abi.hap`（libs/mips）so 被 `unpack skip unsupported abi: mips` 隔离，不落盘；该包 `json/abc` 落盘目录供列表展示不支持原因，属兼容拒绝用途，非安全残留。
- **本地预言机复跑**：`hapcheck.ps1` 批量 8/8 与 `expected.txt` 对齐——legal-edge/legal-normal=OK，sec-bad-abi=ABI_MISSING，compat-bad-magic=BYTECODE_UNSUPPORTED（abc 魔数非 PANDA，老字节码占位），sec-bomb/too-many-entries/traversal-abs/traversal-dir=SECURITY_REJECTED。bad-magic 样本由 `make-security-samples.py` 新增 `compat-bad-magic.hap`（OLDABC 魔数）；设备 UI 弹窗仍受 MuMu 多屏 tap 阻塞，该码靠 Java 同序判定 + 预言机断言 + 代码审查覆盖。
- **三处一致性核查**：ABI 四值（arm64-v8a/armeabi-v7a/armeabi/x86_64）与 PANDA 魔数在 Java 常量、`assets/compat/abi.json`、`bytecode.json`、`tools/hapcheck.ps1` 一致。已知差异：Java ABI 判定对照本机 `Build.SUPPORTED_ABIS`，离线 CLI 对照白名单；解耦 CLI 时改为外部注入（远期）。
- **合法链路回归**：基准包直拉 `LaunchAbility EntryAbility`、`Ability onForeground` 正常，无 `invalid script buffer`；已知噪声（read file failed、GetAsset default.json/manifest.json）不致命。
- **推送状态**：A 线 + #38 修复已提交 `8132e12` 并推 `origin/main`；回滚加固已提交 `c17bc5c`；编译核查台账 `0ef4a1b`；bad-magic 样本与 8/8 预言机对齐 `0c4747e`，均已推 `origin/main`，工作区干净。

### 11.9 #17 命令行编译可行性核查（2026-11-19 续）

- **环境勘察**：DevEco Studio 261.23567.138.36.2600821 装于 `D:\TOOL\devecostudio\devecostudioapp\DevEco Studio`；`tools/` 下含 `hvigor/bin/hvigorw.bat`、`node/node.exe`(v24.14.1)、`ohpm/bin/ohpm.bat`、`llvm`、`arktsdoc`；SDK 在 `sdk/default/{hms,openharmony}`。
- **编译试验**：复制 `E:\2026music` 到 `backup/crosscheck-app/`（排除 build/dist/.hvigor/backup/.idea），`AppScope/app.json5` bundleName 改为 `com.learn.crosscheck`，`local.properties` 配 `sdk.dir` 与 `arkui-x.dir`；设 `DEVECO_SDK_HOME=sdk/default`、`NODE_HOME=tools/node`，跑 `hvigorw assembleHap --mode module -p product=default --no-daemon`。
- **阻塞点 1（SDK 版本识别）**：`00303312 Cannot find the corresponding SDK version under the specified SDK path`。内置 SDK 布局为 `sdk/default/{hms,openharmony}/ets...`，缺 hvigor 期望的版本号目录层。
- **阻塞点 2（缺字节码编译器）**：`sdk/default/*/ets/build-tools` 仅含 `ets-loader`（组件映射）与 `ts-checker-hooks`（since/syscap 检查），`toolchains` 为 configcheck/modulecheck/paccheck/syscapcheck 与 hilogtool；全目录未找到 `es2abc`/ark compiler/abc 生成器。`tools/llvm` 仅 clangd/lldb 脚本，`tools/arktsdoc` 为文档生成器，`jbr` 为 Java 运行时。即内置 SDK 只含类型声明、loader 与检查工具，**不含 ArkTS→abc 编译器**，命令行编不出可加载的 modules.abc。
- **结论**：#17 跨包端到端样本不能在本机命令行新编。需在装有完整 HarmonyOS SDK（含 es2abc/ark 编译器）的环境用 DevEco Studio 构建；跨包副本 `backup/crosscheck-app/` 已备好包名与配置，待编译器环境就位可直接 `assembleHap`。
- **副本与日志**：`backup/crosscheck-app/`（gitignore，不入远程）；编译日志 `backup/crosscheck-app/build-crosscheck.log`。
- **#17 双重阻塞维持**：① 真机 A2LV6R1A11001684 offline（物理连接/USB 授权/锁屏类，命令行不可强制上线）；② 本机缺 ArkTS→abc 编译器与匹配的 SDK 版本布局。模拟器侧链路（解包/隔离/判定/自举渲染）已验证。
