-- ============================================================================
-- 给已有表补充表/字段注释（供 DBeaver/pgAdmin 直接查看）
-- 说明：Hibernate @Comment 仅在建表时写入注释；对已存在的表需手工执行本脚本补注释
-- ============================================================================

-- repositories 仓库表
COMMENT ON TABLE repositories IS '仓库表：接入的代码仓库及其分析状态、画像与概览';
COMMENT ON COLUMN repositories.name                IS '仓库名称';
COMMENT ON COLUMN repositories.git_url             IS 'Git 仓库地址';
COMMENT ON COLUMN repositories.token_encrypted     IS '访问令牌（加密存储）';
COMMENT ON COLUMN repositories.repo_type           IS '仓库类型（GITHUB/GITLAB 等）';
COMMENT ON COLUMN repositories.branch              IS '分析使用的分支';
COMMENT ON COLUMN repositories.local_path          IS '本地克隆路径';
COMMENT ON COLUMN repositories.last_commit_hash    IS '最近一次分析的提交 hash';
COMMENT ON COLUMN repositories.last_sync_time      IS '最近一次同步/分析时间';
COMMENT ON COLUMN repositories.status              IS '状态：CREATED/CLONING/QUEUED/ANALYZING/READY/ERROR';
COMMENT ON COLUMN repositories.created_at          IS '创建时间';
COMMENT ON COLUMN repositories.profile             IS '仓库画像（多仓库场景快速定位用）';
COMMENT ON COLUMN repositories.overview            IS '项目概览文档';
COMMENT ON COLUMN repositories.url_path_identifier IS 'URL 路径标识（用于接口 URL 归属识别）';

-- chunks 方法块表
COMMENT ON TABLE chunks IS '方法块表：每个方法的结构化信息与搜索索引（位置/签名/注解/常量/异常/URL）';
COMMENT ON COLUMN chunks.repo_id          IS '所属仓库 ID';
COMMENT ON COLUMN chunks.full_method      IS '方法全限定签名（类名:方法名(参数)）';
COMMENT ON COLUMN chunks.class_name       IS '所属类全限定名';
COMMENT ON COLUMN chunks.package_name     IS '所属包名';
COMMENT ON COLUMN chunks.method_name      IS '方法名';
COMMENT ON COLUMN chunks.file_path        IS '源文件路径';
COMMENT ON COLUMN chunks.start_line       IS '方法起始行号';
COMMENT ON COLUMN chunks.end_line         IS '方法结束行号';
COMMENT ON COLUMN chunks.return_type      IS '方法返回类型';
COMMENT ON COLUMN chunks.access_flags     IS '方法访问修饰符（public/private/static 等）';
COMMENT ON COLUMN chunks.annotations      IS '方法注解列表';
COMMENT ON COLUMN chunks.parameters       IS '方法参数列表';
COMMENT ON COLUMN chunks.call_summary     IS '方法调用摘要（充实后的搜索文本：常量/注释/异常/URL 等）';
COMMENT ON COLUMN chunks.constants        IS '方法内字符串常量（换行分隔），用于调用链展示';
COMMENT ON COLUMN chunks.exceptions       IS '方法抛出/捕获的异常类型（短类名，换行分隔）';
COMMENT ON COLUMN chunks.resolved_urls    IS '解析后的外部调用 URL（@Value/config 数据流解析，换行分隔）';
COMMENT ON COLUMN chunks.error_codes      IS '业务错误码+消息（JSON 数组，来自 Result/throw 解析）';
COMMENT ON COLUMN chunks.jar_num          IS '所属 jar 序号（对应 jar_info.jar_num）';
COMMENT ON COLUMN chunks.method_hash      IS '方法内容哈希（用于增量/去重）';
COMMENT ON COLUMN chunks.embedding_status IS '向量索引状态：null=待处理, DONE=已完成, FAILED=失败';

-- call_graph 方法调用关系表
COMMENT ON TABLE call_graph IS '方法调用关系表：方法级调用图（谁调用谁），调用链分析核心数据';
COMMENT ON COLUMN call_graph.repo_id                    IS '所属仓库 ID';
COMMENT ON COLUMN call_graph.call_id                    IS 'javacg2 分配的调用序号';
COMMENT ON COLUMN call_graph.caller_method              IS '调用方方法全限定签名';
COMMENT ON COLUMN call_graph.callee_method              IS '被调用方方法全限定签名';
COMMENT ON COLUMN call_graph.call_type                  IS '调用类型：ITF(接口)/IMPL(实现)/STA(静态)/EXTENDS/IMPLEMENTS 等';
COMMENT ON COLUMN call_graph.line_number                IS '调用发生的源码行号';
COMMENT ON COLUMN call_graph.caller_return_type         IS '调用方方法返回类型';
COMMENT ON COLUMN call_graph.callee_obj_type            IS '被调用对象类型标识';
COMMENT ON COLUMN call_graph.callee_raw_return_type     IS '被调用方原始返回类型';
COMMENT ON COLUMN call_graph.callee_actual_return_type  IS '被调用方实际返回类型（泛型推断后）';
COMMENT ON COLUMN call_graph.caller_jar_num             IS '调用方所属 jar 序号';
COMMENT ON COLUMN call_graph.callee_jar_num             IS '被调用方所属 jar 序号';
COMMENT ON COLUMN call_graph.enabled                    IS '该调用边是否启用';

