[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'SilentlyContinue'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$RuntimeRoot = Join-Path $Root '.local-runtime'

foreach ($name in @('vue', 'java', 'algorithm')) {
    $pidFile = Join-Path $RuntimeRoot "$name.pid"
    if (-not (Test-Path $pidFile)) { continue }
    $raw = (Get-Content $pidFile -Raw).Trim()
    $processId = 0
    if ([int]::TryParse($raw, [ref]$processId) -and $processId -gt 0) {
        $process = Get-Process -Id $processId
        if ($null -ne $process) {
            Write-Host "停止 $name (PID $processId)..."
            & taskkill.exe /PID $processId /T /F | Out-Null
        }
    }
    Remove-Item $pidFile -Force
}
Write-Host '本地服务已停止。'
