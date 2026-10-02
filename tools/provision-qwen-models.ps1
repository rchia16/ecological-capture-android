param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Adb = 'D:/Ray/Android/sdk/platform-tools/adb.exe'
)
$ErrorActionPreference = 'Stop'
$package = 'com.rchia.ecocapture.phase0'
$staged = '/data/local/tmp/ecocapture-qwen3vl-spike'
$destination = 'files/models/qwen3vl'
$expected = @{
    'Qwen3VL-4B-Instruct-Q4_K_M.gguf' = '66358cb18bb6b3b1b6675aa412c7a88ef01d228f481184d13668e5201c730a0a'
    'mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf' = '30ba2c7dd3127a4561b6cba9d13d0f711c91bdb38742e2f56d73c8cb596bd06d'
}
function Invoke-Adb {
    & $Adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw "ADB failed ($LASTEXITCODE): $args" }
}
# Debug/study-device preparation only. Do not clear/uninstall the app or touch videos/databases.
Invoke-Adb shell run-as $package mkdir -p $destination
foreach ($name in $expected.Keys) {
    $existing = Invoke-Adb shell "run-as $package sh -c 'if [ -f $destination/$name ]; then sha256sum $destination/$name; fi'"
    if (($existing -split '\s+')[0] -eq $expected[$name]) { Write-Output "Already provisioned: $name"; continue }
    $stagedHash = Invoke-Adb shell sha256sum "$staged/$name"
    if (($stagedHash -split '\s+')[0] -ne $expected[$name]) { throw "Staged model hash mismatch: $name" }
    # Write via a temporary name; publish only after app-owned bytes are independently verified.
    Invoke-Adb shell "cat $staged/$name | run-as $package sh -c 'cat > $destination/$name.provisioning'"
    $copiedHash = Invoke-Adb shell run-as $package sha256sum "$destination/$name.provisioning"
    if (($copiedHash -split '\s+')[0] -ne $expected[$name]) { throw "App model hash mismatch: $name" }
    Invoke-Adb shell run-as $package mv "$destination/$name.provisioning" "$destination/$name"
    Write-Output "Provisioned and verified: $name"
}
