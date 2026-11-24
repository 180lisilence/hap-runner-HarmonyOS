# hapcheck: HAP 本地检查预言机 / CLI 原型
# 判定顺序与 src/com/haprunner/HapPackage.java computeVerdict 保持一致；本脚本是测试镜像，真相源仍是 Java。
# 用法:
#   pwsh -File tools/hapcheck.ps1 <file.hap>            # 文本报告 + 退出码
#   pwsh -File tools/hapcheck.ps1 <file.hap> -Json     # 机器可读 JSON
#   pwsh -File tools/hapcheck.ps1 <dir> -OutCsv matrix.csv
# 退出码(单文件): 0 可运行 / 1 仅查看 / 2 不支持 / 3 安全拒绝
param(
    [Parameter(Mandatory = $true)][string]$Path,
    [switch]$Json,
    [string]$OutCsv,
    [string]$OutMarkdown
)
Add-Type -AssemblyName System.IO.Compression.FileSystem

# ---- 与 MainActivity.unpack 同源的安全常量（改动需与 Java 同步）----
$script:MAX_TOTAL_BYTES   = 300L * 1024 * 1024
$script:MAX_ENTRIES       = 5000
$script:MAX_SINGLE_SO     = 100L * 1024 * 1024
$script:MAX_RATIO         = 100
$script:BOMB_ENTRY_BYTES  = 10L * 1024 * 1024
$script:ALLOWED_ABI = @('arm64-v8a', 'armeabi-v7a', 'armeabi', 'x86_64')

function Get-Advice([string]$code) {
    switch ($code) {
        'OK' { '可直接点运行；若渲染异常，请把样本与现象记入台账。' }
        'UNRECOGNIZED' { '请确认这是有效的 Stage/FA 鸿蒙应用包，重选其他 .hap 再试。' }
        'FA_UNSUPPORTED' { '请改用基于 module.json 的 Stage 模型 HAP。' }
        'NO_ABC' { '从完整构建输出中取 .hap，不要拿源码工程包直接装入。' }
        'EXTENSION_ONLY' { '如需运行，请找包含页面 Ability 的对应应用包。' }
        'DEVICE_MISMATCH' { '该包面向其他设备类型，当前手机环境不匹配。' }
        'RUNTIME_MISSING' { '用带运行时的完整版重新构建，或在已装运行环境的设备上打开。' }
        'ABI_MISSING' { '换与包内 ABI 匹配的设备，或联系开发者提供对应 ABI 的原生库。' }
        'BYTECODE_UNSUPPORTED' { '用与当前引擎版本匹配的工具链重新编译，或升级运行时引擎。' }
        'HSP_UNSUPPORTED' { '缺少共享包/依赖模块，多模块加载暂未支持，无法单独运行。' }
        'SECURITY_REJECTED' { '安全校验未通过，不要强行放行；如需复核，请把样本提交到排雷台账。' }
        default { '样本尚未完成兼容性验证，先归入待验证台账。' }
    }
}

function Get-Level([string]$code) {
    switch ($code) {
        'OK' { '可运行' }
        'RUNTIME_MISSING' { '仅可查看/解包' }
        'UNRECOGNIZED' { '待验证' }
        default { '明确不支持' }
    }
}

function Read-EntryText($zip, $name) {
    $e = $zip.Entries | Where-Object { $_.FullName -eq $name } | Select-Object -First 1
    if (-not $e) { return $null }
    $sr = New-Object System.IO.StreamReader($e.Open(), [System.Text.Encoding]::UTF8)
    $t = $sr.ReadToEnd(); $sr.Close()
    return $t
}

function Get-AbcMagic($zip) {
    $abc = $zip.Entries | Where-Object { $_.FullName -match 'modules\.abc$' } | Select-Object -First 1
    if (-not $abc) { return 'NONE' }
    $s = $abc.Open(); $buf = New-Object byte[] 64
    $n = $s.Read($buf, 0, 64); $s.Close()
    if ($n -le 0) { return 'EMPTY' }
    $ascii = [System.Text.Encoding]::GetEncoding('ISO-8859-1').GetString($buf, 0, $n)
    if ($ascii.Contains('PANDA')) { return 'PANDA' }
    return 'UNKNOWN'
}