-- boundaries 边界点表
COMMENT ON TABLE boundaries IS '边界点表：方法触及的外部系统交互（DB/HTTP/gRPC/MQ/缓存/序列化）';
COMMENT ON COLUMN boundaries.repo_id       IS '所属仓库 ID';
COMMENT ON COLUMN boundaries.full_method   IS '触发该边界的方法全限定签名';
COMMENT ON COLUMN boundaries.boundary_type IS '边界类型：DB/HTTP/GRPC/MQ/CACHE/REDIS 等';
COMMENT ON COLUMN boundaries.line_number   IS '边界调用所在源码行号';
COMMENT ON COLUMN boundaries.context       IS '边界上下文（如 SQL 语句、URL、topic 名等）';
COMMENT ON COLUMN boundaries.callee_method IS '触发边界的被调用方法全限定签名';

-- api_endpoints 接口/入口点表
COMMENT ON TABLE api_endpoints IS '接口/入口点表：Controller HTTP 接口及 MQ/定时/gRPC 等入口';
COMMENT ON COLUMN api_endpoints.repo_id          IS '所属仓库 ID';
COMMENT ON COLUMN api_endpoints.endpoint_type    IS '入口类型：CONTROLLER/KAFKA/ROCKETMQ/SCHEDULED/GRPC 等';
COMMENT ON COLUMN api_endpoints.http_method      IS 'HTTP 方法：GET/POST/PUT/DELETE 等（仅 HTTP 接口有值）';
COMMENT ON COLUMN api_endpoints.url_path         IS '接口 URL 路径（仅 HTTP 接口有值）';
COMMENT ON COLUMN api_endpoints.full_method      IS '入口方法全限定签名';
COMMENT ON COLUMN api_endpoints.class_name       IS '入口所在类全限定名';
COMMENT ON COLUMN api_endpoints.annotation_class IS '识别该入口的注解类全限定名';
COMMENT ON COLUMN api_endpoints.sql_statement    IS '关联的 SQL 语句（若解析到）';
COMMENT ON COLUMN api_endpoints.table_names      IS '关联的数据库表名（逗号分隔）';

-- class_reference 类引用关系表
COMMENT ON TABLE class_reference IS '类引用关系表：源文件 import 解析出的类间引用（参数/返回/字段类型），供仓库拓扑图连线';
COMMENT ON COLUMN class_reference.repo_id      IS '所属仓库 ID';
COMMENT ON COLUMN class_reference.source_class IS '引用发起方类全限定名';
COMMENT ON COLUMN class_reference.target_class IS '被引用的目标类全限定名';

-- repo_config 仓库配置表
COMMENT ON TABLE repo_config IS '仓库配置表：每个仓库解析出的配置项（含来源与默认值）';
COMMENT ON COLUMN repo_config.repo_id       IS '所属仓库 ID';
COMMENT ON COLUMN repo_config.config_key    IS '配置项键名';
COMMENT ON COLUMN repo_config.config_value  IS '配置项当前值';
COMMENT ON COLUMN repo_config.source        IS '值来源：FILE=配置文件, DEFAULT=默认值, USER=用户自定义';
COMMENT ON COLUMN repo_config.default_value IS '默认值';
COMMENT ON COLUMN repo_config.updated_at    IS '更新时间';

-- system_config 系统配置表
COMMENT ON TABLE system_config IS '系统配置表：平台全局配置键值对（如 AI/Embedding 的 url、key）';
COMMENT ON COLUMN system_config.config_key   IS '配置键名（唯一）';
COMMENT ON COLUMN system_config.config_value IS '配置值';
COMMENT ON COLUMN system_config.updated_at   IS '更新时间';

-- mcp_servers MCP 服务器配置表
COMMENT ON TABLE mcp_servers IS 'MCP 服务器配置表：已接入的 MCP 服务及连接/测试状态';
COMMENT ON COLUMN mcp_servers.name              IS 'MCP 服务器名称';
COMMENT ON COLUMN mcp_servers.url               IS 'MCP 服务器地址';
COMMENT ON COLUMN mcp_servers.transport         IS '传输方式：SSE | STDIO';
COMMENT ON COLUMN mcp_servers.enabled           IS '是否启用';
COMMENT ON COLUMN mcp_servers.description       IS '描述';
COMMENT ON COLUMN mcp_servers.last_test_status  IS '最近一次连通性测试状态：OK | FAIL | UNTESTED';
COMMENT ON COLUMN mcp_servers.last_test_message IS '最近一次测试的返回信息';
COMMENT ON COLUMN mcp_servers.created_at        IS '创建时间';
COMMENT ON COLUMN mcp_servers.updated_at        IS '更新时间';

-- api_endpoints 新增 jar_num 字段 + 唯一约束变更（支持同方法在不同模块中各存一条）
-- 注意：需先删除旧约束再加新约束（ddl-auto=update 不会自动处理约束变更）
ALTER TABLE api_endpoints ADD COLUMN IF NOT EXISTS jar_num INTEGER;
COMMENT ON COLUMN api_endpoints.jar_num IS '所属 jar 序号，与 jar_info.jar_num 对应；纳入唯一键以支持同方法在不同模块中各存一条';
-- 删除旧约束（若存在），重建包含 jar_num 的新约束
ALTER TABLE api_endpoints DROP CONSTRAINT IF EXISTS uk_ep_repo_method;
ALTER TABLE api_endpoints DROP CONSTRAINT IF EXISTS uk_ep_repo_method_jar;
ALTER TABLE api_endpoints ADD CONSTRAINT uk_ep_repo_method_jar UNIQUE (repo_id, full_method, jar_num);
