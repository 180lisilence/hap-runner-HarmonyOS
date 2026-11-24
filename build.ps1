# 构建 HAP 运行器（纯命令行：aapt2 + javac + d8 + zipalign + apksigner）
#   .\build.ps1                                  普通构建（无运行时，只能解析/解包）
#   .\build.ps1 -ArkuiXSdk "E:\tool\DevEco StudioArk\26.0.0\arkui-x" -Install -Launch
#                                               带 ArkUI-X 运行时构建（可真正渲染 ArkTS 页面）
#   .\build.ps1 -Probe                           探测运行时
param(
  [switch]$Install,
  [switch]$Launch,
  [switch]$Probe,
  [string]$ArkuiXSdk = "",
  [string]$MusicProject = ""
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$build = Join-Path $root 'build'
$dist = Join-Path $root 'dist'

# 可选示例工程根目录（仅用于取 launcher 图标与内置基线 HAP；运行器本身不绑定任何具体应用）。
# 默认探测 E:\2026music，其次回退到上级目录；取不到对应文件时跳过，不阻断构建。
if ($MusicProject -eq '') {
  $MusicProject = if (Test-Path 'E:\2026music') { 'E:\2026music' } else { Split-Path $root -Parent }
}
$musicRoot = $MusicProject

function Find-ArkuiXSdk {
  $roots = @(
    (Join-Path $env:LOCALAPPDATA 'Huawei\ArkUI-X'),
    (Join-Path $env:LOCALAPPDATA 'Huawei\Sdk'),
    (Join-Path $env:LOCALAPPDATA 'Huawei\DevEcoStudio26.0'),
    'E:\tool\DevEco StudioArk',
    'D:\TOOL\devecostudio\devecostudioapp\DevEco Studio\sdk'
  )
  foreach ($r in $roots) {
    if (-not (Test-Path $r)) { continue }
    $hit = Get-ChildItem $r -Recurse -Filter 'arkui-x.json' -Depth 6 -ErrorAction SilentlyContinue |
      Select-Object -First 1
    if ($hit) { return (Split-Path $hit.FullName -Parent) }
  }
  return ''
}

if ($ArkuiXSdk -eq '') { $ArkuiXSdk = Find-ArkuiXSdk }

if ($Probe) {
  Write-Host "== ArkUI-X 运行时探测 =="
  if ($ArkuiXSdk -eq '') {
    Write-Host "[x] 未找到 ArkUI-X SDK（arkui-x.json）"
    exit 1
  }
  Write-Host "[v] SDK: $ArkuiXSdk"
  $jar = Join-Path $ArkuiXSdk 'engine\lib\arkui\arkui_android_adapter.jar'
  $so = Join-Path $ArkuiXSdk 'engine\lib\arkui\android-arm64-release\libarkui_android.so'
  $sys = Join-Path $ArkuiXSdk 'engine\systemres'
  Write-Host ("{0} adapter jar : {1}" -f $(if (Test-Path $jar) { '[v]' } else { '[x]' }), $jar)
  Write-Host ("{0} engine so   : {1}" -f $(if (Test-Path $so) { '[v]' } else { '[x]' }), $so)
  Write-Host ("{0} systemres   : {1}" -f $(if (Test-Path $sys) { '[v]' } else { '[x]' }), $sys)
  exit 0
}

$sdkDir = 'D:\TOOL\Android\Sdk'
$bt = Join-Path $sdkDir 'build-tools\35.0.0'
$androidJar = Join-Path $sdkDir 'platforms\android-35\android.jar'
$javac = 'D:\TOOL\jdk\bin\javac.exe'
$keytool = 'D:\TOOL\jdk\bin\keytool.exe'
$adb = 'D:\Android\Sdk\platform-tools\adb.exe'

New-Item -ItemType Directory -Force -Path $build, $dist | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $root 'res\drawable') | Out-Null
$iconSrc = Join-Path $musicRoot 'entry\src\main\resources\base\media\startIcon.png'
if (-not (Test-Path (Join-Path $root 'res\drawable\ic_launcher.png')) -and (Test-Path $iconSrc)) {
  Copy-Item $iconSrc (Join-Path $root 'res\drawable\ic_launcher.png') -Force
}

