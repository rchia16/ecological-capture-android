param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true)][string[]]$DeviceRecordings,
    [string]$Adb = 'D:/Ray/Android/sdk/platform-tools/adb.exe'
)
$ErrorActionPreference = 'Stop'
if ($DeviceRecordings.Count -lt 3 -or ($DeviceRecordings | Select-Object -Unique).Count -ne $DeviceRecordings.Count) { throw 'Provide at least three distinct finalized glasses recordings.' }
foreach ($name in $DeviceRecordings) {
    if ($name -notmatch '^clip_[A-Za-z0-9_]+\.mp4$') { throw 'Only recording filenames are accepted.' }
}
$project = Split-Path $PSScriptRoot -Parent
$package = 'com.rchia.ecocapture.phase0'
$results = Join-Path $project 'tools/vlm-feasibility/artifacts/results/checkpoint5'
New-Item -ItemType Directory -Force $results | Out-Null
function Invoke-Adb {
    & $Adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw "ADB failed ($LASTEXITCODE): $args" }
}
Invoke-Adb install -r "$project/app/build/outputs/apk/debug/app-debug.apk"
Invoke-Adb install -r "$project/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
$names = $DeviceRecordings -join ','
$output = Invoke-Adb shell am instrument -w -e checkpoint5RecordingNames $names -e class com.rchia.ecocapture.phase0.vlm.Checkpoint5InspectionTest "$package.test/androidx.test.runner.AndroidJUnitRunner"
$output | Tee-Object -FilePath "$results/instrumentation.txt" | Write-Output
if (($output -join "`n") -notmatch 'OK \(1 test\)') { throw 'Frame inspection test failed.' }
# Keep binary bytes on-device until adb pull: PowerShell text redirection corrupts MP4/JPEG bytes.
Invoke-Adb shell "run-as $package cat cache/checkpoint5-inspection/manifest.json > /data/local/tmp/checkpoint5-manifest.json"
Invoke-Adb pull /data/local/tmp/checkpoint5-manifest.json "$results/manifest.json"
$manifest = Get-Content -Raw "$results/manifest.json" | ConvertFrom-Json
$sections = @()
foreach ($clip in $manifest.clips) {
    $figures = @()
    for ($index = 0; $index -lt $clip.images.Count; $index++) {
        $image = [string]$clip.images[$index]
        if ($image -notmatch '^clip\d+-frame\d+\.jpg$') { throw 'Invalid image filename in manifest.' }
        Invoke-Adb shell "run-as $package cat cache/checkpoint5-inspection/$image > /data/local/tmp/checkpoint5-frame.jpg"
        Invoke-Adb pull /data/local/tmp/checkpoint5-frame.jpg "$results/$image"
        $frame = $clip.sampling.frames[$index]
        $figures += "<figure><img src='$image' alt='Sample $($index + 1), requested $($frame.requestedTimestampMs) milliseconds'><figcaption>Frame $($index + 1) &middot; $($frame.requestedTimestampMs) ms &middot; $($frame.inferenceWidth) &times; $($frame.inferenceHeight)</figcaption></figure>"
    }
    $sections += "<section><h2>$($clip.sourceFilename)</h2><p>Duration $($clip.sampling.durationMs) ms. Requested times shown; actual decoder times are unavailable.</p><div class='frames'>$($figures -join "`n")</div></section>"
}
$html = @"
<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Checkpoint 5 frame inspection</title>
<style>body{font:18px system-ui;background:#151515;color:#fff;margin:24px}a{color:#9cf}.frames{display:flex;flex-wrap:wrap;gap:16px}figure{margin:0;width:240px}img{width:100%;height:auto}figcaption{padding:8px 0}section{margin:32px 0}</style>
<h1>Checkpoint 5: saved-video samples</h1><p>Engineering output. Check timeline coverage, upright orientation, aspect ratio, and absence of crop or stretch. Repeated-looking frames may occur in short or stationary clips.</p>
$($sections -join "`n")</html>
"@
$html | Set-Content -Encoding utf8 "$results/index.html"
Write-Output "Inspection gallery: $results/index.html"
