import { useEffect, useRef, useState, useCallback } from 'react';
import type React from 'react';
import { Select, Spin, Tag, Drawer, Button, Empty, Tooltip, message, Input, Modal, Space, Tabs } from 'antd';
import {
  ZoomInOutlined, ZoomOutOutlined, ExpandOutlined,
  CodeOutlined, BugOutlined, ApiOutlined, FileTextOutlined,
  DownOutlined, RightOutlined, FolderOutlined,
} from '@ant-design/icons';
import { Graph } from '@antv/g6';
import {
  fetchRepos, fetchEntryPoints, fetchCallTree, fetchMethodSource, fetchMethodSourceDetail,
  analyzeLog, getMock, saveMock, generateCallChainCode,
  generateProductDoc, generateDevDoc,
  fetchFileTree, fetchClassEdges, fetchRepoJars,
  type RepoEntity, type EntryPoint, type CallTree, type CallTreeNode,
  type LogAnalysisResult, type MethodSourceDetail, type BoundaryInfo, type FileTreeItem, type ClassEdge,
} from '../api';
import JavaCodeViewer from '../components/JavaCodeViewer';

// ─── Types & Helpers ─────────────────────────────────────────────────────────

interface NodeCustomData {
  label: string; fullMethod: string; className: string; methodName: string;
  callType: string; lineNumber: number | null; boundaries: BoundaryInfo[];
  isRecursive: boolean; isLazyLoad: boolean; ambiguous: boolean; depth: number;
}

