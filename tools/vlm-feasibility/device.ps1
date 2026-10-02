param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Adb = 'D:/Ray/Android/sdk/platform-tools/adb.exe',
    [ValidateSet(1, 3, 5)][int]$Images = 1,
    [switch]$Provision
)
$ErrorActionPreference = 'Stop'
$artifacts = "$PSScriptRoot/artifacts"
$remote = '/data/local/tmp/ecocapture-qwen3vl-spike'
function Invoke-Adb {
    & $Adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw "ADB failed ($LASTEXITCODE): $args" }
}
if ($Provision) {
    $expected = @{
        'Qwen3VL-4B-Instruct-Q4_K_M.gguf' = '66358cb18bb6b3b1b6675aa412c7a88ef01d228f481184d13668e5201c730a0a'
        'mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf' = '30ba2c7dd3127a4561b6cba9d13d0f711c91bdb38742e2f56d73c8cb596bd06d'
    }
    Invoke-Adb shell "mkdir -p $remote"
    foreach ($name in $expected.Keys) {
        if ((Get-FileHash "$artifacts/$name" -Algorithm SHA256).Hash -ne $expected[$name]) {
            throw "Host model hash mismatch: $name"
        }
        Invoke-Adb push "$artifacts/$name" "$remote/"
        $deviceHash = Invoke-Adb shell "sha256sum $remote/$name"
        if (($deviceHash -split '\s+')[0] -ne $expected[$name]) { throw "Device hash mismatch: $name" }
    }
    Invoke-Adb push "$artifacts/build-android/bin/llama-mtmd-cli" "$remote/"
    Invoke-Adb push "$artifacts/llama.cpp/tools/mtmd/test-1.jpeg" "$remote/"
}
# Normalize shell line endings without changing the checked-in script.
$shellScript = [IO.File]::ReadAllText("$PSScriptRoot/run-device.sh").Replace("`r`n", "`n")
[IO.File]::WriteAllText("$artifacts/run-device.sh", $shellScript, [Text.UTF8Encoding]::new($false))
Invoke-Adb push "$artifacts/run-device.sh" "$remote/"
Invoke-Adb shell "chmod 700 $remote/llama-mtmd-cli $remote/run-device.sh"
$running = Invoke-Adb shell 'pidof llama-mtmd-cli || true'
if ($running) { throw 'A feasibility inference is already active.' }
$runId = "run-$Images-v2-$([Guid]::NewGuid().ToString('N'))"
$results = "$artifacts/results/v2/$runId/$Images-images"
New-Item -ItemType Directory -Force $results | Out-Null
& python "$PSScriptRoot/export-prompts.py" --output-directory $results --images $Images --run-id $runId
if ($LASTEXITCODE -ne 0) { throw 'Prompt export failed.' }
foreach ($file in @('system-prompt.txt', 'user-prompt.txt', 'request.json')) {
    Invoke-Adb push "$results/$file" "$remote/"
}
try {
    Invoke-Adb shell "sh $remote/run-device.sh $Images $runId"
} finally {
    foreach ($suffix in @('summary.txt', 'output.txt', 'runtime.txt', 'rss.txt', 'thermal-before.txt', 'thermal-after.txt', 'mem-before.txt', 'mem-after.txt')) {
        & $Adb -s $Serial pull "$remote/$runId-$suffix" "$results/"
    }
}
