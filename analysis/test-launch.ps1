# Single-sample launch probe on emulator (muted). Read-only observation, no shell source change.
# Usage: .\test-launch.ps1 -HapName Clock_Demo.hap -Bundle ohos.samples.etsclock -Module entry -Ability MainAbility
param(
    [Parameter(Mandatory=$true)][string]$HapName,
    [Parameter(Mandatory=$true)][string]$Bundle,
    [Parameter(Mandatory=$true)][string]$Module,
    [Parameter(Mandatory=$true)][string]$Ability,
    [string]$Adb = 'D:\Android\Sdk\platform-tools\adb.exe',
    [string]$Serial = 'emulator-5556',
    [int]$WaitSec = 6
)
$e = @('-s', $Serial)
$pkgDir = "files/haps/$HapName"
$modDir  = "files/arkui-x/$Module"
$absDir = "/data/user/0/com.haprunner/$modDir"

& $Adb @e shell am force-stop com.haprunner
Start-Sleep -Seconds 1
& $Adb @e shell run-as com.haprunner rm -rf $modDir
& $Adb @e shell run-as com.haprunner mkdir -p $modDir
& $Adb @e shell run-as com.haprunner unzip -oq $pkgDir -d $modDir

& $Adb @e logcat -c
$inst = "$Bundle`:$Module`:$Ability`:"
& $Adb @e shell am start -n com.haprunner/.HapHostActivity `
    --es instanceName $inst --es bundleName $Bundle --es moduleName $Module `
    --es bundlePath $absDir --es hapPath "/data/user/0/com.haprunner/$pkgDir" | Out-Null
Start-Sleep -Seconds $WaitSec

$p = (& $Adb @e shell pidof com.haprunner).Trim()
$log = & $Adb @e logcat -d -v brief --pid=$p
$hitAbility = ($log | Select-String 'CreateJsAbilityInfo call').Count
$nullAbil   = ($log | Select-String 'abilityInfo is nullptr|ability is nullptr').Count
$stageNull  = ($log | Select-String 'AbilityStageObj is nullptr').Count
$fatal      = ($log | Select-String 'FATAL|StageActivity.onCreate failed|UnsatisfiedLink').Count
$readFail   = ($log | Select-String 'read file failed').Count
$pageLoad   = ($log | Select-String 'LoadPage Success|page not found').Count

$verdict = if ($fatal -gt 0) { 'CRASH' }
           elseif ($hitAbility -gt 0) { 'ABILITY-OK' }
           elseif ($nullAbil -gt 0) { 'ABILITY-NULL' }
           else { 'UNKNOWN' }

[pscustomobject]@{
    hap=$HapName; bundle=$Bundle; module=$Module; ability=$Ability; pid=$p; verdict=$verdict
    hitAbility=$hitAbility; nullAbil=$nullAbil; stageNull=$stageNull; readFail=$readFail; fatal=$fatal
}
