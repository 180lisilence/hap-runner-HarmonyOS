# HAP static safety scan: structure classification + metadata + suspicious entries/strings
# Usage: powershell -File scan.ps1 [-SampleDir <dir>] [-ReportCsv <path>]
param(
    [string]$SampleDir = (Join-Path $PSScriptRoot 'samples'),
    [string]$ReportCsv = (Join-Path $PSScriptRoot 'scan-report.csv')
)
Add-Type -AssemblyName System.IO.Compression.FileSystem

$SUSP = @(
    'Runtime.exec','ProcessBuilder','/system/bin','/bin/sh','/bin/bash','su -','chmod 777',
    'DexClassLoader','PathClassLoader','loadClass','dlopen','System.load','System.loadLibrary',
    'wget','curl','downloadFile','writeFile','/sdcard/','/data/local',
    'getDeviceId','getImei','getMacAddress','readPhoneState','locationManager','getLocation',
    'sendSms','readContacts','readCallLog','RECORD_AUDIO','CAMERA','getAccounts'
)

function Read-EntryText($zip, $name) {
    $e = $zip.Entries | Where-Object { $_.FullName -eq $name } | Select-Object -First 1
    if (-not $e) { return $null }
    $sr = New-Object System.IO.StreamReader($e.Open(), [System.Text.Encoding]::UTF8)
    $t = $sr.ReadToEnd(); $sr.Close()
    return $t
}

function Scan-BytesForSusp($bytes) {
    $hit = @{}
    $ascii = [System.Text.Encoding]::GetEncoding('ISO-8859-1').GetString($bytes)
    foreach ($k in $SUSP) {
        if ($ascii.Contains($k)) { $hit[$k] = $true }
    }
    return $hit.Keys
}

function Resolve-Compat($r) {
    $allowed = @('arm64-v8a', 'armeabi-v7a', 'armeabi', 'x86_64')
    if ($r.model -eq 'fa') { return 'FA_UNSUPPORTED' }
    if ([int]$r.abc -eq 0) { return 'NO_ABC' }
    if ($r.dep) { return 'HSP_UNSUPPORTED' }
    if ($r.abi) {
        $pkgAbis = $r.abi -split '\|'
        if (-not ($pkgAbis | Where-Object { $allowed -contains $_ })) { return 'ABI_MISSING' }
    }
    return 'OK'
}

