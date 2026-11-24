package com.haprunner;

import android.os.Build;

import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 任意 HAP 的解析结果（不依赖具体项目，纯按包结构解析）。
 */
public class HapPackage {

    public String name = "";
    public File file;
    public long size;
    public String sha = "";
    public String bundleName = "-";
    public String versionName = "-";
    public int versionCode = 0;
    public int minApi = 0;
    public int targetApi = 0;
    public String moduleName = "entry";
    public String mainElement = "-";
    public String pagesProfile = "-";
    public String deviceTypes = "-";
    public final List<String> abilities = new ArrayList<>();
    public final List<String> extensionAbilities = new ArrayList<>();
    public final List<String> permissions = new ArrayList<>();
    /** 包内原生库 ABI（libs/<abi>/*.so 收集） */
    public final List<String> abis = new ArrayList<>();
    /** 声明依赖的模块 / HSP 名称 */
    public final List<String> dependencies = new ArrayList<>();
    public String moduleType = "-";
    /** signed / unsigned / unknown */
    public String signature = "unknown";
    public String abcVersion = "-";
    public int entryCount = 0;
    public int mediaCount = 0;
    public long abcSize = 0;
    public long resourcesIndexSize = 0;
    public String abcInfo = "-";
    public String error = "";
    /** stage / fa */
    public String model = "stage";
    /** 包内条目最新修改时间（epoch ms，DOS 时间戳 2 秒精度），作为构建时间 */
    public long buildTime = 0;
    /** 编译 SDK / 发布类型 / 厂商 / 包类型 / 构建模式（module.json app 段） */
    public String compileSdkVersion = "-";
    public String compileSdkType = "-";
    public String apiReleaseType = "-";
    public String vendor = "-";
    public String bundleType = "-";
    public String buildMode = "-";
    /** 模块段虚拟机标识（ark13.0.1.0 等） */
    public String virtualMachine = "-";
    /** SHA-256 前 12 位短指纹，用于同名包区分 */
    public String shortSha = "-";

    public boolean isFaModel() {
        return "fa".equals(model);
    }

    /** 是否带可执行的 Stage 字节码 modules.abc */
    public boolean hasAbc() {
        return abcSize > 0;
    }

    /** 是否含可打开页面的页面 Ability */
    public boolean hasPageAbility() {
        return !abilities.isEmpty();
    }

    /** 是否为纯扩展包：只有 ExtensionAbility（后台服务/输入法/数据共享/卡片等），无页面 */
    public boolean isExtOnly() {
        return abilities.isEmpty() && !extensionAbilities.isEmpty();
    }

