param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Adb = 'D:/Ray/Android/sdk/platform-tools/adb.exe'
)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^[A-Za-z0-9._:-]+$') { throw 'Invalid device serial.' }
$package = 'com.rchia.ecocapture.phase0'
$staged = '/data/local/tmp/ecocapture-smolvlm2'
$destination = 'files/models/smolvlm2'
$manifest = Get-Content -Raw "$PSScriptRoot/smolvlm2/model-manifest.json" | ConvertFrom-Json
$localModels = "$PSScriptRoot/vlm-feasibility/artifacts/smolvlm2"
function Invoke-Adb {
    & $Adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw 'ADB operation failed; app data were not reset.' }
}
# Validate every local file before stopping the app or copying anything to the phone.
foreach ($file in $manifest.files) {
    if ($file.filename -notmatch '^[A-Za-z0-9._-]+\.gguf$') { throw 'Invalid model filename.' }
    $path = Join-Path $localModels $file.filename
    if (!(Test-Path -LiteralPath $path) -or (Get-Item -LiteralPath $path).Length -ne $file.bytes -or
        (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $file.sha256) { throw 'Run tools/smolvlm2/prepare.py first.' }
}
Invoke-Adb shell am force-stop $package
Invoke-Adb shell mkdir -p $staged
Invoke-Adb shell run-as $package mkdir -p $destination
foreach ($file in $manifest.files) {
    $name = $file.filename
    $existing = Invoke-Adb shell "run-as $package sh -c 'if [ -f $destination/$name ]; then sha256sum $destination/$name; fi'"
    if (($existing -split '\s+')[0] -eq $file.sha256) { Write-Output "Already provisioned: $name"; continue }
    Invoke-Adb push (Join-Path $localModels $name) "$staged/$name"
    $stagedHash = Invoke-Adb shell sha256sum "$staged/$name"
    if (($stagedHash -split '\s+')[0] -ne $file.sha256) { throw 'Staged model hash mismatch.' }
    Invoke-Adb shell "cat $staged/$name | run-as $package sh -c 'cat > $destination/$name.provisioning'"
    $copiedHash = Invoke-Adb shell run-as $package sha256sum "$destination/$name.provisioning"
    if (($copiedHash -split '\s+')[0] -ne $file.sha256) { throw 'App model hash mismatch.' }
    Invoke-Adb shell run-as $package mv "$destination/$name.provisioning" "$destination/$name"
    Write-Output "Provisioned and verified: $name"
}
# Preserve all Qwen models, recordings, database rows and annotations.
