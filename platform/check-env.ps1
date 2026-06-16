# javacg2-platform 环境检查脚本（Windows / PowerShell）
#
# 作用：检查启动平台本身所需的运行环境，缺失必需项时询问并用 winget / choco 安装。
# 用法：
#   独立运行：    .\check-env.ps1
#   被 start.ps1 引用： . .\check-env.ps1   （点号 dot-source，检查通过后 $env:JAVA_HOME 可用）
#
# 检查项（仅平台自身依赖，被分析仓库需要的 JDK 由平台运行时按 pom 版本自动探测）：
#   [必需] JDK 17+      平台运行（Gradle / Spring Boot）
#   [必需] Node.js 18+  前端构建
#   [可选] Maven        分析 Maven 仓库时调用
#   [可选] Docker       仅用于 Qdrant 向量库（缺失则语义搜索降级为关键词匹配）
#
# 被 start.ps1 引用时传 -AsModule，则只执行检查、绝不 exit（避免连带杀掉父脚本）。

param([switch]$AsModule)

$ErrorActionPreference = 'Continue'

# ── 包管理器探测 ──────────────────────────────────────────────────
$script:PM = ''           # winget | choco | ''
if (Get-Command winget -ErrorAction SilentlyContinue) {
    $script:PM = 'winget'
} elseif (Get-Command choco -ErrorAction SilentlyContinue) {
    $script:PM = 'choco'
}

# 各依赖在不同包管理器下的包 ID
$script:PkgIds = @{
    winget = @{ jdk17 = 'EclipseAdoptium.Temurin.17.JDK'; node = 'OpenJS.NodeJS.LTS'; maven = 'Apache.Maven'; docker = 'Docker.DockerDesktop' }
    choco  = @{ jdk17 = 'temurin17';                       node = 'nodejs-lts';        maven = 'maven';        docker = 'docker-desktop' }
}

$script:DownloadUrls = @{
    jdk17  = 'https://adoptium.net/temurin/releases/?version=17'
    node   = 'https://nodejs.org/en/download'
    maven  = 'https://maven.apache.org/download.cgi'
    docker = 'https://www.docker.com/products/docker-desktop/'
}

function Invoke-PkgInstall {
    param([string]$Id)
    if ($script:PM -eq 'winget') {
        winget install --id $Id -e --accept-source-agreements --accept-package-agreements
        return ($LASTEXITCODE -eq 0)
    } elseif ($script:PM -eq 'choco') {
        choco install $Id -y
        return ($LASTEXITCODE -eq 0)
    }
    return $false
}

# 记住用户拒绝安装的可选依赖，下次不再询问。存于 data\.skip-install（已被 .gitignore 排除）。
$script:SkipFile = Join-Path $PSScriptRoot 'data\.skip-install'

function Test-Skipped {
    param([string]$Dep)
    (Test-Path $script:SkipFile) -and (Select-String -Path $script:SkipFile -Pattern "^$([regex]::Escape($Dep))$" -Quiet)
}

function Add-Skip {
    param([string]$Dep)
    $dir = Split-Path -Parent $script:SkipFile
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    if (-not (Test-Skipped $Dep)) { Add-Content -Path $script:SkipFile -Value $Dep -Encoding UTF8 }
}

# 询问并安装，返回 $true=已安装/成功
# $Optional=$true 时：用户拒绝会被记住，后续启动不再询问该依赖。
function Confirm-Install {
    param([string]$Dep, [string]$Name, [switch]$Optional)
    $url = $script:DownloadUrls[$Dep]

    # 可选依赖且此前已拒绝 → 静默跳过
    if ($Optional -and (Test-Skipped $Dep)) {
        Write-Host "   （已按你的选择跳过 $Name 安装；如需重新启用：删除 data\.skip-install）"
        return $false
    }

    if (-not $script:PM) {
        Write-Host "   未检测到 winget / choco，无法自动安装。请手动安装 $Name："
        Write-Host "   下载: $url"
        return $false
    }
    $pkg = $script:PkgIds[$script:PM][$Dep]
    $ans = Read-Host "   是否用 $($script:PM) 安装 $Name？[$pkg]  (y/N)"
    if ($ans -match '^(y|yes)$') {
        Write-Host "   正在安装 $Name ..."
        if (Invoke-PkgInstall -Id $pkg) {
            Write-Host "   ✅ $Name 安装完成（可能需重开终端使 PATH 生效）"
            return $true
        } else {
            Write-Host "   ❌ $Name 安装失败，请手动安装: $url"
            return $false
        }
    } else {
        if ($Optional) {
            Add-Skip $Dep
            Write-Host "   已跳过，下次不再询问（如需安装请删除 data\.skip-install，或手动安装: $url）"
        } else {
            Write-Host "   已跳过。手动安装: $url"
        }
        return $false
    }
}

# ── 取某 java.exe 所在的 JAVA_HOME 与主版本 ──────────────────────
function Get-JavaMajor {
    param([string]$JavaExe)
    if (-not (Test-Path $JavaExe)) { return $null }
    # 合并所有输出行：某些环境会先打印 "Picked up JAVA_TOOL_OPTIONS:" 等噪声行，
    # 只取第一行会漏掉真正的 version 行，故整体匹配。
    $out = (& $JavaExe -version 2>&1 | Out-String)
    if ($out -match 'version "([0-9]+)(\.([0-9]+))?') {
        $maj = [int]$Matches[1]
        if ($maj -eq 1 -and $Matches[3]) { return [int]$Matches[3] }  # 1.8 → 8
        return $maj
    }
    return $null
}

