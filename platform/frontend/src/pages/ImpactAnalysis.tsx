import { useEffect, useRef, useState, useCallback } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { Spin, Tag, Empty, message, Button, Tooltip } from 'antd';
import {
  ArrowLeftOutlined, ReloadOutlined, ZoomInOutlined, ZoomOutOutlined, ExpandOutlined,
  ThunderboltOutlined, ApiOutlined,
} from '@ant-design/icons';
import { Graph } from '@antv/g6';
type GraphType = InstanceType<typeof Graph>;
import { fetchUpstreamTree, type UpstreamTreeNodeDTO, type UpstreamTreeDTO } from '../api';

// ── 颜色 ─────────────────────────────────────────────────────────────────────

const ENDPOINT_COLOR: Record<string, string> = {
  HTTP:       '#52c41a',
  GRPC:       '#722ed1',
  MQ:         '#fa8c16',
  SCHEDULE:   '#13c2c2',
  DUBBO:      '#2f54eb',
};
const ENDPOINT_DEFAULT = '#1890ff';

const REPO_PALETTE = [
  '#1890ff', '#52c41a', '#fa8c16', '#722ed1', '#13c2c2',
  '#eb2f96', '#faad14', '#a0d911', '#2f54eb', '#fa541c',
];
const repoColorMap = new Map<number, string>();
let colorIdx = 0;
function repoColor(repoId: number | null): string {
  if (repoId == null) return '#8c8c8c';
  if (!repoColorMap.has(repoId)) {
    repoColorMap.set(repoId, REPO_PALETTE[colorIdx % REPO_PALETTE.length]);
    colorIdx++;
  }
  return repoColorMap.get(repoId)!;
}

// ── 节点尺寸 ─────────────────────────────────────────────────────────────────

const NODE_W = 180;
const NODE_H = 52;

// ── G6 节点注册 ───────────────────────────────────────────────────────────────

// (G6 v5 uses built-in node types — no manual registerNode needed)

interface UpstreamNodeModel {
  id: string;
  label: string;
  fullMethod: string;
  shortRef: string;
  repoId: number | null;
  repoName: string | null;
  isRoot: boolean;
  isEndpoint: boolean;
  endpointType: string | null;
  httpMethod: string | null;
  urlPath: string | null;
  isRecursive: boolean;
}

// (ensureRegistered removed — G6 v5 uses built-in rect nodes with style functions)

// ── 树 → G6 扁平数据 ──────────────────────────────────────────────────────────

interface G6NodeData {
  id: string;
  label: string;
  type: 'impact-node';
  [key: string]: unknown;
}

interface G6EdgeData {
  id: string;
  source: string;
  target: string;
}

function flattenTree(root: UpstreamTreeNodeDTO): { nodes: G6NodeData[]; edges: G6EdgeData[] } {
  const nodes: G6NodeData[] = [];
  const edges: G6EdgeData[] = [];
  let edgeIdx = 0;

  function walk(node: UpstreamTreeNodeDTO, isRoot: boolean) {
    nodes.push({
      id: node.id,
      label: node.shortRef,
      type: 'impact-node',
      fullMethod: node.fullMethod,
      shortRef: node.shortRef,
      repoId: node.repoId,
      repoName: node.repoName,
      isRoot,
      isEndpoint: node.isEndpoint,
      endpointType: node.endpointType,
      httpMethod: node.httpMethod,
      urlPath: node.urlPath,
      isRecursive: node.isRecursive,
    });

    for (const caller of node.callers) {
      walk(caller, false);
      // 方向：caller → root（上游调用者指向被调用者）
      edges.push({
        id: `e-${edgeIdx++}`,
        source: caller.id,
        target: node.id,
      });
    }
  }

  walk(root, true);
  return { nodes, edges };
}

// ── 找径向布局中心节点（连接度最高，即 root） ─────────────────────────────────

// ── 组件 ─────────────────────────────────────────────────────────────────────

