#!/bin/bash

# javacg2-platform 环境检查脚本（macOS / Linux 通用）
#
# 作用：检查启动平台本身所需的运行环境，缺失必需项时询问并用系统包管理器安装。
# 用法：
#   独立运行：  ./check-env.sh           # 仅检查/安装，不启动
#   被 start.sh 引用： source check-env.sh  # 检查通过后导出 JAVA_HOME 供启动使用
#
# 检查项（仅平台自身依赖，被分析仓库需要的 JDK 由平台运行时按 pom 版本自动探测）：
#   [必需] JDK 17+      平台运行（Gradle / Spring Boot）
#   [必需] Node.js 18+  前端构建
#   [可选] Maven        分析 Maven 仓库时调用（缺失仍可分析 Gradle 项目 / 上传 jar）
#   [可选] Docker       仅用于 Qdrant 向量库（缺失则语义搜索降级为关键词匹配）

# 被 source 时不要开启 set -e，避免污染调用方；用显式返回值控制。

# ── 平台与包管理器探测 ────────────────────────────────────────────
CHECK_ENV_OS=""        # mac | linux
CHECK_ENV_PM=""        # brew | apt | dnf | yum | pacman | zypper | ""
CHECK_ENV_PM_INSTALL="" # 安装命令前缀

_detect_platform() {
    case "$(uname -s)" in
        Darwin) CHECK_ENV_OS="mac" ;;
        Linux)  CHECK_ENV_OS="linux" ;;
        *)      CHECK_ENV_OS="unknown" ;;
    esac

    if [ "$CHECK_ENV_OS" = "mac" ]; then
        if command -v brew &>/dev/null; then
            CHECK_ENV_PM="brew"; CHECK_ENV_PM_INSTALL="brew install"
        fi
    elif [ "$CHECK_ENV_OS" = "linux" ]; then
        if command -v apt-get &>/dev/null; then
            CHECK_ENV_PM="apt";    CHECK_ENV_PM_INSTALL="sudo apt-get install -y"
        elif command -v dnf &>/dev/null; then
            CHECK_ENV_PM="dnf";    CHECK_ENV_PM_INSTALL="sudo dnf install -y"
        elif command -v yum &>/dev/null; then
            CHECK_ENV_PM="yum";    CHECK_ENV_PM_INSTALL="sudo yum install -y"
        elif command -v pacman &>/dev/null; then
            CHECK_ENV_PM="pacman"; CHECK_ENV_PM_INSTALL="sudo pacman -S --noconfirm"
        elif command -v zypper &>/dev/null; then
            CHECK_ENV_PM="zypper"; CHECK_ENV_PM_INSTALL="sudo zypper install -y"
        fi
    fi
}

# 取某依赖在当前包管理器下的包名：_pkg_name <jdk17|node|maven|docker>
_pkg_name() {
    local dep="$1"
    case "$CHECK_ENV_PM" in
        brew)
            case "$dep" in
                jdk17) echo "openjdk@17" ;;
                node)  echo "node" ;;
                maven) echo "maven" ;;
                docker) echo "--cask docker" ;;
            esac ;;
        apt)
            case "$dep" in
                jdk17) echo "openjdk-17-jdk" ;;
                node)  echo "nodejs npm" ;;
                maven) echo "maven" ;;
                docker) echo "docker.io" ;;
            esac ;;
        dnf|yum)
            case "$dep" in
                jdk17) echo "java-17-openjdk-devel" ;;
                node)  echo "nodejs npm" ;;
                maven) echo "maven" ;;
                docker) echo "docker" ;;
            esac ;;
        pacman)
            case "$dep" in
                jdk17) echo "jdk17-openjdk" ;;
                node)  echo "nodejs npm" ;;
                maven) echo "maven" ;;
                docker) echo "docker" ;;
            esac ;;
        zypper)
            case "$dep" in
                jdk17) echo "java-17-openjdk-devel" ;;
                node)  echo "nodejs npm" ;;
                maven) echo "maven" ;;
                docker) echo "docker" ;;
            esac ;;
    esac
}

# 官方下载链接（无包管理器或安装失败时提示）
_download_url() {
    case "$1" in
        jdk17) echo "https://adoptium.net/temurin/releases/?version=17" ;;
        node)  echo "https://nodejs.org/en/download" ;;
        maven) echo "https://maven.apache.org/download.cgi" ;;
        docker) echo "https://www.docker.com/products/docker-desktop/" ;;
    esac
}

