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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private static final String PREFS = "hap_runner";

    // 主题色板（深浅两套，由设置页切换后重建页面生效）
    private String bg, card, txt, sub, accent, warn, ok, pillBg, pillFg;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout listBox;
    private LinearLayout foundBox;
    private TextView statusView;
    private TextView scanStatus;
    private HapStore store;
    private HapScanner scanner;
    private AlertDialog settingsDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyThemePalette();
        RuntimeManager.init(getFilesDir());
        RuntimeManager.setRepoBase(getSharedPreferences(PREFS, MODE_PRIVATE).getString("repo_base", ""));
        store = new HapStore(this);
        scanner = new HapScanner(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor(bg));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(28));
        scroll.addView(root, new ViewGroup.LayoutParams(-1, -2));
        setContentView(scroll);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("HAP 运行器", 24, txt, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1f);
        header.addView(title, titleLp);
        TextView settings = pill("设置", pillBg, pillFg);
        settings.setOnClickListener(v -> showSettings());
        header.addView(settings, new LinearLayout.LayoutParams(-2, -2));
        root.addView(header);

        TextView subtitle = text("打开 HarmonyOS 应用包（.hap）", 12, sub, false);
        subtitle.setPadding(0, dp(4), 0, dp(14));
        root.addView(subtitle);

        TextView pick = text("打开 .hap 文件", 15, txt, true);
        GradientDrawable pb = new GradientDrawable();
        pb.setColor(Color.parseColor(accent));
        pb.setCornerRadius(dp(24));
        pick.setBackground(pb);
        pick.setGravity(Gravity.CENTER);
        pick.setPadding(0, dp(13), 0, dp(13));
        pick.setOnClickListener(v -> openPicker());
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.bottomMargin = dp(10);
        root.addView(pick, plp);

        TextView scanBtn = pill("扫描本机", pillBg, pillFg);
        scanBtn.setOnClickListener(v -> startScan());
        TextView deepBtn = pill("深度扫描", pillBg, pillFg);
        deepBtn.setOnClickListener(v -> startScan(true));
        TextView dirBtn = pill("选择文件夹", pillBg, sub);
        dirBtn.setOnClickListener(v -> openTreePicker());
        TextView allBtn = pill("存储权限", pillBg, sub);
        allBtn.setOnClickListener(v -> requestAllFilesAccess());

        LinearLayout scanRow1 = new LinearLayout(this);
        scanRow1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r1lp = new LinearLayout.LayoutParams(-1, -2);
        r1lp.bottomMargin = dp(8);
        LinearLayout.LayoutParams b1lp = new LinearLayout.LayoutParams(0, -2, 1f);
        b1lp.rightMargin = dp(8);
        scanBtn.setGravity(Gravity.CENTER);
        deepBtn.setGravity(Gravity.CENTER);
        scanRow1.addView(scanBtn, b1lp);
        scanRow1.addView(deepBtn, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(scanRow1, r1lp);

        LinearLayout scanRow2 = new LinearLayout(this);
        scanRow2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r2lp = new LinearLayout.LayoutParams(-1, -2);
        r2lp.bottomMargin = dp(10);
        LinearLayout.LayoutParams b2lp = new LinearLayout.LayoutParams(0, -2, 1f);
        b2lp.rightMargin = dp(8);
        dirBtn.setGravity(Gravity.CENTER);
        allBtn.setGravity(Gravity.CENTER);
        scanRow2.addView(dirBtn, b2lp);
        scanRow2.addView(allBtn, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(scanRow2, r2lp);

        scanStatus = text("", 11, sub, false);
        scanStatus.setPadding(dp(2), 0, dp(2), dp(8));
        root.addView(scanStatus);

        foundBox = new LinearLayout(this);
        foundBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(foundBox, new LinearLayout.LayoutParams(-1, -2));

        statusView = text(runtimeLine(), 12, RuntimeProbe.isReady() ? ok : warn, false);
        statusView.setPadding(dp(2), 0, dp(2), dp(10));
        root.addView(statusView);

        buildDeviceCard(root);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox, new LinearLayout.LayoutParams(-1, -2));

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
                Toast.makeText(this, "请在系统设置中授予完整存储权限", Toast.LENGTH_LONG).show();
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
            scanStatus.setText("未授予完整存储权限，只扫描了部分目录");
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
        scanStatus.setText("扫描到 " + refs.size() + " 个应用包");
        if (refs.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(text("没有找到 .hap 文件", 13, txt, true));
            empty.addView(text("可点「选择文件夹」，或先授予存储权限后重新扫描", 11, sub, false));
            foundBox.addView(empty);
            return;
        }
        int limit = Math.min(refs.size(), 50);
        for (int i = 0; i < limit; i++) {
            foundBox.addView(foundRow(refs.get(i)));
        }
        if (refs.size() > limit) {
            foundBox.addView(text("只显示前 50 个，共 " + refs.size() + " 个", 11, sub, false));
        }
    }

    private LinearLayout foundRow(HapScanner.HapRef ref) {
        LinearLayout box = card();
        box.addView(text(ref.name, 14, txt, true));
        box.addView(text((ref.size / 1024) + " KB", 11, sub, false));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, 0);
        TextView run = pill("运行", accent);
        run.setOnClickListener(v -> importRef(ref, true));
        TextView imp = pill("加入列表", pillBg, txt);
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
                Verdict iv = pkg.computeVerdict();
                Log.i(LOG, "scan-import " + local.getName() + " bundle=" + pkg.bundleName
                        + " module=" + pkg.moduleName + " ability=" + pkg.primaryAbility()
                        + " verdict=" + iv.code + " " + iv.summary);
                ui.post(() -> {
                    Toast.makeText(this, "已加入列表", Toast.LENGTH_SHORT).show();
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
                ? "运行环境：已就绪，可以打开应用"
                : "运行环境：未就绪，只能查看和解包";
    }

    // ------------------------------------------------------------- 设置 / 主题 / 关于

    private void applyThemePalette() {
        String theme = getSharedPreferences(PREFS, MODE_PRIVATE).getString("theme", "dark");
        if ("light".equals(theme)) {
            bg = "#f2f3f7"; card = "#ffffff"; txt = "#16171c"; sub = "#5b5d66";
            accent = "#c94f76"; warn = "#b97a00"; ok = "#2e9e4f";
            pillBg = "#e6e8ee"; pillFg = "#16171c";
        } else {
            bg = "#0d0d10"; card = "#1e1e24"; txt = "#ffffff"; sub = "#a3a3ab";
            accent = "#e4608b"; warn = "#ffb020"; ok = "#4cd964";
            pillBg = "#2a2a32"; pillFg = "#ffffff";
        }
    }

    private boolean isDarkTheme() {
        return !"light".equals(getSharedPreferences(PREFS, MODE_PRIVATE).getString("theme", "dark"));
    }

    private void toggleTheme() {
        String next = isDarkTheme() ? "light" : "dark";
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("theme", next).apply();
        if (settingsDialog != null) { settingsDialog.dismiss(); settingsDialog = null; }
        recreate();
    }

    private void showSettings() {
        List<String> cached = RuntimeManager.cachedVersions();
        String rt = "运行时 " + RuntimeManager.BUNDLED_VERSION
                + " · 缓存：" + (cached.isEmpty() ? "无" : String.join("、", cached))
                + " · 源：" + (RuntimeManager.getRepoBase().isEmpty() ? "未配置" : "已填写");

        if (settingsDialog != null) { settingsDialog.dismiss(); settingsDialog = null; }
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(10), dp(16), dp(6));
        scroll.addView(box, new ViewGroup.LayoutParams(-1, -2));
        box.addView(text(rt, 12, sub, false));

        TextView themeRow = settingsRow("明暗模式：" + (isDarkTheme() ? "深色" : "浅色"));
        themeRow.setOnClickListener(v -> toggleTheme());
        box.addView(themeRow);

        TextView repoRow = settingsRow("下载源地址");
        repoRow.setOnClickListener(v -> editRepoBase());
        box.addView(repoRow);

        TextView aboutRow = settingsRow("关于本运行器");
        aboutRow.setOnClickListener(v -> showAbout());
        box.addView(aboutRow);

        settingsDialog = new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(scroll)
                .setPositiveButton("关闭", null).show();
        settingsDialog.setOnDismissListener(d -> { if (settingsDialog == d) settingsDialog = null; });
    }

    private TextView settingsRow(String label) {
        TextView t = text(label, 14, txt, false);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(pillBg));
        g.setCornerRadius(dp(12));
        t.setBackground(g);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinimumHeight(dp(44));
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private void editRepoBase() {
        final EditText input = new EditText(this);
        input.setText(RuntimeManager.getRepoBase());
        input.setHint("https://example.com/runtime-repo");
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        new AlertDialog.Builder(this)
                .setTitle("下载源地址")
                .setView(input)
                .setMessage("约定产物：<地址>/runtimes/<版本>.zip")
                .setPositiveButton("保存", (d, w) -> {
                    String base = input.getText().toString().trim();
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("repo_base", base).apply();
                    RuntimeManager.setRepoBase(base);
                    Toast.makeText(this, "下载源已保存", Toast.LENGTH_SHORT).show();
                    showSettings();
                })
                .setNegativeButton("取消", null).show();
    }

    private void showAbout() {
        List<String> cached = RuntimeManager.cachedVersions();
        String rt = "运行时信息：内置版本 " + RuntimeManager.BUNDLED_VERSION
                + "；已下载缓存：" + (cached.isEmpty() ? "无" : String.join("、", cached))
                + "；下载源：" + (RuntimeManager.getRepoBase().isEmpty() ? "未配置（可在设置中填写）" : RuntimeManager.getRepoBase())
                + "。运行时按 HAP 编译版本按需下载，同版本复用缓存。\n\n";
        String about = "出品工作室：云客工作室（个人学习项目，非商业发布）\n\n"
                + rt
                + "来源仓库：https://github.com/180lisilence/hap-runner-HarmonyOS\n\n"
                + "应用类型：Android 侧通用 HarmonyOS（.hap）运行器 / 宿主，负责选择、扫描、解析、解包，"
                + "并经 ArkUI-X 运行时渲染 ArkTS 页面；不专属于任何单个应用。\n\n"
                + "关联项目：heimayun-music-self（示例 HarmonyOS 音乐应用）；blackmusic（本地内置客户端）；"
                + "housemusic（配套数据版本仓）。\n\n"
                + "构建方：本机命令行工具链 Android SDK 35 / JDK 21 / aapt2 / d8 / apksigner；"
                + "宿主包名 com.haprunner，版本 1.1.0（versionCode 2），单 arm64-v8a。\n\n"
                + "开源借鉴：ArkUI-X 跨平台框架与 OpenHarmony 系统组件（开放原子开源项目，Apache-2.0 协议）、"
                + "方舟 Panda 字节码虚拟机及官方示例工程；选择 / 扫描 / 解析 / 解包 / 隔离 / 判定等壳层代码为本项目自研。\n\n"
                + "说明：本项目为个人学习用途，与 HarmonyOS / OpenHarmony 官方无关；运行时与引擎版权归原项目所有。";
        new AlertDialog.Builder(this).setTitle("关于").setMessage(about)
                .setPositiveButton("关闭", null).show();
    }

    /** 后台下载所选 HAP 所需版本的运行时；成功后刷新列表并自动重试拉起 */
    private void downloadRuntime(HapPackage pkg) {
        final String ver = pkg.requiredRuntimeVersion();
        Toast.makeText(this, "开始下载运行时 v" + ver + "…", Toast.LENGTH_SHORT).show();
        RuntimeManager.download(ver, new RuntimeManager.Callback() {
            @Override
            public void onProgress(int pct) {
                Log.i(LOG, "runtime download " + ver + " progress " + pct + "%");
            }

            @Override
            public void onSuccess(String v) {
                ui.post(() -> {
                    Toast.makeText(MainActivity.this, "运行时 v" + v + " 已就绪", Toast.LENGTH_SHORT).show();
                    refresh();
                    tryRun(pkg);
                });
            }

            @Override
            public void onFailure(String v, String err) {
                ui.post(() -> Toast.makeText(MainActivity.this, "运行时下载失败：" + err,
                        Toast.LENGTH_LONG).show());
            }
        });
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
                    Toast.makeText(this, "已加入列表", Toast.LENGTH_SHORT).show();
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
        bg.setColor(Color.parseColor(card));
        bg.setCornerRadius(dp(14));
        box.setBackground(bg);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(14);
        root.addView(box, lp);

        box.addView(text("本机", 13, sub, false));
        box.addView(text(android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + "  ·  Android " + android.os.Build.VERSION.RELEASE, 12, txt, false));
    }

    private void refresh() {
        if (listBox == null) {
            return;
        }
        listBox.removeAllViews();
        List<File> files = store.list();
        Set<String> keepKeys = new HashSet<>();
        if (files.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(text("还没有应用", 14, txt, true));
            empty.addView(text("点「打开 .hap 文件」选择应用包，或先扫描本机", 12, sub, false));
            listBox.addView(empty);
        } else {
            for (File f : files) {
                HapPackage pkg = HapPackage.parse(f);
                keepKeys.add(pkg.storageKey());
                listBox.addView(row(pkg));
            }
        }
        pruneExtDirs(keepKeys);
    }

    /** 清理已删除 HAP 遗留的隔离解包目录；当前导入集合之外的 ext 子目录一律移除 */
    private void pruneExtDirs(Set<String> keepKeys) {
        File extRoot = new File(getFilesDir(), "arkui-x/ext");
        File[] kids = extRoot.listFiles();
        if (kids == null) {
            return;
        }
        for (File k : kids) {
            if (k.isDirectory() && !keepKeys.contains(k.getName())) {
                Log.i(LOG, "prune stale ext dir: " + k.getName());
                deleteTree(k);
            }
        }
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(card));
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
        box.addView(text(pkg.name, 15, txt, true));
        box.addView(text(pkg.brief(), 12, sub, false));
        // 同名构建也能靠 包名/版本/构建时间/短哈希 区分
        box.addView(text(pkg.bundleName + "  ·  " + pkg.buildTimeLabel() + "  ·  " + pkg.shortSha,
                11, sub, false));
        Verdict verdict = pkg.computeVerdict();
        boolean canRun = verdict.isRunnable();
        TextView verdictView = text(verdict.summary, 12, canRun ? ok : warn, false);
        verdictView.setPadding(0, dp(4), 0, 0);
        box.addView(verdictView);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(10), 0, 0);

        TextView run = pill("运行", accent);
        run.setOnClickListener(v -> tryRun(pkg));
        TextView detail = pill("详情", pillBg, txt);
        detail.setOnClickListener(v -> showDetail(pkg));
        TextView remove = pill("删除", pillBg, sub);
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
        Verdict v = pkg.computeVerdict();
        if (!v.isRunnable()) {
            Log.i(LOG, "tryRun reject code=" + v.code + " pkg=" + pkg.name);
            String msg = v.detail;
            if (Verdict.RUNTIME_MISSING.equals(v.code)) {
                msg = msg + "\n" + RuntimeProbe.describe();
            }
            msg = msg + "\n\n建议：" + v.advice;
            AlertDialog.Builder b = new AlertDialog.Builder(this)
                    .setTitle(v.level)
                    .setMessage(msg);
            if (Verdict.RUNTIME_VERSION_MISSING.equals(v.code)) {
                b.setPositiveButton("下载运行时", (d, w) -> downloadRuntime(pkg));
                b.setNegativeButton("知道了", null);
            } else {
                b.setPositiveButton("知道了", null);
            }
            b.show();
            return;
        }
        try {
            File dir = unpack(pkg);
            Intent i = new Intent();
            i.setClassName(this, RuntimeProbe.HOST_ACTIVITY);
            i.putExtra("instanceName", pkg.instanceName());
            i.putExtra("bundlePath", dir.getAbsolutePath());
            i.putExtra("bundleName", pkg.bundleName);
            i.putExtra("moduleName", pkg.moduleName);
            i.putExtra("assetsRelPath", pkg.relPath());
            i.putExtra("hapPath", pkg.file != null ? pkg.file.getAbsolutePath() : null);
            startActivity(i);
        } catch (SecurityReject sr) {
            Log.w(LOG, "security reject: " + sr.reason);
            Verdict sv = Verdict.byCode(Verdict.SECURITY_REJECTED, sr.reason);
            new AlertDialog.Builder(this)
                    .setTitle(sv.level)
                    .setMessage(sv.detail)
                    .setPositiveButton("知道了", null).show();
        } catch (Throwable t) {
            Log.e(LOG, "launch failed", t);
            Toast.makeText(this, "启动失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showDetail(HapPackage pkg) {
        StringBuilder sb = new StringBuilder();
        sb.append("文件名：").append(pkg.name).append('\n');
        sb.append("大小：").append(pkg.size / 1024).append(" KB\n");
        sb.append("应用包名：").append(pkg.bundleName).append('\n');
        sb.append("应用模型：").append(pkg.isFaModel() ? "旧版 FA（config.json）" : "Stage（module.json）").append('\n');
        sb.append("版本：").append(pkg.versionLabel()).append('\n');
        sb.append("构建时间：").append(pkg.buildTimeLabel()).append('\n');
        sb.append("编译 SDK：").append(pkg.compileSdkType).append(' ').append(pkg.compileSdkVersion)
                .append(pkg.apiReleaseType.equals("-") ? "" : "（" + pkg.apiReleaseType + "）").append('\n');
        sb.append("厂商：").append(pkg.vendor).append("   包类型：").append(pkg.bundleType)
                .append("   构建模式：").append(pkg.buildMode).append('\n');
        sb.append("虚拟机：").append(pkg.virtualMachine).append('\n');
        sb.append("所需运行时：v").append(pkg.requiredRuntimeVersion())
                .append(RuntimeManager.isAvailable(pkg.requiredRuntimeVersion()) ? "（本地已有）" : "（未下载）").append('\n');
        sb.append("系统要求：").append(pkg.apiLabel(pkg.minApi)).append(" / ").append(pkg.apiLabel(pkg.targetApi)).append('\n');
        sb.append("模块：").append(pkg.moduleName).append("（").append(pkg.moduleType).append("）   入口：").append(pkg.mainElement).append('\n');
        sb.append("入口能力：").append(pkg.abilities.isEmpty() ? "-" : String.join("、", pkg.abilities)).append('\n');
        sb.append("扩展能力：").append(pkg.extensionAbilities.isEmpty() ? "-" : String.join("、", pkg.extensionAbilities)).append('\n');
        sb.append("页面：").append(pkg.pagesProfile).append('\n');
        sb.append("设备：").append(cleanList(pkg.deviceTypes)).append('\n');
        sb.append("原生库 ABI：").append(pkg.abis.isEmpty() ? "无" : String.join("、", pkg.abis)).append('\n');
        sb.append("签名：").append(pkg.signature).append('\n');
        sb.append("依赖模块：").append(pkg.dependencies.isEmpty() ? "无" : String.join("、", pkg.dependencies)).append('\n');
        sb.append("权限：").append(pkg.permissions.isEmpty() ? "无" : String.join("、", pkg.permissions)).append('\n');
        sb.append("压缩包条目：").append(pkg.entryCount).append("  图片等资源：").append(pkg.mediaCount).append(" 项\n");
        sb.append("程序字节码：").append(pkg.abcInfo).append("  (").append(pkg.abcSize / 1024).append(" KB)\n");
        sb.append("内部指纹：").append(pkg.internalFingerprint()).append('\n');
        Verdict v = pkg.computeVerdict();
        sb.append("判定：").append(v.code).append(" · ").append(v.level).append('\n');
        sb.append("建议动作：").append(v.advice).append('\n');
        sb.append("资源索引：").append(pkg.resourcesIndexSize / 1024).append(" KB\n");
        sb.append("SHA256：").append(pkg.sha.isEmpty() ? "-" : pkg.sha.substring(0, 24) + "…").append('\n');
        sb.append("解包目录：").append(store.dir().getParentFile().getAbsolutePath())
                .append("/").append(pkg.relPath());
        new AlertDialog.Builder(this).setTitle("应用详情").setMessage(sb.toString())
                .setPositiveButton("关闭", null).show();
    }

    // -------------------------------------------------------------- unpack

    /** 解包安全上限：总大小 / 条目数 / 单个 so / 压缩比 */
    private static final long MAX_UNPACK_TOTAL_BYTES = 300L * 1024 * 1024;
    private static final int MAX_UNPACK_ENTRIES = 5000;
    private static final long MAX_SINGLE_SO_BYTES = 100L * 1024 * 1024;
    private static final long MAX_COMPRESSION_RATIO = 100;
    private static final long BOMB_ENTRY_BYTES = 10L * 1024 * 1024;
    private static final Set<String> ALLOWED_ABI = new HashSet<>(Arrays.asList(
            "arm64-v8a", "armeabi-v7a", "armeabi", "x86_64"));

    /** 安全校验未通过：拒绝落盘/运行，带可读原因 */
    static class SecurityReject extends Exception {
        final String reason;
        SecurityReject(String reason) {
            super(reason);
            this.reason = reason;
        }
    }

    /** 匹配 hap 内原生插件库条目：libs/<abi>/libxxx.so（或 <module>/libs/<abi>/libxxx.so） */
    private static final java.util.regex.Pattern SO_LIB =
            java.util.regex.Pattern.compile("(?:^|/)libs/([^/]+)/(.+\\.so)$");

    private File unpack(HapPackage pkg) throws Exception {
        File root = new File(getFilesDir(), "arkui-x");
        // 通用隔离布局：外部 HAP 落在 arkui-x/ext/<包名-模块-短哈希>，
        // 不同包/不同构建各自独立，不覆盖内置基准模块树与其他已导入 HAP。
        File extRoot = new File(root, "ext");
        File out = new File(extRoot, pkg.storageKey());
        if (out.exists()) {
            deleteTree(out);
        }
        out.mkdirs();
        // 外部插件 so 目录：引擎第二候选搜索路径 filesDir/arkui-x/libs/<abi>
        File libsRoot = new File(root, "libs");
        boolean libsPreExisting = libsRoot.exists();
        if (libsRoot.exists()) {
            deleteTree(libsRoot);
        }

        try {
        int soCount = 0;
        int entryCount = 0;
        long totalBytes = 0;
        try (ZipFile zip = new ZipFile(pkg.file)) {
            for (java.util.Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry ze = e.nextElement();
                String n = ze.getName();
                // 拒绝绝对路径 / Windows 盘符 / 上级目录跳转，配合 inside() 规范化兜底防逃逸
                if (n.startsWith("/") || n.matches("^[A-Za-z]:.*")
                        || n.contains("../") || n.contains("..\\")) {
                    throw new SecurityReject("条目路径越界：" + n);
                }
                if (ze.isDirectory()) {
                    File target = new File(out, n);
                    if (!inside(out, target)) {
                        throw new SecurityReject("目录条目逃逸模块目录：" + n);
                    }
                    target.mkdirs();
                    continue;
                }
                entryCount++;
                if (entryCount > MAX_UNPACK_ENTRIES) {
                    throw new SecurityReject("条目数超过上限 " + MAX_UNPACK_ENTRIES);
                }
                long size = ze.getSize();
                long csize = ze.getCompressedSize();
                if (size < 0) {
                    // 元数据未给解压大小：流式计量，避免未知大小条目绕过总大小/单条限额
                    size = measureEntry(zip, ze, MAX_UNPACK_TOTAL_BYTES);
                }
                if (size > 0) {
                    totalBytes += size;
                    if (totalBytes > MAX_UNPACK_TOTAL_BYTES) {
                        throw new SecurityReject("解包总大小超过上限 " + (MAX_UNPACK_TOTAL_BYTES / 1024 / 1024) + " MB");
                    }
                    if (csize > 0 && size > BOMB_ENTRY_BYTES && size / csize > MAX_COMPRESSION_RATIO) {
                        throw new SecurityReject("疑似压缩炸弹：" + n);
                    }
                }
                java.util.regex.Matcher m = SO_LIB.matcher(n);
                if (n.endsWith(".so") && m.matches()) {
                    String abi = m.group(1);
                    if (!ALLOWED_ABI.contains(abi)) {
                        Log.w(LOG, "unpack skip unsupported abi: " + abi + " " + n);
                        continue;
                    }
                    if (size > MAX_SINGLE_SO_BYTES) {
                        throw new SecurityReject("原生库过大：" + n);
                    }
                    // 原生插件库释放到独立 so 目录，不放进模块目录
                    File soDir = new File(libsRoot, abi);
                    soDir.mkdirs();
                    File soTarget = new File(soDir, m.group(2));
                    copyEntry(zip, ze, soTarget);
                    soCount++;
                    continue;
                }
                File target = new File(out, n);
                if (!inside(out, target)) {
                    throw new SecurityReject("文件条目逃逸模块目录：" + n);
                }
                File parent = target.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                copyEntry(zip, ze, target);
            }
        }
        Log.i(LOG, "unpack bundle=" + pkg.bundleName + " module=" + pkg.moduleName
                + " dir=" + out.getAbsolutePath() + " pluginSo=" + soCount
                + " entries=" + entryCount + " bytes=" + totalBytes);
        return out;
        } catch (SecurityReject r) {
            // 安全拒绝：回滚本次落盘的模块骨架与新建 so 目录，避免被拒包残留半成品
            rollbackUnpack(out, libsRoot, libsPreExisting);
            throw r;
        } catch (Exception e) {
            rollbackUnpack(out, libsRoot, libsPreExisting);
            throw e;
        }
    }

    /** 解包失败后清理本次写出的目录；解包前已存在的 so 根目录保留 */
    private void rollbackUnpack(File out, File libsRoot, boolean libsPreExisting) {
        deleteTree(out);
        if (!libsPreExisting) {
            deleteTree(libsRoot);
        }
    }

    /** 校验解压目标未逃出模块目录（防 ../ 路径穿越） */
    private static boolean inside(File base, File target) {
        try {
            String basePath = base.getCanonicalPath();
            String targetPath = target.getCanonicalPath();
            return targetPath.equals(basePath) || targetPath.startsWith(basePath + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    /** 流式计量条目解压后字节数；超过上限即拒绝，避免未知大小条目绕过限额 */
    private long measureEntry(ZipFile zip, ZipEntry ze, long limit) throws Exception {
        long total = 0;
        try (InputStream in = zip.getInputStream(ze)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > limit) {
                    throw new SecurityReject("条目解压后超过大小上限：" + ze.getName());
                }
            }
        }
        return total;
    }

    private void copyEntry(ZipFile zip, ZipEntry ze, File target) throws Exception {
        try (InputStream in = zip.getInputStream(ze); OutputStream os = new FileOutputStream(target)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        }
    }

    private String safe(String s) {
        return s == null ? "unknown" : s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** 去掉 JSON 数组的方括号、引号，逗号换成顿号，便于阅读 */
    private String cleanList(String s) {
        if (s == null || s.isEmpty() || s.equals("-")) {
            return "-";
        }
        return s.replace("[", "").replace("]", "").replace("\"", "").replace(",", "、").trim();
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
