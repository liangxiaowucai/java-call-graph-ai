# javacg2-platform 一键停止脚本（Windows / PowerShell）
# 停止顺序: 前端 -> 后端 -> Qdrant
#
# 用法：在 platform 目录下执行  .\stop.ps1

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$PidFile   = Join-Path $ScriptDir '.pids'

Write-Host "=========================================="
Write-Host "  javacg2-platform 停止"
Write-Host "=========================================="

# 从 .pids 读取保存的 PID
$pids = @{}
if (Test-Path $PidFile) {
    foreach ($line in Get-Content $PidFile) {
        if ($line -match '^(\w+)=(\d+)$') { $pids[$Matches[1]] = [int]$Matches[2] }
    }
}

function Stop-ByPid {
    param([int]$ProcId, [string]$Label)
    if ($ProcId -and (Get-Process -Id $ProcId -ErrorAction SilentlyContinue)) {
        Stop-Process -Id $ProcId -Force -ErrorAction SilentlyContinue
        Write-Host "✅ $Label 已停止 (PID: $ProcId)"
    }
}

# 兜底：杀掉占用指定端口的进程
function Stop-ByPort {
    param([int]$Port)
    try {
        $conns = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
        foreach ($c in $conns) {
            Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue
        }
    } catch {
        # 旧系统无 Get-NetTCPConnection，退回 netstat
        $lines = netstat -ano | Select-String ":$Port\s.*LISTENING"
        foreach ($l in $lines) {
            $procId = ($l -split '\s+')[-1]
            if ($procId -match '^\d+$') { Stop-Process -Id ([int]$procId) -Force -ErrorAction SilentlyContinue }
        }
    }
}

# ── Step 1: 停止前端 ──────────────────────────────────────
Write-Host ""
Write-Host "[1/3] 停止前端..."
Stop-ByPid -ProcId $pids['FRONTEND_PID'] -Label '前端'
Stop-ByPort -Port 5173

# ── Step 2: 停止后端 ──────────────────────────────────────
Write-Host "[2/3] 停止后端..."
Stop-ByPid -ProcId $pids['BACKEND_PID'] -Label '后端'
Stop-ByPort -Port 8080

# ── Step 3: 停止 Qdrant ───────────────────────────────────
Write-Host "[3/3] 停止 Qdrant..."
Set-Location $ScriptDir
if (Get-Command docker -ErrorAction SilentlyContinue) {
    docker compose version *>$null 2>&1
    if ($LASTEXITCODE -eq 0) {
        docker compose stop qdrant *>$null 2>&1
        if ($LASTEXITCODE -eq 0) { Write-Host "✅ Qdrant 已停止（数据已保留）" }
    } elseif (Get-Command docker-compose -ErrorAction SilentlyContinue) {
        docker-compose stop qdrant *>$null 2>&1
        if ($LASTEXITCODE -eq 0) { Write-Host "✅ Qdrant 已停止（数据已保留）" }
    }
}

# 清理 PID 文件
Remove-Item -Force $PidFile -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "=========================================="
Write-Host "  所有服务已停止"
Write-Host "  Qdrant 数据保留在 data\qdrant\"
Write-Host "  如需彻底清除向量数据: docker compose down -v"
Write-Host "=========================================="
