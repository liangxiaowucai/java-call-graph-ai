import { useEffect, useRef, useState, useCallback } from 'react';
import type React from 'react';
import { Select, Spin, Tag, Drawer, Button, Empty, Tooltip, message, Input, Modal, Space, Tabs } from 'antd';
import {
  ZoomInOutlined, ZoomOutOutlined, ExpandOutlined,
  CodeOutlined, ApiOutlined, FileTextOutlined,
  DownOutlined, RightOutlined, FolderOutlined,
} from '@ant-design/icons';
import { Graph, register, getExtension, ExtensionCategory, ForceAtlas2Layout } from '@antv/g6';
import {
  fetchRepos, fetchEntryPoints, fetchCallTree, fetchMethodSource, fetchMethodSourceDetail,
  analyzeLog, saveMock,
  generateProductDoc, generateDevDoc,
  fetchFileTree, fetchClassEdges,
  type RepoEntity, type EntryPoint, type CallTree, type CallTreeNode,
  type LogAnalysisResult, type MethodSourceDetail, type BoundaryInfo, type FileTreeItem, type ClassEdge,
} from '../api';
import { getPositions, preloadLayout, layoutCacheKey } from '../services/graphPreloader';
import JavaCodeViewer from '../components/JavaCodeViewer';

// 官方 ForceAtlas2 布局：内置扩展通常导入即自动注册；此处兜底注册，避免个别版本未注册。
if (!getExtension(ExtensionCategory.LAYOUT, 'force-atlas2')) {
  register(ExtensionCategory.LAYOUT, 'force-atlas2', ForceAtlas2Layout);
}

// ─── Types & Helpers ─────────────────────────────────────────────────────────

interface NodeCustomData {
  label: string; fullMethod: string; className: string; methodName: string;
  callType: string; lineNumber: number | null; boundaries: BoundaryInfo[];
  isRecursive: boolean; isLazyLoad: boolean; ambiguous: boolean; depth: number;
}

function flattenTree(root: CallTreeNode) {
  const nodes: Array<{ id: string; data: NodeCustomData }> = [];
  const edges: Array<{ id: string; source: string; target: string; data: { callType: string } }> = [];
  const seenN = new Set<string>(), seenE = new Set<string>();

  function walk(node: CallTreeNode, depth: number) {
    const id = node.fullMethod;
    if (!seenN.has(id)) {
      seenN.add(id);
      const shortClass = (node.className ?? '').split('.').pop()?.split('$')[0] ?? '';
      nodes.push({ id, data: {
        label: `${shortClass}.${node.methodName ?? ''}`, fullMethod: id,
        className: node.className ?? '', methodName: node.methodName ?? '',
        callType: node.callType ?? '', lineNumber: node.lineNumber,
        boundaries: node.boundaries ?? [], isRecursive: node.isRecursive,
        isLazyLoad: node.isLazyLoad, ambiguous: node.ambiguous, depth,
      }});
    }
    for (const child of node.children ?? []) {
      const eKey = `${id}=>${child.fullMethod}`;
      if (!seenE.has(eKey)) { seenE.add(eKey); edges.push({ id: `e${edges.length}`, source: id, target: child.fullMethod, data: { callType: child.callType ?? '' } }); }
      walk(child, depth + 1);
    }
  }
  walk(root, 0);
  return { nodes, edges, rootId: root.fullMethod };
}

function getNodeColor(d: NodeCustomData): string {
  if (d.isRecursive) return '#ff4d4f';
  if (d.ambiguous) return '#faad14';
  // Color by boundaries (IO type)
  const has = (t: string) => d.boundaries.some(b => b.boundaryType === t);
  if (has('DB')) return '#1890ff';
  if (has('GRPC') || has('RPC')) return '#722ed1';
  if (has('HTTP')) return '#fa8c16';
  if (has('MQ')) return '#13c2c2';
  if (has('CACHE') || has('REDIS')) return '#eb2f96';
  // Color by class name pattern
  const cls = (d.className ?? '').toLowerCase();
  if (cls.includes('controller')) return '#52c41a';
  if (cls.includes('service')) return '#1890ff';
  if (cls.includes('mapper') || cls.includes('repository') || cls.includes('dao')) return '#722ed1';
  if (cls.includes('remote') || cls.includes('client') || cls.includes('feign')) return '#fa8c16';
  if (cls.includes('config') || cls.includes('util') || cls.includes('helper')) return '#8c8c8c';
  if (d.depth === 0) return '#fa8c16';
  if (d.isLazyLoad) return '#bfbfbf';
  return '#1890ff';
}

const ENDPOINT_TYPE_COLORS: Record<string, string> = {
  CONTROLLER: '#1890ff', KAFKA: '#fa8c16', ROCKETMQ: '#eb2f96', RABBITMQ: '#13c2c2',
  MQ: '#fa541c', SCHEDULED: '#722ed1', GRPC: '#52c41a', LISTENER: '#fa8c16',
};

// ─── Component ───────────────────────────────────────────────────────────────

