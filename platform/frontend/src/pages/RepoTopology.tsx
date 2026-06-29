import { useEffect, useRef, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Spin, Tag, Drawer, Button, Empty, Tooltip, message, Badge } from 'antd';
import {
  ZoomInOutlined, ZoomOutOutlined, ExpandOutlined, ReloadOutlined, ApartmentOutlined,
  ArrowRightOutlined,
} from '@ant-design/icons';
import G6, { type Graph as GraphType } from '@antv/g6';
import {
  fetchTopology, fetchCrossRepoImpact,
  type TopologyDTO, type RepoNodeDTO, type HotMethod, type CrossRepoImpactDTO,
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

let registered = false;

function ensureRegistered() {
  if (registered) return;
  registered = true;

  G6.registerNode(
    'repo-node',
    {
      draw(cfg, group) {
        if (!cfg || !group) return {} as never;
        const name = (cfg.label as string) ?? '';
        const status = (cfg.status as string) ?? 'CREATED';
        const totalMethods = (cfg.totalMethods as number) ?? 0;
        const exposedMethods = (cfg.exposedMethods as number) ?? 0;
        const entryPoints = (cfg.entryPoints as number) ?? 0;
        const statusColor = STATUS_COLOR[status] ?? '#d9d9d9';

        // 外框
        const keyShape = group.addShape('rect', {
          attrs: {
            x: 0, y: 0,
            width: NODE_W, height: NODE_H,
            radius: 8,
            fill: '#fff',
            stroke: statusColor,
            lineWidth: 2,
            shadowColor: 'rgba(0,0,0,0.08)',
            shadowBlur: 6,
            shadowOffsetY: 2,
            cursor: 'pointer',
          },
          name: 'node-bg',
          draggable: true,
        });

        // 顶部色带
        group.addShape('rect', {
          attrs: {
            x: 0, y: 0,
            width: NODE_W, height: 6,
            radius: [8, 8, 0, 0],
            fill: statusColor,
          },
          name: 'status-bar',
        });

        // 仓库名（截断）
        const displayName = name.length > 18 ? name.slice(0, 17) + '…' : name;
        group.addShape('text', {
          attrs: {
            x: 10, y: 26,
            text: displayName,
            fontSize: 13,
            fontWeight: 600,
            fill: '#262626',
            textBaseline: 'middle',
            cursor: 'pointer',
          },
          name: 'repo-name',
        });

        // 状态标签
        group.addShape('text', {
          attrs: {
            x: NODE_W - 10, y: 26,
            text: STATUS_LABEL[status] ?? status,
            fontSize: 10,
            fill: statusColor,
            textAlign: 'right',
            textBaseline: 'middle',
          },
          name: 'status-text',
        });

        // 分割线
        group.addShape('line', {
          attrs: {
            x1: 10, y1: 36, x2: NODE_W - 10, y2: 36,
            stroke: '#f0f0f0', lineWidth: 1,
          },
          name: 'divider',
        });

        // 统计行：方法数 / 暴露 / 入口点
        const stats = [
          { label: '方法', value: totalMethods, color: '#595959' },
          { label: '暴露', value: exposedMethods, color: '#1890ff' },
          { label: '入口', value: entryPoints, color: '#52c41a' },
        ];
        const colW = NODE_W / 3;
        stats.forEach((s, i) => {
          const cx = colW * i + colW / 2;
          group.addShape('text', {
            attrs: {
              x: cx, y: 54,
              text: String(s.value),
              fontSize: 14,
              fontWeight: 600,
              fill: s.color,
              textAlign: 'center',
              textBaseline: 'middle',
            },
            name: `stat-val-${i}`,
          });
          group.addShape('text', {
            attrs: {
              x: cx, y: 70,
              text: s.label,
              fontSize: 10,
              fill: '#8c8c8c',
              textAlign: 'center',
              textBaseline: 'middle',
            },
            name: `stat-label-${i}`,
          });
        });

        return keyShape;
      },
      getAnchorPoints() {
        return [[0.5, 0], [0.5, 1], [0, 0.5], [1, 0.5]];
      },
    },
    'single-node',
  );
}

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

  const edges: G6EdgeData[] = topo.edges.map((e, i) => ({
    id: `edge-${i}`,
    source: `repo-${e.callerRepoId}`,
    target: `repo-${e.calleeRepoId}`,
    label: `${e.methodCount}个方法 · ${e.callCount}次`,
    callCount: e.callCount,
    methodCount: e.methodCount,
    callerRepoId: e.callerRepoId,
    calleeRepoId: e.calleeRepoId,
    hotMethods: e.hotMethods,
    callType: e.callType ?? null,
  }));

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

  // 方法影响分析（点击热点方法时触发）
  const [impactLoading, setImpactLoading] = useState(false);
  const [impactResult, setImpactResult] = useState<CrossRepoImpactDTO | null>(null);
  const [impactMethod, setImpactMethod] = useState<HotMethod | null>(null);
  const [selectedMethod, setSelectedMethod] = useState<HotMethod | null>(null);

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

  const doImpact = useCallback(async (method: HotMethod) => {
    setImpactMethod(method);
    setImpactLoading(true);
    setImpactResult(null);
    try {
      const result = await fetchCrossRepoImpact(method.fullMethod);
      setImpactResult(result);
    } catch (e: unknown) {
      message.error(e instanceof Error ? e.message : '影响分析失败');
    } finally {
      setImpactLoading(false);
    }
  }, []);

  // G6 图渲染
  useEffect(() => {
    if (!topo || !graphContainerRef.current) return;

    ensureRegistered();

    if (graphRef.current) {
      graphRef.current.destroy();
      graphRef.current = null;
    }

    const container = graphContainerRef.current;
    const width = container.clientWidth || 900;
    const height = container.clientHeight || 650;

    const minimap = new G6.Minimap({ size: [160, 100], type: 'keyShape' });

    const graph = new G6.Graph({
      container,
      width,
      height,
      fitView: true,
      fitViewPadding: [60, 60, 60, 60],
      animate: false,
      modes: {
        default: ['drag-canvas', 'zoom-canvas', 'drag-node'],
      },
      plugins: [minimap],
      defaultNode: {
        type: 'repo-node',
        size: [NODE_W, NODE_H],
        anchorPoints: [[0.5, 0], [0.5, 1], [0, 0.5], [1, 0.5]],
      },
      defaultEdge: {
        type: 'quadratic',
        style: {
          stroke: '#adc6ff',
          lineWidth: 2,
          opacity: 0.8,
          endArrow: {
            path: G6.Arrow.triangle(8, 10, 0),
            fill: '#adc6ff',
          },
        },
        labelCfg: {
          autoRotate: false,
          style: {
            fontSize: 11,
            fill: '#595959',
            background: {
              fill: '#fff',
              stroke: '#e8e8e8',
              padding: [3, 6],
              radius: 3,
            },
          },
        },
      },
      layout: {
        type: 'dagre',
        rankdir: 'LR',
        nodesep: 60,
        ranksep: 160,
        controlPoints: true,
      },
      nodeStateStyles: {
        active: {
          stroke: '#1890ff',
          lineWidth: 2.5,
          shadowColor: 'rgba(24,144,255,0.3)',
          shadowBlur: 10,
        },
      },
      edgeStateStyles: {
        active: {
          stroke: '#1890ff',
          lineWidth: 3,
          opacity: 1,
        },
      },
    });

    // 节点单击 → 进入调用链分析
    graph.on('node:click', (evt) => {
      const model = evt.item?.getModel() as G6NodeData | undefined;
      if (!model) return;
      navigate(`/callgraph?repoId=${model.repoId}`);
    });

    // 边单击 → 展示热点方法 Drawer
    graph.on('edge:click', (evt) => {
      const model = evt.item?.getModel() as G6EdgeData | undefined;
      if (!model) return;
      setSelectedEdge(model);
      setImpactResult(null);
      setImpactMethod(null);
      setSelectedMethod(null);
      setEdgeDrawerOpen(true);
    });

    // hover 高亮
    graph.on('node:mouseenter', (evt) => {
      if (evt.item) graph.setItemState(evt.item, 'active', true);
    });
    graph.on('node:mouseleave', (evt) => {
      if (evt.item) graph.setItemState(evt.item, 'active', false);
    });
    graph.on('edge:mouseenter', (evt) => {
      if (evt.item) graph.setItemState(evt.item, 'active', true);
    });
    graph.on('edge:mouseleave', (evt) => {
      if (evt.item) graph.setItemState(evt.item, 'active', false);
    });

    const { nodes, edges } = buildGraphData(topo);
    graph.data({ nodes, edges } as never);
    graph.render();
    graph.fitView();

    graphRef.current = graph;

    const onResize = () => {
      if (!graphRef.current || graphRef.current.get('destroyed')) return;
      graphRef.current.changeSize(container.clientWidth || 900, container.clientHeight || 650);
    };
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
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
            <Button size="small" icon={<ZoomInOutlined />} onClick={() => graphRef.current?.zoom(1.2)} />
          </Tooltip>
          <Tooltip title="缩小">
            <Button size="small" icon={<ZoomOutOutlined />} onClick={() => graphRef.current?.zoom(0.8)} />
          </Tooltip>
          <Tooltip title="适应画布">
            <Button size="small" icon={<ExpandOutlined />} onClick={() => graphRef.current?.fitView()} />
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
            <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 10 }}>
              <Spin size="large" tip="加载拓扑图..." />
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

      {/* 边点击 → 热点方法 + 影响分析 Drawer */}
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
        width={480}
        open={edgeDrawerOpen}
        onClose={() => { setEdgeDrawerOpen(false); setSelectedMethod(null); }}
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

            {/* 热点方法列表 */}
            <div style={{ fontWeight: 600, fontSize: 13, marginBottom: 10 }}>
              Top {selectedEdge.hotMethods.length} 热点方法
            </div>
            {selectedEdge.hotMethods.map((m, i) => (
              <div
                key={i}
                style={{
                  padding: '10px 12px', marginBottom: 8, borderRadius: 6, cursor: 'pointer',
                  background: selectedMethod?.fullMethod === m.fullMethod ? '#e6f7ff' : '#fafafa',
                  border: `1px solid ${selectedMethod?.fullMethod === m.fullMethod ? '#91d5ff' : '#f0f0f0'}`,
                  transition: 'all 0.2s',
                }}
                onClick={() => { setSelectedMethod(m); setImpactResult(null); setImpactMethod(null); }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
                  <Badge count={i + 1} style={{ backgroundColor: '#8c8c8c', fontSize: 10 }} />
                  {m.httpMethod && <Tag color="green" style={{ fontSize: 10, margin: 0 }}>{m.httpMethod}</Tag>}
                  {m.endpointType && m.endpointType !== 'CONTROLLER' && (
                    <Tag color="blue" style={{ fontSize: 10, margin: 0 }}>{m.endpointType}</Tag>
                  )}
                  <span style={{ fontSize: 12, fontFamily: 'monospace', fontWeight: 500, flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {m.shortName}
                  </span>
                  <span style={{ fontSize: 12, color: '#1890ff', fontWeight: 600, flexShrink: 0 }}>
                    {m.callCount}次
                  </span>
                </div>
                {m.urlPath && (
                  <div style={{ fontSize: 11, color: '#8c8c8c', fontFamily: 'monospace', paddingLeft: 20 }}>
                    {m.urlPath}
                  </div>
                )}
                <div style={{ fontSize: 10, color: '#bfbfbf', paddingLeft: 20, marginTop: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {m.fullMethod}
                </div>
              </div>
            ))}

            {/* 选中方法详情卡片 */}
            {selectedMethod && (
              <div style={{ marginTop: 16, padding: '12px 14px', background: '#f0f9ff', borderRadius: 8, border: '1px solid #bae0ff' }}>
                <div style={{ fontWeight: 600, fontSize: 13, marginBottom: 10, display: 'flex', alignItems: 'center', gap: 6 }}>
                  <span>方法详情</span>
                </div>
                <div style={{ fontSize: 12, marginBottom: 6 }}>
                  <span style={{ color: '#8c8c8c', marginRight: 8 }}>类型：</span>
                  {selectedMethod.endpointType ? (
                    <Tag color={selectedMethod.endpointType === 'CONTROLLER' ? 'green' : selectedMethod.endpointType === 'GRPC' ? 'blue' : 'orange'} style={{ fontSize: 11 }}>
                      {selectedMethod.endpointType}
                    </Tag>
                  ) : <span style={{ color: '#bfbfbf' }}>-</span>}
                  {selectedMethod.httpMethod && <Tag color="green" style={{ fontSize: 11, marginLeft: 4 }}>{selectedMethod.httpMethod}</Tag>}
                </div>
                {selectedMethod.urlPath && (
                  <div style={{ fontSize: 12, marginBottom: 6 }}>
                    <span style={{ color: '#8c8c8c', marginRight: 8 }}>路径：</span>
                    <span style={{ fontFamily: 'monospace', color: '#262626' }}>{selectedMethod.urlPath}</span>
                  </div>
                )}
                <div style={{ fontSize: 12, marginBottom: 12 }}>
                  <span style={{ color: '#8c8c8c', marginRight: 8 }}>调用次数：</span>
                  <span style={{ fontWeight: 600, color: '#1890ff' }}>{selectedMethod.callCount.toLocaleString()}</span>
                </div>
                <div style={{ display: 'flex', gap: 8 }}>
                  <Button
                    size="small"
                    type="primary"
                    onClick={() => doImpact(selectedMethod)}
                    loading={impactLoading}
                  >
                    影响分析
                  </Button>
                  <Button
                    size="small"
                    onClick={() => window.open(`/callgraph?repoId=${selectedEdge.calleeRepoId}&entry=${encodeURIComponent(selectedMethod.fullMethod)}`, '_blank')}
                  >
                    查看调用链 ↗
                  </Button>
                </div>
              </div>
            )}

            {/* 影响分析结果 */}
            {impactMethod && (
              <div style={{ marginTop: 16 }}>
                <div style={{ fontWeight: 600, fontSize: 13, marginBottom: 8, display: 'flex', alignItems: 'center', gap: 6 }}>
                  <span>影响分析</span>
                  <Tag style={{ fontSize: 11, fontFamily: 'monospace', fontWeight: 400 }}>{impactMethod.shortName}</Tag>
                </div>

                {impactLoading ? (
                  <div style={{ textAlign: 'center', padding: 20 }}><Spin /></div>
                ) : impactResult ? (
                  <div>
                    <div style={{ display: 'flex', gap: 16, marginBottom: 12, fontSize: 12 }}>
                      <span>调用方: <strong>{impactResult.totalCallers}</strong></span>
                      <span>受影响仓库: <strong>{impactResult.affectedRepoCount}</strong></span>
                      <span>受影响入口点: <strong>{impactResult.affectedEndpointCount}</strong></span>
                    </div>

                    {impactResult.repoGroups.map(group => (
                      <div key={group.repoId} style={{ marginBottom: 12, padding: '8px 10px', background: '#fafafa', borderRadius: 6 }}>
                        <div style={{ fontWeight: 600, marginBottom: 6, display: 'flex', alignItems: 'center', gap: 6 }}>
                          <Tag color="blue" style={{ fontSize: 11 }}>{group.repoName}</Tag>
                          <span style={{ fontSize: 11, color: '#8c8c8c' }}>{group.callers.length} 个调用方</span>
                        </div>
                        {group.endpoints.length > 0 && (
                          <div style={{ marginBottom: 6 }}>
                            {group.endpoints.map((ep, i) => (
                              <div key={i} style={{ fontSize: 11, padding: '2px 8px', background: '#f6ffed', borderRadius: 4, marginBottom: 2, borderLeft: '2px solid #52c41a' }}>
                                {ep.httpMethod && <Tag color="green" style={{ fontSize: 10 }}>{ep.httpMethod}</Tag>}
                                <span style={{ fontFamily: 'monospace' }}>{ep.urlPath ?? ep.shortRef}</span>
                              </div>
                            ))}
                          </div>
                        )}
                        {group.callers.slice(0, 3).map((c, i) => (
                          <div key={i} style={{ fontSize: 11, color: '#595959', fontFamily: 'monospace', paddingLeft: 8 }}>
                            {'  '.repeat(Math.min(c.depth - 1, 3))}↳ {c.shortRef}
                          </div>
                        ))}
                        {group.callers.length > 3 && (
                          <div style={{ fontSize: 11, color: '#999', paddingLeft: 8 }}>...还有 {group.callers.length - 3} 个</div>
                        )}
                      </div>
                    ))}

                    {impactResult.truncated && (
                      <div style={{ padding: '6px 10px', background: '#fffbe6', borderRadius: 4, fontSize: 11, color: '#ad6800', marginTop: 8 }}>
                        ⚠️ 结果已截断（超过 800 个调用方）
                      </div>
                    )}
                  </div>
                ) : null}
              </div>
            )}
          </div>
        )}
      </Drawer>
    </div>
  );
}
