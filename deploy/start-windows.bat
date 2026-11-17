@echo off
setlocal
cd /d "%~dp0.."

where docker >nul 2>nul
if errorlevel 1 (
  echo Docker Desktop is required. Install it or use the Python/Java local scripts.
  exit /b 1
)

if not exist deploy\.env (
  echo Missing deploy\.env. Copy deploy\.env.example to deploy\.env and set strong secrets first.
  exit /b 1
)

echo Starting drilling platform with CPU fallback when no NVIDIA runtime is available...
docker compose --env-file deploy\.env -f deploy\docker-compose.yml up --build %*
endlocal