export default function CallGraph() {
  // ── State ──
  const [repos, setRepos] = useState<RepoEntity[]>([]);
  const [selectedRepoIds, setSelectedRepoIds] = useState<number[]>([]);
  const [activeTab, setActiveTab] = useState<'files' | 'entries'>('files');

  // File tree tab
  const [fileTreeData, setFileTreeData] = useState<Map<number, FileTreeItem[]>>(new Map());
  const [classEdges, setClassEdges] = useState<ClassEdge[]>([]);
  const [loadingFileTree, setLoadingFileTree] = useState(false);

  // Entry points tab
  const [entryPoints, setEntryPoints] = useState<EntryPoint[]>([]);
  const [loadingEntries, setLoadingEntries] = useState(false);
  const [selectedEntry, setSelectedEntry] = useState<string | null>(null);
  const [callTree, setCallTree] = useState<CallTree | null>(null);
  const [loadingTree, setLoadingTree] = useState(false);
  const [searchText, setSearchText] = useState('');

  // Source code panel (bottom 30% — toggled)
  const [sourcePanelOpen, setSourcePanelOpen] = useState(false);
  const [sourceCode, setSourceCode] = useState<string>('');
  const [sourceMethod, setSourceMethod] = useState<string>('');
  const [sourceDetail, setSourceDetail] = useState<MethodSourceDetail | null>(null);
  const [loadingSource, setLoadingSource] = useState(false);

  // Drawers & modals (kept from original)
  const [logDrawerOpen, setLogDrawerOpen] = useState(false);
  const [logText, setLogText] = useState('');
  const [logAnalyzing, setLogAnalyzing] = useState(false);
  const [logResult, setLogResult] = useState<LogAnalysisResult | null>(null);
  const [mockModalOpen, setMockModalOpen] = useState(false);
  const [mockMethod] = useState('');
  const [mockRequest, setMockRequest] = useState('');
  const [mockResponse, setMockResponse] = useState('');
  const [docDrawerOpen, setDocDrawerOpen] = useState(false);
  const [docContent, setDocContent] = useState('');
  const [docLoading, setDocLoading] = useState(false);
  const [docType, setDocType] = useState<'product' | 'dev'>('product');

  // Collapse state for entry list
  const [collapsedClasses, setCollapsedClasses] = useState<Set<string>>(new Set());

  const graphContainerRef = useRef<HTMLDivElement>(null);
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const graphRef = useRef<any>(null);
  const fitIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  // Graph render ready state (covers the blank period before G6 first paints)
  const [graphReady, setGraphReady] = useState(false);
  const allGraphNodeIdsRef = useRef<Set<string>>(new Set());
  // 当前聚焦选中的类名（图点击 / 目录点击共用，用于「屏蔽蒙层下节点」的判断）
  const selectedNodeRef = useRef<string | null>(null);
  const showTempEdgesRef = useRef<((nodeId: string) => void) | null>(null);
  const clearTempEdgesRef = useRef<(() => void) | null>(null);
  // 记录布局配置，供鼠标离开时重启动画
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const layoutConfigRef = useRef<any>(null);

  // ── Load repos ──
  useEffect(() => {
    fetchRepos().then(r => {
      setRepos(r);
      if (selectedRepoIds.length === 0) {
        const first = r.find(repo => repo.status === 'ANALYZED' || repo.status === 'READY');
        if (first) {
          setSelectedRepoIds([first.id]);
          // 后台预加载布局（用户还没打开拓扑 Tab，趁现在 Worker 先算好）
          preloadLayout(first.id, first.lastSyncTime ?? undefined).catch(() => {});
        }
      }
    }).catch(() => message.error('加载仓库列表失败'));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ── URL params ──
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const repoId = params.get('repoId');
    const method = params.get('method') ?? params.get('entry');
    if (repoId) setSelectedRepoIds([Number(repoId)]);
    if (method) { setActiveTab('entries'); setTimeout(() => loadCallTree(method), 500); }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ── Load entry points when repos change ──
  useEffect(() => {
    // Reset graph state when switching repos
    setCallTree(null);
    setSourceCode('');
    setSourcePanelOpen(false);
    setSourceDetail(null);
    setSelectedEntry(null);
    setGraphReady(false);
    if (graphRef.current) { try { graphRef.current.destroy(); } catch { /* */ } graphRef.current = null; }

    if (selectedRepoIds.length === 0) { setEntryPoints([]); return; }
    setLoadingEntries(true);
    Promise.all(selectedRepoIds.map(id => fetchEntryPoints(id)))
      .then(results => setEntryPoints(results.flat()))
      .catch(() => message.error('加载入口点失败'))
      .finally(() => setLoadingEntries(false));
  }, [selectedRepoIds]);

  // ── Load file tree + class edges when repos change & tab is files ──
  useEffect(() => {
    if (activeTab !== 'files' || selectedRepoIds.length === 0) return;
    setLoadingFileTree(true);
    Promise.all([
      Promise.all(selectedRepoIds.map(id => fetchFileTree(id).then(items => [id, items] as [number, FileTreeItem[]]))),
      Promise.all(selectedRepoIds.map(id => fetchClassEdges(id).catch(() => [] as ClassEdge[]))),
    ])
      .then(([treeResults, edgeResults]) => {
        const m = new Map<number, FileTreeItem[]>();
        treeResults.forEach(([id, items]) => m.set(id, items));
        setFileTreeData(m);
        setClassEdges(edgeResults.flat());
      })
      .catch(() => message.error('加载文件树失败'))
      .finally(() => setLoadingFileTree(false));
  }, [selectedRepoIds, activeTab]);

  // ── Load call tree ──
  const loadCallTree = useCallback(async (fullMethod: string) => {
    const repoId = selectedRepoIds[0];
    if (!repoId) return;
    setSelectedEntry(fullMethod);
    setLoadingTree(true);
    try { const tree = await fetchCallTree(repoId, fullMethod); setCallTree(tree); }
    catch { message.error('加载调用树失败'); }
    finally { setLoadingTree(false); }
  }, [selectedRepoIds]);

  // ── Show source in bottom panel ──
  const showSource = useCallback(async (fullMethod: string) => {
    const repoId = selectedRepoIds[0];
    if (!repoId) return;
    setSourceMethod(fullMethod);
    setSourcePanelOpen(true);
    setLoadingSource(true);
    try {
      const detail = await fetchMethodSourceDetail(repoId, fullMethod, selectedEntry ?? undefined);
      if (detail) { setSourceCode(detail.sourceCode ?? ''); setSourceDetail(detail); }
      else { const src = await fetchMethodSource(repoId, fullMethod); setSourceCode(src ?? '// 未找到源码'); setSourceDetail(null); }
    } catch { setSourceCode('// 获取源码失败'); }
    finally { setLoadingSource(false); }
  }, [selectedRepoIds, selectedEntry]);

  // ── Log analysis ──
  const handleAnalyzeLog = useCallback(async () => {
    const repoId = selectedRepoIds[0];
    if (!repoId || !selectedEntry || !logText.trim()) { message.warning('请先选择接口并粘贴日志'); return; }
    setLogAnalyzing(true);
    try { const r = await analyzeLog(repoId, selectedEntry, logText); setLogResult(r); message.success(r.summary); }
    catch (e: unknown) { if (e instanceof Error) message.error(e.message); }
    finally { setLogAnalyzing(false); }
  }, [selectedRepoIds, selectedEntry, logText]);

  // ── Mock ──
  const handleSaveMock = useCallback(async () => {
    const repoId = selectedRepoIds[0]; if (!repoId) return;
    try { await saveMock(repoId, mockMethod, mockRequest, mockResponse); message.success('Mock 已保存'); setMockModalOpen(false); }
    catch (e: unknown) { if (e instanceof Error) message.error(e.message); }
  }, [selectedRepoIds, mockMethod, mockRequest, mockResponse]);

  // ── Doc gen ──
  const handleGenerateDoc = useCallback(async (type: 'product' | 'dev') => {
    const repoId = selectedRepoIds[0]; if (!repoId || !selectedEntry) return;
    setDocType(type); setDocDrawerOpen(true); setDocLoading(true);
    try {
      const doc = type === 'product' ? await generateProductDoc(repoId, selectedEntry) : await generateDevDoc(repoId, selectedEntry);
      setDocContent(doc);
    } catch { setDocContent('生成失败'); }
    finally { setDocLoading(false); }
  }, [selectedRepoIds, selectedEntry]);

  // ── G6 file relationship graph (files tab) ──
  useEffect(() => {
    if (activeTab !== 'files' || fileTreeData.size === 0 || !graphContainerRef.current) return;

    if (graphRef.current) { try { graphRef.current.destroy(); } catch { /* */ } graphRef.current = null; }
    const container = graphContainerRef.current;

    // ── 仓库拓扑图 ──────────────────────────────────────────────────────────────
    const PALETTE = ['#1890ff', '#52c41a', '#722ed1', '#fa8c16', '#eb2f96', '#13c2c2', '#2f54eb', '#fa541c'];
    const allItems = [...fileTreeData.values()].flat();
    const pkgList = [...new Set(allItems.map(i => i.packageName || ''))].sort();
    const pkgColorMap = new Map(pkgList.map((pkg, i) => [pkg, PALETTE[i % PALETTE.length]]));
    const nodeIdSet = new Set(allItems.map(i => i.className));

    const nodes = allItems.map(item => ({
      id: item.className,
      data: {
        label: (item.className.split('.').pop() ?? item.className).replace(/\$.+/, ''),
        pkg: item.packageName || 'default',
        methodCount: item.methodCount,
        color: pkgColorMap.get(item.packageName || '') ?? '#1890ff',
      },
    }));

    const externalNodes: typeof nodes = [];
    const allNodes = [...nodes, ...externalNodes];

    // ── 包节点（每个 package 一个节点）——层级边的 source ─────────────────────────
    const pkgNodeMap = new Map<string, { id: string; label: string; color: string }>();
    pkgList.forEach(pkg => {
      if (!pkg) return;
      const label = pkg.split('.').pop() ?? pkg;
      const color = pkgColorMap.get(pkg) ?? '#8c8c8c';
      pkgNodeMap.set(pkg, { id: `pkg::${pkg}`, label, color });
    });
    const pkgNodes = [...pkgNodeMap.values()].map(p => ({
      id: p.id,
      data: { label: p.label, pkg: p.id, methodCount: 0, color: p.color, isPkg: true },
    }));

    // ── 模块节点（jar 级别）─────────────────────────────────────────────────────
    // 按 jarName 分组，每个 jar 一个模块节点
    const jarGroupMap = new Map<string, { items: typeof allItems; color: string }>();
    allItems.forEach(item => {
      const jarKey = item.jarName ?? `module-${item.jarNum ?? 0}`;
      if (!jarGroupMap.has(jarKey)) {
        const pkgOfFirst = item.packageName || '';
        jarGroupMap.set(jarKey, { items: [], color: pkgColorMap.get(pkgOfFirst) ?? '#8c8c8c' });
      }
      jarGroupMap.get(jarKey)!.items.push(item);
    });
    const moduleNodes = [...jarGroupMap.entries()].map(([jarKey]) => ({
      id: `mod::${jarKey}`,
      data: { label: jarKey, pkg: `mod::${jarKey}`, methodCount: 0, color: '#555', isPkg: true, isMod: true },
    }));

    // 层级边：模块 → 包（每个包连到它所属的模块）
    const modPkgEdgeSet = new Set<string>();
    const modulePkgEdges: { id: string; source: string; target: string; style: Record<string, unknown>; data: { edgeType: string } }[] = [];
    allItems.forEach(item => {
      const jarKey = item.jarName ?? `module-${item.jarNum ?? 0}`;
      const pkgKey = item.packageName;
      if (!pkgKey || !pkgNodeMap.has(pkgKey)) return;
      const edgeId = `mod::${jarKey}>>pkg::${pkgKey}`;
      if (!modPkgEdgeSet.has(edgeId)) {
        modPkgEdgeSet.add(edgeId);
        modulePkgEdges.push({ id: edgeId, source: `mod::${jarKey}`, target: `pkg::${pkgKey}`,
          style: { stroke: '#bfbfbf', lineWidth: 1, opacity: 0.5, endArrow: false },
          data: { edgeType: 'hierarchy' } });
      }
    });

    const hierarchyEdges = allItems
      .filter(item => item.packageName && pkgNodeMap.has(item.packageName))
      .map((item, i) => {
        const color = pkgColorMap.get(item.packageName ?? '') ?? '#b0b0b0';
        return {
          id: `hie${i}`,
          source: pkgNodeMap.get(item.packageName!)!.id,
          target: item.className,
          // 样式直接放进数据，不用函数 → updateEdgeData 修改才能生效
          style: { stroke: color, lineWidth: 1.2, opacity: 0.65, endArrow: false },
          data: { edgeType: 'hierarchy', color },
        };
      });

    const allNodesFull = [...moduleNodes, ...pkgNodes, ...allNodes];
    const allHierarchyEdges = [...modulePkgEdges, ...hierarchyEdges];
    // edges 里只保留仓库内部的 import/call 边（外部类已过滤，tempEdgeIds 计算时也用这份）

    // ── 布局坐标：优先内存缓存（Worker 预算）→ localStorage → d3-force ────
    const repoId = selectedRepoIds[0];
    const repo = repos.find(r => r.id === repoId);
    const cacheKey = layoutCacheKey(repoId, repo?.lastSyncTime ?? undefined);

    // 优先读内存（Worker 预算结果），内存没有则读 localStorage
    let cachedPositions: Record<string, { x: number; y: number }> | null = getPositions(cacheKey);
    if (!cachedPositions) {
      try {
        const raw = localStorage.getItem(cacheKey);
        if (raw) cachedPositions = JSON.parse(raw);
      } catch { /* */ }
    }
    const hasCache = cachedPositions !== null && Object.keys(cachedPositions).length > 10;

    const cx = (container.clientWidth  || 800) / 2;
    const cy = (container.clientHeight || 500) / 2;
    const allNodesWithPos = allNodesFull.map(n => ({
      ...n,
      style: hasCache && cachedPositions![n.id]
        ? { x: cachedPositions![n.id].x, y: cachedPositions![n.id].y }
        : { x: cx + (Math.random() - 0.5) * 400, y: cy + (Math.random() - 0.5) * 300 },
    }));

    const layoutConfig = {
      type: 'd3-force',
      link:   { distance: 30 },
      charge: { strength: -60 },
      center: { x: cx, y: cy, strength: 0.15 },
      collide: { radius: 12 },
      x: { strength: 0.08 },
      y: { strength: 0.08 },
      alphaDecay: hasCache ? 0.12 : 0.05,
      alphaMin: 0.001,
    };
    layoutConfigRef.current = layoutConfig;

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const graph = new (Graph as any)({
      container,
      width:  container.clientWidth  || 800,
      height: container.clientHeight || 500,
      autoFit: 'view',
      data: { nodes: allNodesWithPos, edges: allHierarchyEdges },
      animation: true,
      node: {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        style: (d: any) => {
          const color = d.data?.color ?? '#1890ff';
          const isExt  = d.data?.pkg === '__ext__';
          const isPkg  = d.data?.isPkg === true;
          const isMod  = d.data?.isMod === true;
          const size   = isMod ? 28                                           // 模块节点最大
                       : isPkg ? 22                                           // 包节点中等
                       : isExt ? 10 : Math.min(Math.max((d.data?.methodCount ?? 1) * 2.2 + 14, 16), 42);
          return {
            size,
            type: isMod ? 'star' : isPkg ? 'diamond' : 'circle',            // 模块=星形 包=菱形 类=圆形
            fill:      isExt ? '#f0f0f0' : color,
            stroke:    isExt ? '#bfbfbf' : '#fff',
            lineWidth: 1.5,
            lineDash:  isExt ? [3, 3] : undefined,
            opacity:   isExt ? 0.7 : 0.9,
            // 标签默认隐藏，悬停 / 选中时 state 会开启
            labelText:        d.data?.label ?? '',
            labelFillOpacity: 0,
            labelPlacement:   'bottom',
            labelOffsetY:     4,
            cursor: 'pointer',
            // 不设 shadowBlur，阴影是 Canvas 每帧最贵的操作
          };
        },
        state: {
          selected: {
            stroke: '#ff4d00', lineWidth: 3,
            halo: true, haloStroke: '#ff4d00', haloLineWidth: 12, haloStrokeOpacity: 0.8,
            labelFontWeight: 700, labelFontSize: 12, labelFill: '#c41d00', labelFillOpacity: 1,
            labelBackground: true, labelBackgroundFill: 'rgba(255,255,255,0.95)',
            labelBackgroundRadius: 4, labelBackgroundPadding: [2, 6, 2, 6],
            zIndex: 1000,
          },
          active: {
            stroke: '#1677ff', lineWidth: 2,
            halo: true, haloStroke: '#1677ff', haloLineWidth: 8, haloStrokeOpacity: 0.45,
            labelFill: '#0958d9', labelFillOpacity: 1,
            labelBackground: true, labelBackgroundFill: 'rgba(255,255,255,0.9)',
            labelBackgroundRadius: 4, labelBackgroundPadding: [1, 5, 1, 5],
            zIndex: 999,
          },
          inactive: { opacity: 0.25, labelFillOpacity: 0 },
        },
        // 节点进场不做动画（初始 468 个同时动画会卡），状态切换保留过渡
        animation: { enter: false, exit: false },
      },
      edge: {
        // 无 style 函数：样式全部在每条边的 data.style 里（见 hierarchyEdges/modulePkgEdges/showTempEdges）
        // 这样 updateEdgeData({ style: { opacity } }) 才能真正生效
        state: {
          active:   { lineWidth: 2.5, opacity: 1 },
          inactive: { opacity: 0.06 },
        },
        animation: { enter: 'fade', duration: 400, easing: 'ease-in' },
      },
      layout: layoutConfig,
      behaviors: [
        'drag-canvas', 'zoom-canvas',
        'optimize-viewport-transform',
        { type: 'hover-activate', degree: 0, state: 'active' },
        {
          type: 'click-select', degree: 1,
          state: 'selected', neighborState: 'active', unselectedState: 'inactive',
        },
      ],
    });

    // ── 临时边逻辑：点击节点时加该节点的所有相关边，点击其他地方或再次点击时清除 ──
    // 边只在需要时出现，默认视图是干净的节点云
    let tempEdgeIds: string[] = [];

    const showTempEdges = (nodeId: string) => {
      if (tempEdgeIds.length > 0) {
        try { (graph as any).removeEdgeData?.(tempEdgeIds); } catch { /* */ }
        tempEdgeIds = [];
      }

      // 无 style 函数，updateEdgeData 直接修改 style.opacity 才能生效
      try {
        (graph as any).updateEdgeData?.(allHierarchyEdges.map(e => ({ id: e.id, style: { opacity: 0.04 } })));
      } catch { /* */ }

      const nodeEdges = classEdges.filter(e =>
        (e.source === nodeId || e.target === nodeId) &&
        nodeIdSet.has(e.source) && nodeIdSet.has(e.target)
      );
      if (nodeEdges.length > 0) {
        const tempEdges = nodeEdges.map((e, i) => {
          const isCall = e.type === 'call';
          return {
            id: `temp_${nodeId}_${i}`,
            source: e.source, target: e.target,
            style: {
              stroke: isCall ? '#6366f1' : '#10b981',
              lineWidth: 1.8, opacity: 0.9, endArrow: true, endArrowSize: 3,
              ...(isCall ? {} : { lineDash: [4, 3] }),
            },
            data: { edgeType: e.type ?? 'import' },
          };
        });
        tempEdgeIds = tempEdges.map(e => e.id);
        try { (graph as any).addEdgeData?.(tempEdges); } catch { /* */ }

        const neighborIds = nodeEdges.map(e => e.source === nodeId ? e.target : e.source);
        if (neighborIds.length > 0) {
          try {
            const ns: Record<string, string[]> = {};
            neighborIds.forEach(id => { ns[id] = ['active']; });
            (graph as any).setElementState?.(ns);
          } catch { /* */ }
        }
      }
      try { (graph as any).draw?.(); } catch { /* */ }
    };

    const clearTempEdges = () => {
      if (tempEdgeIds.length > 0) {
        try { (graph as any).removeEdgeData?.(tempEdgeIds); } catch { /* */ }
        tempEdgeIds = [];
      }
      // 恢复层级边原始 opacity
      try {
        (graph as any).updateEdgeData?.(
          allHierarchyEdges.map(e => ({ id: e.id, style: { opacity: (e.style as Record<string, unknown>).opacity ?? 0.65 } }))
        );
        const clearStates: Record<string, string[]> = {};
        allNodesFull.forEach(n => { clearStates[n.id] = []; });
        (graph as any).setElementState?.(clearStates);
        (graph as any).draw?.();
      } catch { /* */ }
    };

    showTempEdgesRef.current = showTempEdges;
    clearTempEdgesRef.current = clearTempEdges;

    // 鼠标进入：停止 d3-force，节点定住方便查看
    // 鼠标离开：重启布局动画，按当前位置继续收敛
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    graph.on('canvas:pointerenter', () => { try { (graph as any).stopLayout?.(); } catch { /* */ } });
    graph.on('canvas:pointerleave', () => {
      try {
        // 重启 d3-force（从当前节点位置出发继续动画）
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        (graph as any).layout?.(layoutConfigRef.current);
      } catch { /* */ }
    });
    // 点击节点：视觉高亮由官方 click-select 负责，这里只做「临时边 + 目录/源码联动」
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    graph.on('node:click', (event: any) => {
      const nodeId = event?.itemId ?? event?.target?.id;
      if (!nodeId) return;

      // 再点选中项 → 取消选中 + 清除临时边
      if (selectedNodeRef.current === nodeId) {
        selectedNodeRef.current = null;
        setSelectedClassName(null);
        setSourcePanelOpen(false);
        clearTempEdges();
        return;
      }

      selectedNodeRef.current = nodeId;

      // 加载该节点的临时边
      showTempEdges(nodeId);

      // 只有类节点才同步目录树和源码（包/模块节点没有对应目录条目）
      const isClassNode = !nodeId.startsWith('pkg::') && !nodeId.startsWith('mod::') && !nodeId.startsWith('__ext__');
      if (isClassNode) {
        setSelectedClassName(nodeId);
        const foundItem = allItems.find(i => i.className === nodeId);
        setTimeout(() => {
          const el = document.querySelector(`[data-classname="${CSS.escape(nodeId)}"]`);
          if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' });
        }, 150);
        showSource((foundItem?.className ?? nodeId) + ':__CLASS__()');
      }
    });

    // Click empty canvas → 清除临时边 + 关闭源码
    graph.on('canvas:click', () => {
      selectedNodeRef.current = null;
      setSelectedClassName(null);
      setSourcePanelOpen(false);
      clearTempEdges();
    });

    // 600ms 后移除进度条（节点开始出现），fitView 在 afterlayout 收敛后调用
    const readyTimer = setTimeout(() => setGraphReady(true), 600);

    graph.render().catch(console.warn);

    // afterlayout：fitView + 加边 + 保存 localStorage（d3-force 收敛后）
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    graph.on('afterlayout', () => {
      setGraphReady(true);
      try { (graph as any).fitView?.({ padding: 30 }, { duration: 600, easing: 'ease-in-out' }); } catch { /* */ }

      // 收敛后保存 localStorage（边不再预加载，只在点击时按需加载）
      if (!hasCache) {
        try {
          const positions: Record<string, { x: number; y: number }> = {};
          allNodesFull.forEach(n => {
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            const d = (graph as any).getNodeData?.(n.id);
            if (d?.style?.x !== undefined) positions[n.id] = { x: d.style.x, y: d.style.y };
          });
          if (Object.keys(positions).length > 10) {
            localStorage.setItem(cacheKey, JSON.stringify(positions));
          }
        } catch { /* */ }
      }
    });

    // 保存完整节点 ID 集合（含外部依赖虚节点）供 handleFileTreeClick 使用
    allGraphNodeIdsRef.current = new Set(allNodesFull.map(n => n.id));

    // 布局为逐帧 tick 的 force 布局，配合 autoFit:'view' 官方自适应即可。
    graphRef.current = graph;

    const ro = new ResizeObserver(() => {
      if (!graphRef.current) return;
      try { graphRef.current.changeSize?.(container.clientWidth, container.clientHeight); } catch { /* */ }
    });
    ro.observe(container);
    return () => { clearTimeout(readyTimer); ro.disconnect(); };
  }, [activeTab, fileTreeData, classEdges, showSource, selectedRepoIds]);

  // ── G6 Dagre graph rendering ──
  useEffect(() => {
    if (activeTab !== 'entries' || !callTree?.root || !graphContainerRef.current) return;

    const { nodes, edges } = flattenTree(callTree.root);
    const nodeMap = new Map(nodes.map(n => [n.id, n.data]));

    if (graphRef.current) { try { graphRef.current.destroy(); } catch { /* */ } graphRef.current = null; }
    const container = graphContainerRef.current;

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const graph = new (Graph as any)({
      container,
      width: container.clientWidth || 800,
      height: container.clientHeight || 400,
      autoFit: 'view',
      padding: [40, 40, 40, 40],
      data: { nodes, edges },
      node: {
        type: 'rect',
        style: (datum: { id: string; data?: NodeCustomData }) => {
          const d = datum.data ?? {} as NodeCustomData;
          const color = getNodeColor(d);
          // Truncate label to max 28 chars, ensure node fits
          const rawLabel = d.label ?? '';
          const displayLabel = rawLabel.length > 25 ? rawLabel.slice(0, 24) + '…' : rawLabel;
          const nodeWidth = 280;

          // Build badges for boundary types (DB/HTTP/GRPC etc)
          const BADGE_LABELS: Record<string, string> = { DB: 'DB', HTTP: 'HTTP', GRPC: 'RPC', MQ: 'MQ', CACHE: 'Redis' };
          const badges = [...new Set((d.boundaries ?? []).map(b => BADGE_LABELS[b.boundaryType]).filter(Boolean))];
          const badgeLine = badges.length > 0 ? badges.join(' | ') : '';

          return {
            size: [nodeWidth, 64], radius: 12,
            fill: color, stroke: color, lineWidth: d.depth === 0 ? 3 : 1.5,
            labelText: badgeLine ? `${displayLabel}\n${badgeLine}` : displayLabel,
            labelFill: '#fff', labelFontSize: 13,
            labelPlacement: 'center', cursor: 'pointer',
            shadowColor: color, shadowBlur: 6, shadowOffsetY: 2,
          };
        },
        state: {
          selected: { lineWidth: 3, stroke: '#1890ff', shadowBlur: 12, shadowColor: '#1890ff' },
          active: { lineWidth: 2.5, stroke: '#4a90e2' },
          inactive: { opacity: 0.65 },
        },
      },
      edge: {
        type: 'polyline',
        style: (datum: { data?: { callType: string } }) => {
          const ct = datum.data?.callType ?? '';
          const isImpl = ['IMPL', 'INT', '_ITF'].includes(ct);
          return {
            stroke: isImpl ? '#fa8c16' : '#000',
            lineWidth: 2,
            endArrow: true,
            endArrowSize: 10,
            radius: 8,
            labelText: ct && ct !== 'ITR' && ct !== 'STA' ? ct : '',
            labelFontSize: 10,
            labelFill: isImpl ? '#fa8c16' : '#666',
            labelBackground: true,
            labelBackgroundFill: '#fff',
            labelBackgroundRadius: 2,
          };
        },
        state: { active: { stroke: '#1890ff', lineWidth: 1.5 }, inactive: { opacity: 0.1 } },
      },
      layout: { type: 'antv-dagre', rankdir: 'TB', nodesep: 30, ranksep: 80, nodeSize: [280, 70] },
      behaviors: ['drag-canvas', 'drag-element', { type: 'click-select', multiple: false }],
      animation: { duration: 300 },
    });

    // Node click → show source in bottom panel (click again to deselect)
    let lastSelectedNode: string | null = null;
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    graph.on('node:click', (event: any) => {
      const nodeId = event?.itemId ?? event?.target?.id;
      if (!nodeId) return;
      const data = nodeMap.get(nodeId);
      if (!data) return;

      // 点同一节点再次点击 → 取消选中
      if (lastSelectedNode === nodeId) {
        lastSelectedNode = null;
        try {
          const states: Record<string, string[]> = {};
          nodes.forEach(n => { states[n.id] = []; });
          graph.setElementState?.(states);
        } catch { /* */ }
        setSourcePanelOpen(false);
        return;
      }

      // 任意节点点击都更新选中
      lastSelectedNode = nodeId;

      try {
        const neighbors = new Set<string>();
        neighbors.add(nodeId);
        edges.forEach(e => {
          if (e.source === nodeId) neighbors.add(e.target);
          if (e.target === nodeId) neighbors.add(e.source);
        });
        const states: Record<string, string[]> = {};
        nodes.forEach(n => {
          if (n.id === nodeId) states[n.id] = ['selected'];
          else if (neighbors.has(n.id)) states[n.id] = ['active'];
          else states[n.id] = ['inactive'];
        });
        graph.setElementState?.(states);
      } catch { /* */ }

      showSource(data.fullMethod);
    });

    // Click empty canvas → clear highlights + close source
    graph.on('canvas:click', () => {
      try {
        const clearStates: Record<string, string[]> = {};
        nodes.forEach(n => { clearStates[n.id] = []; });
        edges.forEach(e => { clearStates[e.id] = []; });
        graph.setElementState?.(clearStates);
      } catch { /* */ }
      setSourcePanelOpen(false);
    });

    graph.render().catch(console.warn);
    graphRef.current = graph;

    const ro = new ResizeObserver(() => {
      if (!graphRef.current) return;
      try { graphRef.current.changeSize?.(container.clientWidth, container.clientHeight); } catch { /* */ }
    });
    ro.observe(container);
    return () => ro.disconnect();
  }, [callTree, showSource]);

  // ── Toolbar ──
  const handleZoomIn = () => { try { graphRef.current?.zoomBy?.(1.3); } catch { /* */ } };
  const handleZoomOut = () => { try { graphRef.current?.zoomBy?.(0.7); } catch { /* */ } };
  const handleFitView = () => { try { graphRef.current?.fitView?.({ padding: 20 }); } catch { /* */ } };

  // ── File tree click: highlight graph node + show source ──
  const handleFileTreeClick = useCallback((className: string) => {
    const repoId = selectedRepoIds[0];
    if (!repoId) return;

    if (fitIntervalRef.current) { clearInterval(fitIntervalRef.current); fitIntervalRef.current = null; }

    // 再次点击同一节点 → 取消高亮，恢复初始状态
    if (selectedNodeRef.current === className) {
      selectedNodeRef.current = null;
      setSelectedClassName(null);
      setSourcePanelOpen(false);
      clearTempEdgesRef.current?.();
      return;
    }

    setSelectedClassName(className);
    selectedNodeRef.current = className;

    const graph = graphRef.current;
    if (graph) {
      try {
        const neighbors = new Set<string>();
        neighbors.add(className);
        classEdges.forEach(e => {
          if (e.source === className) neighbors.add(e.target);
          if (e.target === className) neighbors.add(e.source);
        });

        // 用完整节点集合（含外部依赖虚节点）设置状态：选中/相邻高亮，其余轻度淡化作背景
        const allNodeIds = allGraphNodeIdsRef.current;
        const states: Record<string, string[]> = {};
        allNodeIds.forEach(id => {
          if (id === className) states[id] = ['selected'];
          else if (neighbors.has(id)) states[id] = ['active'];
          else states[id] = ['inactive'];
        });
        try { graph.setElementState?.(states); } catch { /* */ }

        // 边现在是临时动态加载的（点击节点时 addEdgeData），没有固定 ID，不再在这里设置边状态

      } catch { /* */ }

      // 目录树点击：通过 ref 调用 effect 内的 showTempEdges（同图节点点击，会压暗层级边）
      if (showTempEdgesRef.current) {
        showTempEdgesRef.current(className);
      }
    }

    showSource(className + ':__CLASS__()');
  }, [selectedRepoIds, classEdges, fileTreeData, showSource]);

  // ── 源码面板高度拖拽 ──
  const [sourcePanelHeight, setSourcePanelHeight] = useState(280); // px，默认 280px
  const isDraggingRef = useRef(false);
  const dragStartYRef = useRef(0);
  const dragStartHeightRef = useRef(0);

  const handleDividerMouseDown = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    isDraggingRef.current = true;
    dragStartYRef.current = e.clientY;
    dragStartHeightRef.current = sourcePanelHeight;

    const onMove = (ev: MouseEvent) => {
      if (!isDraggingRef.current) return;
      const delta = dragStartYRef.current - ev.clientY; // 向上拖 → 面板变高
      const next = Math.max(80, Math.min(600, dragStartHeightRef.current + delta));
      setSourcePanelHeight(next);
    };
    const onUp = () => {
      isDraggingRef.current = false;
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
  }, [sourcePanelHeight]);
  const grouped = entryPoints.reduce<Record<string, EntryPoint[]>>((acc, ep) => {
    const t = ep.endpointType || 'OTHER'; if (!acc[t]) acc[t] = []; acc[t].push(ep); return acc;
  }, {});
  const groupOrder = ['CONTROLLER','KAFKA','ROCKETMQ','RABBITMQ','MQ','GRPC','SCHEDULED','LISTENER','OTHER'];
  const groupLabels: Record<string,string> = { CONTROLLER:'HTTP 接口',KAFKA:'Kafka',ROCKETMQ:'RocketMQ',RABBITMQ:'RabbitMQ',MQ:'MQ',GRPC:'gRPC 服务',SCHEDULED:'定时任务',LISTENER:'监听',OTHER:'其他' };

  const filterEntries = (eps: EntryPoint[]) => {
    if (!searchText) return eps;
    const lower = searchText.toLowerCase();
    return eps.filter(ep =>
      (ep.urlPath ?? '').toLowerCase().includes(lower) ||
      (ep.className ?? '').split('.').pop()?.toLowerCase().includes(lower) ||
      (ep.fullMethod ?? '').split(':').pop()?.split('(')[0]?.toLowerCase().includes(lower)
    );
  };

  const groupByClass = (eps: EntryPoint[]): Array<[string, string, EntryPoint[]]> => {
    const map = new Map<string, EntryPoint[]>();
    for (const ep of eps) { const cls = ep.className ?? '?'; (map.get(cls) ?? map.set(cls, []).get(cls)!).push(ep); }
    return Array.from(map.entries()).map(([cls, list]) => [(cls.split('.').pop() ?? cls), cls, list] as [string, string, EntryPoint[]]).sort((a, b) => a[0].localeCompare(b[0]));
  };

  const toggleClass = (cls: string) => setCollapsedClasses(prev => { const n = new Set(prev); n.has(cls) ? n.delete(cls) : n.add(cls); return n; });

  // ── File tree rendering helper — IDEA style ──
  const [expandedPkgs, setExpandedPkgs] = useState<Set<string>>(new Set());
  const [selectedClassName, setSelectedClassName] = useState<string | null>(null);
  const togglePkg = (key: string) => setExpandedPkgs(prev => {
    const n = new Set(prev); n.has(key) ? n.delete(key) : n.add(key); return n;
  });

  const classColor = (shortName: string): string => {
    if (shortName.endsWith('Controller')) return '#2e7d32';
    if (shortName.endsWith('Service') || shortName.endsWith('ServiceImpl')) return '#1565c0';
    if (shortName.endsWith('Mapper') || shortName.endsWith('Repository') || shortName.endsWith('Dao')) return '#6a1b9a';
    if (shortName.endsWith('Config') || shortName.endsWith('Configuration')) return '#4527a0';
    if (shortName.endsWith('Exception') || shortName.endsWith('Error')) return '#c62828';
    if (shortName.endsWith('Interceptor') || shortName.endsWith('Filter') || shortName.endsWith('Aspect')) return '#e65100';
    return '#333';
  };

  const buildPackageTree = (items: FileTreeItem[], repoName: string) => {
    // 1) Find common prefix to strip
    const pkgs = items.map(i => i.packageName).filter(Boolean);
    let commonLen = 0;
    if (pkgs.length > 0) {
      const first = pkgs[0].split('.');
      for (let i = 0; i < first.length; i++) {
        if (pkgs.every(p => p.split('.')[i] === first[i])) commonLen++;
        else break;
      }
    }

    // 2) Build tree
    type N = { seg: string; path: string; children: Map<string, N>; classes: FileTreeItem[] };
    const root: N = { seg: repoName, path: '', children: new Map(), classes: [] };

    for (const item of items) {
      const parts = (item.packageName || '').split('.').slice(commonLen);
      let cur = root;
      const trail: string[] = [];
      for (const p of parts) {
        trail.push(p);
        if (!cur.children.has(p)) cur.children.set(p, { seg: p, path: trail.join('.'), children: new Map(), classes: [] });
        cur = cur.children.get(p)!;
      }
      cur.classes.push(item);
    }

    // 3) Compact middle packages (IDEA style: if node has 1 child and 0 classes → merge)
    function compact(node: N): N {
      const newCh = new Map<string, N>();
      for (const [, child] of node.children) {
        let c = compact(child);
        while (c.children.size === 1 && c.classes.length === 0) {
          const [, grandchild] = [...c.children.entries()][0];
          c = { ...grandchild, seg: c.seg + '.' + grandchild.seg };
        }
        newCh.set(c.seg, c);
      }
      return { ...node, children: newCh };
    }
    const tree = compact(root);

    // 4) Render with collapse/expand
    function count(n: N): number { return n.classes.length + [...n.children.values()].reduce((s, c) => s + count(c), 0); }

    // 该节点子树是否包含当前选中类（用于从图节点反查目录时自动展开路径）
    function hasSelected(n: N): boolean {
      if (selectedClassName == null) return false;
      if (n.classes.some(c => c.className === selectedClassName)) return true;
      for (const c of n.children.values()) if (hasSelected(c)) return true;
      return false;
    }

    function render(node: N, depth: number, isRoot: boolean): React.ReactNode {
      const key = `${repoName}::${node.path}`;
      const expanded = isRoot || expandedPkgs.has(key) || hasSelected(node);
      const total = count(node);
      const hasKids = node.children.size > 0 || node.classes.length > 0;

      return (
        <div key={key}>
          <div onClick={() => hasKids && !isRoot && togglePkg(key)}
            style={{ padding: `2px 4px 2px ${depth * 16}px`, fontSize: 12, display: 'flex', alignItems: 'center', gap: 4,
              cursor: hasKids ? 'pointer' : 'default', userSelect: 'none',
              fontWeight: isRoot ? 600 : 400, background: isRoot ? '#fafafa' : undefined }}>
            {hasKids && !isRoot ? (expanded ? <DownOutlined style={{ fontSize: 8, color: '#999' }} /> : <RightOutlined style={{ fontSize: 8, color: '#999' }} />) : <span style={{ width: 10 }} />}
            <span style={{ color: '#b09050', fontSize: 11 }}>{isRoot ? '📦' : '📂'}</span>
            <span style={{ flex: 1, color: '#262626' }}>{node.seg}</span>
            <span style={{ color: '#bfbfbf', fontSize: 10, paddingRight: 4 }}>{total}</span>
          </div>
          {expanded && [...node.children.values()].sort((a, b) => a.seg.localeCompare(b.seg)).map(c => render(c, depth + 1, false))}
          {expanded && node.classes.sort((a, b) => a.className.localeCompare(b.className)).map(cls => {
            const short = (cls.className.split('.').pop() ?? '').replace(/\$.+/, '');
            const isSelected = selectedClassName === cls.className;
            return (
              <div key={cls.className} className={`entry-item${isSelected ? ' active' : ''}`}
                data-classname={cls.className} onClick={() => handleFileTreeClick(cls.className)}
                style={{ padding: `2px 4px 2px ${(depth + 1) * 16}px`, fontSize: 12, display: 'flex', alignItems: 'center', gap: 5, cursor: 'pointer',
                  background: isSelected ? '#e6f4ff' : undefined,
                  borderLeft: isSelected ? '2px solid #1890ff' : '2px solid transparent' }}>
                <span style={{ color: '#6897bb', fontWeight: 700, fontSize: 10, width: 12 }}>C</span>
                <span style={{ color: isSelected ? '#1890ff' : classColor(short), flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontWeight: isSelected ? 600 : 400 }}>{short}</span>
                <span style={{ color: '#bfbfbf', fontSize: 10 }}>{cls.methodCount}</span>
              </div>
            );
          })}
        </div>
      );
    }

    return render(tree, 0, true);
  };

  // ── JSX Render ──
  return (
    <div className="callgraph-page">
      {/* ═══ Left Panel ═══ */}
      <div className="callgraph-left">
        {/* Repo multi-select */}
        <div className="left-header">
          <Select style={{ width: '100%', marginBottom: 8 }} placeholder="选择仓库"
            value={selectedRepoIds[0] ?? null} onChange={v => {
            setSelectedRepoIds(v ? [v] : []);
            // 切换仓库时触发后台预加载
            if (v) {
              const repo = repos.find(r => r.id === v);
              preloadLayout(v, repo?.lastSyncTime ?? undefined).catch(() => {});
            }
          }}
            options={repos.filter(r => r.status === 'ANALYZED' || r.status === 'READY').map(r => ({ label: r.name, value: r.id }))}
            allowClear />
        </div>

        {/* Tabs: 目录结构 / 调用链 */}
        <Tabs activeKey={activeTab} onChange={k => { setActiveTab(k as 'files' | 'entries'); setSourcePanelOpen(false); setSourceCode(''); setSourceDetail(null); }} size="small"
          style={{ padding: '0 8px' }}
          items={[
            { key: 'files', label: <span><FolderOutlined /> 目录结构</span> },
            { key: 'entries', label: <span><ApiOutlined /> 调用链</span> },
          ]} />

        <div className="entry-list">
          {activeTab === 'files' ? (
            /* ── 目录结构 Tab ── */
            loadingFileTree ? <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div> :
            selectedRepoIds.length === 0 ? <Empty description="请先选择仓库" image={Empty.PRESENTED_IMAGE_SIMPLE} /> :
            Array.from(fileTreeData.entries()).map(([repoId, items]) => {
              const repo = repos.find(r => r.id === repoId);
              // Group items by jarNum (module)
              const byJar = new Map<number, FileTreeItem[]>();
              for (const item of items) {
                const j = item.jarNum ?? 0;
                (byJar.get(j) ?? byJar.set(j, []).get(j)!).push(item);
              }
              return (
                <div key={repoId}>
                  <div style={{ padding: '6px 8px', fontWeight: 600, fontSize: 12, color: '#262626', background: '#fafafa', borderBottom: '1px solid #f0f0f0' }}>
                    📦 {repo?.name ?? `repo-${repoId}`}
                    <span style={{ color: '#bfbfbf', fontWeight: 400, marginLeft: 6 }}>{items.length} 类 · {byJar.size} 模块</span>
                  </div>
                  {[...byJar.entries()].sort((a, b) => a[0] - b[0]).map(([jarNum, jarItems]) => {
                    // 直接用后端返回的 jarName，不再用错误的数组下标索引
                    const moduleName = jarItems[0]?.jarName ?? `module-${jarNum}`;
                    return (
                      <div key={`${repoId}:${jarNum}`}>
                        {byJar.size > 1 && (
                          <div style={{ padding: '4px 8px 2px 12px', fontSize: 11, fontWeight: 600, color: '#1890ff', background: '#f0f7ff', borderBottom: '1px solid #e6f0fa' }}>
                            📂 {moduleName} <span style={{ color: '#bfbfbf', fontWeight: 400 }}>{jarItems.length}</span>
                          </div>
                        )}
                        {buildPackageTree(jarItems, moduleName)}
                      </div>
                    );
                  })}
                </div>
              );
            })
          ) : (
            /* ── 调用链 Tab ── */
            <>
              {selectedRepoIds.length > 0 && (
                <Input.Search placeholder="搜索入口点..." value={searchText}
                  onChange={e => setSearchText(e.target.value)} allowClear size="small" style={{ margin: '0 8px 8px', width: 'calc(100% - 16px)' }} />
              )}
              {loadingEntries ? <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div> :
               selectedRepoIds.length === 0 ? <Empty description="请先选择仓库" image={Empty.PRESENTED_IMAGE_SIMPLE} /> :
               entryPoints.length === 0 ? <Empty description="暂无入口点" image={Empty.PRESENTED_IMAGE_SIMPLE} /> :
               groupOrder.map(type => {
                 const eps = filterEntries(grouped[type] ?? []);
                 if (!eps.length) return null;
                 const classGroups = groupByClass(eps);
                 return (
                   <div key={type}>
                     <div className="entry-group-title">
                       <Tag color={ENDPOINT_TYPE_COLORS[type] ?? '#8c8c8c'} style={{ fontSize: 10 }}>{groupLabels[type] ?? type}</Tag>
                       <span style={{ fontSize: 10, color: '#bfbfbf' }}>{eps.length}</span>
                     </div>
                     {classGroups.map(([shortClass, fullClass, classEps]) => {
                       const col = collapsedClasses.has(fullClass);
                       return (
                         <div key={fullClass} className="entry-class-group">
                           <div className="entry-class-header" onClick={() => toggleClass(fullClass)}>
                             {col ? <RightOutlined /> : <DownOutlined />}
                             <span className="entry-class-name" title={fullClass}>{shortClass}</span>
                             <span className="entry-class-count">{classEps.length}</span>
                           </div>
                           {!col && classEps.map(ep => (
                             <div key={ep.id} className={`entry-item${selectedEntry === ep.fullMethod ? ' active' : ''}`}
                               onClick={() => loadCallTree(ep.fullMethod)}>
                               <div className="entry-method">
                                 {ep.httpMethod && <Tag color={ep.httpMethod==='GET'?'green':ep.httpMethod==='POST'?'blue':'orange'} style={{ fontSize: 9, marginRight: 4 }}>{ep.httpMethod}</Tag>}
                                 {ep.urlPath || ep.fullMethod.split(':').pop()?.split('(')[0]}
                               </div>
                             </div>
                           ))}
                         </div>
                       );
                     })}
                   </div>
                 );
               })}
            </>
          )}
        </div>
      </div>

      {/* ═══ Right Panel ═══ */}
      <div className="callgraph-right" style={{ display: 'flex', flexDirection: 'column' }}>
        {activeTab === 'files' ? (
          /* ── 目录结构 Tab: 整个仓库的类关系力导向图 + 底部源码 ── */
          <>
            <div className="graph-toolbar">
              <Tooltip title="放大"><Button size="small" icon={<ZoomInOutlined />} onClick={handleZoomIn} /></Tooltip>
              <Tooltip title="缩小"><Button size="small" icon={<ZoomOutOutlined />} onClick={handleZoomOut} /></Tooltip>
              <Tooltip title="适应画布"><Button size="small" icon={<ExpandOutlined />} onClick={handleFitView} /></Tooltip>
              <div style={{ flex: 1 }} />
              <span style={{ fontSize: 11, color: '#8c8c8c' }}>
                {fileTreeData.size > 0 && `${[...fileTreeData.values()].reduce((s, v) => s + v.length, 0)} 个类`}
              </span>
            </div>
            {/* Graph (fills available space) */}
            <div style={{ flex: 1, minHeight: 0, position: 'relative', overflow: 'hidden', borderBottom: sourcePanelOpen ? '1px solid #f0f0f0' : 'none' }}>
              <div ref={graphContainerRef} style={{ width: '100%', height: '100%' }} />
              {/* d3-force 计算时顶部显示细进度条，不遮挡节点运动动画 */}
              {!graphReady && (
                <div style={{ position: 'absolute', top: 0, left: 0, right: 0, height: 3,
                  background: 'linear-gradient(90deg, #1677ff 0%, #69b1ff 50%, #1677ff 100%)',
                  backgroundSize: '200% 100%',
                  animation: 'shimmer 1.5s infinite',
                  zIndex: 10 }} />
              )}
            </div>
            <style>{`@keyframes shimmer { 0%{background-position:200% 0} 100%{background-position:-200% 0} }`}</style>
            {/* Source panel — height adjustable by dragging the divider */}
            {sourcePanelOpen && (
            <div style={{ display: 'flex', flexDirection: 'column', background: '#fff', borderTop: '1px solid #e8e8e8' }}>
              {/* 拖拽分割线 */}
              <div
                onMouseDown={handleDividerMouseDown}
                style={{ height: 5, background: '#f0f0f0', cursor: 'row-resize', flexShrink: 0,
                  borderTop: '1px solid #e0e0e0', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                <div style={{ width: 32, height: 3, borderRadius: 2, background: '#bfbfbf' }} />
              </div>
              <div style={{ height: sourcePanelHeight, overflow: 'auto', position: 'relative' }}>
              <Button size="small" type="text" onClick={() => setSourcePanelOpen(false)}
                style={{ position: 'sticky', top: 0, right: 0, zIndex: 10, float: 'right', color: '#8c8c8c' }}>✕</Button>
              {loadingSource ? (
                <div style={{ textAlign: 'center', padding: 24 }}><Spin /><div style={{ color: '#8c8c8c', marginTop: 8, fontSize: 12 }}>加载源码...</div></div>
              ) : sourceCode ? (
                <div style={{ height: '100%' }}>
                  <div style={{ padding: '6px 12px', background: '#f5f5f5', borderBottom: '1px solid #e8e8e8', display: 'flex', alignItems: 'center', gap: 8 }}>
                    <CodeOutlined style={{ color: '#1890ff' }} />
                    <span style={{ fontSize: 12, color: '#262626', fontFamily: 'monospace', fontWeight: 600 }}>
                      {sourceMethod.split(':')[0]?.split('.').pop() ?? sourceMethod}
                    </span>
                  </div>
                  <JavaCodeViewer code={sourceCode} maxHeight="100%" />
                </div>
              ) : (
                <div style={{ textAlign: 'center', padding: 24, color: '#8c8c8c', fontSize: 12 }}>无源码</div>
              )}
              </div>
            </div>
            )}
          </>
        ) : (
          /* ── 调用链 Tab: Dagre 流程图 + 底部源码 ── */
          <>
            {loadingTree ? (
              <div className="graph-placeholder"><Spin size="large" /><div style={{ marginTop: 12, color: '#8c8c8c' }}>加载调用树...</div></div>
            ) : !callTree ? (
              <div className="graph-placeholder">
                <Empty description={<span style={{ color: '#8c8c8c' }}>选择左侧入口查看调用链</span>} image={Empty.PRESENTED_IMAGE_SIMPLE} />
              </div>
            ) : (
              <>
                {/* Toolbar */}
                <div className="graph-toolbar">
                  <Tooltip title="放大"><Button size="small" icon={<ZoomInOutlined />} onClick={handleZoomIn} /></Tooltip>
                  <Tooltip title="缩小"><Button size="small" icon={<ZoomOutOutlined />} onClick={handleZoomOut} /></Tooltip>
                  <Tooltip title="适应画布"><Button size="small" icon={<ExpandOutlined />} onClick={handleFitView} /></Tooltip>
                  <div style={{ width: 1, height: 16, background: '#e8e8e8', margin: '0 4px' }} />
                  <Button size="small" icon={<FileTextOutlined />} onClick={() => handleGenerateDoc('product')}>产品文档</Button>
                  <div style={{ flex: 1 }} />
                  <span style={{ fontSize: 11, color: '#8c8c8c' }}>节点: {callTree.totalNodes} | 深度: {callTree.maxDepth}</span>
                </div>

                {/* Dagre graph (fills space) */}
                <div style={{ flex: 1, minHeight: 0, position: 'relative', overflow: 'hidden', borderBottom: sourcePanelOpen ? '1px solid #f0f0f0' : 'none' }}>
                  <div ref={graphContainerRef} style={{ width: '100%', height: '100%' }} />
                </div>

                {/* Source code panel — height adjustable by dragging the divider */}
                {sourcePanelOpen && (
                <div style={{ display: 'flex', flexDirection: 'column', background: '#fff', borderTop: '1px solid #e8e8e8' }}>
                  <div
                    onMouseDown={handleDividerMouseDown}
                    style={{ height: 5, background: '#f0f0f0', cursor: 'row-resize', flexShrink: 0,
                      borderTop: '1px solid #e0e0e0', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                    <div style={{ width: 32, height: 3, borderRadius: 2, background: '#bfbfbf' }} />
                  </div>
                  <div style={{ height: sourcePanelHeight, overflow: 'auto', position: 'relative' }}>
                  <Button size="small" type="text" onClick={() => setSourcePanelOpen(false)}
                    style={{ position: 'sticky', top: 0, right: 0, zIndex: 10, float: 'right', color: '#8c8c8c' }}>✕</Button>
                  {loadingSource ? (
                    <div style={{ textAlign: 'center', padding: 24 }}><Spin /><div style={{ color: '#8c8c8c', marginTop: 8, fontSize: 12 }}>加载源码...</div></div>
                  ) : sourceCode ? (
                    <div style={{ height: '100%' }}>
                      <div style={{ padding: '6px 12px', background: '#f5f5f5', borderBottom: '1px solid #e8e8e8', display: 'flex', alignItems: 'center', gap: 8 }}>
                        <CodeOutlined style={{ color: '#1890ff' }} />
                        <span style={{ fontSize: 12, color: '#262626', fontFamily: 'monospace', fontWeight: 600 }}>
                          {sourceMethod.split(':').pop()?.split('(')[0] ?? sourceMethod}
                        </span>
                        <span style={{ fontSize: 11, color: '#8c8c8c', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          {sourceMethod.split(':')[0]?.split('.').slice(-2).join('.')}
                        </span>
                      </div>
                      {/* Structured info ABOVE code */}
                      {sourceDetail && (
                        <div style={{ padding: '10px 12px', borderBottom: '1px solid #e8e8e8', fontSize: 12, background: '#fafafa' }}>
                          {sourceDetail.paramClasses && sourceDetail.paramClasses.length > 0 && (
                            <div style={{ marginBottom: 10 }}>
                              <div style={{ fontWeight: 700, color: '#1890ff', marginBottom: 6 }}>📋 入参类型</div>
                              {sourceDetail.paramClasses.map((p, i) => (
                                <div key={i} style={{ marginBottom: 4, paddingLeft: 12 }}>
                                  <span style={{ fontWeight: 600, color: '#262626' }}>{p.shortName}</span>
                                  {p.fields.length > 0 && (
                                    <div style={{ paddingLeft: 12, color: '#595959', fontSize: 11, marginTop: 2 }}>
                                      {p.fields.map((f, j) => <span key={j} style={{ marginRight: 8, padding: '1px 6px', background: '#e6f4ff', borderRadius: 3, display: 'inline-block', marginBottom: 2 }}>{f}</span>)}
                                    </div>
                                  )}
                                </div>
                              ))}
                            </div>
                          )}
                          {sourceDetail.chainContext && sourceDetail.chainContext.length > 0 && (
                            <div style={{ marginBottom: 10 }}>
                              <div style={{ fontWeight: 700, color: '#fa8c16', marginBottom: 6 }}>🔗 外部依赖</div>
                              <div style={{ paddingLeft: 12, display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                                {sourceDetail.chainContext.map((c, i) => (
                                  <span key={i} style={{ padding: '2px 8px', background: '#fff7e6', border: '1px solid #ffd591', borderRadius: 4, color: '#d46b08', fontSize: 11 }}>{c}</span>
                                ))}
                              </div>
                            </div>
                          )}
                          {sourceDetail.enumValues && sourceDetail.enumValues.length > 0 && (
                            <div>
                              <div style={{ fontWeight: 700, color: '#722ed1', marginBottom: 6 }}>📌 常量/枚举</div>
                              <div style={{ paddingLeft: 12, display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                                {sourceDetail.enumValues.slice(0, 12).map((v, i) => (
                                  <span key={i} style={{ padding: '2px 8px', background: '#f9f0ff', border: '1px solid #d3adf7', borderRadius: 4, color: '#531dab', fontFamily: 'monospace', fontSize: 11 }}>{v}</span>
                                ))}
                              </div>
                            </div>
                          )}
                        </div>
                      )}
                      <JavaCodeViewer code={sourceCode} maxHeight="none" />
                    </div>
                  ) : (
                    <div style={{ textAlign: 'center', padding: 24, color: '#bfbfbf', fontSize: 12 }}>无源码</div>
                  )}
                  </div>
                </div>
                )}
              </>
            )}
          </>
        )}
      </div>

      {/* ═══ Drawers & Modals ═══ */}
      <Drawer title="日志诊断" placement="right" width={560} open={logDrawerOpen} onClose={() => setLogDrawerOpen(false)}>
        <div style={{ marginBottom: 12, fontSize: 13, color: '#8c8c8c' }}>粘贴接口日志，自动标注异常节点。</div>
        <Input.TextArea rows={12} value={logText} onChange={e => setLogText(e.target.value)} placeholder="粘贴日志内容..." style={{ fontFamily: 'monospace', fontSize: 12 }} />
        <Button type="primary" danger style={{ marginTop: 12 }} onClick={handleAnalyzeLog} loading={logAnalyzing} block>开始诊断</Button>
        {logResult && (
          <div style={{ marginTop: 16 }}>
            <div style={{ fontWeight: 600, marginBottom: 8 }}>{logResult.summary}</div>
            {logResult.nodeStatuses.filter(s => s.status === 'ERROR').map((s, i) => (
              <div key={i} style={{ padding: '8px 10px', background: '#fff2f0', border: '1px solid #ffccc7', borderRadius: 6, marginBottom: 6 }}>
                <div style={{ fontFamily: 'monospace', fontSize: 11, color: '#cf1322' }}>{s.fullMethod.split(':').pop()}</div>
                {s.errorMessage && <div style={{ fontSize: 11, marginTop: 4, whiteSpace: 'pre-wrap' }}>{s.errorMessage}</div>}
              </div>
            ))}
          </div>
        )}
      </Drawer>

      <Drawer title={<span><FileTextOutlined style={{ marginRight: 8 }} />{docType === 'product' ? '产品文档' : '研发文档'}</span>}
        placement="right" width={700} open={docDrawerOpen} onClose={() => setDocDrawerOpen(false)}
        extra={<Space>
          <Button size="small" type={docType==='product'?'primary':'default'} onClick={() => handleGenerateDoc('product')}>产品视角</Button>
          <Button size="small" type={docType==='dev'?'primary':'default'} onClick={() => handleGenerateDoc('dev')}>研发视角</Button>
          <Button size="small" onClick={() => { navigator.clipboard.writeText(docContent); message.success('已复制'); }}>复制</Button>
        </Space>}>
        {docLoading ? <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div> :
          <div style={{ whiteSpace: 'pre-wrap', fontSize: 13 }}>{docContent}</div>}
      </Drawer>

      <Modal title={`Mock: ${mockMethod.split(':').pop()?.split('(')[0]}`} open={mockModalOpen}
        onCancel={() => setMockModalOpen(false)} onOk={handleSaveMock} okText="保存" width={700}>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
          <div><div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 4 }}>请求</div>
            <Input.TextArea rows={10} value={mockRequest} onChange={e => setMockRequest(e.target.value)} style={{ fontFamily: 'monospace', fontSize: 12 }} /></div>
          <div><div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 4 }}>响应</div>
            <Input.TextArea rows={10} value={mockResponse} onChange={e => setMockResponse(e.target.value)} style={{ fontFamily: 'monospace', fontSize: 12 }} /></div>
        </div>
      </Modal>
    </div>
  );
}