    /** 包内原生库 ABI 与本机支持 ABI 是否有交集（忽略大小写）；无 so 时视为不涉及 ABI 匹配 */
    private boolean abiMatchesDevice() {
        String[] deviceAbis = Build.SUPPORTED_ABIS;
        if (deviceAbis == null) {
            return true;
        }
        for (String d : deviceAbis) {
            for (String a : abis) {
                if (d != null && d.equalsIgnoreCase(a)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** HarmonyOS API 编码以 60000000 为基数（如 60000020 = API 20），与 Android SDK_INT 不是一套编号，不能直接比 */
    private static final int HARMONY_API_BASE = 60000000;

    /** 结构化判定：按固定顺序产出错误码结论，UI/日志/台账统一引用 */
    public Verdict computeVerdict() {
        if (error != null && !error.isEmpty()) {
            return Verdict.byCode(Verdict.UNRECOGNIZED, error);
        }
        if (isFaModel()) {
            return Verdict.byCode(Verdict.FA_UNSUPPORTED, null);
        }
        if (!hasAbc()) {
            return Verdict.byCode(Verdict.NO_ABC, null);
        }
        // 有字节码但魔数不在当前引擎支持列表（见 assets/compat/bytecode.json）
        if (abcInfo != null && abcInfo.startsWith("未知格式")) {
            return Verdict.byCode(Verdict.BYTECODE_UNSUPPORTED, abcInfo);
        }
        if (isExtOnly()) {
            String exts = extensionAbilities.isEmpty() ? null : String.join("、", extensionAbilities);
            return Verdict.byCode(Verdict.EXTENSION_ONLY, exts);
        }
        // deviceTypes：default 为通用类型，phone/default 均视为可在手机运行
        if (deviceTypes != null && deviceTypes.contains("[")
                && !deviceTypes.contains("phone") && !deviceTypes.contains("default")) {
            return Verdict.byCode(Verdict.DEVICE_MISMATCH, cleanDeviceTypes(deviceTypes));
        }
        if (!dependencies.isEmpty()) {
            return Verdict.byCode(Verdict.HSP_UNSUPPORTED, String.join("、", dependencies));
        }
        // 包内带原生库但与本机支持 ABI 无交集：属兼容缺失，不按安全拒绝处理
        if (!abis.isEmpty() && !abiMatchesDevice()) {
            return Verdict.byCode(Verdict.ABI_MISSING, String.join("、", abis));
        }
        if (!RuntimeProbe.isReady()) {
            return Verdict.byCode(Verdict.RUNTIME_MISSING, null);
        }
        if (!RuntimeManager.isAvailable(requiredRuntimeVersion())) {
            return Verdict.byCode(Verdict.RUNTIME_VERSION_MISSING, requiredRuntimeVersion());
        }
        return Verdict.byCode(Verdict.OK, null);
    }

    /** 兼容旧调用：返回判定短文案 */
    public String compatVerdict(int deviceApi) {
        return computeVerdict().summary;
    }

    private static String cleanDeviceTypes(String s) {
        if (s == null || s.isEmpty() || "-".equals(s)) {
            return "-";
        }
        return s.replace("[", "").replace("]", "").replace("\"", "").replace(",", "、").trim();
    }

    public static HapPackage parse(File hap) {
        HapPackage p = new HapPackage();
        p.file = hap;
        p.name = hap.getName();
        p.size = hap.length();
        try {
            p.sha = sha256(hap);
            p.shortSha = p.sha.length() >= 12 ? p.sha.substring(0, 12) : p.sha;
            ZipFile zip = new ZipFile(hap);
            String moduleJson = null;
            String configJson = null;
            for (java.util.Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry ze = e.nextElement();
                if (ze.isDirectory()) {
                    continue;
                }
                p.entryCount++;
                String n = ze.getName();
                long et = ze.getTime();
                if (et > p.buildTime) {
                    p.buildTime = et;
                }
                if (n.equals("module.json")) {
                    moduleJson = read(zip.getInputStream(ze));
                } else if (n.equals("config.json")) {
                    configJson = read(zip.getInputStream(ze));
                } else if (n.endsWith("ets/modules.abc")) {
                    p.abcSize = ze.getSize();
                    p.abcInfo = probeAbc(zip.getInputStream(ze));
                    p.abcVersion = p.abcInfo;
                } else if (n.endsWith("resources.index")) {
                    p.resourcesIndexSize = ze.getSize();
                } else if (n.startsWith("resources/")) {
                    p.mediaCount++;
                } else {
                    java.util.regex.Matcher m = SO_ABI.matcher(n);
                    if (m.matches()) {
                        String abi = m.group(1);
                        if (!p.abis.contains(abi)) {
                            p.abis.add(abi);
                        }
                    } else if (isSignatureEntry(n)) {
                        p.signature = "signed";
                    }
                }
            }
            zip.close();
            if ("unknown".equals(p.signature)) {
                p.signature = "unsigned";
            }
            if (moduleJson != null) {
                p.model = "stage";
                p.fill(moduleJson);
            } else if (configJson != null) {
                p.model = "fa";
                p.fillFa(configJson);
            } else {
                p.error = "不是有效的 HarmonyOS 应用包";
            }
        } catch (Exception e) {
            p.error = "解析失败：" + e;
        }
        return p;
    }

    private void fill(String json) {
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);
            org.json.JSONObject app = root.optJSONObject("app");
            org.json.JSONObject mod = root.optJSONObject("module");
            if (app != null) {
                bundleName = app.optString("bundleName", "-");
                versionName = app.optString("versionName", "-");
                versionCode = app.optInt("versionCode", 0);
                minApi = app.optInt("minAPIVersion", 0);
                targetApi = app.optInt("targetAPIVersion", 0);
                compileSdkVersion = app.optString("compileSdkVersion", "-");
                compileSdkType = app.optString("compileSdkType", "-");
                apiReleaseType = app.optString("apiReleaseType", "-");
                vendor = app.optString("vendor", "-");
                bundleType = app.optString("bundleType", "-");
                buildMode = app.optString("buildMode", "-");
            }
            if (mod != null) {
                moduleName = mod.optString("name", "entry");
                moduleType = mod.optString("type", "-");
                virtualMachine = mod.optString("virtualMachine", "-");
                mainElement = mod.optString("mainElement", "-");
                pagesProfile = mod.optString("pages", "-");
                deviceTypes = mod.optJSONArray("deviceTypes") != null ? mod.optJSONArray("deviceTypes").toString() : "-";
                org.json.JSONArray dep = mod.optJSONArray("dependencies");
                if (dep != null) {
                    for (int i = 0; i < dep.length(); i++) {
                        org.json.JSONObject d = dep.optJSONObject(i);
                        String name = d != null ? d.optString("name", "") : dep.optString(i).toString();
                        if (!name.isEmpty() && !dependencies.contains(name)) {
                            dependencies.add(name);
                        }
                    }
                }
                org.json.JSONArray ab = mod.optJSONArray("abilities");
                if (ab != null) {
                    for (int i = 0; i < ab.length(); i++) {
                        abilities.add(ab.optJSONObject(i).optString("name"));
                    }
                }
                org.json.JSONArray ext = mod.optJSONArray("extensionAbilities");
                if (ext != null) {
                    for (int i = 0; i < ext.length(); i++) {
                        extensionAbilities.add(ext.optJSONObject(i).optString("name"));
                    }
                }
                org.json.JSONArray pm = mod.optJSONArray("requestPermissions");
                if (pm != null) {
                    for (int i = 0; i < pm.length(); i++) {
                        permissions.add(pm.optJSONObject(i).optString("name"));
                    }
                }
            }
        } catch (Exception e) {
            error = "module.json 解析失败：" + e.getMessage();
        }
    }

    /** FA 模型（config.json）解析，兼容旧版 HAP */
    private void fillFa(String json) {
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);
            org.json.JSONObject app = root.optJSONObject("app");
            org.json.JSONObject mod = root.optJSONObject("module");
            if (app != null) {
                bundleName = app.optString("bundleName", "-");
                org.json.JSONObject ver = app.optJSONObject("version");
                if (ver != null) {
                    versionName = ver.optString("name", "-");
                    versionCode = ver.optInt("code", 0);
                } else {
                    versionName = app.optString("versionName", "-");
                    versionCode = app.optInt("versionCode", 0);
                }
                minApi = app.optInt("minAPIVersion", 0);
                targetApi = app.optInt("targetAPIVersion", minApi);
            }
            if (mod != null) {
                moduleName = mod.optString("name", "entry");
                mainElement = mod.optString("mainAbility", "-");
                org.json.JSONArray dev = mod.optJSONArray("deviceType");
                deviceTypes = dev != null ? dev.toString() : "-";
                org.json.JSONArray ab = mod.optJSONArray("abilities");
                if (ab != null) {
                    for (int i = 0; i < ab.length(); i++) {
                        abilities.add(ab.optJSONObject(i).optString("name"));
                    }
                }
                org.json.JSONArray pm = mod.optJSONArray("reqPermissions");
                if (pm != null) {
                    for (int i = 0; i < pm.length(); i++) {
                        org.json.JSONObject o = pm.optJSONObject(i);
                        permissions.add(o != null ? o.optString("name") : pm.optString(i));
                    }
                }
            }
        } catch (Exception e) {
            error = "config.json 解析失败：" + e.getMessage();
        }
    }

