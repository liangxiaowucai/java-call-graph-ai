# javacg2-platform 一键启动脚本（Windows / PowerShell）
# 启动顺序: 环境检查 -> Qdrant -> 核心jar构建 -> Spring Boot后端 -> Vite前端
#
# 用法：在 platform 目录下执行  .\start.ps1
# 若提示脚本被禁止运行，先执行：Set-ExecutionPolicy -Scope CurrentUser RemoteSigned

$ErrorActionPreference = 'Stop'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RootDir   = Split-Path -Parent $ScriptDir
$PidFile   = Join-Path $ScriptDir '.pids'
$LogDir    = Join-Path $ScriptDir 'data\logs'
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

# ── 环境检查 ───────────────────────────────────────────────────────
# 检查 JDK 17+ / Node 18+（必需）与 Maven / Docker（可选），缺失时询问安装。
# 通过后 check-env.ps1 会设置 $env:JAVA_HOME（指向检测到的 JDK 17+）。
# 如需指定固定 JDK，可在此前 $env:JAVA17_HOME = 'C:\path\to\jdk-17'
# -AsModule：让 check-env.ps1 只检查、不 exit（否则其 exit 会连带终止本脚本）
. (Join-Path $ScriptDir 'check-env.ps1') -AsModule
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    Write-Host "❌ 环境检查未通过，启动中止"
    exit 1
}

# 动态生成 gradle.properties（使用检测到的 JDK 路径，Gradle 要求正斜杠）
$javaHomeFwd = $env:JAVA_HOME -replace '\\','/'
"org.gradle.java.home=$javaHomeFwd" | Set-Content -Encoding UTF8 (Join-Path $ScriptDir 'gradle.properties')

Write-Host "=========================================="
Write-Host "  javacg2-platform 启动"
Write-Host "  JAVA_HOME: $env:JAVA_HOME"
Write-Host "  Java版本: $((& "$env:JAVA_HOME\bin\java.exe" -version 2>&1 | Select-Object -First 1))"
Write-Host "=========================================="

# ── Step 0: 启动 Qdrant（向量数据库）──────────────────────
Write-Host ""
Write-Host "[0/4] 启动 Qdrant (向量数据库)..."
Set-Location $ScriptDir

$composeCmd = $null
if (Get-Command docker -ErrorAction SilentlyContinue) {
    docker compose version *>$null 2>&1
    if ($LASTEXITCODE -eq 0) {
        $composeCmd = 'compose'
    } elseif (Get-Command docker-compose -ErrorAction SilentlyContinue) {
        $composeCmd = 'docker-compose'
    }
}
if (-not $composeCmd) {
    Write-Host "⚠️  未检测到 docker / docker compose，跳过 Qdrant（问答将使用关键词降级模式）"
} else {
    if ($composeCmd -eq 'compose') { docker compose up -d qdrant } else { docker-compose up -d qdrant }
    Write-Host "等待 Qdrant 就绪..."
    $qdrantOk = $false
    for ($i = 0; $i -lt 30; $i++) {
        try {
            Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:6333/healthz' -TimeoutSec 2 *>$null
            $qdrantOk = $true; break
        } catch { Start-Sleep -Seconds 2 }
    }
    if ($qdrantOk) { Write-Host "✅ Qdrant 就绪 (http://localhost:6333)" }
    else { Write-Host "⚠️  Qdrant 启动超时，问答将使用关键词降级模式" }
}

# ── Step 1: 构建核心 jar ──────────────────────────────────
Write-Host ""
Write-Host "[1/4] 构建 java-callgraph2 核心 jar..."
Set-Location $RootDir
& (Join-Path $RootDir 'gradlew.bat') jar -x test --quiet
if ($LASTEXITCODE -ne 0) { Write-Host "❌ 核心 jar 构建失败"; exit 1 }
Write-Host "✅ 核心 jar 构建完成"

# ── Step 2: 启动后端 ──────────────────────────────────────
Write-Host ""
Write-Host "[2/4] 启动后端 (http://localhost:8080)..."
Set-Location $ScriptDir
$backendLog = Join-Path $LogDir 'backend.log'
$backend = Start-Process -FilePath (Join-Path $ScriptDir 'gradlew.bat') `
    -ArgumentList 'bootRun' -WorkingDirectory $ScriptDir -PassThru -NoNewWindow `
    -RedirectStandardOutput $backendLog -RedirectStandardError (Join-Path $LogDir 'backend.err.log')
$BackendPid = $backend.Id
Write-Host "后端 PID: $BackendPid"
Write-Host "等待后端启动（最多 60s）..."
for ($i = 0; $i -lt 30; $i++) {
    try {
        Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:8080/api/repos' -TimeoutSec 2 *>$null
        Write-Host "✅ 后端启动成功"; break
    } catch {
        if ($i -eq 29) { Write-Host "⚠️  后端启动超时，请查看日志: data\logs\backend.log" }
        Start-Sleep -Seconds 2
    }
}

# ── Step 3: 启动前端 ──────────────────────────────────────
Write-Host ""
Write-Host "[3/4] 启动前端 (http://localhost:5173)..."
$frontendDir = Join-Path $ScriptDir 'frontend'
$npmCmd = (Get-Command npm.cmd -ErrorAction SilentlyContinue).Source
if (-not $npmCmd) { $npmCmd = 'npm' }
# 首次启动或依赖缺失时安装前端依赖
if (-not (Test-Path (Join-Path $frontendDir 'node_modules'))) {
    Write-Host "📦 安装前端依赖（首次启动，可能需要几分钟）..."
    & $npmCmd install --prefix $frontendDir
}
$frontend = Start-Process -FilePath $npmCmd `
    -ArgumentList 'run','dev' -WorkingDirectory $frontendDir -PassThru -NoNewWindow `
    -RedirectStandardOutput (Join-Path $LogDir 'frontend.log') `
    -RedirectStandardError (Join-Path $LogDir 'frontend.err.log')
$FrontendPid = $frontend.Id
Write-Host "前端 PID: $FrontendPid"

# 保存 PID 供 stop.ps1 使用
"BACKEND_PID=$BackendPid"   | Set-Content -Encoding UTF8 $PidFile
"FRONTEND_PID=$FrontendPid" | Add-Content -Encoding UTF8 $PidFile

Write-Host ""
Write-Host "=========================================="
Write-Host "  启动完成！"
Write-Host "  前端:   http://localhost:5173"
Write-Host "  后端:   http://localhost:8080"
Write-Host "  Qdrant: http://localhost:6333"
Write-Host ""
Write-Host "  按 Ctrl+C 停止所有服务（等同 .\stop.ps1）"
Write-Host "  后端日志: Get-Content -Wait data\logs\backend.log"
Write-Host "  前端日志: Get-Content -Wait data\logs\frontend.log"
Write-Host "=========================================="

# 保持前台运行；用户 Ctrl+C 时进入 finally，自动调用 stop.ps1 清理，不残留后台进程。
try {
    while ($true) { Start-Sleep -Seconds 1 }
} finally {
    Write-Host ""
    Write-Host "🛑 收到停止信号，正在清理服务..."
    & (Join-Path $ScriptDir 'stop.ps1')
}
