[CmdletBinding()]
param(
    [int]$AlgorithmPort = 8000,
    [int]$JavaPort = 8080,
    [int]$WebPort = 5173,
    [switch]$BuildJava,
    [switch]$NoBrowser
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$AlgorithmRoot = Join-Path $Root 'algorithm-server'
$JavaRoot = Join-Path $Root 'ruoyi-java'
$VueRoot = Join-Path $Root 'ruoyi-vue3'
$RuntimeRoot = Join-Path $Root '.local-runtime'
$LogRoot = Join-Path $RuntimeRoot 'logs'
$ProfileRoot = Join-Path $RuntimeRoot 'uploadPath'
$ModelRoot = Join-Path $RuntimeRoot 'models'

New-Item -ItemType Directory -Force -Path $LogRoot, $ProfileRoot, $ModelRoot | Out-Null

function Get-Executable([string]$Name) {
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -eq $command) { return $null }
    return $command.Source
}

function Require-Executable([string]$Name, [string]$Hint) {
    $path = Get-Executable $Name
    if ($null -eq $path) {
        throw "找不到 $Name。$Hint"
    }
    return $path
}

function Test-LocalPort([int]$Port) {
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $async = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        $connected = $async.AsyncWaitHandle.WaitOne(300)
        if ($connected -and $client.Connected) { $client.EndConnect($async); $client.Close(); return $true }
        $client.Close()
    } catch { }
    return $false
}

