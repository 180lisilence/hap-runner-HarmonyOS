package com.haprunner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 通用 HAP 运行器（Android 侧宿主）
 *
 * 用法：点「选择 HAP 文件」→ 文件管理器里选任意 .hap → 自动解析并解包成 ArkUI-X 目录结构
 *       → 点「运行」交给 ArkUI-X 运行时拉起 ArkTS 页面（运行时缺失时给出精确缺失项）。
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK = 1001;
    private static final int REQ_TREE = 1002;
    private static final int REQ_STORAGE = 1003;
    private static final String LOG = "HAPRUNNER";
    private static final String BG = "#0d0d10";
    private static final String CARD = "#1e1e24";
    private static final String TXT = "#ffffff";
    private static final String SUB = "#a3a3ab";
    private static final String ACCENT = "#e4608b";
    private static final String WARN = "#ffb020";
    private static final String OK = "#4cd964";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout listBox;
    private LinearLayout foundBox;
    private TextView statusView;
    private TextView scanStatus;
    private HapStore store;
    private HapScanner scanner;
    private String bakedBundle = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new HapStore(this);
        scanner = new HapScanner(this);
        bakedBundle = readBakedBundle();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor(BG));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(28));
        scroll.addView(root, new ViewGroup.LayoutParams(-1, -2));
        setContentView(scroll);

        TextView title = text("HAP 运行器", 24, TXT, true);
        root.addView(title);
        TextView sub = text("选择任意 .hap → 解析 → 解包 → 运行", 12, SUB, false);
        sub.setPadding(0, dp(4), 0, dp(14));
        root.addView(sub);

        TextView pick = text("选择 HAP 文件", 15, "#ffffff", true);
        GradientDrawable pb = new GradientDrawable();
        pb.setColor(Color.parseColor(ACCENT));
        pb.setCornerRadius(dp(24));
        pick.setBackground(pb);
        pick.setGravity(Gravity.CENTER);
        pick.setPadding(0, dp(13), 0, dp(13));
        pick.setOnClickListener(v -> openPicker());
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.bottomMargin = dp(10);
        root.addView(pick, plp);

        LinearLayout scanRow = new LinearLayout(this);
        scanRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView scanBtn = pill("自动扫描 HAP", "#2a2a32", "#ffffff");
        scanBtn.setOnClickListener(v -> startScan());
        TextView deepBtn = pill("深度扫描", "#2a2a32", "#ffffff");
        deepBtn.setOnClickListener(v -> startScan(true));
        TextView dirBtn = pill("添加扫描目录", "#2a2a32", SUB);
        dirBtn.setOnClickListener(v -> openTreePicker());
        TextView allBtn = pill("全部文件访问", "#2a2a32", SUB);
        allBtn.setOnClickListener(v -> requestAllFilesAccess());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.rightMargin = dp(8);
        scanRow.addView(scanBtn, slp);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.rightMargin = dp(8);
        scanRow.addView(deepBtn, dlp);
        LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(-2, -2);
        slp2.rightMargin = dp(8);
        scanRow.addView(dirBtn, slp2);
        scanRow.addView(allBtn, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams scanRowLp = new LinearLayout.LayoutParams(-1, -2);
        scanRowLp.bottomMargin = dp(10);
        root.addView(scanRow, scanRowLp);

        scanStatus = text("", 11, SUB, false);
        scanStatus.setPadding(dp(2), 0, dp(2), dp(8));
        root.addView(scanStatus);

        foundBox = new LinearLayout(this);
        foundBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(foundBox, new LinearLayout.LayoutParams(-1, -2));

        statusView = text(runtimeLine(), 12, RuntimeProbe.isReady() ? OK : WARN, false);
        statusView.setPadding(dp(2), 0, dp(2), dp(10));
        root.addView(statusView);

        buildDeviceCard(root);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = text("HAP = HarmonyOS 应用包（zip）。Android 没有 ArkUI/方舟运行时，"
                + "需宿主内嵌 ArkUI-X 才能渲染 ArkTS 页面；解析与解包在本机完成。", 11, SUB, false);
        hint.setPadding(0, dp(16), 0, 0);
        root.addView(hint);

        refresh();
        handleViewIntent(getIntent());

        if (HapScanner.canScanAll(this)) {
            ui.postDelayed(this::startScan, 300);
        }
    }

    // ------------------------------------------------------------- 自动扫描

    private void openTreePicker() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE);
        } catch (Exception e) {
            Toast.makeText(this, "没有目录选择器：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable t) {
                Toast.makeText(this, "请在系统设置里授予「所有文件访问」", Toast.LENGTH_LONG).show();
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            startScan();
        }
    }

    private void startScan() {
        startScan(false);
    }

    private void startScan(boolean deep) {
        if (scanner != null) {
            scanner.setDeepScan(deep);
        }
        if (scanStatus == null) {
            return;
        }
        if (!HapScanner.canScanAll(this)) {
            scanStatus.setText("未获得全盘访问权限，扫描范围将受限（可点「全部文件访问」或「添加扫描目录」）");
        } else {
            scanStatus.setText("扫描中…");
        }
        new Thread(() -> {
            List<HapScanner.HapRef> refs = scanner.scan(new HapScanner.Listener() {
                @Override
                public void onProgress(String msg) {
                    ui.post(() -> {
                        if (scanStatus != null) {
                            scanStatus.setText(msg);
                        }
                    });
                }

                @Override
                public void onFound(List<HapScanner.HapRef> r) {
                    ui.post(() -> renderFound(r));
                }

                @Override
                public void onPartial(List<HapScanner.HapRef> r, String msg) {
                    ui.post(() -> {
                        if (scanStatus != null) {
                            scanStatus.setText(msg);
                        }
                        if (r != null && !r.isEmpty()) {
                            renderFound(r);
                        }
                    });
                }
            });
            Log.i(LOG, "scan found " + refs.size());
            for (HapScanner.HapRef r : refs) {
                Log.i(LOG, "scan: " + r.path + "  " + r.size + "B");
            }
        }).start();
    }

    private void renderFound(List<HapScanner.HapRef> refs) {
        if (foundBox == null) {
            return;
        }
        foundBox.removeAllViews();
        scanStatus.setText("扫描到 " + refs.size() + " 个 HAP"
                + (HapScanner.canScanAll(this) ? "（全盘）" : "（受限范围）")
                + "  遍历 " + scanner.getVisited() + " 项"
                + (scanner.getNullDirs() > 0 ? "，受限目录 " + scanner.getNullDirs() : "")
                + (scanner.isTruncated() ? "，已截断" : ""));
        if (refs.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(text("没有找到 .hap", 13, TXT, true));
            empty.addView(text("可点「添加扫描目录」授权某个文件夹，或点「全部文件访问」后重扫", 11, SUB, false));
            foundBox.addView(empty);
            return;
        }
        int limit = Math.min(refs.size(), 50);
        for (int i = 0; i < limit; i++) {
            foundBox.addView(foundRow(refs.get(i)));
        }
        if (refs.size() > limit) {
            foundBox.addView(text("仅显示前 50 个，共 " + refs.size() + " 个", 11, SUB, false));
        }
    }

    private String readBakedBundle() {
        try (InputStream in = getAssets().open("arkui-x/entry/module.json")) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            org.json.JSONObject root = new org.json.JSONObject(new String(bos.toByteArray(), "UTF-8"));
            return root.optJSONObject("app") != null ? root.getJSONObject("app").optString("bundleName", "") : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private LinearLayout foundRow(HapScanner.HapRef ref) {
        LinearLayout box = card();
        box.addView(text(ref.name, 14, TXT, true));
        box.addView(text(ref.path, 11, SUB, false));
        box.addView(text((ref.size / 1024) + " KB", 11, SUB, false));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, 0);
        TextView run = pill("运行", ACCENT);
        run.setOnClickListener(v -> importRef(ref, true));
        TextView imp = pill("仅导入", "#2a2a32", TXT);
        imp.setOnClickListener(v -> importRef(ref, false));
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(-2, -2);
        lp1.rightMargin = dp(8);
        actions.addView(run, lp1);
        actions.addView(imp, new LinearLayout.LayoutParams(-2, -2));
        box.addView(actions);
        return box;
    }

    private void importRef(HapScanner.HapRef ref) {
        importRef(ref, false);
    }

    private void importRef(HapScanner.HapRef ref, boolean thenRun) {
        Toast.makeText(this, "正在导入…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                File local = ref.file != null ? store.importFile(ref.file) : store.importUri(ref.uri);
                HapPackage pkg = HapPackage.parse(local);
                unpack(pkg);
                Log.i(LOG, "scan-import " + local.getName() + " bundle=" + pkg.bundleName
                        + " module=" + pkg.moduleName + " ability=" + pkg.primaryAbility()
                        + " verdict=" + pkg.compatVerdict(Build.VERSION.SDK_INT));
                ui.post(() -> {
                    Toast.makeText(this, "已导入：" + pkg.bundleName, Toast.LENGTH_SHORT).show();
                    refresh();
                    if (thenRun) {
                        tryRun(pkg);
                    }
                });
            } catch (Exception e) {
                Log.e(LOG, "scan-import failed", e);
                ui.post(() -> Toast.makeText(this, "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleViewIntent(intent);
    }

    /** 支持从文件管理器「打开方式」/「分享」直接把 .hap 送进来 */
    private void handleViewIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        Uri uri = intent.getData();
        if (uri == null) {
            return;
        }
        final Uri u = uri;
        Toast.makeText(this, "正在导入…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                File local = store.importUri(u);
                HapPackage pkg = HapPackage.parse(local);
                unpack(pkg);
                Log.i(LOG, "view-intent imported " + local.getName() + " bundle=" + pkg.bundleName
                        + " module=" + pkg.moduleName + " ability=" + pkg.primaryAbility());
                ui.post(this::refresh);
            } catch (Exception e) {
                Log.e(LOG, "view-intent import failed", e);
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusView != null) {
            statusView.setText(runtimeLine());
        }
    }

    private String runtimeLine() {
        return RuntimeProbe.isReady()
                ? "运行时：ArkUI-X 已就绪，可直接运行 HAP"
                : "运行时：未安装 ArkUI-X（可解析/解包，运行需补齐运行时）";
    }

    private void openPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/octet-stream", "application/zip", "application/x-zip-compressed", "*/*"});
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的文件选择器：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_TREE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri tree = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(tree,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                scanner.addTree(tree);
                Toast.makeText(this, "已授权目录，开始扫描", Toast.LENGTH_SHORT).show();
                startScan();
            }
            return;
        }
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        final Uri u = uri;
        Toast.makeText(this, "正在导入…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                File local = store.importUri(u);
                HapPackage pkg = HapPackage.parse(local);
                File unpacked = unpack(pkg);
                Log.i(LOG, "imported " + local.getName() + " bundle=" + pkg.bundleName
                        + " module=" + pkg.moduleName + " ability=" + pkg.primaryAbility()
                        + " minApi=" + pkg.minApi + " abc=" + pkg.abcInfo
                        + " unpacked=" + (unpacked != null ? unpacked.getAbsolutePath() : "null"));
                ui.post(() -> {
                    Toast.makeText(this, "已导入：" + pkg.bundleName, Toast.LENGTH_SHORT).show();
                    refresh();
                });
            } catch (Exception e) {
                Log.e(LOG, "import failed", e);
                ui.post(() -> Toast.makeText(this, "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ------------------------------------------------------------------ UI

    private void buildDeviceCard(LinearLayout root) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(CARD));
        bg.setCornerRadius(dp(14));
        box.setBackground(bg);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(14);
        root.addView(box, lp);

        box.addView(text("兼容性", 13, SUB, false));
        String abis = android.os.Build.SUPPORTED_ABIS != null
                ? java.util.Arrays.toString(android.os.Build.SUPPORTED_ABIS) : "-";
        box.addView(text("设备：" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + "  ·  Android " + android.os.Build.VERSION.RELEASE + "（API " + android.os.Build.VERSION.SDK_INT + "）", 12, TXT, false));
        box.addView(text("ABI：" + abis, 11, SUB, false));
        box.addView(text("本 APK minSdk " + minSdk() + " / targetSdk 31  ·  arm64-v8a + armeabi-v7a + x86_64 运行时均可加载", 11, SUB, false));
        box.addView(text("HAP 兼容：支持 Stage 模型（module.json）与 FA 模型（config.json）；"
                + "按 minAPIVersion / deviceTypes 判定", 11, SUB, false));
    }

    private int minSdk() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).applicationInfo.minSdkVersion;
        } catch (Throwable t) {
            return 21;
        }
    }

    private void refresh() {
        if (listBox == null) {
            return;
        }
        listBox.removeAllViews();
        List<File> files = store.list();
        if (files.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(text("还没有 HAP", 14, TXT, true));
            empty.addView(text("点上面的按钮，从文件管理器选择 .hap 文件", 12, SUB, false));
            listBox.addView(empty);
            return;
        }
        for (File f : files) {
            HapPackage pkg = HapPackage.parse(f);
            listBox.addView(row(pkg));
        }
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(CARD));
        bg.setCornerRadius(dp(14));
        box.setBackground(bg);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(12);
        box.setLayoutParams(lp);
        return box;
    }

    private LinearLayout row(HapPackage pkg) {
        LinearLayout box = card();
        box.addView(text(pkg.name, 15, TXT, true));
        box.addView(text(pkg.brief(), 12, SUB, false));
        box.addView(text("minApi " + pkg.minApi + " / target " + pkg.targetApi
                + "  ·  abc " + pkg.abcInfo + "  ·  " + (pkg.size / 1024) + " KB", 11, SUB, false));
        box.addView(text("pages: " + pkg.pagesProfile + "   资源: " + pkg.mediaCount + " 项"
                + "   模型: " + pkg.model.toUpperCase(), 11, SUB, false));
        String verdict = pkg.compatVerdict(android.os.Build.VERSION.SDK_INT);
        boolean canRun = verdict.equals("可运行");
        TextView verdictView = text("兼容性：" + verdict, 12, canRun ? OK : WARN, false);
        verdictView.setPadding(0, dp(4), 0, 0);
        box.addView(verdictView);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(10), 0, 0);

        TextView run = pill("运行", ACCENT);
        run.setOnClickListener(v -> tryRun(pkg));
        TextView detail = pill("详情", "#2a2a32", TXT);
        detail.setOnClickListener(v -> showDetail(pkg));
        TextView remove = pill("删除", "#2a2a32", SUB);
        remove.setOnClickListener(v -> {
            store.delete(pkg.file);
            refresh();
        });

        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(-2, -2);
        lp1.rightMargin = dp(8);
        actions.addView(run, lp1);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-2, -2);
        lp2.rightMargin = dp(8);
        actions.addView(detail, lp2);
        actions.addView(remove, new LinearLayout.LayoutParams(-2, -2));
        box.addView(actions);
        return box;
    }

    private void tryRun(HapPackage pkg) {
        if (pkg.error != null && !pkg.error.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("无法运行").setMessage(pkg.error)
                    .setPositiveButton("知道了", null).show();
            return;
        }
        if (!RuntimeProbe.isReady()) {
            new AlertDialog.Builder(this).setTitle("缺少 ArkUI-X 运行时")
                    .setMessage(RuntimeProbe.describe() + "\n" + RuntimeProbe.missingHint())
                    .setPositiveButton("知道了", null).show();
            return;
        }
        if (bakedBundle.length() > 0 && !bakedBundle.equals(pkg.bundleName)) {
            new AlertDialog.Builder(this).setTitle("这个 HAP 还没有被打进宿主")
                    .setMessage("当前运行时的资源入口是 APK 的 assets/arkui-x/<module>/，只能加载打包时内置的那一个包。\n\n"
                            + "内置包：" + bakedBundle + "\n"
                            + "当前选择：" + pkg.bundleName + "\n\n"
                            + "要运行任意 HAP，需要把它重打包成宿主 APK（素材/字节码都在手机上，"
                            + "可以在 PC 上用 android-host/build.ps1 一键生成，或等运行时的沙箱路径能力接入）。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        try {
            File dir = unpack(pkg);
            Intent i = new Intent();
            i.setClassName(this, RuntimeProbe.HOST_ACTIVITY);
            i.putExtra("instanceName", pkg.instanceName());
            i.putExtra("bundlePath", dir.getAbsolutePath());
            i.putExtra("bundleName", pkg.bundleName);
            startActivity(i);
        } catch (Throwable t) {
            Log.e(LOG, "launch failed", t);
            Toast.makeText(this, "拉起失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showDetail(HapPackage pkg) {
        StringBuilder sb = new StringBuilder();
        sb.append("文件：").append(pkg.name).append('\n');
        sb.append("大小：").append(pkg.size / 1024).append(" KB\n");
        sb.append("SHA256：").append(pkg.sha.isEmpty() ? "-" : pkg.sha.substring(0, 24) + "…").append('\n');
        sb.append("bundleName：").append(pkg.bundleName).append('\n');
        sb.append("版本：").append(pkg.versionName).append(" (").append(pkg.versionCode).append(")\n");
        sb.append("minApi / targetApi：").append(pkg.minApi).append(" / ").append(pkg.targetApi).append('\n');
        sb.append("module：").append(pkg.moduleName).append("   mainElement：").append(pkg.mainElement).append('\n');
        sb.append("abilities：").append(pkg.abilities).append('\n');
        sb.append("pages：").append(pkg.pagesProfile).append('\n');
        sb.append("deviceTypes：").append(pkg.deviceTypes).append('\n');
        sb.append("权限：").append(pkg.permissions.isEmpty() ? "无" : pkg.permissions.toString()).append('\n');
        sb.append("压缩条目：").append(pkg.entryCount).append("  媒体资源：").append(pkg.mediaCount).append('\n');
        sb.append("abc：").append(pkg.abcInfo).append("  (").append(pkg.abcSize / 1024).append(" KB)\n");
        sb.append("resources.index：").append(pkg.resourcesIndexSize / 1024).append(" KB\n");
        sb.append("解包目录：").append(store.dir().getParentFile().getAbsolutePath()).append("/arkui-x/")
                .append(safe(pkg.bundleName)).append('/').append(pkg.moduleName);
        new AlertDialog.Builder(this).setTitle("HAP 详情").setMessage(sb.toString())
                .setPositiveButton("关闭", null).show();
    }

    // -------------------------------------------------------------- unpack

    private File unpack(HapPackage pkg) throws Exception {
        File root = new File(getFilesDir(), "arkui-x");
        File out = new File(new File(root, safe(pkg.bundleName)), pkg.moduleName);
        if (out.exists()) {
            deleteTree(out);
        }
        out.mkdirs();
        try (ZipFile zip = new ZipFile(pkg.file)) {
            for (java.util.Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry ze = e.nextElement();
                File target = new File(out, ze.getName());
                File parent = target.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                if (ze.isDirectory()) {
                    target.mkdirs();
                    continue;
                }
                try (InputStream in = zip.getInputStream(ze); OutputStream os = new FileOutputStream(target)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                    }
                }
            }
        }
        return out;
    }

    private String safe(String s) {
        return s == null ? "unknown" : s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        f.delete();
    }

    // -------------------------------------------------------------- widgets

    private TextView text(String s, float size, String color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        tv.setTextColor(Color.parseColor(color));
        if (bold) {
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        }
        tv.setLineSpacing(0, 1.15f);
        return tv;
    }

    private TextView pill(String s, String bgColor) {
        return pill(s, bgColor, "#ffffff");
    }

    private TextView pill(String s, String bgColor, String fgColor) {
        TextView tv = text(s, 13, fgColor, false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(bgColor));
        bg.setCornerRadius(dp(18));
        tv.setBackground(bg);
        tv.setPadding(dp(14), dp(8), dp(14), dp(8));
        return tv;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
