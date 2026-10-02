param(
    [string]$NdkPath = "$PSScriptRoot/artifacts/android-ndk-r28c",
    [string]$CMake = 'cmake',
    [string]$Ninja = 'ninja',
    [int]$Jobs = 8
)
$ErrorActionPreference = 'Stop'
$source = "$PSScriptRoot/artifacts/llama.cpp"
$build = "$PSScriptRoot/artifacts/build-android"
$candidate = '0c1e57098bba43ac29e6e3b677cdceebdd22334f'
$commit = & git -c "safe.directory=$($source.Replace('\', '/'))" -C $source rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $commit -ne $candidate) { throw 'Unexpected runtime source commit.' }
if (!(Test-Path "$NdkPath/build/cmake/android.toolchain.cmake")) { throw 'NDK r28c is missing.' }
$previousGitCount = $env:GIT_CONFIG_COUNT
$gitIndex = if ($previousGitCount) { [int]$previousGitCount } else { 0 }
try {
    # Pass a trust exception only to this build and its child Git processes.
    [Environment]::SetEnvironmentVariable("GIT_CONFIG_KEY_$gitIndex", 'safe.directory', 'Process')
    [Environment]::SetEnvironmentVariable("GIT_CONFIG_VALUE_$gitIndex", $source.Replace('\', '/'), 'Process')
    $env:GIT_CONFIG_COUNT = [string]($gitIndex + 1)
    & $CMake -S $source -B $build -G Ninja "-DCMAKE_MAKE_PROGRAM=$Ninja" `
        "-DCMAKE_TOOLCHAIN_FILE=$NdkPath/build/cmake/android.toolchain.cmake" `
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 -DANDROID_STL=c++_static `
        -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF `
        -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF -DGGML_CCACHE=OFF `
        -DGGML_VULKAN=OFF -DGGML_CUDA=OFF -DGGML_METAL=OFF `
        -DLLAMA_OPENSSL=OFF -DLLAMA_SUBPROCESS=OFF `
        -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF `
        -DLLAMA_BUILD_APP=OFF -DLLAMA_BUILD_TOOLS=ON -DMTMD_VIDEO=OFF
    if ($LASTEXITCODE -ne 0) { throw 'CMake configuration failed.' }
    & $CMake --build $build --target llama-mtmd-cli --parallel $Jobs
    if ($LASTEXITCODE -ne 0) { throw 'Native build failed.' }
} finally {
    $env:GIT_CONFIG_COUNT = $previousGitCount
    [Environment]::SetEnvironmentVariable("GIT_CONFIG_KEY_$gitIndex", $null, 'Process')
    [Environment]::SetEnvironmentVariable("GIT_CONFIG_VALUE_$gitIndex", $null, 'Process')
}