function collapseBridges(node: CallTreeNode): CallTreeNode {
  let n = node;
  while (n.children && n.children.length === 1) {
    const child = n.children[0];
    if ((child.methodName || '') === (n.methodName || '') && ['_ITF', 'IMPL', 'INT'].includes(child.callType ?? '')) {
      n = { ...child, boundaries: [...(n.boundaries ?? []), ...(child.boundaries ?? [])] };
    } else break;
  }
  return { ...n, children: (n.children ?? []).map(collapseBridges) };
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

const _BOUNDARY_COLORS: Record<string, string> = {
  DB: '#1890ff', HTTP: '#52c41a', GRPC: '#722ed1', MQ: '#fa8c16', CACHE: '#eb2f96', REDIS: '#eb2f96',
};

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
  const [jarNames, setJarNames] = useState<Map<number, string[]>>(new Map()); // repoId → jar name list (index = jarNum)
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
  const [mockMethod, setMockMethod] = useState('');
  const [mockRequest, setMockRequest] = useState('');
  const [mockResponse, setMockResponse] = useState('');
  const [codeDrawerOpen, setCodeDrawerOpen] = useState(false);
  const [generatedCode, setGeneratedCode] = useState('');
  const [generatingCode, setGeneratingCode] = useState(false);
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

  // ── Load repos ──
  useEffect(() => {
    fetchRepos().then(r => {
      setRepos(r);
      // Auto-select first analyzed repo
      if (selectedRepoIds.length === 0) {
        const first = r.find(repo => repo.status === 'ANALYZED' || repo.status === 'READY');
        if (first) setSelectedRepoIds([first.id]);
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
    if (graphRef.current) { try { graphRef.current.destroy(); } catch { /* */ } graphRef.current = null; }

    if (selectedRepoIds.length === 0) { setEntryPoints([]); return; }
    setLoadingEntries(true);
    Promise.all(selectedRepoIds.map(id => fetchEntryPoints(id)))
      .then(results => setEntryPoints(results.flat()))
      .catch(() => message.error('加载入口点失败'))
      .finally(() => setLoadingEntries(false));
  }, [selectedRepoIds]);

  // ── Load file tree + class edges + jar names when repos change & tab is files ──
  useEffect(() => {
    if (activeTab !== 'files' || selectedRepoIds.length === 0) return;
    setLoadingFileTree(true);
    Promise.all([
      Promise.all(selectedRepoIds.map(id => fetchFileTree(id).then(items => [id, items] as [number, FileTreeItem[]]))),
      Promise.all(selectedRepoIds.map(id => fetchClassEdges(id).catch(() => [] as ClassEdge[]))),
      Promise.all(selectedRepoIds.map(id => fetchRepoJars(id).then(jars => [id, jars.map(j => j.name.replace(/\.jar$|\.war$/, ''))] as [number, string[]]))),
    ])
      .then(([treeResults, edgeResults, jarResults]) => {
        const m = new Map<number, FileTreeItem[]>();
        treeResults.forEach(([id, items]) => m.set(id, items));
        setFileTreeData(m);
        setClassEdges(edgeResults.flat());
        const jm = new Map<number, string[]>();
        jarResults.forEach(([id, names]) => jm.set(id, names));
        setJarNames(jm);
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
  const _openMockEditor = useCallback(async (fullMethod: string) => {
    const repoId = selectedRepoIds[0]; if (!repoId) return;
    setMockMethod(fullMethod);
    try { const mock = await getMock(repoId, fullMethod); setMockRequest(mock?.mockRequest ?? '{}'); setMockResponse(mock?.mockResponse ?? '{"code":200}'); }
    catch { setMockRequest('{}'); setMockResponse('{"code":200}'); }
    setMockModalOpen(true);
  }, [selectedRepoIds]);
  const handleSaveMock = useCallback(async () => {
    const repoId = selectedRepoIds[0]; if (!repoId) return;
    try { await saveMock(repoId, mockMethod, mockRequest, mockResponse); message.success('Mock 已保存'); setMockModalOpen(false); }
    catch (e: unknown) { if (e instanceof Error) message.error(e.message); }
  }, [selectedRepoIds, mockMethod, mockRequest, mockResponse]);

  // ── Code gen ──
  const handleGenerateCode = useCallback(async () => {
    const repoId = selectedRepoIds[0]; if (!repoId || !selectedEntry) return;
    setGeneratingCode(true);
    try { const code = await generateCallChainCode(repoId, selectedEntry); setGeneratedCode(code); setCodeDrawerOpen(true); }
    catch (e: unknown) { if (e instanceof Error) message.error(e.message); }
    finally { setGeneratingCode(false); }
  }, [selectedRepoIds, selectedEntry]);

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

    // Color palette per package (rotate through 8 colors)
    const PALETTE = ['#1890ff', '#52c41a', '#722ed1', '#fa8c16', '#eb2f96', '#13c2c2', '#2f54eb', '#fa541c'];
    const allItems = [...fileTreeData.values()].flat();
    const pkgList = [...new Set(allItems.map(i => i.packageName || ''))].sort();
    const pkgColorMap = new Map(pkgList.map((pkg, i) => [pkg, PALETTE[i % PALETTE.length]]));

    // Include ALL classes (single repo, no limit needed)
    const nodes = allItems.map(item => {
      const short = (item.className.split('.').pop() ?? item.className).replace(/\$.+/, '');
      const color = pkgColorMap.get(item.packageName || '') ?? '#1890ff';
      return { id: item.className, data: { label: short, pkg: item.packageName, methodCount: item.methodCount, color } };
    });

    // Edges: use real call_graph class-level edges (filtered to only nodes present in graph)
    const nodeIdSet = new Set(nodes.map(n => n.id));
    const edges = classEdges
      .filter(e => nodeIdSet.has(e.source) && nodeIdSet.has(e.target))
      .map((e, i) => ({ id: `fe${i}`, source: e.source, target: e.target }));

    // Initialize node positions near center to avoid left-top-corner start
    const cx = (container.clientWidth || 800) / 2;
    const cy = (container.clientHeight || 500) / 2;
    const nodesWithPos = nodes.map(n => ({
      ...n,
      style: { x: cx + (Math.random() - 0.5) * 200, y: cy + (Math.random() - 0.5) * 200 },
    }));

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const graph = new (Graph as any)({
      container,
      width: container.clientWidth || 800,
      height: container.clientHeight || 500,
      autoFit: 'view',
      data: { nodes: nodesWithPos, edges },
      node: {
        style: (datum: { id: string; data?: { label: string; pkg: string; methodCount: number; color: string } }) => {
          const d = datum.data ?? { label: '', pkg: '', methodCount: 1, color: '#1890ff' };
          const size = Math.min(Math.max(d.methodCount * 3 + 12, 18), 50);
          return {
            size, fill: d.color, stroke: '#fff', lineWidth: 1.5,
            opacity: 0.9, labelText: d.label, labelFill: '#262626', labelFontSize: 10,
            labelPlacement: 'bottom', labelOffsetY: 5, cursor: 'pointer',
            shadowColor: d.color, shadowBlur: 4, shadowOffsetY: 0,
          };
        },
        state: {
          selected: { stroke: '#fa8c16', lineWidth: 3, halo: true, haloStroke: '#fa8c16', haloLineWidth: 18, haloStrokeOpacity: 0.35, labelFontWeight: 700, labelFontSize: 12 },
          active: { halo: true, haloStroke: '#1890ff', haloLineWidth: 12, haloStrokeOpacity: 0.25 },
          inactive: { opacity: 0.5 },
        },
        animation: { enter: 'fade' },
      },
      edge: {
        style: { stroke: '#999', lineWidth: 1.5, opacity: 0.4, endArrow: true, endArrowSize: 5 },
        state: { active: { stroke: '#fa8c16', lineWidth: 2.5, opacity: 1 }, inactive: { opacity: 0.08 } },
      },
      layout: {
        type: 'd3-force',
        link: { distance: 50 },
        charge: { strength: -100 },
        center: { x: 0, y: 0, strength: 0.1 },
        collide: { radius: 18 },
        x: { strength: 0.05 },
        y: { strength: 0.05 },
      },
      behaviors: ['drag-canvas', 'drag-element-force', { type: 'click-select', multiple: false }],
    });

    // Click node: highlight neighbors + show source
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    graph.on('node:click', (event: any) => {
      const nodeId = event?.itemId ?? event?.target?.id;
      if (!nodeId) return;
      const item = allItems.find(i => i.className === nodeId);
      if (!item) return;

      // Highlight: clicked=selected, neighbors=active, rest=inactive
      const neighbors = new Set<string>();
      neighbors.add(nodeId);
      classEdges.forEach(e => {
        if (e.source === nodeId) neighbors.add(e.target);
        if (e.target === nodeId) neighbors.add(e.source);
      });
      const states: Record<string, string[]> = {};
      for (const n of allItems) {
        if (n.className === nodeId) states[n.className] = ['selected'];
        else if (neighbors.has(n.className)) states[n.className] = ['active'];
        else states[n.className] = ['inactive'];
      }
      try { graph.setElementState?.(states); } catch { /* */ }

      // Load source with structured detail
      const repoId = selectedRepoIds[0];
      if (repoId) {
        showSource(item.className + ':__CLASS__()');
      }

      // Scroll left panel to the file
      setTimeout(() => {
        const el = document.querySelector(`[data-classname="${item.className}"]`);
        if (el) {
          el.scrollIntoView({ behavior: 'smooth', block: 'center' });
          el.classList.add('active');
        }
      }, 100);
    });

    // Click empty canvas → clear highlights + close source
    graph.on('canvas:click', () => {
      try {
        const clearStates: Record<string, string[]> = {};
        allItems.forEach(item => { clearStates[item.className] = []; });
        classEdges.forEach((_, i) => { clearStates[`fe${i}`] = []; });
        graph.setElementState?.(clearStates);
      } catch { /* */ }
      setSourcePanelOpen(false);
    });

    graph.render().catch(console.warn);

    // Keep graph fitted during force simulation (nodes spread out over time)
    const fitInterval = setInterval(() => { try { graph.fitView?.(); } catch { /* */ } }, 1500);
    fitIntervalRef.current = fitInterval;
    setTimeout(() => { clearInterval(fitInterval); fitIntervalRef.current = null; }, 8000);

    graphRef.current = graph;

    const ro = new ResizeObserver(() => {
      if (!graphRef.current) return;
      try { graphRef.current.changeSize?.(container.clientWidth, container.clientHeight); } catch { /* */ }
    });
    ro.observe(container);
    return () => ro.disconnect();
  }, [activeTab, fileTreeData, classEdges, showSource, selectedRepoIds]);

  // ── G6 Dagre graph rendering ──
  useEffect(() => {
    if (activeTab !== 'entries' || !callTree?.root || !graphContainerRef.current) return;

    const { nodes, edges, rootId } = flattenTree(callTree.root);
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

      // If clicking same node again → deselect, restore all, hide source
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
      lastSelectedNode = nodeId;

      // Highlight clicked node + adjacent
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

    // Stop fitView loop to prevent interference
    if (fitIntervalRef.current) { clearInterval(fitIntervalRef.current); fitIntervalRef.current = null; }

    // Highlight in graph: set clicked node + neighbors to active/selected, rest to inactive
    const graph = graphRef.current;
    if (graph) {
      try {
        // Find neighbors of clicked node from classEdges
        const neighbors = new Set<string>();
        neighbors.add(className);
        classEdges.forEach(e => {
          if (e.source === className) neighbors.add(e.target);
          if (e.target === className) neighbors.add(e.source);
        });

        // Build state map: all nodes are in graph (single repo, full render)
        const states: Record<string, string[]> = {};
        const allItems = [...fileTreeData.values()].flat();
        for (const item of allItems) {
          if (item.className === className) {
            states[item.className] = ['selected'];
          } else if (neighbors.has(item.className)) {
            states[item.className] = ['active'];
          } else {
            states[item.className] = ['inactive'];
          }
        }
        try { graph.setElementState?.(states); } catch { /* */ }

        // Highlight edges
        const edgeStates: Record<string, string[]> = {};
        classEdges.forEach((e, i) => {
          const edgeId = `fe${i}`;
          if (e.source === className || e.target === className) {
            edgeStates[edgeId] = ['active'];
          } else {
            edgeStates[edgeId] = ['inactive'];
          }
        });
        try { graph.setElementState?.(edgeStates); } catch { /* */ }

      } catch { /* graph API variation */ }
    }

    // Load full class source with structured detail (params, dependencies, etc)
    showSource(className + ':__CLASS__()');
  }, [selectedRepoIds, classEdges, fileTreeData, showSource]);

  // ── Entry point grouping helpers ──
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

    function render(node: N, depth: number, isRoot: boolean): React.ReactNode {
      const key = `${repoName}::${node.path}`;
      const expanded = isRoot || expandedPkgs.has(key);
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
            return (
              <div key={cls.className} className="entry-item" data-classname={cls.className} onClick={() => handleFileTreeClick(cls.className)}
                style={{ padding: `2px 4px 2px ${(depth + 1) * 16}px`, fontSize: 12, display: 'flex', alignItems: 'center', gap: 5, cursor: 'pointer' }}>
                <span style={{ color: '#6897bb', fontWeight: 700, fontSize: 10, width: 12 }}>C</span>
                <span style={{ color: classColor(short), flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{short}</span>
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
            value={selectedRepoIds[0] ?? null} onChange={v => setSelectedRepoIds(v ? [v] : [])}
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
              const jars = jarNames.get(repoId) ?? [];
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
                    const moduleName = jars[jarNum] ?? `module-${jarNum}`;
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
            </div>
            {/* Source panel (30%) — only visible when a node is clicked */}
            {sourcePanelOpen && (
            <div style={{ height: '30%', maxHeight: '30%', minHeight: 0, overflow: 'auto', background: '#fff', borderTop: '1px solid #e8e8e8', position: 'relative' }}>
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

                {/* Source code panel (30%) — toggled */}
                {sourcePanelOpen && (
                <div style={{ height: '30%', maxHeight: '30%', minHeight: 0, overflow: 'auto', background: '#fff', borderTop: '1px solid #e8e8e8', position: 'relative' }}>
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

      <Drawer title={<span><CodeOutlined style={{ marginRight: 8 }} />调用链展平代码</span>} placement="bottom" height="60%" open={codeDrawerOpen} onClose={() => setCodeDrawerOpen(false)}
        extra={<Button size="small" onClick={() => { navigator.clipboard.writeText(generatedCode); message.success('已复制'); }}>复制</Button>}>
        <div style={{ height: '100%' }}><JavaCodeViewer code={generatedCode} maxHeight="100%" /></div>
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