    public String primaryAbility() {
        if (abilities.isEmpty()) {
            return mainElement;
        }
        if (mainElement != null && !mainElement.isEmpty() && !"-".equals(mainElement)) {
            for (String ab : abilities) {
                if (mainElement.equals(ab) || shortName(mainElement).equals(shortName(ab))) {
                    return ab;
                }
            }
        }
        return abilities.get(0);
    }

    /** 取全限定名最后一段（类名），用于短名归一匹配 */
    private static String shortName(String name) {
        if (name == null) {
            return "";
        }
        int idx = name.lastIndexOf('.');
        return idx >= 0 ? name.substring(idx + 1) : name;
    }
    public String instanceName() {
        return bundleName + ":" + moduleName + ":" + primaryAbility() + ":";
    }

    /**
     * 解包存储键：包名 + 模块名 + 短哈希前 8 位。
     * 不同 HAP（含同名不同构建）落到各自隔离目录，互不覆盖，也不碰内置基准模块树。
     */
    public String storageKey() {
        String h = shortSha.length() >= 8 ? shortSha.substring(0, 8) : shortSha;
        return safeKey(bundleName) + "_" + safeKey(moduleName) + "_" + safeKey(h);
    }

    /** 解包目录相对 arkui-x 根的前缀（供引擎路径注入） */
    public String relPath() {
        return "arkui-x/ext/" + storageKey();
    }

