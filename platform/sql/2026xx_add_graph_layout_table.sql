-- ============================================================================
-- 仓库拓扑图布局缓存表
-- 分析完成后由后端自动计算（径向分包布局），所有用户共享同一份坐标数据。
-- computed_at 作为版本号，前端通过对比版本决定是否刷新本地渲染。
-- 重新分析时先 DELETE 再重算（BytecodeAnalyzerImpl 中触发）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS graph_layout (
    id          BIGSERIAL PRIMARY KEY,
    repo_id     BIGINT        NOT NULL,
    class_name  VARCHAR(500)  NOT NULL,
    x           DOUBLE PRECISION NOT NULL,
    y           DOUBLE PRECISION NOT NULL,
    computed_at TIMESTAMP     NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_gl_repo       ON graph_layout (repo_id);
CREATE INDEX IF NOT EXISTS idx_gl_repo_class ON graph_layout (repo_id, class_name);

COMMENT ON TABLE  graph_layout             IS '仓库拓扑图布局缓存：后端分析完自动计算节点坐标，所有用户共享，重新分析时自动清除重算';
COMMENT ON COLUMN graph_layout.repo_id     IS '所属仓库 ID';
COMMENT ON COLUMN graph_layout.class_name  IS '节点对应的类全限定名';
COMMENT ON COLUMN graph_layout.x           IS '节点 x 坐标（像素，相对 1200×800 画布）';
COMMENT ON COLUMN graph_layout.y           IS '节点 y 坐标（像素，相对 1200×800 画布）';
COMMENT ON COLUMN graph_layout.computed_at IS '布局计算时间（版本号），与分析完成时间对齐，前端用于判断是否需刷新';