# ---------------------------------------------------------------- 运行时装配
$libs = Join-Path $root 'libs'
$jni = Join-Path $root 'jniLibs\arm64-v8a'
$assetsDir = Join-Path $root 'assets'
$assetsArk = Join-Path $assetsDir 'arkui-x'
Remove-Item $libs, (Join-Path $root 'jniLibs') -Recurse -Force -ErrorAction SilentlyContinue
$srcDirs = @((Join-Path $root 'src'))
$withRuntime = $false

if ($ArkuiXSdk -ne '') {
  $engine = Join-Path $ArkuiXSdk 'engine'
  $adapterJar = Join-Path $engine 'lib\arkui\arkui_android_adapter.jar'
  if (-not (Test-Path $adapterJar)) { throw "SDK 里没有 arkui_android_adapter.jar：$ArkuiXSdk" }
  $withRuntime = $true
  New-Item -ItemType Directory -Force -Path $libs, $jni, $assetsArk | Out-Null

  # 1) 适配层 + 插件 jar
  Copy-Item $adapterJar $libs -Force
  Get-ChildItem (Join-Path $ArkuiXSdk 'plugins') -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue |
    ForEach-Object { Copy-Item $_.FullName (Join-Path $libs ('p_' + $_.Name)) -Force }

  # 2) 原生库（arm64-release）
  Get-ChildItem $engine -Recurse -Filter '*.so' -ErrorAction SilentlyContinue |
    Where-Object { $_.DirectoryName -match 'arm64-release$' } |
    ForEach-Object { Copy-Item $_.FullName $jni -Force }
  Get-ChildItem (Join-Path $ArkuiXSdk 'plugins') -Recurse -Filter '*.so' -ErrorAction SilentlyContinue |
    Where-Object { $_.DirectoryName -match 'arm64-release$' } |
    ForEach-Object { Copy-Item $_.FullName $jni -Force }

  # 3) 框架资源
  Copy-Item (Join-Path $engine 'systemres') $assetsArk -Recurse -Force

  # 4) 把示例工程 dist 下首个 HAP 解到 assets/arkui-x/<module>（可选基线，缺失则跳过）
  $hapFile = Get-ChildItem (Join-Path $musicRoot 'dist') -Filter *.hap -ErrorAction SilentlyContinue |
    Select-Object -First 1
  if ($hapFile) {
    $hap = $hapFile.FullName
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $z = [System.IO.Compression.ZipFile]::OpenRead($hap)
    $mj = $z.Entries | Where-Object { $_.FullName -eq 'module.json' }
    $moduleName = 'entry'
    if ($mj) {
      $sr = New-Object System.IO.StreamReader($mj.Open())
      $json = $sr.ReadToEnd() | ConvertFrom-Json
      $sr.Close()
      if ($json.module.name) { $moduleName = $json.module.name }
    }
    $target = Join-Path $assetsArk $moduleName
    Remove-Item $target -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $target | Out-Null
    foreach ($e in $z.Entries) {
      if ($e.FullName.EndsWith('/')) { continue }
      $out = Join-Path $target $e.FullName
      New-Item -ItemType Directory -Force -Path (Split-Path $out -Parent) | Out-Null
      [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $out, $true)
    }
    $z.Dispose()
    Write-Host "[i] 内置基线 HAP 已解包到 assets/arkui-x/$moduleName"
  } else {
    Write-Host "[i] 示例工程 dist 下未发现 .hap，跳过内置基线包装配（仅解包/查看不受影响）"
  }

  $srcDirs += (Join-Path $root 'host-runtime')
  Write-Host "[i] 接入 ArkUI-X 运行时：$ArkuiXSdk"
}

# ------------------------------------------------------------------ 构建流程
Remove-Item (Join-Path $build '*') -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path (Join-Path $build 'classes'), (Join-Path $build 'gen'), (Join-Path $build 'dex') | Out-Null

# manifest（带运行时才挂 HapApp）
$mf = Get-Content (Join-Path $root 'AndroidManifest.xml') -Raw
if ($withRuntime) {
  $mf = $mf.Replace('<application', '<application android:name=".HapApp"')
  if ($mf -notmatch 'extractNativeLibs') {
    $mf = $mf.Replace('android:debuggable="true"', 'android:debuggable="true" android:extractNativeLibs="true"')
  }
}
$mfPath = Join-Path $build 'AndroidManifest.xml'
Set-Content $mfPath $mf -Encoding utf8

