-- ============================================================================
-- 回滚脚本：删除固化到 DB 的分析数据表
-- 对应 2026xx_add_analysis_db_tables.sql
-- ============================================================================

DROP TABLE IF EXISTS jar_info;
DROP TABLE IF EXISTS enum_constant;
DROP TABLE IF EXISTS static_field_usage;
DROP TABLE IF EXISTS method_return_const;
DROP TABLE IF EXISTS field_constant;