# 记住用户拒绝安装的可选依赖，下次不再询问。存于 data/.skip-install（已被 .gitignore 排除）。
_skip_file() { echo "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/data/.skip-install"; }

_is_skipped() {
    local dep="$1" f; f="$(_skip_file)"
    [ -f "$f" ] && grep -qxF "$dep" "$f"
}

_remember_skip() {
    local dep="$1" f; f="$(_skip_file)"
    mkdir -p "$(dirname "$f")" 2>/dev/null
    _is_skipped "$dep" || echo "$dep" >> "$f"
}

# 询问并安装：_prompt_install <dep> <人类可读名> [optional]
# 第三参数为 "optional" 时：用户拒绝会被记住，后续启动不再询问该依赖。
# 返回 0=已安装/成功，1=未安装
_prompt_install() {
    local dep="$1" name="$2" optional="$3"
    local url; url="$(_download_url "$dep")"

    # 可选依赖且用户此前已拒绝 → 静默跳过
    if [ "$optional" = "optional" ] && _is_skipped "$dep"; then
        printf '   （已按你的选择跳过 %s 安装；如需重新启用：删除 data/.skip-install）\n' "$name"
        return 1
    fi

    if [ -z "$CHECK_ENV_PM" ]; then
        echo "   未检测到包管理器，无法自动安装。请手动安装 $name："
        echo "   下载: $url"
        [ "$CHECK_ENV_OS" = "mac" ] && echo "   提示: 可先安装 Homebrew (https://brew.sh) 后重试本脚本"
        return 1
    fi

    local pkg; pkg="$(_pkg_name "$dep")"
    printf '   是否用 %s 安装 %s？[%s %s]  (y/N) ' "$CHECK_ENV_PM" "$name" "$CHECK_ENV_PM_INSTALL" "$pkg"
    read -r answer
    case "$answer" in
        [yY]|[yY][eE][sS])
            echo "   正在安装 $name ..."
            # shellcheck disable=SC2086
            if [ "$CHECK_ENV_PM" = "apt" ]; then sudo apt-get update -qq || true; fi
            if $CHECK_ENV_PM_INSTALL $pkg; then
                echo "   ✅ $name 安装完成"
                return 0
            else
                echo "   ❌ $name 安装失败，请手动安装: $url"
                return 1
            fi
            ;;
        *)
            # 可选依赖：记住拒绝，下次不再问
            if [ "$optional" = "optional" ]; then
                _remember_skip "$dep"
                printf '   已跳过，下次不再询问（如需安装请删除 data/.skip-install，或手动安装：%s）\n' "$url"
            else
                printf '   已跳过。手动安装: %s\n' "$url"
            fi
            return 1
            ;;
    esac
}

# ── JDK 17+ 检测 ──────────────────────────────────────────────────
# 成功时设置全局 JAVA17_HOME 并 export JAVA_HOME
_java_major() {
    # 解析 "$1/bin/java -version" 的主版本号（1.8→8, 17.0.x→17）
    local jhome="$1"
    [ -x "$jhome/bin/java" ] || return 1
    local ver
    ver="$("$jhome/bin/java" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+(\.[0-9]+)?).*/\1/')"
    [ -z "$ver" ] && return 1
    case "$ver" in
        1.*) echo "${ver#1.}" | cut -d. -f1 ;;
        *)   echo "$ver" | cut -d. -f1 ;;
    esac
}

