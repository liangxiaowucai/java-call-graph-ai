#!/bin/bash

# javacg2-platform 一键启动脚本
# 启动顺序: 环境检查 → Qdrant → 核心jar构建 → Spring Boot后端 → Vite前端

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PID_FILE="$SCRIPT_DIR/.pids"

# 用户 Ctrl+C（或脚本退出）时，自动调用 stop.sh 优雅停止所有服务，不残留后台进程
CLEANED_UP=false
cleanup() {
    # 防止重复执行（INT 触发后 EXIT 还会再触发一次）
    if [ "$CLEANED_UP" = true ]; then return; fi
    CLEANED_UP=true
    echo ""
    echo "🛑 收到停止信号，正在清理服务..."
    # stop.sh 自身出错不应让 trap 再抛错
    bash "$SCRIPT_DIR/stop.sh" || true
}
trap cleanup INT TERM

# ── 环境检查 ───────────────────────────────────────────────────────
# 检查 JDK 17+ / Node 18+（必需）与 Maven / Docker（可选），缺失时询问安装。
# 通过后 check-env.sh 会 export JAVA_HOME（指向检测到的 JDK 17+）。
# 如需指定固定 JDK，可在此前 export JAVA17_HOME=/path/to/jdk-17
source "$SCRIPT_DIR/check-env.sh"
if [ -z "$JAVA_HOME" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo "❌ 环境检查未通过，启动中止"
    exit 1
fi

# 动态生成 gradle.properties（使用检测到的 JDK 路径）
echo "org.gradle.java.home=$JAVA_HOME" > "$SCRIPT_DIR/gradle.properties"

echo "=========================================="
echo "  javacg2-platform 启动"
echo "  JAVA_HOME: $JAVA_HOME"
echo "  Java版本: $($JAVA_HOME/bin/java -version 2>&1 | head -1)"
echo "  数据库:   ${DB_HOST:-localhost}:${DB_PORT:-5432}/${DB_NAME:-javacg2}"
echo "  Embedding: ${EMBEDDING_URL:-未配置（降级为关键词匹配）}"
echo "=========================================="

# ── Step 0: 启动基础服务（PostgreSQL / Qdrant / Ollama）──────
echo ""
echo "[0/4] 启动基础服务 (docker compose)..."
cd "$SCRIPT_DIR"

if ! command -v docker &> /dev/null; then
    echo "⚠️  未检测到 docker，跳过容器服务启动"
else
    if docker compose version &> /dev/null 2>&1; then
        COMPOSE_CMD="docker compose"
    elif command -v docker-compose &> /dev/null; then
        COMPOSE_CMD="docker-compose"
    else
        echo "⚠️  未检测到 docker compose，跳过容器服务"
        COMPOSE_CMD=""
    fi

    if [ -n "$COMPOSE_CMD" ]; then
        # 启动 postgres + qdrant（必需）；ollama 已在 check-env.sh 里按需询问启动
        $COMPOSE_CMD up -d postgres qdrant

        # 等待 PostgreSQL 健康
        echo "等待 PostgreSQL 就绪..."
        PG_OK=false
        for i in $(seq 1 20); do
            docker exec javacg2-postgres pg_isready -U "${DB_USER:-javacg2}" -d "${DB_NAME:-javacg2}" &>/dev/null 2>&1 \
                && { PG_OK=true; break; }
            sleep 2
        done
        $PG_OK && echo "✅ PostgreSQL 就绪" || echo "⚠️  PostgreSQL 启动超时，请检查: docker logs javacg2-postgres"

        # 等待 Qdrant 健康
        echo "等待 Qdrant 就绪..."
        QDRANT_OK=false
        for i in $(seq 1 15); do
            curl -sf http://localhost:6333/healthz > /dev/null 2>&1 && { QDRANT_OK=true; break; }
            sleep 2
        done
        $QDRANT_OK && echo "✅ Qdrant 就绪 (http://localhost:6333)" || echo "⚠️  Qdrant 启动超时，问答将使用关键词降级模式"
    fi
fi

# ── Step 1: 构建核心 jar ──────────────────────────────────
echo ""
echo "[1/4] 构建 java-callgraph2 核心 jar..."
cd "$ROOT_DIR"
./gradlew jar -x test --quiet
echo "✅ 核心 jar 构建完成"

# ── Step 2: 启动后端 ──────────────────────────────────────
echo ""
echo "[2/4] 启动后端 (http://localhost:8080)..."
cd "$SCRIPT_DIR"
mkdir -p "$SCRIPT_DIR/data/logs"
./gradlew bootRun 2>&1 | tee "$SCRIPT_DIR/data/logs/backend.log" &
BACKEND_PID=$!

echo "后端 PID: $BACKEND_PID"
echo "等待后端启动（最多 60s）..."
for i in $(seq 1 30); do
    if curl -s http://localhost:8080/api/repos > /dev/null 2>&1; then
        echo "✅ 后端启动成功"
        break
    fi
    if [ $i -eq 30 ]; then
        echo "⚠️  后端启动超时，请查看日志: data/logs/backend.log"
    fi
    sleep 2
done

# ── Step 3: 启动前端 ──────────────────────────────────────
echo ""
echo "[3/4] 启动前端 (http://localhost:5173)..."
cd "$SCRIPT_DIR/frontend"
# 首次启动或依赖缺失时安装前端依赖
if [ ! -d "node_modules" ]; then
    echo "📦 安装前端依赖（首次启动，可能需要几分钟）..."
    npm install
fi
npm run dev > "$SCRIPT_DIR/data/logs/frontend.log" 2>&1 &
FRONTEND_PID=$!
echo "前端 PID: $FRONTEND_PID"

# 保存 PID 供 stop.sh 使用
echo "BACKEND_PID=$BACKEND_PID" > "$PID_FILE"
echo "FRONTEND_PID=$FRONTEND_PID" >> "$PID_FILE"

echo ""
echo "=========================================="
echo "  启动完成！"
echo "  前端:    http://localhost:5173"
echo "  后端:    http://localhost:8080"
echo "  Qdrant:  http://localhost:6333"
echo "  数据库:  ${DB_HOST:-localhost}:${DB_PORT:-5432}/${DB_NAME:-javacg2}"
if [ -n "$EMBEDDING_URL" ]; then
  echo "  Embedding: $EMBEDDING_URL ($EMBEDDING_MODEL)"
else
  echo "  Embedding: 未配置（降级为关键词匹配）"
fi
echo ""
echo "  按 Ctrl+C 停止所有服务（等同 ./stop.sh）"
echo "  后端日志: tail -f data/logs/backend.log"
echo "  前端日志: tail -f data/logs/frontend.log"
echo "=========================================="

# 等待后台进程；Ctrl+C 会中断 wait 并触发 cleanup。
# wait 被信号打断返回非零，用 || true 避免 set -e 直接退出，让 trap 正常执行。
wait || true
