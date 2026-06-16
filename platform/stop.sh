#!/bin/bash

# javacg2-platform 一键停止脚本
# 停止顺序: 前端 → 后端 → Qdrant

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PID_FILE="$SCRIPT_DIR/.pids"

echo "=========================================="
echo "  javacg2-platform 停止"
echo "=========================================="

# ── Step 1: 停止前端 ──────────────────────────────────────
echo ""
echo "[1/3] 停止前端..."
if [ -f "$PID_FILE" ]; then
    FRONTEND_PID=$(grep FRONTEND_PID "$PID_FILE" | cut -d= -f2)
    if [ -n "$FRONTEND_PID" ] && kill -0 "$FRONTEND_PID" 2>/dev/null; then
        kill "$FRONTEND_PID" 2>/dev/null
        echo "✅ 前端已停止 (PID: $FRONTEND_PID)"
    fi
fi
# 兜底：按进程名清理
pkill -f "vite.*platform/frontend" 2>/dev/null || true
lsof -ti:5173 | xargs kill -9 2>/dev/null || true

# ── Step 2: 停止后端 ──────────────────────────────────────
echo "[2/3] 停止后端..."
if [ -f "$PID_FILE" ]; then
    BACKEND_PID=$(grep BACKEND_PID "$PID_FILE" | cut -d= -f2)
    if [ -n "$BACKEND_PID" ] && kill -0 "$BACKEND_PID" 2>/dev/null; then
        kill "$BACKEND_PID" 2>/dev/null
        echo "✅ 后端已停止 (PID: $BACKEND_PID)"
    fi
fi
# 兜底：按进程名清理
pkill -f "javacg2-platform" 2>/dev/null || true
pkill -f "bootRun" 2>/dev/null || true
lsof -ti:8080 | xargs kill -9 2>/dev/null || true

# ── Step 3: 停止 Qdrant ───────────────────────────────────
echo "[3/3] 停止 Qdrant..."
cd "$SCRIPT_DIR"
if command -v docker &> /dev/null; then
    if docker compose version &> /dev/null 2>&1; then
        docker compose stop qdrant 2>/dev/null && echo "✅ Qdrant 已停止（数据已保留）" || true
    elif command -v docker-compose &> /dev/null; then
        docker-compose stop qdrant 2>/dev/null && echo "✅ Qdrant 已停止（数据已保留）" || true
    fi
fi

# 清理 PID 文件
rm -f "$PID_FILE"

echo ""
echo "=========================================="
echo "  所有服务已停止"
echo "  Qdrant 数据保留在 data/qdrant/"
echo "  如需彻底清除向量数据: docker compose down -v"
echo "=========================================="