_find_jdk17() {
    # 1) 已显式配置且有效
    if [ -n "$JAVA17_HOME" ] && [ -x "$JAVA17_HOME/bin/java" ]; then
        local m; m="$(_java_major "$JAVA17_HOME")"
        [ -n "$m" ] && [ "$m" -ge 17 ] 2>/dev/null && { echo "$JAVA17_HOME"; return 0; }
    fi
    # 2) macOS java_home
    if [ "$CHECK_ENV_OS" = "mac" ] && [ -x /usr/libexec/java_home ]; then
        local h; h="$(/usr/libexec/java_home -v 17 2>/dev/null)"
        [ -n "$h" ] && [ -x "$h/bin/java" ] && { echo "$h"; return 0; }
    fi
    # 3) Linux 常见安装目录
    if [ "$CHECK_ENV_OS" = "linux" ]; then
        for base in /usr/lib/jvm /usr/java /opt/java /opt; do
            [ -d "$base" ] || continue
            for d in "$base"/*; do
                [ -x "$d/bin/java" ] || continue
                local m; m="$(_java_major "$d")"
                [ -n "$m" ] && [ "$m" -ge 17 ] 2>/dev/null && { echo "$d"; return 0; }
            done
        done
    fi
    # 4) 当前 PATH 中的 java 恰好 >= 17
    if command -v java &>/dev/null; then
        local jbin jhome
        jbin="$(command -v java)"
        # 解析软链 + 去掉 /bin/java
        jhome="$(cd "$(dirname "$jbin")/.." && pwd)"
        local m; m="$(_java_major "$jhome")"
        [ -n "$m" ] && [ "$m" -ge 17 ] 2>/dev/null && { echo "$jhome"; return 0; }
    fi
    return 1
}

check_jdk17() {
    local home
    if home="$(_find_jdk17)"; then
        JAVA17_HOME="$home"
        export JAVA_HOME="$home"
        echo "✅ JDK 17+  : $home"
        return 0
    fi
    echo "❌ JDK 17+  : 未检测到（平台运行必需）"
    if _prompt_install jdk17 "JDK 17"; then
        if home="$(_find_jdk17)"; then
            JAVA17_HOME="$home"; export JAVA_HOME="$home"
            echo "✅ JDK 17+  : $home"
            return 0
        fi
        echo "   ⚠️ 安装后仍未找到 JDK 17，可能需要重开终端或手动设置 JAVA17_HOME"
    fi
    return 1
}

# ── Node.js 18+ 检测 ──────────────────────────────────────────────
check_node() {
    if command -v node &>/dev/null; then
        local ver major
        ver="$(node -v 2>/dev/null | sed 's/^v//')"
        major="$(echo "$ver" | cut -d. -f1)"
        if [ -n "$major" ] && [ "$major" -ge 18 ] 2>/dev/null; then
            echo "✅ Node.js  : v$ver"
            return 0
        fi
        echo "❌ Node.js  : v$ver（需要 18+）"
    else
        echo "❌ Node.js  : 未检测到（前端构建必需）"
    fi
    if _prompt_install node "Node.js 18+"; then
        if command -v node &>/dev/null; then
            local major; major="$(node -v 2>/dev/null | sed 's/^v//' | cut -d. -f1)"
            [ -n "$major" ] && [ "$major" -ge 18 ] 2>/dev/null && { echo "✅ Node.js  : $(node -v)"; return 0; }
        fi
    fi
    return 1
}

# ── Maven 检测（可选）─────────────────────────────────────────────
check_maven() {
    if command -v mvn &>/dev/null; then
        echo "✅ Maven    : $(mvn -v 2>/dev/null | head -1 | awk '{print $3}')"
        return 0
    fi
    echo "⚠️ Maven    : 未检测到（可选；分析 Maven 仓库时需要，Gradle 项目/上传 jar 不受影响）"
    _prompt_install maven "Maven" optional || true
    return 0
}

# ── Docker 检测（可选）────────────────────────────────────────────
check_docker() {
    if command -v docker &>/dev/null; then
        echo "✅ Docker   : $(docker --version 2>/dev/null | awk '{print $3}' | tr -d ',')"
        return 0
    fi
    echo "⚠️ Docker   : 未检测到（可选；仅用于 Qdrant 向量库，缺失则语义搜索降级为关键词匹配）"
    _prompt_install docker "Docker" optional || true
    return 0
}

# ── 主流程 ────────────────────────────────────────────────────────
check_env_main() {
    _detect_platform

    echo "=========================================="
    echo "  环境检查 (OS: ${CHECK_ENV_OS:-unknown}, 包管理器: ${CHECK_ENV_PM:-无})"
    echo "=========================================="

    if [ "$CHECK_ENV_OS" = "unknown" ]; then
        echo "⚠️ 未识别的操作系统，跳过自动检查。请确保已安装 JDK 17+ 与 Node.js 18+"
        return 0
    fi

    local required_ok=0

    echo ""
    echo "[必需项]"
    check_jdk17 || required_ok=1
    check_node  || required_ok=1

    echo ""
    echo "[可选项]"
    check_maven
    check_docker

    echo ""
    if [ "$required_ok" -ne 0 ]; then
        echo "❌ 必需环境缺失，无法启动。请按上面提示安装后重试。"
        echo "=========================================="
        return 1
    fi
    echo "✅ 必需环境就绪"
    echo "=========================================="
    return 0
}

# 被直接执行 → 跑主流程并以其结果作为退出码；被 source → 仅定义函数 + 执行检查导出 JAVA_HOME
if [ "${BASH_SOURCE[0]}" = "${0}" ]; then
    check_env_main
    exit $?
else
    check_env_main
fi