$rows = @()
Get-ChildItem $SampleDir -Filter *.hap | Sort-Object Name | ForEach-Object {
    $f = $_.FullName
    $row = [ordered]@{
        file=$_.Name; sizeKB=[math]::Round($_.Length/1KB,1); model='?'; bundle='-'; module='-'; ability='-'
        mtype='-'; abi=''; sig='unknown'; dep=''; minApi=0; targetApi=0; abc=0; so=0; js=0; scripts=0; rawfile=0; permissions=''; entries=0
        susp=''; risk=''; note=''; compat='-'
    }
    try {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($f)
        $entries = $zip.Entries
        $row.entries = $entries.Count
        $moduleJson = $entries | Where-Object { $_.FullName -eq 'module.json' }
        $configJson = $entries | Where-Object { $_.FullName -eq 'config.json' }
        $abc = $entries | Where-Object { $_.FullName -match 'modules\.abc$' }
        $so  = $entries | Where-Object { $_.FullName -match '\.so$' }
        $js  = $entries | Where-Object { $_.FullName -match '(^|/)(js|ets)/.*\.(js|cjs|ts)$' }
        $scripts = $entries | Where-Object { $_.FullName -match '\.(sh|cjs|dex|bin|bat)$' }
        $raw = $entries | Where-Object { $_.FullName -match 'rawfile/' }
        $row.abc=$abc.Count; $row.so=$so.Count; $row.js=$js.Count; $row.scripts=$scripts.Count; $row.rawfile=$raw.Count

        $abis = @{}
        foreach ($s in $so) {
            if ($s.FullName -match '(?:^|/)libs/([^/]+)/') { $abis[$Matches[1]] = $true }
        }
        $row.abi = ($abis.Keys | Sort-Object) -join '|'
        $sigEntry = $entries | Where-Object { $_.FullName -match '\.(p7b|sig|rsa|dsa|ec|cert|pem|x509)$' }
        if ($sigEntry) { $row.sig = 'signed' } else { $row.sig = 'unsigned' }

        $json = $null
        if ($moduleJson) { $row.model='stage'; $json=Read-EntryText $zip 'module.json' }
        elseif ($configJson) { $row.model='fa'; $json=Read-EntryText $zip 'config.json' }
        else { $row.note='no module.json/config.json' }

        if ($json) {
            try {
                $o = $json | ConvertFrom-Json
                $app = $o.app
                if ($app) {
                    $row.bundle = $app.bundleName
                    $row.minApi = [int]$app.minAPIVersion
                    $row.targetApi = [int]$app.targetAPIVersion
                }
                $mod = $o.module
                if ($mod) {
                    $row.module = $mod.name
                    if ($mod.type) { $row.mtype = $mod.type } else { $row.mtype = '-' }
                    $ab = $mod.abilities
                    if ($ab) { $row.ability = ($ab | ForEach-Object { $_.name }) -join '|' }
                    $dep = $mod.dependencies
                    if ($dep) { $row.dep = ($dep | ForEach-Object { if ($_ -is [string]) { $_ } else { $_.name } }) -join '|' }
                    $pm = $mod.requestPermissions
                    if (-not $pm) { $pm = $mod.reqPermissions }
                    if ($pm) { $row.permissions = ($pm | ForEach-Object { if ($_ -is [string]) { $_ } else { $_.name } }) -join '|' }
                }
                if ($o.abilities) { $row.ability = ($o.abilities | ForEach-Object { $_.name }) -join '|' }
            } catch { $row.note = 'json parse: ' + $_.Exception.Message }
        }

        $hitSet = @{}
        foreach ($e in $entries) {
            if ($e.Length -gt 20MB) { continue }
            $ext = [System.IO.Path]::GetExtension($e.FullName)
            if ($ext -in '.abc','.js','.cjs','.json','.ets','.so','.dex') {
                $s = $e.Open(); $ms = New-Object System.IO.MemoryStream; $s.CopyTo($ms); $s.Close()
                $hits = Scan-BytesForSusp $ms.ToArray()
                foreach ($h in $hits) { $hitSet[$h] = $true }
                $ms.Dispose()
            }
        }
        if ($scripts.Count -gt 0) { $hitSet['SCRIPT_ENTRY'] = $true }
        if ($so.Count -gt 0) { $hitSet['NATIVE_SO'] = $true }
        $row.susp = ($hitSet.Keys | Sort-Object) -join '|'

        $hard = $hitSet.Keys | Where-Object { $_ -in 'Runtime.exec','ProcessBuilder','/system/bin','/bin/sh','/bin/bash','DexClassLoader','su -' }
        if ($hard) { $row.risk='HIGH-review' }
        elseif ($hitSet.Count -gt 0) { $row.risk='MED-apis' }
        else { $row.risk='LOW' }

        $zip.Dispose()
    } catch {
        $row.risk='READ_FAIL'; $row.note=$_.Exception.Message
    }
    $obj = [pscustomobject][ordered]@{
        file=$row.file; sizeKB=$row.sizeKB; model=$row.model; bundle=$row.bundle; module=$row.module; ability=$row.ability
        mtype=$row.mtype; abi=$row.abi; sig=$row.sig; dep=$row.dep; minApi=$row.minApi; targetApi=$row.targetApi
        abc=$row.abc; so=$row.so; js=$row.js; scripts=$row.scripts; rawfile=$row.rawfile; permissions=$row.permissions
        entries=$row.entries; susp=$row.susp; risk=$row.risk; note=$row.note; compat='-'
    }
    $rows += $obj
}

# 按与 HapPackage.computeVerdict 同序的规则回填预期判定码（安全拒绝在解包阶段先行，此处为兼容/功能判定）
foreach ($r in $rows) {
    $r.compat = Resolve-Compat $r
}

$rows | Export-Csv $ReportCsv -Encoding UTF8 -NoTypeInformation
$rows | ForEach-Object {
    "{0,-22} [{1,-5}] {2,-26} abc={3} so={4} js={5} risk={6}  {7}" -f $_.file,$_.model,$_.bundle,$_.abc,$_.so,$_.js,$_.risk,$_.susp
}
"Report: $ReportCsv"
