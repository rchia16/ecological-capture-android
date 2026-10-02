param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true)][string]$DeviceRecording,
    [ValidateSet(1, 3, 5)][int]$FrameCount = 1,
    [ValidateRange(1, 2048)][int]$MaxLongEdge = 1024,
    [string]$Adb = 'D:/Ray/Android/sdk/platform-tools/adb.exe',
    [switch]$Install
)
$ErrorActionPreference = 'Stop'
if ($DeviceRecording -notmatch '^clip_[A-Za-z0-9_]+\.mp4$') { throw 'Provide a finalized recording filename.' }
$project = Split-Path $PSScriptRoot -Parent
$package = 'com.rchia.ecocapture.phase0'
$stem = "$($DeviceRecording.Replace('.mp4', ''))-${FrameCount}frames-${MaxLongEdge}px"
$results = Join-Path $project 'tools/vlm-feasibility/artifacts/results/checkpoint6'
New-Item -ItemType Directory -Force $results | Out-Null
function Invoke-Adb {
    & $Adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw "ADB failed ($LASTEXITCODE): $args" }
}
if ($Install) {
    Invoke-Adb install -r "$project/app/build/outputs/apk/debug/app-debug.apk"
    Invoke-Adb install -r "$project/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
}
Invoke-Adb shell dumpsys thermalservice | Set-Content -Encoding utf8 "$results/$stem-thermal-before.txt"
Write-Output "Starting real inference: $DeviceRecording, $FrameCount frames, long edge $MaxLongEdge"
$output = Invoke-Adb shell am instrument -w -e checkpoint6Recording $DeviceRecording -e frameCount $FrameCount -e maxLongEdge $MaxLongEdge -e class com.rchia.ecocapture.phase0.vlm.Checkpoint6InferenceTest "$package.test/androidx.test.runner.AndroidJUnitRunner"
$output | Tee-Object -FilePath "$results/$stem-instrumentation.txt" | Write-Output
Invoke-Adb shell dumpsys thermalservice | Set-Content -Encoding utf8 "$results/$stem-thermal-after.txt"
foreach ($extension in @('json', 'txt')) {
    $name = if ($extension -eq 'json') { "$stem.json" } else { "$stem-output.txt" }
    # Binary-safe export retains exact UTF-8 output, including uncertainty and non-ASCII characters.
    Invoke-Adb shell "run-as $package sh -c 'if [ -f cache/checkpoint6-comparison/$name ]; then cat cache/checkpoint6-comparison/$name; fi' > /data/local/tmp/checkpoint6-export"
    Invoke-Adb pull /data/local/tmp/checkpoint6-export "$results/$name"
}
if (($output -join "`n") -notmatch 'OK \(1 test\)') { throw "Inference test failed; inspect $results/$stem.json" }
Write-Output "Comparison report: $results/$stem.json"
