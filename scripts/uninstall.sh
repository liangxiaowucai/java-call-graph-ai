#!/bin/bash
# UAI Kiro 一键卸载脚本
# 用途：移除 .kiro/ 配置目录

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 辅助函数
info() { echo -e "${BLUE}[INFO]${NC} $1"; }
success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }

# 解析参数
SKIP_CONFIRM=false
PURGE_ALL=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --yes|-y)
            SKIP_CONFIRM=true
            shift
            ;;
        --purge)
            PURGE_ALL=true
            shift
            ;;
        *)
            error "未知参数: $1"
            echo "用法: $0 [--yes] [--purge]"
            echo "  --yes    跳过确认提示"
            echo "  --purge  同时删除 .kiro/ 配置（包括自定义内容）"
            exit 1
            ;;
    esac
done

# 检测项目根目录
PROJECT_ROOT="$(pwd)"
info "项目根目录: $PROJECT_ROOT"

# 检查 .kiro/ 是否存在
if [ ! -d "$PROJECT_ROOT/.kiro" ]; then
    warn ".kiro/ 目录不存在，无需卸载"
    exit 0
fi

# 显示将要删除的内容
echo ""
echo "📋 将要删除的文件和目录："
echo ""

if [ "$PURGE_ALL" = true ]; then
    echo "  .kiro/ (完整删除，包括所有自定义内容)"
    echo "    ├── hooks/"
    echo "    ├── steering/"
    echo "    ├── README.md"
    echo "    └── ARCHITECTURE.md"
else
    echo "  .kiro/hooks/ (仅删除 UAI 标准 hooks)"
    echo "  .kiro/steering/uai-standards.md (仅删除 UAI 标准 steering)"
    echo ""
    warn "保留目录结构和自定义配置"
fi

echo ""

# 确认
if [ "$SKIP_CONFIRM" = false ]; then
    read -p "确认卸载？(y/N): " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        info "已取消卸载，未做任何改动"
        exit 0
    fi
fi

# 执行卸载
info "开始卸载..."

if [ "$PURGE_ALL" = true ]; then
    # 完整删除
    rm -rf "$PROJECT_ROOT/.kiro"
    success "已删除 .kiro/ 目录"
else
    # 选择性删除：所有 UAI hooks
    for h in uai-session-init uai-request-classifier uai-post-edit-check \
             uai-four-piece-gate uai-gate-reset uai-pre-commit-compile \
             uai-session-context uai-stop-learning \
             uai-health-check uai-bash-guard; do
        if [ -f "$PROJECT_ROOT/.kiro/hooks/$h.json" ]; then
            rm -f "$PROJECT_ROOT/.kiro/hooks/$h.json"
            success "删除 $h.json"
        fi
    done

    # 删除 hooks 脚本和运行时标记
    if [ -d "$PROJECT_ROOT/.kiro/hooks/scripts" ]; then
        rm -f "$PROJECT_ROOT/.kiro/hooks/scripts/uai-precheck.sh" \
              "$PROJECT_ROOT/.kiro/hooks/scripts/uai-precheck.py" \
              "$PROJECT_ROOT/.kiro/hooks/scripts/uai-commit-compile.sh" \
              "$PROJECT_ROOT/.kiro/hooks/scripts/uai-session-context.sh" \
              "$PROJECT_ROOT/.kiro/hooks/scripts/uai-health-check.sh" \
              "$PROJECT_ROOT/.kiro/hooks/scripts/uai-bash-guard.sh" \
              "$PROJECT_ROOT/.kiro/hooks/scripts/uai-postwrite.sh"
        success "删除 hooks/scripts/ 下的 UAI 脚本"
        if [ -z "$(ls -A "$PROJECT_ROOT/.kiro/hooks/scripts")" ]; then
            rmdir "$PROJECT_ROOT/.kiro/hooks/scripts"
        fi
    fi
    rm -f "$PROJECT_ROOT/.kiro/.uai-gate-open" "$PROJECT_ROOT/.kiro/.uai-write-count"

    # 卸载 git pre-commit（仅当是 UAI 安装的；有 .bak 则恢复）
    HOOKS_DIR="$(git -C "$PROJECT_ROOT" rev-parse --git-path hooks 2>/dev/null)"
    if [ -n "$HOOKS_DIR" ]; then
        case "$HOOKS_DIR" in /*) ;; *) HOOKS_DIR="$PROJECT_ROOT/$HOOKS_DIR" ;; esac
        PC_HOOK="$HOOKS_DIR/pre-commit"
        if [ -f "$PC_HOOK" ] && grep -q "UAI git pre-commit" "$PC_HOOK" 2>/dev/null; then
            rm -f "$PC_HOOK"
            if [ -f "$PC_HOOK.bak" ]; then
                mv "$PC_HOOK.bak" "$PC_HOOK"
                info "已恢复原 pre-commit（来自 .bak）"
            fi
            success "卸载 git pre-commit"
        fi
    fi

    if [ -f "$PROJECT_ROOT/.kiro/steering/uai-standards.md" ]; then
        rm -f "$PROJECT_ROOT/.kiro/steering/uai-standards.md"
        success "删除 uai-standards.md"
    fi
    
    if [ -f "$PROJECT_ROOT/.kiro/README.md" ]; then
        rm -f "$PROJECT_ROOT/.kiro/README.md"
        success "删除 README.md"
    fi
    
    if [ -f "$PROJECT_ROOT/.kiro/ARCHITECTURE.md" ]; then
        rm -f "$PROJECT_ROOT/.kiro/ARCHITECTURE.md"
        success "删除 ARCHITECTURE.md"
    fi
    
    # 如果目录为空，删除目录
    if [ -d "$PROJECT_ROOT/.kiro/hooks" ] && [ -z "$(ls -A $PROJECT_ROOT/.kiro/hooks)" ]; then
        rmdir "$PROJECT_ROOT/.kiro/hooks"
        info "删除空目录 hooks/"
    fi
    
    if [ -d "$PROJECT_ROOT/.kiro/steering" ] && [ -z "$(ls -A $PROJECT_ROOT/.kiro/steering)" ]; then
        rmdir "$PROJECT_ROOT/.kiro/steering"
        info "删除空目录 steering/"
    fi
    
    if [ -d "$PROJECT_ROOT/.kiro" ] && [ -z "$(ls -A $PROJECT_ROOT/.kiro)" ]; then
        rmdir "$PROJECT_ROOT/.kiro"
        info "删除空目录 .kiro/"
    fi
fi

# 总结
echo ""
success "✅ Kiro 配置卸载完成！"
echo ""

if [ "$PURGE_ALL" = false ] && [ -d "$PROJECT_ROOT/.kiro" ]; then
    info "📁 保留的文件："
    find "$PROJECT_ROOT/.kiro" -type f | while read -r file; do
        echo "  ${file#$PROJECT_ROOT/}"
    done
    echo ""
fi

echo "🎯 后续步骤："
echo "  1. 重新启动 Kiro IDE 以清除旧配置"
echo "  2. 如需重新安装，运行："
echo "     bash ai-kiro/scripts/setup.sh"
echo ""
success "卸载完成！"
