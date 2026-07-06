-- repositories 仓库信息表
CREATE TABLE IF NOT EXISTS repositories (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    git_url VARCHAR(500) NOT NULL,
    token_encrypted VARCHAR(500),
    repo_type VARCHAR(20) NOT NULL,
    branch VARCHAR(100) DEFAULT 'main',
    local_path VARCHAR(500) NOT NULL,
    last_commit_hash VARCHAR(64),
    last_sync_time TIMESTAMP,
    status VARCHAR(20) DEFAULT 'CREATED',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    url_path_identifier VARCHAR(200)
);

-- H2 兼容的列存在性检查：用 ALTER TABLE 追加
ALTER TABLE repositories ADD COLUMN IF NOT EXISTS url_path_identifier VARCHAR(200);

-- chunks 方法分块表
CREATE TABLE IF NOT EXISTS chunks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    repo_id BIGINT NOT NULL,
    full_method VARCHAR(1000) NOT NULL,
    class_name VARCHAR(500),
    package_name VARCHAR(500),
    method_name VARCHAR(200),
    file_path VARCHAR(500),
    start_line INT,
    end_line INT,
    return_type VARCHAR(200),
    access_flags VARCHAR(50),
    annotations TEXT,
    parameters TEXT,
    call_summary TEXT,
    jar_num INT,
    method_hash VARCHAR(64),
    embedding_status VARCHAR(10) DEFAULT NULL,  -- NULL=待处理, DONE=已完成, FAILED=失败
    FOREIGN KEY (repo_id) REFERENCES repositories(id) ON DELETE CASCADE
);

-- H2 兼容的列存在性检查：用 ALTER TABLE 追加（首次运行 schema.sql 会创建，后续 ddl-auto=update 补列）
ALTER TABLE chunks ADD COLUMN IF NOT EXISTS embedding_status VARCHAR(10) DEFAULT NULL;

-- 调用链展示用的干净结构化数据（与 call_summary 搜索索引分离）
ALTER TABLE chunks ADD COLUMN IF NOT EXISTS constants TEXT;
ALTER TABLE chunks ADD COLUMN IF NOT EXISTS exceptions TEXT;
ALTER TABLE chunks ADD COLUMN IF NOT EXISTS resolved_urls TEXT;
ALTER TABLE chunks ADD COLUMN IF NOT EXISTS error_codes TEXT;

CREATE INDEX IF NOT EXISTS idx_chunks_repo_class ON chunks(repo_id, class_name);
CREATE INDEX IF NOT EXISTS idx_chunks_repo_package ON chunks(repo_id, package_name);
CREATE INDEX IF NOT EXISTS idx_chunks_full_method ON chunks(repo_id, full_method);
CREATE INDEX IF NOT EXISTS idx_chunks_method_hash ON chunks(repo_id, method_hash);

-- call_graph 调用关系表
CREATE TABLE IF NOT EXISTS call_graph (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    repo_id BIGINT NOT NULL,
    call_id INT,
    caller_method VARCHAR(1000) NOT NULL,
    callee_method VARCHAR(1000) NOT NULL,
    call_type VARCHAR(20),
    line_number INT,
    caller_return_type VARCHAR(200),
    callee_obj_type VARCHAR(10),
    callee_raw_return_type VARCHAR(200),
    callee_actual_return_type VARCHAR(200),
    caller_jar_num INT,
    callee_jar_num INT,
    enabled BOOLEAN DEFAULT TRUE,
    FOREIGN KEY (repo_id) REFERENCES repositories(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_cg_repo_caller ON call_graph(repo_id, caller_method);
CREATE INDEX IF NOT EXISTS idx_cg_repo_callee ON call_graph(repo_id, callee_method);
CREATE INDEX IF NOT EXISTS idx_cg_call_type ON call_graph(repo_id, call_type);

-- boundaries 边界点表
CREATE TABLE IF NOT EXISTS boundaries (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    repo_id BIGINT NOT NULL,
    full_method VARCHAR(1000) NOT NULL,
    boundary_type VARCHAR(30) NOT NULL,
    line_number INT,
    context TEXT,
    callee_method VARCHAR(1000),
    FOREIGN KEY (repo_id) REFERENCES repositories(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_bd_repo_method ON boundaries(repo_id, full_method);
CREATE INDEX IF NOT EXISTS idx_bd_type ON boundaries(repo_id, boundary_type);

-- api_endpoints 入口点/接口表
CREATE TABLE IF NOT EXISTS api_endpoints (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    repo_id BIGINT NOT NULL,
    endpoint_type VARCHAR(30) NOT NULL,
    http_method VARCHAR(10),
    url_path VARCHAR(500),
    full_method VARCHAR(1000) NOT NULL,
    class_name VARCHAR(500),
    annotation_class VARCHAR(200),
    sql_statement TEXT,
    table_names VARCHAR(500),
    FOREIGN KEY (repo_id) REFERENCES repositories(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_ep_repo_type ON api_endpoints(repo_id, endpoint_type);
CREATE INDEX IF NOT EXISTS idx_ep_url ON api_endpoints(repo_id, url_path);
CREATE INDEX IF NOT EXISTS idx_ep_method ON api_endpoints(repo_id, full_method);

-- system_config 系统配置表
CREATE TABLE IF NOT EXISTS system_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_key VARCHAR(100) NOT NULL UNIQUE,
    config_value TEXT,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- repo_config 仓库配置表（从配置文件提取 + 用户自定义）
CREATE TABLE IF NOT EXISTS repo_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    repo_id BIGINT NOT NULL,
    config_key VARCHAR(500) NOT NULL,
    config_value TEXT,
    source VARCHAR(20) DEFAULT 'FILE',
    default_value TEXT,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (repo_id) REFERENCES repositories(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_rc_repo ON repo_config(repo_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_rc_repo_key ON repo_config(repo_id, config_key);

-- mcp_servers 外部 MCP Server 配置表（平台作为 MCP Client 调用外部服务）
CREATE TABLE IF NOT EXISTS mcp_servers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    url VARCHAR(500) NOT NULL,
    transport VARCHAR(20) NOT NULL DEFAULT 'SSE',  -- SSE | STDIO
    enabled BOOLEAN DEFAULT TRUE,
    description VARCHAR(500),
    last_test_status VARCHAR(20),    -- OK | FAIL | UNTESTED
    last_test_message VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