    /** 该 HAP 所需的运行时版本号：优先编译 SDK 版本，缺失时回退虚拟机标识 */
    public String requiredRuntimeVersion() {
        if (compileSdkVersion != null && !compileSdkVersion.isEmpty() && !"-".equals(compileSdkVersion)) {
            return compileSdkVersion;
        }
        return virtualMachine != null ? virtualMachine : "-";
    }

    private static String safeKey(String s) {
        if (s == null || s.isEmpty()) {
            return "unknown";
        }
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public String brief() {
        return versionLabel() + "  ·  " + (size / 1024) + " KB";
    }

    /** 版本显示：versionName (versionCode)，缺项时回退到可用字段 */
    public String versionLabel() {
        boolean hasName = versionName != null && !versionName.isEmpty() && !"-".equals(versionName);
        if (!hasName && versionCode <= 0) {
            return "-";
        }
        if (!hasName) {
            return String.valueOf(versionCode);
        }
        return versionCode > 0 ? versionName + " (" + versionCode + ")" : versionName;
    }

    /** 构建时间（包内条目时间戳最大值），格式 yyyy-MM-dd HH:mm；无时间戳时返回 - */
    public String buildTimeLabel() {
        if (buildTime <= 0) {
            return "-";
        }
        java.text.SimpleDateFormat sdf =
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US);
        return sdf.format(new java.util.Date(buildTime));
    }

    /** 内部指纹：字节码体积 + 条目数 + 短哈希，用于同名包区分 */
    public String internalFingerprint() {
        return "abc " + (abcSize / 1024) + " KB · " + entryCount + " 条目 · " + shortSha;
    }

    /** API 版本号显示：HarmonyOS 编码换算为 API level，否则按 Android API 显示 */
    public String apiLabel(int v) {
        if (v <= 0) {
            return "-";
        }
        return v >= HARMONY_API_BASE ? "HarmonyOS API " + (v - HARMONY_API_BASE) : "Android API " + v;
    }

    /** 匹配 libs/<abi>/libxxx.so（或 <module>/libs/<abi>/libxxx.so） */
    private static final java.util.regex.Pattern SO_ABI =
            java.util.regex.Pattern.compile("(?:^|/)libs/([^/]+)/(.+\\.so)$");

    private static boolean isSignatureEntry(String n) {
        String l = n.toLowerCase();
        return l.matches(".*\\.(p7b|sig|rsa|dsa|ec|cert|pem|x509)$");
    }

    // ---------------------------------------------------------------- utils

    private static String read(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static String probeAbc(InputStream in) {
        try {
            byte[] head = new byte[64];
            int n = in.read(head);
            in.close();
            if (n <= 0) {
                return "(空)";
            }
            String ascii = new String(head, 0, n, "ISO-8859-1");
            int idx = ascii.indexOf("PANDA");
            StringBuilder ver = new StringBuilder();
            if (idx >= 0) {
                for (int i = idx + 5; i < n && ver.length() < 12; i++) {
                    char c = (char) (head[i] & 0xff);
                    if (c >= '0' && c <= '9') {
                        ver.append(c);
                    } else if (c == '.' && ver.length() > 0) {
                        ver.append(c);
                    } else if (ver.length() > 0) {
                        break;
                    }
                }
            }
            return (idx >= 0 ? "PANDA 字节码" : "未知格式") + (ver.length() > 0 ? "  v" + ver : "");
        } catch (Exception e) {
            return "读取失败";
        }
    }

    public static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
