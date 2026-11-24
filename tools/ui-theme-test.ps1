# 明暗模式切换实测脚本（MuMu / adb）
# 流程：首页(深色) → 设置 → 切浅色 → 首页(浅色) → 再进设置 → 切回深色 → 首页(深色)
# 点击坐标全部由 uiautomator dump 的文本节点 bounds 解析，不写死坐标；截图与 XML 落到 analysis/ui-shots/
param(
    [string]$Device = '127.0.0.1:16384',
    [string]$Adb = 'D:\TOOL\Android\Sdk\platform-tools\adb.exe',
    [string]$Pkg = 'com.haprunner'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$shotDir = Join-Path $root 'analysis\ui-shots'
New-Item -ItemType Directory -Force -Path $shotDir | Out-Null
$tmpXml = Join-Path $shotDir '_tmp_ui.xml'

function AdbShell([string]$cmd) {
    & $Adb -s $Device shell $cmd
}

function Dump-Ui {
    AdbShell 'uiautomator dump /sdcard/ui.xml' | Out-Null
    Start-Sleep -Milliseconds 400
    & $Adb -s $Device pull /sdcard/ui.xml $tmpXml | Out-Null
    return Get-Content $tmpXml -Raw -Encoding UTF8
}

function Find-Center([string]$xml, [string]$text) {
    # 节点形如 <node ... text="设置" ... bounds="[x1,y1][x2,y2]" ...>
    $pattern = 'text="' + [regex]::Escape($text) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    $m = [regex]::Match($xml, $pattern)
    if (-not $m.Success) {
        # 兼容属性顺序颠倒：bounds 在前 text 在后
        $pattern2 = 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="' + [regex]::Escape($text) + '"'
        $m = [regex]::Match($xml, $pattern2)
        if (-not $m.Success) { return $null }
    }
    $x1 = [int]$m.Groups[1].Value; $y1 = [int]$m.Groups[2].Value
    $x2 = [int]$m.Groups[3].Value; $y2 = [int]$m.Groups[4].Value
    return @{ X = [int](($x1 + $x2) / 2); Y = [int](($y1 + $y2) / 2) }
}

function Tap-Text([string]$text, [int]$retries = 3) {
    for ($i = 0; $i -lt $retries; $i++) {
        $xml = Dump-Ui
        $c = Find-Center $xml $text
        if ($c) {
            AdbShell "input tap $($c.X) $($c.Y)" | Out-Null
            Start-Sleep -Milliseconds 700
            return $true
        }
        Start-Sleep -Milliseconds 600
    }
    Write-Warning "未找到可点击节点：$text"
    return $false
}

function Shot([string]$name) {
    AdbShell "screencap -p /sdcard/$name.png" | Out-Null
    Dump-Ui | Out-Null
    & $Adb -s $Device pull "/sdcard/$name.png" (Join-Path $shotDir "$name.png") | Out-Null
    Copy-Item $tmpXml (Join-Path $shotDir "$name.xml") -Force -ErrorAction SilentlyContinue
    Write-Host "[shot] $name.png"
}

# 0. 干净启动
AdbShell "am force-stop $Pkg" | Out-Null
Start-Sleep -Seconds 1
AdbShell "monkey -p $Pkg -c android.intent.category.LAUNCHER 1" | Out-Null
Start-Sleep -Seconds 4

# 1. 深色首页基线
Shot 'home-dark'

# 2. 打开设置
if (-not (Tap-Text '设置')) { throw '设置按钮未命中' }
Start-Sleep -Seconds 1
Shot 'settings-dark'

# 3. 切到浅色（行文案随当前主题变化，匹配前缀）
$xml = Dump-Ui
$themeRow = if ($xml -match '明暗模式：深色') { '明暗模式：深色' } else { '明暗模式：浅色' }
if (-not (Tap-Text $themeRow)) { throw '明暗模式行未命中' }
Start-Sleep -Seconds 3   # recreate 重建页面
Shot 'home-light'

# 4. 再进设置切回深色
if (-not (Tap-Text '设置')) { throw '浅色页设置按钮未命中' }
Start-Sleep -Seconds 1
Shot 'settings-light'

$xml = Dump-Ui
$themeRow = if ($xml -match '明暗模式：浅色') { '明暗模式：浅色' } else { '明暗模式：深色' }
if (-not (Tap-Text $themeRow)) { throw '浅色设置页明暗行未命中' }
Start-Sleep -Seconds 3
Shot 'home-dark-restored'

Write-Host '完成：home-dark / settings-dark / home-light / settings-light / home-dark-restored'
