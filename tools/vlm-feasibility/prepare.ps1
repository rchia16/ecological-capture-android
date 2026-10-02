param([switch]$SkipDownloads)
$ErrorActionPreference = 'Stop'
$artifacts = "$PSScriptRoot/artifacts"
New-Item -ItemType Directory -Force $artifacts | Out-Null
$revision = '1cd86afb9a95c410a6038ab3b40d8b578c892266'
$files = @(
    @{ Name = 'Qwen3VL-4B-Instruct-Q4_K_M.gguf'; Algorithm = 'SHA256'; Hash = '66358cb18bb6b3b1b6675aa412c7a88ef01d228f481184d13668e5201c730a0a'; Url = "https://huggingface.co/Qwen/Qwen3-VL-4B-Instruct-GGUF/resolve/$revision/Qwen3VL-4B-Instruct-Q4_K_M.gguf" },
    @{ Name = 'mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf'; Algorithm = 'SHA256'; Hash = '30ba2c7dd3127a4561b6cba9d13d0f711c91bdb38742e2f56d73c8cb596bd06d'; Url = "https://huggingface.co/Qwen/Qwen3-VL-4B-Instruct-GGUF/resolve/$revision/mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf" },
    @{ Name = 'android-ndk-r28c-windows.zip'; Algorithm = 'SHA1'; Hash = '086bba43ff2f5eb0e387b15c8278bb4e0d89ba1d'; Url = 'https://dl.google.com/android/repository/android-ndk-r28c-windows.zip' }
)
foreach ($file in $files) {
    $path = Join-Path $artifacts $file.Name
    $valid = (Test-Path $path) -and ((Get-FileHash $path -Algorithm $file.Algorithm).Hash -eq $file.Hash)
    if (!$valid -and !$SkipDownloads) {
        & curl.exe --fail --location --retry 3 --connect-timeout 30 --speed-time 120 --speed-limit 1024 --continue-at - --output $path $file.Url
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $($file.Name)" }
    }
    if (!(Test-Path $path) -or (Get-FileHash $path -Algorithm $file.Algorithm).Hash -ne $file.Hash) {
        throw "File verification failed: $($file.Name)"
    }
    Write-Output "Verified $($file.Name)"
}
if (!(Test-Path "$artifacts/android-ndk-r28c/source.properties")) {
    Expand-Archive -LiteralPath "$artifacts/android-ndk-r28c-windows.zip" -DestinationPath $artifacts
}
$source = "$artifacts/llama.cpp"
$candidate = '0c1e57098bba43ac29e6e3b677cdceebdd22334f'
if (!(Test-Path "$source/.git")) {
    & git init $source
    if ($LASTEXITCODE -ne 0) { throw 'Source initialization failed.' }
    & git -C $source remote add origin https://github.com/ggml-org/llama.cpp.git
    if ($LASTEXITCODE -ne 0) { throw 'Source remote setup failed.' }
    & git -C $source fetch --depth 1 origin $candidate
    if ($LASTEXITCODE -ne 0) { throw 'Source fetch failed.' }
}
& git -c "safe.directory=$($source.Replace('\', '/'))" -C $source checkout --detach $candidate
if ($LASTEXITCODE -ne 0) { throw 'Runtime checkout failed.' }
