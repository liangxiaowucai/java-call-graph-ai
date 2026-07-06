# Java Call Graph

[![Apache License 2.0](https://img.shields.io/badge/license-Apache%20License%202.0-green.svg)]()

> 基于 [java-callgraph2](https://github.com/Adrninistrator/java-callgraph2) 二次开发。通过静态分析提取完整的 Java 方法调用链，提供 **调用链分析**、**调用链追踪**、**AI 问答** 三大核心功能，并以 MCP Server 形式暴露给 AI 编程助手。

---

## 三大核心功能

### 1. 调用链分析

从入口点（Controller / MQ 消费者 / 定时任务 / gRPC 服务）出发，图形化展开完整调用树：

- 支持继承、多态、接口→实现自动桥接、Lambda、Stream 方法引用、线程调用
- 边界点标注：DB / HTTP / MQ / CACHE / GRPC / TRANSACTION
- 单击节点查看源码、入参实体类字段、枚举常量值、调用链上下文
- 日志诊断：粘贴线上报错日志，定位出错方法
- 一键生成产品文档 / 研发文档

### 2. 调用链追踪

配合 Chrome 插件，自动捕获浏览器中的 API 请求，提交后端进行全链路分析：

- Chrome 插件拦截请求，支持 URL 筛选、耗时过滤
- 后端自动匹配请求到 Controller 方法，全展开调用树
- 提取外部依赖（HTTP URL / DB SQL / 缓存 / MQ）、业务常量、错误码
- AI 流式分析：基于调用链数据生成性能与风险报告
- 数据流推断：根据入参和调用链绑定关系，重建发往下游的实际请求

### 3. AI 问答

基于源码和调用链的智能问答（Claude function calling）：

- 直接输入问题，自动语义匹配相关接口
- 目标驱动工具调用：先用 `getChainOutline` 拿调用链地图，再按需并行深挖
- 支持工具：`getChainOutline` / `getMethodSource` / `getCallees` / `getCallers` / `getConstants` / `getExceptions` / `getParamClassDef` / `getImplementations` / `getBoundaries`
- 多态分派解析：自动找到接口的实现类
- 降噪过滤：自动跳过 getter/setter/日志/工具类
- 所有结论严格引用源码，不编造

---

## 快速开始

**环境要求**：JDK 17+、Node.js 18+、Docker（推荐）

### 方式一：一键启动（推荐）

```bash
git clone https://github.com/liangxiaowucai/java-call-graph.git
cd java-call-graph/platform

./start.sh
```

`start.sh` 会自动完成环境检查、启动 PostgreSQL + Qdrant 容器、编译后端、启动前端。

访问 http://localhost:5173（前端）| http://localhost:8080（后端 API）

### 方式二：Docker Compose 部署

```bash
cd platform
docker compose up -d
```

包含服务：`app`（后端 + 前端静态资源）、`postgres`、`qdrant`、`ollama`（可选）。

### 方式三：手动启动

```bash
# 1. 启动 PostgreSQL（本地或 Docker）
docker run -d --name javacg2-postgres \
  -e POSTGRES_DB=javacg2 -e POSTGRES_USER=javacg2 -e POSTGRES_PASSWORD=javacg2 \
  -p 5432:5432 postgres:16-alpine

# 2. 构建核心引擎
./gradlew jar -x test

# 3. 启动后端
cd platform && ./gradlew bootRun

# 4. 启动前端（另开终端）
cd platform/frontend && npm install && npm run dev
```

### 配置 AI 模型

启动后前端 → **系统配置** → 填写：

| 配置项 | 说明 | 示例 |
|--------|------|------|
| Claude API 地址 | 对话模型 API | `https://api.anthropic.com` 或 AWS Bedrock 代理 |
| Claude API Key | 密钥 | `sk-ant-xxxx` |
| Claude 模型 | 模型名称 | `claude-sonnet-4-20250514` |
| Embedding API 地址 | 向量模型（可选，默认 Ollama） | `http://localhost:11434` |
| Embedding 模型 | 向量模型名（可选） | `nomic-embed-text` |

> 不配置 AI 模型不影响调用链分析，只影响 AI 问答和调用链追踪的 AI 分析报告。

#### 环境变量覆盖（Docker / 生产环境）

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `DB_HOST` | `localhost` | PostgreSQL 地址 |
| `DB_PORT` | `5432` | PostgreSQL 端口 |
| `DB_NAME` | `javacg2` | 数据库名 |
| `DB_USER` | `javacg2` | 数据库用户 |
| `DB_PASS` | `javacg2` | 数据库密码 |
| `EMBEDDING_URL` | `http://localhost:11434` | Embedding API 地址 |
| `EMBEDDING_MODEL` | `nomic-embed-text` | Embedding 模型名 |
| `EMBEDDING_DIMENSIONS` | `768` | 向量维度 |
| `QDRANT_URL` | `http://localhost:6333` | Qdrant 地址 |

---

## 使用流程

1. **仓库管理** → 添加 Git 仓库（填写包前缀），点击「分析」
2. **调用链分析** → 选择仓库，点击入口点查看调用树
3. **调用链追踪** → 粘贴 JSON / cURL / Chrome 插件数据，点击分析
4. **AI 问答** → 直接输入业务问题

---

## 接入 AI 编程助手（MCP）

平台以 MCP Server 形式暴露调用链工具，让 AI 编程助手直接查询。

### 配置

在 IDE 的 MCP 配置文件中添加：

```json
{
  "mcpServers": {
    "java-callgraph": {
      "url": "http://localhost:8080/mcp/sse",
      "transport": "sse"
    }
  }
}
```

支持 Kiro（`.kiro/settings/mcp.json`）、Cursor（`.cursor/mcp.json`）、Claude Desktop。

### MCP 工具列表

| 工具 | 功能 |
|------|------|
| `listRepositories` | 列出所有已分析仓库 |
| `listApiEndpoints` | 列出仓库的全部入口点 |
| `semanticSearch` | 语义搜索相关方法 |
| `getCallGraph` | 获取方法的下游调用树 |
| `getMethodSource` | 获取方法源码 |
| `getBoundaries` | 获取方法的外部依赖 |
| `getImpactAnalysis` | 上游影响分析 |
| `getImpactWithSource` | 影响分析 + 调用点源码 |
| `getCrossRepoImpact` | 跨仓库影响分析 |
| `getCrossRepoCallTree` | 跨仓库调用链 |

---

## Chrome 插件

`chrome-extension/` 目录包含请求捕获插件，用于调用链追踪功能。

### 安装方式

**方式一：直接安装 crx（推荐）**

1. 下载 `chrome-extension/javacg2-tracker.crx`
2. Chrome → `chrome://extensions` → 开启开发者模式
3. 将 crx 文件拖入浏览器窗口即可安装

**方式二：开发者模式加载源码**

1. Chrome → `chrome://extensions` → 开启开发者模式
2. 「加载已解压的扩展程序」→ 选择 `chrome-extension/` 目录

### 使用

1. 在目标网页操作，插件自动捕获 API 请求
2. 使用筛选框过滤请求（支持 URL / 方法 / 状态码）
3. 点击「智能分析」发送到后端进行全链路分析
4. 或点击「导出 JSON」手动粘贴到调用链追踪页面

---

## 技术栈

| 层 | 技术 |
|----|------|
| 静态分析 | Apache BCEL（java-callgraph2 核心） |
| 后端 | Spring Boot 3.3、Spring AI MCP、JPA、PostgreSQL |
| 前端 | React 18、TypeScript、Vite、AntV G6 |
| AI | Claude function calling、Embedding（Ollama / OpenAI） |
| 向量数据库 | Qdrant（可选，不装则降级为关键词匹配） |
| 容器化 | Docker、Docker Compose、多阶段构建 |

---

## 致谢

- [java-callgraph2](https://github.com/Adrninistrator/java-callgraph2) — 静态分析核心
- [java-callgraph](https://github.com/gousiosg/java-callgraph) — 最初灵感来源

## License

[Apache License 2.0](LICENSE)
