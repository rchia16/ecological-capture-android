param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Adb = 'D:/Ray/Android/sdk/platform-tools/adb.exe'
)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$package = 'com.rchia.ecocapture.phase0'
function Invoke-Adb {
    & $Adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw "ADB failed ($LASTEXITCODE): $args" }
}
# Use replacement installs, then run instrumentation directly. Do not use Gradle's
# connected-test installer, which is configured to uninstall the app after tests.
Invoke-Adb install -r "$project/app/build/outputs/apk/debug/app-debug.apk"
Invoke-Adb install -r "$project/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
$output = Invoke-Adb shell am instrument -w "$package.test/androidx.test.runner.AndroidJUnitRunner"
$output | Write-Output
if (($output -join "`n") -notmatch 'OK \(\d+ tests?\)') { throw 'Instrumentation did not report success.' }
$installed = Invoke-Adb shell pm path $package
if (!$installed) { throw 'Target package missing after instrumentation.' }
Write-Output 'Instrumentation passed; target app remains installed.'