function Find-Jdk17 {
    # 1) 已显式配置 JAVA17_HOME
    if ($env:JAVA17_HOME -and (Test-Path "$env:JAVA17_HOME\bin\java.exe")) {
        $m = Get-JavaMajor "$env:JAVA17_HOME\bin\java.exe"
        if ($m -ge 17) { return $env:JAVA17_HOME }
    }
    # 2) JAVA_HOME 恰好 >= 17
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
        $m = Get-JavaMajor "$env:JAVA_HOME\bin\java.exe"
        if ($m -ge 17) { return $env:JAVA_HOME }
    }
    # 3) 常见安装目录
    $bases = @(
        "$env:ProgramFiles\Eclipse Adoptium",
        "$env:ProgramFiles\Java",
        "$env:ProgramFiles\Microsoft\jdk*",
        "$env:ProgramFiles\Zulu",
        "${env:ProgramFiles(x86)}\Java"
    )
    foreach ($base in $bases) {
        foreach ($dir in (Get-ChildItem -Path $base -Directory -ErrorAction SilentlyContinue)) {
            $exe = Join-Path $dir.FullName 'bin\java.exe'
            if (Test-Path $exe) {
                $m = Get-JavaMajor $exe
                if ($m -ge 17) { return $dir.FullName }
            }
        }
    }
    # 4) PATH 中的 java
    $j = Get-Command java -ErrorAction SilentlyContinue
    if ($j) {
        $m = Get-JavaMajor $j.Source
        if ($m -ge 17) { return (Split-Path (Split-Path $j.Source -Parent) -Parent) }
    }
    return $null
}

function Test-Jdk17 {
    $home17 = Find-Jdk17
    if ($home17) {
        $env:JAVA_HOME = $home17
        Write-Host "✅ JDK 17+  : $home17"
        return $true
    }
    Write-Host "❌ JDK 17+  : 未检测到（平台运行必需）"
    if (Confirm-Install -Dep 'jdk17' -Name 'JDK 17') {
        $home17 = Find-Jdk17
        if ($home17) { $env:JAVA_HOME = $home17; Write-Host "✅ JDK 17+  : $home17"; return $true }
        Write-Host "   ⚠️ 安装后仍未找到，请重开终端或手动设置 JAVA17_HOME"
    }
    return $false
}

function Test-NodeJs {
    $n = Get-Command node -ErrorAction SilentlyContinue
    if ($n) {
        $ver = (& node -v) -replace '^v',''
        $maj = [int]($ver -split '\.')[0]
        if ($maj -ge 18) { Write-Host "✅ Node.js  : v$ver"; return $true }
        Write-Host "❌ Node.js  : v$ver（需要 18+）"
    } else {
        Write-Host "❌ Node.js  : 未检测到（前端构建必需）"
    }
    if (Confirm-Install -Dep 'node' -Name 'Node.js 18+') {
        $n = Get-Command node -ErrorAction SilentlyContinue
        if ($n) {
            $maj = [int](((& node -v) -replace '^v','') -split '\.')[0]
            if ($maj -ge 18) { Write-Host "✅ Node.js  : $(& node -v)"; return $true }
        }
    }
    return $false
}

function Test-Maven {
    if (Get-Command mvn -ErrorAction SilentlyContinue) {
        Write-Host "✅ Maven    : 已安装"
        return
    }
    Write-Host "⚠️ Maven    : 未检测到（可选；分析 Maven 仓库时需要，Gradle 项目/上传 jar 不受影响）"
    [void](Confirm-Install -Dep 'maven' -Name 'Maven' -Optional)
}

function Test-Docker {
    if (Get-Command docker -ErrorAction SilentlyContinue) {
        Write-Host "✅ Docker   : 已安装"
        return
    }
    Write-Host "⚠️ Docker   : 未检测到（可选；仅用于 Qdrant 向量库，缺失则语义搜索降级为关键词匹配）"
    [void](Confirm-Install -Dep 'docker' -Name 'Docker Desktop' -Optional)
}

function Invoke-CheckEnv {
    Write-Host "=========================================="
    Write-Host "  环境检查 (OS: Windows, 包管理器: $(if ($script:PM) { $script:PM } else { '无' }))"
    Write-Host "=========================================="

    $requiredOk = $true
    Write-Host ""
    Write-Host "[必需项]"
    if (-not (Test-Jdk17))  { $requiredOk = $false }
    if (-not (Test-NodeJs)) { $requiredOk = $false }

    Write-Host ""
    Write-Host "[可选项]"
    Test-Maven
    Test-Docker

    Write-Host ""
    if (-not $requiredOk) {
        Write-Host "❌ 必需环境缺失，无法启动。请按上面提示安装后重试。"
        Write-Host "=========================================="
        return $false
    }
    Write-Host "✅ 必需环境就绪"
    Write-Host "=========================================="
    return $true
}

# 执行检查。被 dot-source 引用（-AsModule）时绝不 exit，把结果留给父脚本通过 $env:JAVA_HOME 判断；
# 直接运行时以检查结果作为退出码。
$global:CheckEnvOk = Invoke-CheckEnv
if (-not $AsModule) {
    if ($global:CheckEnvOk) { exit 0 } else { exit 1 }
}
