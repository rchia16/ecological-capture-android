param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Adb = 'D:/Ray/Android/sdk/platform-tools/adb.exe',
    [string]$PlaybackFixture = "$PSScriptRoot/../clips/clip_439.mp4",
    [string]$DeviceRecording
)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$package = 'com.rchia.ecocapture.phase0'
$results = Join-Path $project 'tools/vlm-feasibility/artifacts/results/checkpoint4'
New-Item -ItemType Directory -Force $results | Out-Null
function Invoke-Adb {
    & $Adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw "ADB failed ($LASTEXITCODE): $args" }
}
if ($DeviceRecording) {
    if ($DeviceRecording -notmatch '^clip_[A-Za-z0-9_]+\.mp4$') { throw 'DeviceRecording must be a recording filename.' }
} else {
    if (!(Test-Path -LiteralPath $PlaybackFixture -PathType Leaf)) { throw 'Provide a finalized HEVC playback fixture.' }
    $header = [System.IO.File]::OpenRead((Resolve-Path -LiteralPath $PlaybackFixture).Path)
    try {
        $bytes = New-Object byte[] 8
        if ($header.Read($bytes, 0, 8) -ne 8 -or [System.Text.Encoding]::ASCII.GetString($bytes, 4, 4) -ne 'ftyp') {
            throw 'Playback fixture is not an MP4 with an ftyp header. Supply a binary-safe export or -DeviceRecording.'
        }
    } finally { $header.Dispose() }
}
Invoke-Adb install -r "$project/app/build/outputs/apk/debug/app-debug.apk"
Invoke-Adb install -r "$project/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
& "$PSScriptRoot/provision-qwen-models.ps1" -Serial $Serial -Adb $Adb
if ($DeviceRecording) {
    Invoke-Adb shell run-as $package cp "files/recordings/$DeviceRecording" cache/checkpoint4-playback.mp4
} else {
    Invoke-Adb push $PlaybackFixture /data/local/tmp/checkpoint4-playback.mp4
    Invoke-Adb shell "cat /data/local/tmp/checkpoint4-playback.mp4 | run-as $package sh -c 'cat > cache/checkpoint4-playback.mp4'"
}
$output = Invoke-Adb shell am instrument -w -e nativeCheckpoint4 true -e class com.rchia.ecocapture.phase0.vlm.Checkpoint4NativeTest "$package.test/androidx.test.runner.AndroidJUnitRunner"
$output | Tee-Object -FilePath "$results/instrumentation.txt" | Write-Output
$report = Invoke-Adb shell run-as $package cat files/checkpoint4-native-report.txt
$report | Set-Content -Encoding utf8 "$results/memory-and-timing.txt"
$report | Write-Output
if (($output -join "`n") -notmatch 'OK \(1 test\)') { throw 'Native checkpoint test did not report success.' }
if (!(Invoke-Adb shell pm path $package)) { throw 'Target app missing after native testing.' }
Write-Output 'Native checkpoint test passed; target app remains installed.'
