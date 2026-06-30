import { useEffect, useRef, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Tag, Drawer, Button, Empty, Tooltip, message, Spin } from 'antd';
import {
  ZoomInOutlined, ZoomOutOutlined, ExpandOutlined, ReloadOutlined, ApartmentOutlined,
  ArrowRightOutlined,
} from '@ant-design/icons';
import { Graph } from '@antv/g6';
type GraphType = InstanceType<typeof Graph>;
import {
  fetchTopology,
  type TopologyDTO, type RepoNodeDTO, type HotMethod,
} from '../api';

// ── 常量 ─────────────────────────────────────────────────────────────────────

const STATUS_COLOR: Record<string, string> = {
  ANALYZED: '#52c41a',
  READY: '#1890ff',
  ANALYZING: '#faad14',
  CREATED: '#d9d9d9',
  ERROR: '#ff4d4f',
};

const STATUS_LABEL: Record<string, string> = {
  ANALYZED: '已分析',
  READY: '就绪',
  ANALYZING: '分析中',
  CREATED: '待分析',
  ERROR: '异常',
};

// 节点尺寸
const NODE_W = 200;
const NODE_H = 80;

// ── 注册 G6 自定义节点（repo-node） ──────────────────────────────────────────

// ─── G6 v5: graph rendering via useEffect below ─────────────────────────────
// (no registerNode needed in v5; rect node used with style function)

// ── 数据转换 ──────────────────────────────────────────────────────────────────

interface G6NodeData {
  id: string;
  label: string;
  type: 'repo-node';
  repoId: number;
  status: string;
  totalMethods: number;
  exposedMethods: number;
  entryPoints: number;
  x?: number;
  y?: number;
}

interface G6EdgeData {
  id: string;
  source: string;
  target: string;
  label: string;
  callCount: number;
  methodCount: number;
  callerRepoId: number;
  calleeRepoId: number;
  hotMethods: HotMethod[];
  callType: string | null;
  style?: Record<string, unknown>;
}

function buildGraphData(topo: TopologyDTO): { nodes: G6NodeData[]; edges: G6EdgeData[] } {
  const nodes: G6NodeData[] = topo.repos.map(r => ({
    id: `repo-${r.repoId}`,
    label: r.name,
    type: 'repo-node',
    repoId: r.repoId,
    status: r.status,
    totalMethods: r.totalMethods,
    exposedMethods: r.exposedMethods,
    entryPoints: r.entryPoints,
  }));

  const nodeIds = new Set(nodes.map(n => n.id));
  const seenPairs = new Set<string>();

  // 先收集所有有效边
  const validEdges = topo.edges.filter(e => {
    const src = `repo-${e.callerRepoId}`;
    const tgt = `repo-${e.calleeRepoId}`;
    const key = `${src}|${tgt}`;
    if (!nodeIds.has(src) || !nodeIds.has(tgt)) {
      console.warn('[Topology] dropped edge with missing node:', src, '->', tgt);
      return false;
    }
    if (seenPairs.has(key)) {
      console.warn('[Topology] dropped duplicate edge:', key);
      return false;
    }
    seenPairs.add(key);
    return true;
  });

  // 识别双向对（A→B 且 B→A 同时存在），在数据阶段直接注入 curveOffset。
  // 必须在数据里设置而非 post-render updateItem，因为 cubic-horizontal 不响应后者。
  const builtPairSet = new Set(validEdges.map(e => `repo-${e.callerRepoId}|repo-${e.calleeRepoId}`));

  const edges: G6EdgeData[] = validEdges.map((e, i) => {
    const src = `repo-${e.callerRepoId}`;
    const tgt = `repo-${e.calleeRepoId}`;
    const isBidirectional = builtPairSet.has(`${tgt}|${src}`);
    // 双向对按 id 大小固定偏移方向，两条边各弯向一侧，避免重叠
    const curveOffset = isBidirectional ? (src < tgt ? 60 : -60) : 0;
    return {
      id: `edge-${i}`,
      source: src,
      target: tgt,
      label: `${e.methodCount}个方法 · ${e.callCount}次`,
      callCount: e.callCount,
      methodCount: e.methodCount,
      callerRepoId: e.callerRepoId,
      calleeRepoId: e.calleeRepoId,
      hotMethods: e.hotMethods,
      callType: e.callType ?? null,
      style: { curveOffset },
    };
  });

  return { nodes, edges };
}

// ── 组件 ─────────────────────────────────────────────────────────────────────

