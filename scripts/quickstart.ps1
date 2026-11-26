#requires -Version 5.1
<#
.SYNOPSIS
Town Demon-Subduing Tower gateway quick smoke test.

.DESCRIPTION
Run from anywhere:
    powershell -ExecutionPolicy Bypass -File scripts/quickstart.ps1

Steps: build core -> build gateway -> start server -> 4 checks -> stop server.
Checks:
  1) GET  /api/health              -> status=up
  2) POST /api/evaluate missing arg -> INTENT_FORMAT_INVALID
  3) POST /api/evaluate R3 tool     -> R3_DENIED
  4) python contract/verify_codes.py (skipped when python missing)

Requires: JDK, Maven, Python3 (optional). Exit code 0=all passed, 1=failure.
#>

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$token = 'quickstart-token'
$port = 18091
$base = "http://127.0.0.1:$port"
$jar = Join-Path $root 'towndemontower-gateway/target/towndemontower-gateway-0.1.0.jar'
$proc = $null
$failures = @()

function Check([string]$name, [bool]$ok, [string]$detail) {
    if ($ok) { Write-Host "  [PASS] $name" -ForegroundColor Green }
    else {
        Write-Host "  [FAIL] $name -- $detail" -ForegroundColor Red
        $script:failures += $name
    }
}

try {
    Write-Host '== 1/4 Install core to local Maven repo =='
    & mvn -B -q -f towndemontower-core/pom.xml clean install -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "core install failed (exit $LASTEXITCODE)" }

    Write-Host '== 2/4 Package gateway =='
    & mvn -B -q -f towndemontower-gateway/pom.xml clean package
    if ($LASTEXITCODE -ne 0) { throw "gateway package failed (exit $LASTEXITCODE)" }
    if (-not (Test-Path -LiteralPath $jar)) { throw "jar not found: $jar" }

    Write-Host '== 3/4 Contract gate =='
    if (Get-Command python -ErrorAction SilentlyContinue) {
        & python contract/verify_codes.py 2>&1 | Select-Object -Last 2
        Check 'contract gate' ($LASTEXITCODE -eq 0) "verify_codes.py exit $LASTEXITCODE"
    } else {
        Write-Host '  [SKIP] python not found' -ForegroundColor Yellow
    }

    Write-Host '== 4/4 Start server and assert =='
    $env:INKARMOR_API_TOKEN = $token
    $env:PORT = "$port"
    # Quote the jar path: repo path may contain spaces (PowerShell 5.1 splits ArgumentList).
    $proc = Start-Process -FilePath 'java' -ArgumentList @('-jar', "`"$jar`"") -PassThru -WindowStyle Hidden

    $health = $null
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Milliseconds 500
        try {
            $health = Invoke-RestMethod -Uri "$base/api/health" -TimeoutSec 2
            break
        } catch { }
    }
    Check 'health status=up' ($null -ne $health -and $health.data.status -eq 'up') "health=$($health | ConvertTo-Json -Compress)"

    $headers = @{ 'X-Inkarmor-Token' = $token; 'Content-Type' = 'application/json' }

    $badBody = @'
{"tool":"deleteData","args":{"rows":"1"},"confidence":0.9,"reason":"missing table arg","expectedEffect":"none","rollbackHint":"none"}
'@
    try {
        $r1 = Invoke-RestMethod -Uri "$base/api/evaluate" -Method Post -Headers $headers -Body $badBody -TimeoutSec 5
        Check 'missing-arg intent rejected' ($r1.data.code -eq 'INTENT_FORMAT_INVALID') "code=$($r1.data.code)"
    } catch {
        Check 'missing-arg intent rejected' $false "request failed: $($_.Exception.Message)"
    }

    $r3Body = @'
{"tool":"deleteData","args":{"table":"test_table","rows":"1"},"confidence":0.9,"reason":"cleanup test data","expectedEffect":"delete one row","rollbackHint":"none"}
'@
    try {
        $r2 = Invoke-RestMethod -Uri "$base/api/evaluate" -Method Post -Headers $headers -Body $r3Body -TimeoutSec 5
        Check 'irreversible tool R3 denied' ($r2.data.code -eq 'R3_DENIED') "code=$($r2.data.code)"
    } catch {
        Check 'irreversible tool R3 denied' $false "request failed: $($_.Exception.Message)"
    }
}
catch {
    Write-Host "  [FAIL] exception: $($_.Exception.Message)" -ForegroundColor Red
    $failures += 'exception'
}
finally {
    if ($null -ne $proc -and -not $proc.HasExited) {
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    }
    Remove-Item Env:INKARMOR_API_TOKEN -ErrorAction SilentlyContinue
    Remove-Item Env:PORT -ErrorAction SilentlyContinue
}

if ($failures.Count -gt 0) {
    Write-Host "FAILED: $($failures -join ', ')" -ForegroundColor Red
    exit 1
}
Write-Host 'ALL CHECKS PASSED' -ForegroundColor Green
exit 0
