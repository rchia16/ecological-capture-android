param([Parameter(Mandatory = $true)][string]$Serial,
    [string]$Adb = 'D:/Ray/Android/sdk/platform-tools/adb.exe',
    [switch]$SkipFailureFixtures,
    [string[]]$SystemScenarios = @('background', 'force-stop', 'process-kill', 'notification-cancel', 'critical-memory'))
$ErrorActionPreference = 'Stop'
$package = 'com.rchia.ecocapture.phase0'
$runner = "$package.test/androidx.test.runner.AndroidJUnitRunner"
$testClass = 'com.rchia.ecocapture.phase0.vlm.Checkpoint8SystemRecoveryTest'
$results = Join-Path $PSScriptRoot 'vlm-feasibility/artifacts/results/checkpoint8'
New-Item -ItemType Directory -Force $results | Out-Null
function Invoke-Adb {
    & $Adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $args" }
}
function Stage($token, $action, $scenario = 'success', $delayMs = 300, $waitRunning = 'false', $expectSuccess = 'false') {
    $output = Invoke-Adb shell am instrument -w -e class $testClass -e checkpoint8Action $action -e testToken $token -e scenario $scenario -e delayMs $delayMs -e waitRunning $waitRunning -e expectSuccess $expectSuccess $runner
    $output | Set-Content -Encoding utf8 "$results/$token-$action.txt"
    if (($output -join "`n") -notmatch 'OK \(1 test\)') { throw "Recovery stage failed: $token/$action" }
}
function Relaunch { Invoke-Adb shell am start -n "$package/.MainActivity" | Out-Null }
function Start-HeldSeed($token) {
    $seedArguments = @(
        '-s', $Serial, 'shell', 'am', 'instrument', '-w', '-e', 'class', $testClass,
        '-e', 'checkpoint8Action', 'seed', '-e', 'testToken', $token,
        '-e', 'scenario', 'success', '-e', 'delayMs', '12000', '-e', 'waitRunning', 'true',
        '-e', 'holdSeed', 'true', $runner
    )
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $Adb
    $startInfo.Arguments = $seedArguments -join ' '
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $script:seedProcess = [System.Diagnostics.Process]::Start($startInfo)
    $script:seedOutput = $script:seedProcess.StandardOutput.ReadToEndAsync()
    $script:seedError = $script:seedProcess.StandardError.ReadToEndAsync()
    $deadline = (Get-Date).AddSeconds(40)
    while ((Get-Date) -lt $deadline) {
        & $Adb -s $Serial shell run-as $package test -f "files/checkpoint8-$token.running"
        if ($LASTEXITCODE -eq 0) { return }
        if ($script:seedProcess.HasExited) { throw 'Held seed exited before generation started' }
        Start-Sleep -Milliseconds 250
    }
    throw 'Held seed did not start generation'
}
function Wait-Done($token) {
    $deadline = (Get-Date).AddSeconds(180)
    while ((Get-Date) -lt $deadline) {
        & $Adb -s $Serial shell run-as $package test -f "files/checkpoint8-$token.done.json"
        if ($LASTEXITCODE -eq 0) { return }
        Start-Sleep -Seconds 3
    }
    throw "Timed out waiting for disposable recovery work: $token"
}
function Finish-HeldSeed($token) {
    if (!$script:seedProcess.WaitForExit(30000)) { throw 'Held seed did not finish after its worker' }
    $script:seedOutput.Result | Set-Content -Encoding utf8 "$results/$token-seed.txt"
    $script:seedError.Result | Set-Content -Encoding utf8 "$results/$token-seed-error.txt"
}
$summary = @()
if ($SkipFailureFixtures -and (Test-Path "$results/system-summary.json")) {
    $previousSummary = Get-Content -Raw "$results/system-summary.json" | ConvertFrom-Json
    $summary = @($previousSummary | Where-Object { $_.scenario -notin $SystemScenarios })
}
$failureScenarios = if ($SkipFailureFixtures) { @() } else { @('missing', 'hash', 'frames', 'native', 'oom', 'success') }
foreach ($scenario in $failureScenarios) {
    $token = [guid]::NewGuid().ToString()
    Write-Output "Recovery fixture: $scenario"
    Stage $token seed $scenario
    if ($scenario -eq 'success') { Invoke-Adb shell am force-stop $package; Relaunch }
    Stage $token verify $scenario 300 false $(if ($scenario -eq 'success') { 'true' } else { 'false' })
    $summary += [pscustomobject]@{ scenario = $scenario; token = $token; result = 'PASS' }
    Stage $token cleanup
}
foreach ($scenario in $SystemScenarios) {
    $token = [guid]::NewGuid().ToString()
    Write-Output "System recovery fixture: $scenario"
    Start-HeldSeed $token
    switch ($scenario) {
        'background' { Invoke-Adb shell input keyevent KEYCODE_HOME }
        'force-stop' {
            Invoke-Adb shell am force-stop $package
            & $Adb -s $Serial shell run-as $package test -f "files/checkpoint8-$token.done.json"
            if ($LASTEXITCODE -eq 0) { throw 'Force-stopped partial work was incorrectly completed' }
            Relaunch
        }
        'process-kill' {
            $targetProcess = (Invoke-Adb shell pidof $package).Trim()
            if ($targetProcess -notmatch '^\d+$') { throw 'Expected exactly one target app PID' }
            Invoke-Adb shell run-as $package kill -9 $targetProcess
            Relaunch
        }
        'notification-cancel' { Invoke-Adb shell run-as $package touch "files/checkpoint8-$token.request-cancel" }
        'critical-memory' { Invoke-Adb shell am send-trim-memory $package RUNNING_CRITICAL }
    }
    if ($scenario -eq 'notification-cancel') {
        Finish-HeldSeed $token
        Stage $token verify success 300 false false
    } else {
        Wait-Done $token
        Finish-HeldSeed $token
        Invoke-Adb shell "run-as $package cat files/checkpoint8-$token.done.json > /data/local/tmp/checkpoint8-report"
        Invoke-Adb pull /data/local/tmp/checkpoint8-report "$results/$token-result.json" | Out-Null
        $report = Get-Content -Raw "$results/$token-result.json" | ConvertFrom-Json
        if ($scenario -in @('force-stop', 'process-kill', 'critical-memory') -and $report.invocations -lt 2) { throw 'Interrupted inference did not restart' }
        Stage $token verify success 300 false true
    }
    $summary += [pscustomobject]@{ scenario = $scenario; token = $token; result = 'PASS' }
    $summary | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 "$results/system-summary.json"
    Stage $token cleanup
}
$summary | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 "$results/system-summary.json"
Write-Output 'Disposable system recovery checks passed; participant records and models were not used as fixtures.'