export default function ImpactAnalysis() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const targetMethod = searchParams.get('method') ?? '';
  const repoIdParam = searchParams.get('repoId') ? Number(searchParams.get('repoId')) : null;

  const [loading, setLoading] = useState(false);
  const [treeData, setTreeData] = useState<UpstreamTreeDTO | null>(null);

  const containerRef = useRef<HTMLDivElement>(null);
  const graphRef = useRef<GraphType | null>(null);

  const load = useCallback(async () => {
    if (!targetMethod) return;
    setLoading(true);
    try {
      const data = await fetchUpstreamTree(targetMethod);
      setTreeData(data);
    } catch (e: unknown) {
      message.error(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [targetMethod]);

  useEffect(() => { load(); }, [load]);

  // G6 v5 渲染
  useEffect(() => {
    if (!treeData || !containerRef.current) return;

    if (graphRef.current) {
      try { (graphRef.current as GraphType).destroy(); } catch { /* */ }
      graphRef.current = null;
    }

    const container = containerRef.current;
    const width = container.clientWidth || 900;
    const height = container.clientHeight || 650;

    const { nodes, edges } = flattenTree(treeData.root);
    const rootId = treeData.root.id;

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const graph = new (Graph as any)({
      container, width, height,
      autoFit: 'view',
      data: {
        nodes: nodes.map(n => {
          const m = n as unknown as UpstreamNodeModel & G6NodeData;
          const isRoot = m.isRoot ?? false;
          const isEndpoint = m.isEndpoint ?? false;
          const barColor = isRoot ? '#1890ff' : isEndpoint ? (ENDPOINT_COLOR[m.endpointType ?? ''] ?? ENDPOINT_DEFAULT) : repoColor(m.repoId ?? null);
          return {
            id: n.id,
            data: { ...n, fullMethod: m.fullMethod, repoId: m.repoId },
            style: {
              width: NODE_W, height: NODE_H,
              fill: isRoot ? '#e6f7ff' : isEndpoint ? '#fff7e6' : '#fff',
              stroke: barColor, lineWidth: isRoot ? 2.5 : 1.5, radius: 6,
              labelText: (m.shortRef ?? m.label ?? '').substring(0, 22),
              labelFill: isRoot ? '#1890ff' : '#262626',
              labelFontSize: isRoot ? 13 : 12,
              labelFontWeight: (isRoot ? 700 : 500) as number,
              labelPlacement: 'center' as const,
              cursor: 'pointer',
            },
          };
        }),
        edges: edges.map(e => ({ id: e.id, source: e.source, target: e.target, style: { stroke: '#d9d9d9', lineWidth: 1.5, opacity: 0.8, endArrow: true, endArrowSize: 6 } })),
      },
      node: { type: 'rect' },
      layout: { type: 'radial', focusNode: rootId, linkDistance: 280, unitRadius: 200, preventOverlap: true, nodeSize: [NODE_W, NODE_H], strictRadial: false },
      behaviors: ['drag-canvas', 'zoom-canvas', 'drag-element'],
      plugins: [{ type: 'minimap', size: [150, 90] }],
    });

    graph.on('node:click', (evt: { itemId?: string }) => {
      const nodeId = evt.itemId;
      if (!nodeId) return;
      try {
        const nd = graph.getNodeData?.(nodeId);
        const data = nd?.data as (UpstreamNodeModel & { fullMethod?: string; repoId?: number }) | undefined;
        if (!data?.fullMethod) return;
        const rid = data.isRoot ? (repoIdParam ?? data.repoId) : (data.repoId ?? repoIdParam);
        if (rid == null) return;
        navigate(`/callgraph?repoId=${rid}&method=${encodeURIComponent(data.fullMethod)}`);
      } catch { /* */ }
    });

    graph.render().catch(console.warn);
    graphRef.current = graph as GraphType;

    const resizeObs = new ResizeObserver(() => {
      if (!graphRef.current) return;
      try { (graphRef.current as any).setSize?.([container.clientWidth || 900, container.clientHeight || 650]); } catch { /* */ }
    });
    resizeObs.observe(container);
    return () => resizeObs.disconnect();
  }, [treeData, repoIdParam, navigate]);

  const shortMethod = targetMethod.includes('#')
    ? targetMethod.split('#').slice(-1)[0]
    : targetMethod.split('.').slice(-1)[0];

  // 统计入口点
  const endpoints: UpstreamTreeNodeDTO[] = [];
  const affectedRepos = new Set<number>();
  function collectStats(node: UpstreamTreeNodeDTO) {
    if (node.repoId) affectedRepos.add(node.repoId);
    if (node.isEndpoint) endpoints.push(node);
    node.callers.forEach(collectStats);
  }
  if (treeData) collectStats(treeData.root);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden' }}>
      {/* 工具栏 */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: 8, padding: '8px 12px',
        borderBottom: '1px solid #f0f0f0', flexShrink: 0, flexWrap: 'wrap',
      }}>
        <Button
          size="small" icon={<ArrowLeftOutlined />}
          onClick={() => navigate(-1)}
        />
        <ApiOutlined style={{ fontSize: 15, color: '#1890ff' }} />
        <span style={{ fontWeight: 600, fontSize: 14 }}>影响分析</span>
        <Tag color="blue" style={{ fontSize: 12, fontFamily: 'monospace' }}>{shortMethod}</Tag>

        {treeData && (
          <span style={{ fontSize: 12, color: '#8c8c8c' }}>
            {affectedRepos.size} 个仓库 · {endpoints.length} 个入口点 · {treeData.totalNodes} 个节点
            {treeData.truncated && <Tag color="warning" style={{ marginLeft: 6 }}>结果已截断</Tag>}
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
            <Button size="small" icon={<ReloadOutlined />} onClick={load} loading={loading} />
          </Tooltip>
        </div>
      </div>

      {/* 主体 */}
      <div style={{ flex: 1, position: 'relative', overflow: 'hidden' }}>
        {loading && (
          <div style={{ position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', zIndex: 10, gap: 12 }}>
            <Spin size="large" />
            <span style={{ color: '#8c8c8c', fontSize: 13 }}>追溯上游调用链...</span>
          </div>
        )}

        {!loading && !targetMethod && (
          <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <Empty description="请通过调用链页面进入影响分析" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          </div>
        )}

        {!loading && treeData && treeData.root.callers.length === 0 && (
          <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', flexDirection: 'column', gap: 12 }}>
            <ThunderboltOutlined style={{ fontSize: 32, color: '#faad14' }} />
            <span style={{ color: '#8c8c8c' }}>该方法没有上游调用方（可能是入口点本身）</span>
          </div>
        )}

        <div ref={containerRef} style={{ width: '100%', height: '100%' }} />

        {/* 入口点图例 */}
        {treeData && treeData.root.callers.length > 0 && (
          <div style={{
            position: 'absolute', bottom: 12, left: 12,
            background: 'rgba(255,255,255,0.93)', borderRadius: 6,
            padding: '8px 14px', fontSize: 11, color: '#595959',
            boxShadow: '0 2px 8px rgba(0,0,0,0.08)', zIndex: 10,
          }}>
            <div style={{ fontWeight: 600, marginBottom: 6 }}>图例</div>
            <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
              <span style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                <span style={{ width: 10, height: 10, borderRadius: 2, background: '#1890ff', display: 'inline-block' }} />
                目标方法（中心）
              </span>
              {Object.entries(ENDPOINT_COLOR).map(([k, c]) => (
                <span key={k} style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                  <span style={{ width: 10, height: 10, borderRadius: 2, background: c, display: 'inline-block' }} />
                  {k} 入口
                </span>
              ))}
              <span style={{ color: '#8c8c8c' }}>单击节点 → 调用链分析</span>
            </div>

            {/* 入口点摘要 */}
            {endpoints.length > 0 && (
              <div style={{ marginTop: 8, borderTop: '1px solid #f0f0f0', paddingTop: 6 }}>
                <span style={{ fontWeight: 600 }}>受影响入口（{endpoints.length}）：</span>
                <div style={{ marginTop: 4, display: 'flex', flexDirection: 'column', gap: 2, maxHeight: 120, overflow: 'auto' }}>
                  {endpoints.slice(0, 8).map((ep, i) => (
                    <span key={i} style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                      <Tag
                        color={ENDPOINT_COLOR[ep.endpointType ?? ''] ?? ENDPOINT_DEFAULT}
                        style={{ fontSize: 10, margin: 0, padding: '0 4px' }}
                      >
                        {ep.httpMethod ? `${ep.httpMethod} ` : ''}{ep.endpointType}
                      </Tag>
                      <span style={{ fontFamily: 'monospace', fontSize: 11 }}>
                        {ep.urlPath ?? ep.shortRef}
                      </span>
                    </span>
                  ))}
                  {endpoints.length > 8 && (
                    <span style={{ color: '#8c8c8c', fontSize: 11 }}>…还有 {endpoints.length - 8} 个</span>
                  )}
                </div>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
