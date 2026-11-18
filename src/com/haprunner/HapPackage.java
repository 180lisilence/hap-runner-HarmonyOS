package com.haprunner;

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
    public int entryCount = 0;
    public int mediaCount = 0;
    public long abcSize = 0;
    public long resourcesIndexSize = 0;
    public String abcInfo = "-";
    public String error = "";
    /** stage / fa */
    public String model = "stage";

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

    /** HarmonyOS API 编码以 60000000 为基数（如 60000020 = API 20），与 Android SDK_INT 不是一套编号，不能直接比 */
    private static final int HARMONY_API_BASE = 60000000;

    /** 兼容性判定：设备 API + 包自身声明 + 运行时状态 */
    public String compatVerdict(int deviceApi) {
        if (error != null && !error.isEmpty()) {
            return "无法识别：" + error;
        }
        if (isFaModel()) {
            return "旧版 FA 模型，手机端运行时不支持";
        }
        if (!hasAbc()) {
            return "缺少程序字节码（无 modules.abc），无法运行";
        }
        if (isExtOnly()) {
            return "后台服务/扩展能力，没有可打开的页面";
        }
        // deviceTypes：default 为通用类型，phone/default 均视为可在手机运行
        if (deviceTypes != null && deviceTypes.contains("[")
                && !deviceTypes.contains("phone") && !deviceTypes.contains("default")) {
            return "不支持在手机上运行";
        }
        if (!RuntimeProbe.isReady()) {
            return "只能查看，不能运行（缺运行环境）";
        }
        return "可以运行";
    }

    public static HapPackage parse(File hap) {
        HapPackage p = new HapPackage();
        p.file = hap;
        p.name = hap.getName();
        p.size = hap.length();
        try {
            p.sha = sha256(hap);
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
                if (n.equals("module.json")) {
                    moduleJson = read(zip.getInputStream(ze));
                } else if (n.equals("config.json")) {
                    configJson = read(zip.getInputStream(ze));
                } else if (n.endsWith("ets/modules.abc")) {
                    p.abcSize = ze.getSize();
                    p.abcInfo = probeAbc(zip.getInputStream(ze));
                } else if (n.endsWith("resources.index")) {
                    p.resourcesIndexSize = ze.getSize();
                } else if (n.startsWith("resources/")) {
                    p.mediaCount++;
                }
            }
            zip.close();
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
            }
            if (mod != null) {
                moduleName = mod.optString("name", "entry");
                mainElement = mod.optString("mainElement", "-");
                pagesProfile = mod.optString("pages", "-");
                deviceTypes = mod.optJSONArray("deviceTypes") != null ? mod.optJSONArray("deviceTypes").toString() : "-";
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

    public String brief() {
        return "v" + versionName + "  ·  " + (size / 1024) + " KB";
    }

    /** API 版本号显示：HarmonyOS 编码换算为 API level，否则按 Android API 显示 */
    public String apiLabel(int v) {
        if (v <= 0) {
            return "-";
        }
        return v >= HARMONY_API_BASE ? "HarmonyOS API " + (v - HARMONY_API_BASE) : "Android API " + v;
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
