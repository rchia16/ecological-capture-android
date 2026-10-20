$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    go install golang.org/x/mobile/cmd/gobind
    if ($LASTEXITCODE) { throw 'gobind installation failed' }
    $env:PATH = (Join-Path (go env GOPATH) 'bin') + ';' + $env:PATH
    go test ./...
    if ($LASTEXITCODE) { throw 'age tests failed' }
    New-Item -ItemType Directory -Force ../../app/libs | Out-Null
    go tool gomobile init
    if ($LASTEXITCODE) { throw 'gomobile init failed' }
    go tool gomobile bind '-target=android/arm64' '-androidapi=31' '-javapkg=org.ecocapture.crypto' -o ../../app/libs/ecocrypto.aar .
    if ($LASTEXITCODE) { throw 'gomobile bind failed' }
} finally { Pop-Location }
