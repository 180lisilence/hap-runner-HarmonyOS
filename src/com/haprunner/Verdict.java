package com.haprunner;

/**
 * HAP 判定结论：稳定错误码 + 支持等级 + 文案 + 建议动作。
 *
 * UI 列表、运行拦截对话框、日志与回归台账都以错误码为准，中文文案由码映射，
 * 避免同一结论在多处各写一句措辞。新增支持等级时在此补码与默认文案即可。
 */
public final class Verdict {

    // ---- 错误码（稳定，不要随文案改名）----
    /** 通过检查，可尝试拉起 */
    public static final String OK = "OK";
    /** 包结构无法解析/识别 */
    public static final String UNRECOGNIZED = "UNRECOGNIZED";
    /** 旧版 FA（config.json），运行时不支持 */
    public static final String FA_UNSUPPORTED = "FA_UNSUPPORTED";
    /** 缺少可执行字节码 modules.abc */
    public static final String NO_ABC = "NO_ABC";
    /** 纯扩展能力包，无页面 Ability */
    public static final String EXTENSION_ONLY = "EXTENSION_ONLY";
    /** 设备类型不匹配手机 */
    public static final String DEVICE_MISMATCH = "DEVICE_MISMATCH";
    /** 运行时三件套缺失，只能查看/解包 */
    public static final String RUNTIME_MISSING = "RUNTIME_MISSING";
    /** 本机内置/缓存的运行时版本与 HAP 编译版本不匹配，需联网下载对应版本 */
    public static final String RUNTIME_VERSION_MISSING = "RUNTIME_VERSION_MISSING";
    /** 缺少与设备匹配的原生库 ABI */
    public static final String ABI_MISSING = "ABI_MISSING";
    /** 字节码/API 版本与当前引擎不兼容 */
    public static final String BYTECODE_UNSUPPORTED = "BYTECODE_UNSUPPORTED";
    /** 依赖模块 / HSP 尚未支持加载 */
    public static final String HSP_UNSUPPORTED = "HSP_UNSUPPORTED";
    /** 安全检查未通过，已拒绝落盘/运行 */
    public static final String SECURITY_REJECTED = "SECURITY_REJECTED";

    // ---- 支持等级 ----
    public static final String LEVEL_RUNNABLE = "可运行";
    public static final String LEVEL_VIEW_ONLY = "仅可查看/解包";
    public static final String LEVEL_UNSUPPORTED = "明确不支持";
    public static final String LEVEL_UNKNOWN = "待验证";

    public final String code;
    public final String level;
    /** 列表卡片上的短文案 */
    public final String summary;
    /** 拦截对话框里的详细说明 */
    public final String detail;
    /** 针对该结论的下一步建议动作 */
    public final String advice;

    public Verdict(String code, String level, String summary, String detail, String advice) {
        this.code = code;
        this.level = level;
        this.summary = summary;
        this.detail = detail;
        this.advice = advice;
    }

    public boolean isRunnable() {
        return OK.equals(code);
    }

    /** 按错误码取默认等级 */
    public static String levelFor(String code) {
        if (OK.equals(code)) {
            return LEVEL_RUNNABLE;
        }
        if (RUNTIME_MISSING.equals(code) || RUNTIME_VERSION_MISSING.equals(code)) {
            return LEVEL_VIEW_ONLY;
        }
        if (UNRECOGNIZED.equals(code)) {
            return LEVEL_UNKNOWN;
        }
        return LEVEL_UNSUPPORTED;
    }