function Start-LoggedProcess(
    [string]$Name,
    [string]$FilePath,
    [string[]]$ArgumentList,
    [string]$WorkingDirectory,
    [hashtable]$Environment
) {
    $stdout = Join-Path $LogRoot "$Name.out.log"
    $stderr = Join-Path $LogRoot "$Name.err.log"
    $oldValues = @{}
    foreach ($entry in $Environment.GetEnumerator()) {
        $oldValues[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
        Set-Item -Path "Env:$($entry.Key)" -Value ([string]$entry.Value)
    }
    try {
        $process = Start-Process -FilePath $FilePath -ArgumentList $ArgumentList -WorkingDirectory $WorkingDirectory `
            -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru -WindowStyle Hidden
    } finally {
        foreach ($entry in $Environment.GetEnumerator()) {
            if ($null -eq $oldValues[$entry.Key]) { Remove-Item -Path "Env:$($entry.Key)" -ErrorAction SilentlyContinue }
            else { Set-Item -Path "Env:$($entry.Key)" -Value $oldValues[$entry.Key] }
        }
    }
    Set-Content -Path (Join-Path $RuntimeRoot "$Name.pid") -Value $process.Id -Encoding ascii
    Write-Host ("{0,-10} PID {1,-7} logs: {2}" -f $Name, $process.Id, $stdout)
    return $process
}

if ((Test-LocalPort $AlgorithmPort) -or (Test-LocalPort $JavaPort) -or (Test-LocalPort $WebPort)) {
    throw "目标端口已被占用。请先运行 deploy\\stop-windows-local.bat，或用 -AlgorithmPort/-JavaPort/-WebPort 修改端口。"
}

$python = Get-Executable 'python'
$pythonPrefix = @()
if ($null -eq $python) {
    $python = Require-Executable 'py' '请安装 Python 3.11+，或将 python/py 加入 PATH。'
    $pythonPrefix = @('-3')
}
$npm = Get-Executable 'npm.cmd'
if ($null -eq $npm) { $npm = Require-Executable 'npm' '请安装 Node.js 18+，并将 npm 加入 PATH。' }
$java = Require-Executable 'java' '请安装 JDK 17+，并将 java 加入 PATH。'

$venvPython = Join-Path $AlgorithmRoot '.venv/Scripts/python.exe'
if (Test-Path $venvPython) { $python = (Resolve-Path $venvPython).Path; $pythonPrefix = @() }

$callbackToken = if ($env:DRILLING_CALLBACK_TOKEN) { $env:DRILLING_CALLBACK_TOKEN } else { 'local-development-callback-token' }
$uploadPath = (Resolve-Path $ProfileRoot).Path
$redisUrl = if ($env:DRILLING_REDIS_URL) { $env:DRILLING_REDIS_URL } else { '' }
$minioEndpoint = if ($env:MINIO_ENDPOINT) { $env:MINIO_ENDPOINT } else { '' }
$minioAccessKey = if ($env:MINIO_ACCESS_KEY) { $env:MINIO_ACCESS_KEY } else { '' }
$minioSecretKey = if ($env:MINIO_SECRET_KEY) { $env:MINIO_SECRET_KEY } else { '' }
$algorithmEnvironment = @{
    PYTHONPATH = $AlgorithmRoot
    TRAIN_DATA_ROOT = $uploadPath
    TRAIN_TEMPLATE_ROOT = $uploadPath
    MODEL_ROOT = (Resolve-Path $ModelRoot).Path
    PREDICTION_STATE_FILE = (Join-Path $RuntimeRoot 'prediction-tasks.json')
    TRAINING_STATE_FILE = (Join-Path $RuntimeRoot 'training-tasks.json')
    TASK_CALLBACK_URL = "http://127.0.0.1:$JavaPort/api/train/task/callback"
    TASK_CALLBACK_TOKEN = $callbackToken
    REDIS_URL = $redisUrl
    MINIO_ENDPOINT = $minioEndpoint
    MINIO_ACCESS_KEY = $minioAccessKey
    MINIO_SECRET_KEY = $minioSecretKey
}

$pythonArgs = $pythonPrefix + @('-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1', '--port', "$AlgorithmPort")
Start-LoggedProcess 'algorithm' $python $pythonArgs $AlgorithmRoot $algorithmEnvironment | Out-Null

$jar = Join-Path $JavaRoot 'ruoyi-admin/target/ruoyi-admin.jar'
$mvn = Get-Executable 'mvn.cmd'
if ($null -eq $mvn) { $mvn = Get-Executable 'mvn' }
if ($BuildJava -or -not (Test-Path $jar)) {
    if ($null -eq $mvn) { throw "找不到 ruoyi-admin/target/ruoyi-admin.jar，且找不到 Maven。请先构建 Java 服务，或安装 Maven。" }
    Write-Host '正在构建 Spring Boot 服务...'
    Push-Location $JavaRoot
    try { & $mvn '-pl' 'ruoyi-admin' '-am' '-DskipTests' 'package' } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $jar)) { throw 'Java 构建失败，详见 Maven 输出。' }
}

$dbUrl = if ($env:DRILLING_DB_URL) { $env:DRILLING_DB_URL } else { 'jdbc:mysql://127.0.0.1:3306/ry?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=false&serverTimezone=GMT%2B8' }
$dbUser = if ($env:DRILLING_DB_USER) { $env:DRILLING_DB_USER } else { 'root' }
$dbPassword = if ($env:DRILLING_DB_PASSWORD) { $env:DRILLING_DB_PASSWORD } else { 'password' }
$javaEnvironment = @{
    SERVER_PORT = "$JavaPort"
    DRILLING_ALGORITHM_URL = "http://127.0.0.1:$AlgorithmPort"
    DRILLING_CALLBACK_TOKEN = $callbackToken
    RUOYI_PROFILE = $uploadPath
    SPRING_DATASOURCE_DRUID_MASTER_URL = $dbUrl
    SPRING_DATASOURCE_DRUID_MASTER_USERNAME = $dbUser
    SPRING_DATASOURCE_DRUID_MASTER_PASSWORD = $dbPassword
}
Start-LoggedProcess 'java' $java @('-jar', (Resolve-Path $jar).Path) $JavaRoot $javaEnvironment | Out-Null

$vueArgs = @('run', 'dev', '--', '--host', '127.0.0.1', '--port', "$WebPort")
Start-LoggedProcess 'vue' $npm $vueArgs $VueRoot @{} | Out-Null

Write-Host ''
Write-Host "算法服务: http://127.0.0.1:$AlgorithmPort/health"
Write-Host "Java 服务: http://127.0.0.1:$JavaPort"
Write-Host "前端页面: http://127.0.0.1:$WebPort"
Write-Host "日志目录: $LogRoot"
Write-Host '停止服务: deploy\\stop-windows-local.bat'
if (-not $NoBrowser) { Start-Process "http://127.0.0.1:$WebPort" }