Write-Host '== 1/6 aapt2 compile =='
& (Join-Path $bt 'aapt2.exe') compile --dir (Join-Path $root 'res') -o (Join-Path $build 'res.zip') 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

Write-Host '== 2/6 aapt2 link =='
$linkArgs = @('-o', (Join-Path $build 'base.apk'), '-I', $androidJar,
  '--manifest', $mfPath, '-R', (Join-Path $build 'res.zip'),
  '--java', (Join-Path $build 'gen'), '--auto-add-overlay')

& (Join-Path $bt 'aapt2.exe') link @linkArgs 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Host '== 3/6 javac =='
$sources = @()
foreach ($d in $srcDirs) {
  if (Test-Path $d) { $sources += @(Get-ChildItem $d -Recurse -Filter *.java | ForEach-Object { $_.FullName }) }
}
$sources += @(Get-ChildItem (Join-Path $build 'gen') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$cp = @($androidJar)
$jars = @()
if (Test-Path $libs) {
  $jars = @(Get-ChildItem $libs -Filter *.jar | ForEach-Object { $_.FullName })
  $cp += $jars
}
& $javac -encoding UTF-8 --release 8 -nowarn -cp ($cp -join ';') -d (Join-Path $build 'classes') @sources 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Write-Host '== 4/6 d8 =='
$classes = @(Get-ChildItem (Join-Path $build 'classes') -Recurse -Filter *.class | ForEach-Object { $_.FullName })
$d8Args = @('--release', '--min-api', '26', '--lib', $androidJar, '--output', (Join-Path $build 'dex'))
& (Join-Path $bt 'd8.bat') @d8Args @classes @jars 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Write-Host '== 5/6 打包 dex + 原生库 + zipalign =='
Copy-Item (Join-Path $build 'base.apk') (Join-Path $build 'app-unsigned.apk') -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zipPath = Join-Path $build 'app-unsigned.apk'
$zip = [System.IO.Compression.ZipFile]::Open($zipPath, 'Update')
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $build 'dex\classes.dex'), 'classes.dex', 'Optimal') | Out-Null
if (Test-Path $assetsDir) {
  $baseLen = (Resolve-Path $assetsDir).Path.Length + 1
  Get-ChildItem $assetsDir -Recurse -File | ForEach-Object {
    $rel = $_.FullName.Substring($baseLen).Replace('\', '/')
    $level = if ($rel -match '\.(abc|index)$') { 'NoCompression' } else { 'Optimal' }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, ('assets/' + $rel), $level) | Out-Null
  }
}
if (Test-Path $jni) {
  foreach ($so in Get-ChildItem $jni -Filter *.so) {
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $so.FullName, "lib/arm64-v8a/$($so.Name)", 'Optimal') | Out-Null
  }
}
$zip.Dispose()

$aligned = Join-Path $build 'app-aligned.apk'
& (Join-Path $bt 'zipalign.exe') -f 4 $zipPath $aligned 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

Write-Host '== 6/6 签名 =='
New-Item -ItemType Directory -Force -Path (Join-Path $root 'keys') | Out-Null
$ks = Join-Path $root 'keys\debug.keystore'
if (-not (Test-Path $ks)) {
  & $keytool -genkeypair -keystore $ks -storepass android -keypass android -alias androiddebugkey `
    -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000 > $null 2>&1
}
$tag = if ($withRuntime) { 'arkui-x' } else { 'lite' }
$signed = Join-Path $dist ("HapRunner-1.1.0-{0}.apk" -f $tag)
& (Join-Path $bt 'apksigner.bat') sign --ks $ks --ks-pass pass:android --key-pass pass:android `
  --min-sdk-version 26 --out $signed $aligned
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }
Write-Host ("[OK] APK: {0} ({1:N0} bytes)" -f $signed, (Get-Item $signed).Length)

if ($Install) {
  & $adb install -r $signed
  if ($LASTEXITCODE -ne 0) { throw 'adb install failed' }
  Write-Host '[OK] installed'
}
if ($Launch) {
  & $adb shell am force-stop com.haprunner
  & $adb shell am start -n com.haprunner/.MainActivity | Out-Null
}