    /** 用错误码 + 可选补充参数构造结论，文案与建议动作取默认映射 */
    public static Verdict byCode(String code, String arg) {
        String summary;
        String detail;
        String advice;
        switch (code) {
            case OK:
                summary = "可以运行";
                detail = "通过结构与环境检查，可尝试拉起页面。";
                advice = "可直接点运行；若渲染异常，请把样本与现象记入台账。";
                break;
            case UNRECOGNIZED:
                summary = "无法识别：" + (arg != null && !arg.isEmpty() ? arg : "包结构不合法");
                detail = "未能解析出有效的 HarmonyOS 应用包结构。";
                advice = "请确认这是有效的 Stage/FA 鸿蒙应用包，重选其他 .hap 再试。";
                break;
            case FA_UNSUPPORTED:
                summary = "旧版 FA 模型，手机端运行时不支持";
                detail = "这是基于 config.json 的旧版 FA（Feature Ability）包。\n"
                        + "本机 ArkUI-X 运行时只支持 Stage 模型，且包内没有可执行字节码 modules.abc，无法在手机上拉起页面。";
                advice = "请改用基于 module.json 的 Stage 模型 HAP。";
                break;
            case NO_ABC:
                summary = "缺少程序字节码（无 modules.abc）";
                detail = "包内未找到 ets/modules.abc 编译产物，运行时没有可执行的 ArkTS 字节码。\n"
                        + "请使用已编译打包的 Stage 模型 HAP。";
                advice = "从完整构建输出中取 .hap，不要拿源码工程包直接装入。";
                break;
            case EXTENSION_ONLY:
                summary = "后台服务/扩展能力，没有可打开的页面";
                detail = "这是后台服务/扩展能力" + (arg != null && !arg.isEmpty() ? "（" + arg + "）" : "")
                        + "，依赖系统服务或作为能力被其他应用调用，本身没有页面，不能在宿主里直接运行。";
                advice = "如需运行，请找包含页面 Ability 的对应应用包。";
                break;
            case DEVICE_MISMATCH:
                summary = "不支持在手机上运行";
                detail = "包声明的设备类型为 " + (arg != null && !arg.isEmpty() ? arg : "未知")
                        + "，不含 phone/default，当前手机环境不在支持范围内。";
                advice = "该包面向其他设备类型（平板/车机/手表等），当前手机环境不匹配。";
                break;
            case RUNTIME_MISSING:
                summary = "只能查看，不能运行（缺运行环境）";
                detail = "运行环境未就绪，只能查看和解包。";
                advice = "用带运行时的完整版重新构建，或在已装 ArkUI-X 运行环境的设备上打开。";
                break;
            case RUNTIME_VERSION_MISSING:
                summary = "缺少对应版本运行时" + (arg != null && !arg.isEmpty() ? "（v" + arg + "）" : "");
                detail = "该 HAP 的编译 SDK / 虚拟机版本与本机内置运行时不一致，本地缓存中也没有对应版本。\n"
                        + "首次需联网下载该版本运行时；同一版本下载过后会直接复用缓存，不会重复下载。";
                advice = "在弹窗中点「下载运行时」获取对应版本；若提示下载源未配置，需先在设置中配置运行时仓库地址。";
                break;
            case ABI_MISSING:
                summary = "缺少匹配的原生库 ABI";
                detail = "包内原生库 ABI 与当前设备/转译层不匹配，未能找到可加载的 .so。";
                advice = "换与包内 ABI（见详情）匹配的设备，或联系开发者提供对应 ABI 的原生库。";
                break;
            case BYTECODE_UNSUPPORTED:
                summary = "字节码版本与引擎不兼容";
                detail = "ArkTS 字节码版本/格式超出当前方舟引擎支持范围，能力类无法注册。";
                advice = "用与当前引擎版本匹配的工具链重新编译，或升级运行时引擎。";
                break;
            case HSP_UNSUPPORTED:
                summary = "依赖模块/HSP 尚未支持";
                detail = "该包依赖其他模块或共享包（HSP），当前运行器尚未实现多模块加载。"
                        + (arg != null && !arg.isEmpty() ? " 缺失：" + arg : "");
                advice = "缺少共享包/依赖模块（见详情），多模块加载暂未支持，无法单独运行。";
                break;
            case SECURITY_REJECTED:
                summary = "安全检查未通过，已拒绝";
                detail = "解包/原生库/签名安全校验未通过，为避免越界或风险，已拒绝运行。"
                        + (arg != null && !arg.isEmpty() ? " 原因：" + arg : "");
                advice = "安全校验未通过，不要强行放行；如需复核，请把样本提交到排雷台账。";
                break;
            default:
                summary = "待验证";
                detail = "尚未对该包完成判定。";
                advice = "样本尚未完成兼容性验证，先归入待验证台账。";
                break;
        }
        return new Verdict(code, levelFor(code), summary, detail, advice);
    }
}