function Audit-Hap([string]$file) {
    $rep = [ordered]@{
        file = Split-Path $file -Leaf; sizeKB = [math]::Round((Get-Item $file).Length / 1KB, 1)
        type = '-'; bundle = '-'; module = '-'; mtype = '-'; ability = '-'
        abis = ''; bytecode = '-'; apiVersion = '-'; deviceTypes = '-'; permissions = ''
        sigStatus = 'unknown'; dep = ''; entries = 0; soCount = 0; totalBytes = 0
        versionName = '-'; versionCode = 0; buildTime = '-'; compileSdk = '-'; vm = '-'; requiredRuntime = '-'
        verdictCode = 'UNRECOGNIZED'; supportLevel = '待验证'; reasons = @(); advice = ''
    }
    $reasons = New-Object System.Collections.Generic.List[string]

    $zip = $null
    try {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($file)
        $entries = $zip.Entries
        $rep.entries = $entries.Count

        # ---- 1) 安全审计（先于兼容/功能判定）----
        $total = 0L; $abis = @{}; $soCount = 0; $secReject = $null
        $entryIndex = 0; $latest = [datetimeoffset]::MinValue
        foreach ($e in $entries) {
            $entryIndex++
            $n = $e.FullName
            if ($n.StartsWith('/') -or $n -match '^[A-Za-z]:.*' -or $n.Contains('../') -or $n.Contains('..\')) {
                $secReject = "条目路径越界：$n"; break
            }
            if (-not $e.IsDirectory) {
                if ($e.LastWriteTime -gt $latest) { $latest = $e.LastWriteTime }
                $size = $e.Length
                $csize = $e.CompressedLength
                if ($size -lt 0) { $size = 0 }  # 预言机侧不做流式计量，未知大小记 0 并在 reasons 标注
                $total += $size
                if ($entryIndex -gt $script:MAX_ENTRIES) { $secReject = "条目数超过上限 $($script:MAX_ENTRIES)"; break }
                if ($total -gt $script:MAX_TOTAL_BYTES) { $secReject = "解包总大小超过上限"; break }
                if ($csize -gt 0 -and $size -gt $script:BOMB_ENTRY_BYTES -and ($size / $csize) -gt $script:MAX_RATIO) {
                    $secReject = "疑似压缩炸弹：$n"; break
                }
                if ($n -match '\.so$') {
                    $soCount++
                    if ($size -gt $script:MAX_SINGLE_SO) { $secReject = "原生库过大：$n"; break }
                    if ($n -match '(?:^|/)libs/([^/]+)/') { $abis[$Matches[1]] = $true }
                }
            }
        }
        $rep.totalBytes = $total; $rep.soCount = $soCount
        $rep.abis = ($abis.Keys | Sort-Object) -join '|'
        if ($latest -gt [datetimeoffset]::MinValue) {
            $rep.buildTime = $latest.LocalDateTime.ToString('yyyy-MM-dd HH:mm')
        }

        if ($secReject) {
            $rep.verdictCode = 'SECURITY_REJECTED'; $reasons.Add($secReject)
        }
        else {
            $moduleJson = $entries | Where-Object { $_.FullName -eq 'module.json' }
            $configJson = $entries | Where-Object { $_.FullName -eq 'config.json' }
            $sig = $entries | Where-Object { $_.FullName -match '\.(p7b|sig|rsa|dsa|ec|cert|pem|x509)$' }
            $rep.sigStatus = if ($sig) { 'signed' } else { 'unsigned' }

            $json = $null
            if ($moduleJson) { $rep.type = 'stage'; $json = Read-EntryText $zip 'module.json' }
            elseif ($configJson) { $rep.type = 'fa'; $json = Read-EntryText $zip 'config.json' }
            else { $reasons.Add('无 module.json/config.json') }

            $o = $null
            if ($json) { try { $o = $json | ConvertFrom-Json } catch { $reasons.Add('json parse: ' + $_.Exception.Message) } }

            if ($o) {
                $app = $o.app
                if ($app) {
                    $rep.bundle = $app.bundleName
                    if ($app.minAPIVersion) { $rep.apiVersion = [string]$app.minAPIVersion }
                    if ($app.versionName) { $rep.versionName = [string]$app.versionName }
                    if ($app.versionCode) { $rep.versionCode = [int]$app.versionCode }
                    $sdkType = [string]$app.compileSdkType
                    $sdkVer = [string]$app.compileSdkVersion
                    if ($sdkVer) { $rep.compileSdk = ($sdkType, $sdkVer -join ' ') }
                }
                $mod = $o.module
                if ($mod) {
                    $rep.module = $mod.name
                    if ($mod.type) { $rep.mtype = $mod.type }
                    if ($mod.virtualMachine) { $rep.vm = [string]$mod.virtualMachine }
                    $rep.requiredRuntime = if ($app -and $app.compileSdkVersion) { [string]$app.compileSdkVersion } else { $rep.vm }
                    if ($mod.deviceTypes) { $rep.deviceTypes = ($mod.deviceTypes | ForEach-Object { "$_" }) -join '|' }
                    if ($mod.dependencies) { $rep.dep = ($mod.dependencies | ForEach-Object { if ($_ -is [string]) { $_ } else { $_.name } }) -join '|' }
                    $ab = $mod.abilities
                    if ($ab) { $rep.ability = ($ab | ForEach-Object { $_.name }) -join '|' }
                    $pm = $mod.requestPermissions
                    if (-not $pm) { $pm = $mod.reqPermissions }
                    if ($pm) { $rep.permissions = ($pm | ForEach-Object { if ($_ -is [string]) { $_ } else { $_.name } }) -join '|' }
                }
            }

            # ---- 2) 兼容/功能判定（顺序对齐 computeVerdict）----
            $magic = Get-AbcMagic $zip
            $rep.bytecode = $magic
            $code = 'OK'; $arg = $null
            if ($rep.type -eq 'fa') { $code = 'FA_UNSUPPORTED' }
            elseif ($magic -eq 'NONE') { $code = 'NO_ABC' }
            elseif ($magic -eq 'UNKNOWN') { $code = 'BYTECODE_UNSUPPORTED'; $reasons.Add('字节码魔数不在支持列表') }
            elseif (-not $rep.ability -and $o.module.extensionAbilities) { $code = 'EXTENSION_ONLY'; $arg = ($o.module.extensionAbilities | ForEach-Object { $_.name }) -join '、' }
            elseif ($rep.deviceTypes -and -not $rep.deviceTypes.Contains('phone') -and -not $rep.deviceTypes.Contains('default')) { $code = 'DEVICE_MISMATCH'; $arg = $rep.deviceTypes }
            elseif ($rep.dep) { $code = 'HSP_UNSUPPORTED'; $arg = $rep.dep }
            elseif ($rep.abis) {
                $pkg = $rep.abis -split '\|'
                if (-not ($pkg | Where-Object { $script:ALLOWED_ABI -contains $_ })) { $code = 'ABI_MISSING'; $arg = $rep.abis }
            }
            $rep.verdictCode = $code
            if ($arg) { if ($arg -is [array]) { $arg = ($arg | ForEach-Object { "$_" }) -join '、' }; $reasons.Add([string]$arg) }
        }
    }
    catch {
        $rep.verdictCode = 'UNRECOGNIZED'; $reasons.Add('解析失败: ' + $_.Exception.Message)
    }
    finally {
        if ($zip) { $zip.Dispose() }
    }

    $rep.supportLevel = Get-Level $rep.verdictCode
    $rep.advice = Get-Advice $rep.verdictCode
    if ($reasons.Count -gt 0) { $rep.reasons = $reasons.ToArray() }
    return [pscustomobject]$rep
}

function Exit-Code([string]$code) {
    switch ($code) {
        'OK' { 0 }
        'RUNTIME_MISSING' { 1 }
        'SECURITY_REJECTED' { 3 }
        default { 2 }
    }
}

# ---- 入口 ----
if ((Test-Path $Path -PathType Container)) {
    $files = Get-ChildItem $Path -Filter *.hap | Sort-Object Name
    $reports = @()
    foreach ($f in $files) { $reports += Audit-Hap $f.FullName }
    if ($OutCsv) { $reports | Export-Csv $OutCsv -Encoding UTF8 -NoTypeInformation }
    if ($OutMarkdown) {
        $lines = @('# HAP 兼容矩阵', '', "生成时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm')  样本数: $($reports.Count)", '',
            '| 文件 | 判定码 | 等级 | 包名 | 版本 | 构建时间 | 模块 | 类型 | ABI | 字节码 | 设备类型 | 依赖 | 签名 | 依据 |',
            '|---|---|---|---|---|---|---|---|---|---|---|---|---|---|')
        foreach ($x in $reports) {
            $reason = ($x.reasons -join '; ')
            $ver = "$($x.versionName) ($($x.versionCode))"
            $lines += "| $($x.file) | $($x.verdictCode) | $($x.supportLevel) | $($x.bundle) | $ver | $($x.buildTime) | $($x.module) | $($x.mtype) | $($x.abis) | $($x.bytecode) | $($x.deviceTypes) | $($x.dep) | $($x.sigStatus) | $reason |"
        }
        [System.IO.File]::WriteAllLines($OutMarkdown, $lines, [System.Text.Encoding]::UTF8)
    }
    if ($Json) { $reports | ConvertTo-Json -Depth 5 }
    else {
        $reports | ForEach-Object {
            "{0,-26} [{1,-18}] {2,-22} v{3} {4} {5}" -f $_.file, $_.verdictCode, $_.bundle, $_.versionName, $_.buildTime, ($_.reasons -join '; ')
        }
    }
    exit 0
}
else {
    $r = Audit-Hap $Path
    if ($Json) { $r | ConvertTo-Json -Depth 5 }
    else {
        "文件: $($r.file)  大小: $($r.sizeKB) KB"
        "版本: $($r.versionName) ($($r.versionCode))  构建时间: $($r.buildTime)"
        "编译 SDK: $($r.compileSdk)  虚拟机: $($r.vm)"
        "所需运行时: v$($r.requiredRuntime)"
        "判定: $($r.verdictCode) · $($r.supportLevel)"
        if ($r.reasons) { "依据: $($r.reasons -join '; ')" }
        "建议: $($r.advice)"
    }
    exit (Exit-Code $r.verdictCode)
}