export default function RepoTopology() {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [topo, setTopo] = useState<TopologyDTO | null>(null);

  // 边详情 Drawer（点击边时展示热点方法）
  const [edgeDrawerOpen, setEdgeDrawerOpen] = useState(false);
  const [selectedEdge, setSelectedEdge] = useState<G6EdgeData | null>(null);

  // 方法选中状态


  const graphContainerRef = useRef<HTMLDivElement>(null);
  const graphRef = useRef<GraphType | null>(null);

  const loadTopology = useCallback(async () => {
    setLoading(true);
    try {
      const data = await fetchTopology();
      setTopo(data);
    } catch (e: unknown) {
      message.error(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadTopology(); }, [loadTopology]);

  // G6 v5 图渲染
  useEffect(() => {
    if (!topo || !graphContainerRef.current) return;

    if (graphRef.current) {
      try { (graphRef.current as GraphType).destroy(); } catch { /* */ }
      graphRef.current = null;
    }

    const container = graphContainerRef.current;
    const width = container.clientWidth || 900;
    const height = container.clientHeight || 650;

    const { nodes, edges } = buildGraphData(topo);
    // Build an edge lookup map for click handler
    const edgesMap = new Map(edges.map(e => [e.id, e]));

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const graph = new (Graph as any)({
      container, width, height,
      autoFit: 'view',
      data: {
        nodes: nodes.map(n => {
          const shortName = n.label.length > 16 ? n.label.slice(0, 15) + '…' : n.label;
          const statsLine = `${n.totalMethods}方法 · ${n.exposedMethods}暴露 · ${n.entryPoints}入口`;
          return {
            id: n.id,
            data: { repoId: n.repoId, status: n.status, label: n.label,
                    totalMethods: n.totalMethods, exposedMethods: n.exposedMethods, entryPoints: n.entryPoints },
            style: {
              fill: '#ffffff',
              stroke: STATUS_COLOR[n.status] ?? '#d9d9d9',
              lineWidth: 2,
              labelText: shortName,
              labelFill: '#262626',
              labelFontSize: 13,
              labelFontWeight: 700,
              labelPlacement: 'center' as const,
              cursor: 'pointer',
              // badge 用于展示统计信息
              badges: [
                { text: statsLine, placement: 'bottom' as const,
                  backgroundFill: '#f0f5ff', backgroundStroke: '#adc6ff',
                  fill: '#2f54eb', fontSize: 10, padding: [2, 6] },
              ],
            },
          };
        }),
        edges: edges.map(e => ({
          id: e.id, source: e.source, target: e.target,
          data: { callerRepoId: e.callerRepoId, calleeRepoId: e.calleeRepoId,
                  callCount: e.callCount, methodCount: e.methodCount,
                  hotMethods: e.hotMethods, callType: e.callType },
          style: {
            stroke: '#adc6ff', lineWidth: 2, opacity: 0.9,
            endArrow: true, endArrowSize: 8,
            curveOffset: (e.style as { curveOffset?: number } | undefined)?.curveOffset ?? 0,
            labelText: `${e.methodCount}个方法 · ${e.callCount}次`,
            labelFill: '#262626', labelFontSize: 12,
            labelBackground: true, labelBackgroundFill: '#fff',
            labelBackgroundRadius: 4,
          },
        })),
      },
      node: {
        type: 'rect',
        style: {
          width: NODE_W,
          height: NODE_H,
          radius: 8,
          fill: '#ffffff',
          stroke: '#d9d9d9',
          lineWidth: 2,
        },
      },
      layout: { type: 'dagre', rankdir: 'LR', nodesep: 80, ranksep: 220 },
      behaviors: ['drag-canvas', 'zoom-canvas', 'drag-element'],
      plugins: [{ type: 'minimap', size: [160, 100] }],
    });

    graph.on('node:click', (evt: { itemId?: string }) => {
      const nodeId = evt.itemId;
      if (!nodeId) return;
      const repoId = Number(nodeId.replace('repo-', ''));
      if (!isNaN(repoId)) navigate(`/callgraph?repoId=${repoId}`);
    });

    graph.on('edge:click', (evt: { itemId?: string }) => {
      const edgeId = evt.itemId;
      if (!edgeId) return;
      const edge = edgesMap.get(edgeId);
      if (edge) { setSelectedEdge(edge); setEdgeDrawerOpen(true); }
    });

    graph.render().catch(console.warn);
    graphRef.current = graph as GraphType;

    const resizeObs = new ResizeObserver(() => {
      if (!graphRef.current) return;
      try { (graphRef.current as any).setSize?.([container.clientWidth || 900, container.clientHeight || 650]); } catch { /* */ }
    });
    resizeObs.observe(container);
    return () => resizeObs.disconnect();
  }, [topo, navigate]);

  const repoName = useCallback((repoId: number) =>
    topo?.repos.find(r => r.repoId === repoId)?.name ?? `repo-${repoId}`,
  [topo]);

  const totalEdges = topo?.edges.reduce((s, e) => s + e.callCount, 0) ?? 0;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden' }}>
      {/* 工具栏 */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: 8, padding: '8px 12px',
        borderBottom: '1px solid #f0f0f0', flexShrink: 0,
      }}>
        <ApartmentOutlined style={{ fontSize: 16, color: '#1890ff' }} />
        <span style={{ fontWeight: 600, fontSize: 14 }}>仓库拓扑图</span>
        {topo && (
          <span style={{ fontSize: 12, color: '#8c8c8c', marginLeft: 8 }}>
            {topo.repos.length} 个仓库 · {topo.edges.length} 条调用链路 · {totalEdges.toLocaleString()} 次跨库调用
          </span>
        )}
        <div style={{ marginLeft: 'auto', display: 'flex', gap: 4 }}>
          <Tooltip title="放大">
            <Button size="small" icon={<ZoomInOutlined />} onClick={() => (graphRef.current as any)?.zoom?.(1.2)} />
          </Tooltip>
          <Tooltip title="缩小">
            <Button size="small" icon={<ZoomOutOutlined />} onClick={() => (graphRef.current as any)?.zoom?.(0.8)} />
          </Tooltip>
          <Tooltip title="适应画布">
            <Button size="small" icon={<ExpandOutlined />} onClick={() => (graphRef.current as any)?.fitView?.()} />
          </Tooltip>
          <Tooltip title="刷新">
            <Button size="small" icon={<ReloadOutlined />} onClick={loadTopology} loading={loading} />
          </Tooltip>
        </div>
      </div>

      {/* 主体 */}
      <div style={{ flex: 1, display: 'flex', overflow: 'hidden' }}>
        {/* 左侧仓库列表 */}
        <div style={{
          width: 200, flexShrink: 0, borderRight: '1px solid #f0f0f0',
          overflow: 'auto', padding: '8px 0',
        }}>
          {loading ? (
            <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>
          ) : !topo || topo.repos.length === 0 ? (
            <Empty description="暂无仓库" image={Empty.PRESENTED_IMAGE_SIMPLE} style={{ marginTop: 40 }} />
          ) : (
            topo.repos.map((repo: RepoNodeDTO) => (
              <div
                key={repo.repoId}
                style={{ padding: '8px 12px', cursor: 'pointer', borderBottom: '1px solid #fafafa' }}
                onMouseEnter={e => (e.currentTarget.style.background = '#f5f5f5')}
                onMouseLeave={e => (e.currentTarget.style.background = '')}
                onClick={() => navigate(`/callgraph?repoId=${repo.repoId}`)}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                  <span style={{
                    width: 8, height: 8, borderRadius: '50%',
                    background: STATUS_COLOR[repo.status] ?? '#d9d9d9',
                    flexShrink: 0, display: 'inline-block',
                  }} />
                  <span style={{
                    fontWeight: 500, fontSize: 12, flex: 1, minWidth: 0,
                    overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                  }}>
                    {repo.name}
                  </span>
                </div>
                <div style={{ fontSize: 11, color: '#8c8c8c', marginTop: 2, paddingLeft: 14 }}>
                  {STATUS_LABEL[repo.status] ?? repo.status}
                  {repo.exposedMethods > 0 && ` · ${repo.exposedMethods} 暴露`}
                </div>
              </div>
            ))
          )}
        </div>

        {/* G6 画布 */}
        <div style={{ flex: 1, position: 'relative', overflow: 'hidden' }}>
          {loading && (
            <div style={{ position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', zIndex: 10, gap: 12 }}>
              <Spin size="large" />
              <span style={{ color: '#8c8c8c', fontSize: 13 }}>加载拓扑图...</span>
            </div>
          )}
          {!loading && (!topo || topo.repos.length === 0) && (
            <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              <Empty description="请先分析仓库" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            </div>
          )}
          <div ref={graphContainerRef} style={{ width: '100%', height: '100%' }} />

          {/* 图例 */}
          {topo && topo.repos.length > 0 && (
            <div style={{
              position: 'absolute', bottom: 12, left: 12,
              background: 'rgba(255,255,255,0.92)', borderRadius: 6,
              padding: '8px 14px', fontSize: 11, color: '#595959',
              boxShadow: '0 2px 8px rgba(0,0,0,0.08)', zIndex: 10,
              display: 'flex', gap: 12, flexWrap: 'wrap',
            }}>
              <span>单击节点 → 进入调用链分析</span>
              <span>单击边 → 查看跨库热点方法</span>
              {Object.entries(STATUS_COLOR).slice(0, 3).map(([k, c]) => (
                <span key={k} style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                  <span style={{ width: 8, height: 8, borderRadius: '50%', background: c, display: 'inline-block' }} />
                  {STATUS_LABEL[k] ?? k}
                </span>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* 边点击 → 热点方法 Drawer */}
      <Drawer
        title={
          selectedEdge && topo ? (
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13 }}>
              <Tag color="blue">{repoName(selectedEdge.callerRepoId)}</Tag>
              <ArrowRightOutlined style={{ color: '#8c8c8c' }} />
              <Tag color="green">{repoName(selectedEdge.calleeRepoId)}</Tag>
              {selectedEdge.callType && (
                <Tag color={
                  selectedEdge.callType === 'HTTP' ? 'green' :
                  selectedEdge.callType === 'MQ' ? 'orange' :
                  selectedEdge.callType === 'MIXED' ? 'purple' : 'blue'
                } style={{ fontSize: 10 }}>{selectedEdge.callType}</Tag>
              )}
            </div>
          ) : '跨库调用详情'
        }
        placement="right"
        width={520}
        open={edgeDrawerOpen}
        onClose={() => { setEdgeDrawerOpen(false); }}
      >
        {selectedEdge && (
          <div>
            {/* 聚合统计 */}
            <div style={{ display: 'flex', gap: 20, marginBottom: 16, padding: '10px 14px', background: '#f5f5f5', borderRadius: 6 }}>
              <div style={{ textAlign: 'center' }}>
                <div style={{ fontSize: 20, fontWeight: 700, color: '#1890ff' }}>{selectedEdge.callCount.toLocaleString()}</div>
                <div style={{ fontSize: 11, color: '#8c8c8c' }}>调用次数</div>
              </div>
              <div style={{ textAlign: 'center' }}>
                <div style={{ fontSize: 20, fontWeight: 700, color: '#52c41a' }}>{selectedEdge.methodCount}</div>
                <div style={{ fontSize: 11, color: '#8c8c8c' }}>涉及方法</div>
              </div>
            </div>

            {/* 热点方法列表：每行末尾直接放「详情 ↗」按钮 */}
            <div style={{ fontWeight: 600, fontSize: 13, marginBottom: 10 }}>
              全部方法（{selectedEdge.hotMethods.length} 个）
            </div>
            {selectedEdge.hotMethods.map((m, i) => (
              <div
                key={i}
                style={{
                  display: 'flex', alignItems: 'center', gap: 8,
                  padding: '8px 10px', marginBottom: 6, borderRadius: 6,
                  background: '#fafafa', border: '1px solid #f0f0f0',
                }}
              >
                {/* 序号 */}
                <span style={{ fontSize: 11, color: '#bfbfbf', width: 18, flexShrink: 0, textAlign: 'right' }}>{i + 1}</span>

                {/* 方法信息 */}
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 4, marginBottom: 2 }}>
                    {m.httpMethod && <Tag color="green" style={{ fontSize: 10, margin: 0, padding: '0 4px' }}>{m.httpMethod}</Tag>}
                    {m.endpointType && m.endpointType !== 'CONTROLLER' && (
                      <Tag color="blue" style={{ fontSize: 10, margin: 0, padding: '0 4px' }}>{m.endpointType}</Tag>
                    )}
                    <span style={{ fontSize: 12, fontFamily: 'monospace', fontWeight: 500, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {m.shortName}
                    </span>
                  </div>
                  {m.urlPath && (
                    <div style={{ fontSize: 11, color: '#8c8c8c', fontFamily: 'monospace', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {m.urlPath}
                    </div>
                  )}
                </div>

                {/* 调用次数 */}
                <span style={{ fontSize: 12, color: '#1890ff', fontWeight: 600, flexShrink: 0 }}>{m.callCount}次</span>

                {/* 详情按钮 → 影响分析 */}
                <Button
                  size="small"
                  type="link"
                  style={{ flexShrink: 0, padding: '0 4px', fontSize: 12 }}
                  onClick={() => {
                    const url = `/impact?method=${encodeURIComponent(m.fullMethod)}&repoId=${selectedEdge.calleeRepoId}`;
                    const a = document.createElement('a');
                    a.href = url;
                    a.target = '_blank';
                    a.rel = 'noopener noreferrer';
                    document.body.appendChild(a);
                    a.click();
                    document.body.removeChild(a);
                  }}
                >
                  详情 ↗
                </Button>
              </div>
            ))}
          </div>
        )}
      </Drawer>
    </div>
  );
}
