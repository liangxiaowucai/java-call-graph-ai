-- ============================================================================
-- 将查询时仍读中间文件的数据固化到 PostgreSQL
-- 中间文件（data/analysis）退化为一次性暂存，导入后即清理
-- 说明：应用默认 Hibernate ddl-auto 会自动建表；本脚本用于手工建库/回滚留档（红线要求）
-- ============================================================================

-- jar 模块信息（jarNum → 显示名，来自 jar_info.txt）
CREATE TABLE IF NOT EXISTS jar_info (
    id       BIGSERIAL PRIMARY KEY,
    repo_id  BIGINT       NOT NULL,
    jar_num  INTEGER      NOT NULL,
    jar_name VARCHAR(500) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_jarinfo_repo ON jar_info (repo_id);

-- 枚举常量定义（聚合 code/description，来自 enum_init_assign_info.txt）
CREATE TABLE IF NOT EXISTS enum_constant (
    id          BIGSERIAL PRIMARY KEY,
    repo_id     BIGINT        NOT NULL,
    enum_class  VARCHAR(500)  NOT NULL,
    const_name  VARCHAR(255)  NOT NULL,
    ordinal     VARCHAR(50),
    code        VARCHAR(255),
    description VARCHAR(1000)
);
CREATE INDEX IF NOT EXISTS idx_enumconst_repo       ON enum_constant (repo_id);
CREATE INDEX IF NOT EXISTS idx_enumconst_repo_class ON enum_constant (repo_id, enum_class);

-- 方法用到的静态字段/枚举引用（来自 method_call_static_field.txt）
CREATE TABLE IF NOT EXISTS static_field_usage (
    id            BIGSERIAL PRIMARY KEY,
    repo_id       BIGINT        NOT NULL,
    caller_method VARCHAR(1000) NOT NULL,
    field_class   VARCHAR(500)  NOT NULL,
    field_name    VARCHAR(255)  NOT NULL,
    line_num      INTEGER
);
CREATE INDEX IF NOT EXISTS idx_sfu_repo        ON static_field_usage (repo_id);
CREATE INDEX IF NOT EXISTS idx_sfu_repo_caller ON static_field_usage (repo_id, caller_method);

-- 方法返回的常量值（来自 method_return_const_value.txt）
CREATE TABLE IF NOT EXISTS method_return_const (
    id          BIGSERIAL PRIMARY KEY,
    repo_id     BIGINT        NOT NULL,
    full_method VARCHAR(1000) NOT NULL,
    value       VARCHAR(2000)
);
CREATE INDEX IF NOT EXISTS idx_mrc_repo        ON method_return_const (repo_id);
CREATE INDEX IF NOT EXISTS idx_mrc_repo_method ON method_return_const (repo_id, full_method);

-- static final 常量字段（来自 field_info.txt）
CREATE TABLE IF NOT EXISTS field_constant (
    id         BIGSERIAL PRIMARY KEY,
    repo_id    BIGINT        NOT NULL,
    class_name VARCHAR(500)  NOT NULL,
    field_name VARCHAR(255)  NOT NULL,
    field_type VARCHAR(500),
    value      VARCHAR(2000)
);
CREATE INDEX IF NOT EXISTS idx_fieldconst_repo ON field_constant (repo_id);

-- ============================================================================
-- 表与字段注释（供 DBeaver/pgAdmin 等直接查看）
-- ============================================================================

COMMENT ON TABLE jar_info IS 'jar 模块信息表：jarNum→模块显示名，来自 javacg2 中间文件 jar_info.txt，供目录树展示模块名';
COMMENT ON COLUMN jar_info.repo_id  IS '所属仓库 ID';
COMMENT ON COLUMN jar_info.jar_num  IS 'jar 序号（javacg2 分配，与方法/类记录的 jarNum 对应）';
COMMENT ON COLUMN jar_info.jar_name IS '模块显示名（去掉 .jar/.war 扩展名的文件名）';

COMMENT ON TABLE enum_constant IS '枚举常量定义表：聚合后的枚举 code/描述，来自 enum_init_assign_info.txt';
COMMENT ON COLUMN enum_constant.repo_id     IS '所属仓库 ID';
COMMENT ON COLUMN enum_constant.enum_class  IS '枚举类全限定名';
COMMENT ON COLUMN enum_constant.const_name  IS '枚举常量名（如 PAID、REFUNDED）';
COMMENT ON COLUMN enum_constant.ordinal     IS '枚举序号（当无业务 code 时回退使用）';
COMMENT ON COLUMN enum_constant.code        IS '业务 code 值（取首个整型构造参数）';
COMMENT ON COLUMN enum_constant.description IS '中文描述（取首个长度>=2 的字符串构造参数）';

COMMENT ON TABLE static_field_usage IS '静态字段/枚举引用表：某方法用到的静态字段（含枚举常量），来自 method_call_static_field.txt';
COMMENT ON COLUMN static_field_usage.repo_id       IS '所属仓库 ID';
COMMENT ON COLUMN static_field_usage.caller_method IS '引用该字段的方法全限定签名';
COMMENT ON COLUMN static_field_usage.field_class   IS '被引用静态字段所属的类全限定名';
COMMENT ON COLUMN static_field_usage.field_name    IS '被引用的静态字段/枚举常量名';
COMMENT ON COLUMN static_field_usage.line_num      IS '引用发生的源码行号';

COMMENT ON TABLE method_return_const IS '方法返回常量表：方法直接 return 的常量值，来自 method_return_const_value.txt';
COMMENT ON COLUMN method_return_const.repo_id     IS '所属仓库 ID';
COMMENT ON COLUMN method_return_const.full_method IS '方法全限定签名（类名:方法名(参数)）';
COMMENT ON COLUMN method_return_const.value       IS '方法返回的常量值';

COMMENT ON TABLE field_constant IS '常量字段表：类的 static final 常量字段，来自 field_info.txt';
COMMENT ON COLUMN field_constant.repo_id    IS '所属仓库 ID';
COMMENT ON COLUMN field_constant.class_name IS '常量所属类的全限定名';
COMMENT ON COLUMN field_constant.field_name IS '常量字段名';
COMMENT ON COLUMN field_constant.field_type IS '常量字段类型';
COMMENT ON COLUMN field_constant.value      IS '常量值（当前占位，javacg2 未提供具体值时为空）';
