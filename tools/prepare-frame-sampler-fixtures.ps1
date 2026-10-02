param([string]$Ffmpeg = 'ffmpeg')
$ErrorActionPreference = 'Stop'
$destination = Join-Path (Split-Path $PSScriptRoot -Parent) 'app/build/generated/checkpoint5-fixtures/checkpoint5'
New-Item -ItemType Directory -Force $destination | Out-Null
function Encode-Fixture([string]$name, [string]$size, [string]$duration) {
    & $Ffmpeg -hide_banner -loglevel error -y -f lavfi -i "testsrc2=size=${size}:rate=2" -t $duration -an -c:v libx265 -preset ultrafast -x265-params 'log-level=error:pools=1' -tag:v hvc1 -pix_fmt yuv420p -movflags +faststart "$destination/$name.mp4"
    if ($LASTEXITCODE -ne 0) { throw "Failed to create $name fixture." }
}
Encode-Fixture normal60 320x180 60
Encode-Fixture short 320x180 1
Encode-Fixture portrait 108x192 2
& $Ffmpeg -hide_banner -loglevel error -y -display_rotation 90 -i "$destination/short.mp4" -c copy "$destination/rotated.mp4"
if ($LASTEXITCODE -ne 0) { throw 'Failed to create rotated fixture.' }
Write-Output "Generated disposable HEVC test assets in $destination"
