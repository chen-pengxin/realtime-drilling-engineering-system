Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
  throw 'Docker Desktop is required. Install it or use the Python/Java local scripts.'
}
if (-not (Test-Path (Join-Path $PSScriptRoot '.env'))) {
  throw 'Missing deploy/.env. Copy deploy/.env.example to deploy/.env and set strong secrets first.'
}

Write-Host 'Starting drilling platform with CPU fallback when no NVIDIA runtime is available...'
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up --build @args
