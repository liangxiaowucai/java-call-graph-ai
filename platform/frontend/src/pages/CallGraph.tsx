import { useEffect, useRef, useState, useCallback } from 'react';
import { Select, Spin, Tag, Drawer, Button, Empty, Tooltip, message, Input, Modal, Space } from 'antd';
import {
  AimOutlined, ZoomInOutlined, ZoomOutOutlined, ExpandOutlined,
  CodeOutlined, BugOutlined, ApiOutlined, FileTextOutlined,
  DownOutlined, RightOutlined,
} from '@ant-design/icons';
import G6, { type TreeGraph as TreeGraphType } from '@antv/g6';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import mermaid from 'mermaid';
import {
  fetchRepos, fetchEntryPoints, fetchCallTree, fetchMethodSource, fetchMethodSourceDetail,
  analyzeLog, getMock, saveMock, generateCallChainCode,
  generateProductDoc, generateDevDoc, generateProductDocDiagrams,
  type RepoEntity, type EntryPoint, type CallTree, type CallTreeNode,
  type LogAnalysisResult, type MethodSourceDetail,
} from '../api';
import JavaCodeViewer from '../components/JavaCodeViewer';

// ─── Mermaid initialization ──────────────────────────────────────────────────

mermaid.initialize({ startOnLoad: false, theme: 'default', securityLevel: 'loose' });

// ─── MermaidBlock component ──────────────────────────────────────────────────

function MermaidBlock({ code }: { code: string }) {
  const ref = useRef<HTMLDivElement>(null);
  const [svg, setSvg] = useState('');

  useEffect(() => {
    const id = 'mermaid-' + Math.random().toString(36).slice(2);
    mermaid.parse(code)
      .then(() => mermaid.render(id, code))
      .then(({ svg }) => setSvg(svg))
      .catch((err) => {
        console.error('Mermaid render error:', err);
        setSvg('');
      });
  }, [code]);

  return svg ? (
    <div ref={ref} dangerouslySetInnerHTML={{ __html: svg }} style={{ overflow: 'auto', margin: '8px 0' }} />
  ) : (
    <pre style={{ background: '#f5f5f5', padding: 12, borderRadius: 6, fontSize: 12 }}>{code}</pre>
  );
}

// ─── Boundary color mapping ──────────────────────────────────────────────────

const BOUNDARY_COLORS: Record<string, string> = {
  DB: '#1890ff',
  HTTP: '#52c41a',
  EXCEPTION: '#ff4d4f',
  TRANSACTION: '#722ed1',
  SERIALIZATION: '#fa8c16',
  MQ: '#13c2c2',
  CACHE: '#eb2f96',
};

const ENDPOINT_TYPE_COLORS: Record<string, string> = {
  CONTROLLER: '#1890ff',
  KAFKA: '#fa8c16',
  ROCKETMQ: '#eb2f96',
  RABBITMQ: '#13c2c2',
  MQ: '#fa541c',
  SCHEDULED: '#722ed1',
  GRPC: '#52c41a',
  LISTENER: '#fa8c16',
};

// ─── G6 data transform ──────────────────────────────────────────────────────

interface G6Node {
  id: string;
  label: string;
  fullMethod: string;
  className: string;
  methodName: string;
  callType: string;
  lineNumber: number | null;
  boundaries: { boundaryType: string; lineNumber: number; context: string }[];
  isRecursive: boolean;
  isLazyLoad: boolean;
  ambiguous: boolean;
  children: G6Node[];
  // 日志诊断状态
  diagStatus?: 'OK' | 'ERROR' | 'UNKNOWN';
  diagMessage?: string;
}

let nodeIdCounter = 0;

function transformNode(node: CallTreeNode): G6Node {
  nodeIdCounter += 1;
  const shortClass = node.className.split('.').pop() ?? node.className;
  return {
    id: `node-${nodeIdCounter}`,
    label: `${shortClass}.${node.methodName}`,
    fullMethod: node.fullMethod,
    className: node.className,
    methodName: node.methodName,
    callType: node.callType,
    lineNumber: node.lineNumber,
    boundaries: node.boundaries ?? [],
    isRecursive: node.isRecursive,
    isLazyLoad: node.isLazyLoad,
    ambiguous: node.ambiguous,
    children: (node.children ?? []).map(transformNode),
  };
}

// ─── Custom G6 node registration ────────────────────────────────────────────

const NODE_HEIGHT = 52;
const NODE_MIN_WIDTH = 200;

function registerCustomNode() {
  G6.registerNode(
    'call-node',
    {
      draw(cfg, group) {
        if (!cfg || !group) return {} as never;
        const label = (cfg.label as string) ?? '';
        const boundaries = (cfg.boundaries as G6Node['boundaries']) ?? [];
        const isRecursive = cfg.isRecursive as boolean;
        const isAmbiguous = cfg.ambiguous as boolean;
        const callType = (cfg.callType as string) ?? '';

        // Measure text width
        const textWidth = G6.Util.getTextSize(label, 12)[0];
        const boundaryWidth = boundaries.length * 12;
        const width = Math.max(NODE_MIN_WIDTH, textWidth + boundaryWidth + 40);

        // Background rect - 歧义节点用橙色边框，递归节点用红色边框
        const strokeColor = isRecursive ? '#ff4d4f' : isAmbiguous ? '#faad14' : '#d9d9d9';
        const fillColor = isRecursive ? '#fff2f0' : isAmbiguous ? '#fffbe6' : '#ffffff';
        const lineWidth = (isRecursive || isAmbiguous) ? 2 : 1;
        const keyShape = group.addShape('rect', {
          attrs: {
            x: 0,
            y: 0,
            width,
            height: NODE_HEIGHT,
            radius: 6,
            fill: fillColor,
            stroke: strokeColor,
            lineWidth: lineWidth,
            shadowColor: 'rgba(0,0,0,0.06)',
            shadowBlur: 4,
            shadowOffsetY: 2,
            cursor: 'pointer',
          },
          name: 'node-rect',
          draggable: true,
        });

        // Call type indicator bar
        if (callType) {
          group.addShape('rect', {
            attrs: {
              x: 0,
              y: 0,
              width: 4,
              height: NODE_HEIGHT,
              radius: [6, 0, 0, 6],
              fill: callType === 'INTERFACE' ? '#1890ff' : callType === 'VIRTUAL' ? '#52c41a' : '#8c8c8c',
            },
            name: 'type-bar',
          });
        }

        // Diagnosis status icon (✅❌❓)
        const diagStatus = cfg.diagStatus as string | undefined;
        if (diagStatus) {
          const statusIcon = diagStatus === 'OK' ? '✅' : diagStatus === 'ERROR' ? '❌' : '❓';
          group.addShape('text', {
            attrs: {
              x: width - 18,
              y: 16,
              text: statusIcon,
              fontSize: 14,
              textAlign: 'center',
              textBaseline: 'middle',
            },
            name: 'status-icon',
          });
          // 如果是 ERROR，给整个节点加红色边框
          if (diagStatus === 'ERROR') {
            keyShape.attr('stroke', '#ff4d4f');
            keyShape.attr('lineWidth', 2);
            keyShape.attr('fill', '#fff2f0');
          }
        }

        // Method name
        const shortClass = ((cfg.className as string) ?? '').split('.').pop() ?? '';
        group.addShape('text', {
          attrs: {
            x: 12,
            y: 16,
            text: (cfg.methodName as string) ?? '',
            fontSize: 12,
            fontWeight: 500,
            fill: '#262626',
            textBaseline: 'middle',
            cursor: 'pointer',
          },
          name: 'method-text',
        });

        // Class name (truncated)
        const displayClass = shortClass.length > 28 ? shortClass.slice(0, 26) + '…' : shortClass;
        group.addShape('text', {
          attrs: {
            x: 12,
            y: 34,
            text: displayClass,
            fontSize: 10,
            fill: '#8c8c8c',
            textBaseline: 'middle',
            cursor: 'pointer',
          },
          name: 'class-text',
        });

        // Boundary tags (colored labels showing type)
        let tagX = 12;
        const tagY = 34;
        boundaries.forEach((b) => {
          const typeLabel = b.boundaryType;
          const tagWidth = G6.Util.getTextSize(typeLabel, 9)[0] + 8;

          group.addShape('rect', {
            attrs: {
              x: tagX,
              y: tagY - 6,
              width: tagWidth,
              height: 14,
              radius: 3,
              fill: BOUNDARY_COLORS[b.boundaryType] ?? '#d9d9d9',
              cursor: 'pointer',
            },
            name: `boundary-bg-${typeLabel}`,
          });
          group.addShape('text', {
            attrs: {
              x: tagX + tagWidth / 2,
              y: tagY + 1,
              text: typeLabel,
              fontSize: 9,
              fontWeight: 500,
              fill: '#fff',
              textAlign: 'center',
              textBaseline: 'middle',
              cursor: 'pointer',
            },
            name: `boundary-text-${typeLabel}`,
          });
          tagX += tagWidth + 3;
        });

        // Recursive icon
        if (isRecursive) {
          group.addShape('text', {
            attrs: {
              x: width - 14,
              y: 36,
              text: '↻',
              fontSize: 14,
              fill: '#ff4d4f',
              textAlign: 'center',
              textBaseline: 'middle',
            },
            name: 'recursive-icon',
          });
        }

        return keyShape;
      },
      getAnchorPoints() {
        return [
          [0, 0.5],
          [1, 0.5],
        ];
      },
    },
    'single-node',
  );
}

let customNodeRegistered = false;

// ─── Component ───────────────────────────────────────────────────────────────

export default function CallGraph() {
  const [repos, setRepos] = useState<RepoEntity[]>([]);
  const [selectedRepoId, setSelectedRepoId] = useState<number | null>(null);
  const [entryPoints, setEntryPoints] = useState<EntryPoint[]>([]);
  const [loadingEntries, setLoadingEntries] = useState(false);
  const [selectedEntry, setSelectedEntry] = useState<string | null>(null);
  const [callTree, setCallTree] = useState<CallTree | null>(null);
  const [loadingTree, setLoadingTree] = useState(false);
  const [sourceDrawerOpen, setSourceDrawerOpen] = useState(false);
  const [sourceCode, setSourceCode] = useState<string>('');
  const [sourceMethod, setSourceMethod] = useState<string>('');
  const [sourceDetail, setSourceDetail] = useState<MethodSourceDetail | null>(null);
  const [loadingSource, setLoadingSource] = useState(false);
  const [searchText, setSearchText] = useState('');
  const [logDrawerOpen, setLogDrawerOpen] = useState(false);
  const [collapsedClasses, setCollapsedClasses] = useState<Set<string>>(new Set());
  const [collapsedModules, setCollapsedModules] = useState<Set<string>>(new Set());

  // 支持从 URL 参数跳转（从 AI 问答页面点击链接）
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const repoId = params.get('repoId');
    const method = params.get('method');
    if (repoId) {
      setSelectedRepoId(Number(repoId));
      if (method) {
        // 延迟加载调用树，等入口点列表加载完
        setTimeout(() => {
          setSelectedEntry(method);
        }, 500);
      }
    }
  }, []);
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
  const [detailDrawerOpen, setDetailDrawerOpen] = useState(false);
  const [detailNode, setDetailNode] = useState<G6Node | null>(null);
  const [docDrawerOpen, setDocDrawerOpen] = useState(false);
  const [docContent, setDocContent] = useState('');
  const [docLoading, setDocLoading] = useState(false);
  const [docType, setDocType] = useState<'product' | 'dev'>('product');
  const [docDiagrams, setDocDiagrams] = useState<Record<string, string>>({});
  const [selectedDiagramType, setSelectedDiagramType] = useState<'flowchart' | 'sequence' | 'swimlane'>('sequence');
  const [diagramLoading, setDiagramLoading] = useState(false);

  const graphContainerRef = useRef<HTMLDivElement>(null);
  const graphRef = useRef<TreeGraphType | null>(null);

  // Load repos
  useEffect(() => {
    fetchRepos().then(setRepos).catch(() => message.error('加载仓库列表失败'));
  }, []);

  // Load entry points when repo changes
  useEffect(() => {
    if (!selectedRepoId) {
      setEntryPoints([]);
      return;
    }
    setLoadingEntries(true);
    setSelectedEntry(null);
    setCallTree(null);
    fetchEntryPoints(selectedRepoId)
      .then(setEntryPoints)
      .catch(() => message.error('加载入口点失败'))
      .finally(() => setLoadingEntries(false));
  }, [selectedRepoId]);

  // Load call tree
  const loadCallTree = useCallback(async (fullMethod: string) => {
    if (!selectedRepoId) return;
    setSelectedEntry(fullMethod);
    setLoadingTree(true);
    try {
      const tree = await fetchCallTree(selectedRepoId, fullMethod);
      setCallTree(tree);
    } catch {
      message.error('加载调用树失败');
    } finally {
      setLoadingTree(false);
    }
  }, [selectedRepoId]);

  // 从 URL 参数自动加载调用树
  useEffect(() => {
    if (selectedEntry && selectedRepoId && !callTree) {
      loadCallTree(selectedEntry);
    }
  }, [selectedEntry, selectedRepoId, callTree, loadCallTree]);

  // Show source code
  const showSource = useCallback(async (fullMethod: string) => {
    if (!selectedRepoId) return;
    setSourceMethod(fullMethod);
    setSourceDrawerOpen(true);
    setLoadingSource(true);
    setSourceDetail(null);
    try {
      const detail = await fetchMethodSourceDetail(selectedRepoId, fullMethod, selectedEntry ?? undefined);
      if (detail) {
        setSourceCode(detail.sourceCode ?? '');
        setSourceDetail(detail);
        if (!detail.sourceCode) {
          const src = await fetchMethodSource(selectedRepoId, fullMethod);
          if (src) setSourceCode(src);
        }
      } else {
        const src = await fetchMethodSource(selectedRepoId, fullMethod);
        setSourceCode(src ?? '// 未找到源码');
      }
    } catch {
      setSourceCode('// 获取源码失败');
    } finally {
      setLoadingSource(false);
    }
  }, [selectedRepoId, selectedEntry]);

  // Log analysis handler
  const handleAnalyzeLog = useCallback(async () => {
    if (!selectedRepoId || !selectedEntry || !logText.trim()) {
      message.warning('请先选择接口并粘贴日志');
      return;
    }
    setLogAnalyzing(true);
    try {
      const result = await analyzeLog(selectedRepoId, selectedEntry, logText);
      setLogResult(result);
      message.success(result.summary);

      // 更新 G6 节点状态
      if (graphRef.current && !graphRef.current.get('destroyed')) {
        const statusMap = new Map(result.nodeStatuses.map(s => [s.fullMethod, s]));
        graphRef.current.getNodes().forEach(node => {
          const model = node.getModel() as unknown as G6Node;
          const status = statusMap.get(model.fullMethod);
          if (status) {
            graphRef.current!.updateItem(node, {
              diagStatus: status.status,
              diagMessage: status.errorMessage,
            });
          }
        });
      }
    } catch (err: unknown) {
      if (err instanceof Error) message.error(err.message);
    } finally {
      setLogAnalyzing(false);
    }
  }, [selectedRepoId, selectedEntry, logText]);

  // Mock handler
  const openMockEditor = useCallback(async (fullMethod: string) => {
    if (!selectedRepoId) return;
    setMockMethod(fullMethod);
    try {
      const mock = await getMock(selectedRepoId, fullMethod);
      setMockRequest(mock?.mockRequest ?? '{\n  \n}');
      setMockResponse(mock?.mockResponse ?? '{\n  "code": 200,\n  "data": {}\n}');
    } catch {
      setMockRequest('{\n  \n}');
      setMockResponse('{\n  "code": 200,\n  "data": {}\n}');
    }
    setMockModalOpen(true);
  }, [selectedRepoId]);

  const handleSaveMock = useCallback(async () => {
    if (!selectedRepoId) return;
    try {
      await saveMock(selectedRepoId, mockMethod, mockRequest, mockResponse);
      message.success('Mock 已保存');
      setMockModalOpen(false);
    } catch (err: unknown) {
      if (err instanceof Error) message.error(err.message);
    }
  }, [selectedRepoId, mockMethod, mockRequest, mockResponse]);

  const handleGenerateCode = useCallback(async () => {
    if (!selectedRepoId || !selectedEntry) return;
    setGeneratingCode(true);
    try {
      // 如果有日志诊断结果，传给后端
      let statuses: Record<string, string> | undefined;
      if (logResult?.nodeStatuses) {
        statuses = {};
        for (const ns of logResult.nodeStatuses) {
          statuses[ns.fullMethod] = ns.status;
          if (ns.errorMessage) {
            statuses[ns.fullMethod + '.error'] = ns.errorMessage;
          }
        }
      }
      const code = await generateCallChainCode(selectedRepoId, selectedEntry, statuses);
      setGeneratedCode(code);
      setCodeDrawerOpen(true);
    } catch (err: unknown) {
      if (err instanceof Error) message.error(err.message);
    } finally {
      setGeneratingCode(false);
    }
  }, [selectedRepoId, selectedEntry, logResult]);

  const handleGenerateDoc = useCallback(async (type: 'product' | 'dev') => {
    if (!selectedRepoId || !selectedEntry) return;
    setDocType(type);
    setDocLoading(true);
    setDocDrawerOpen(true);
    try {
      if (type === 'product') {
        // 只获取文档内容，图表按需加载
        const doc = await generateProductDoc(selectedRepoId, selectedEntry);
        setDocContent(doc);
        setDocDiagrams({}); // 清空之前的图表
        setSelectedDiagramType('sequence'); // 默认时序图
        // 立即加载默认图表（时序图）
        loadDiagram('sequence');
      } else {
        const doc = await generateDevDoc(selectedRepoId, selectedEntry);
        setDocContent(doc);
        setDocDiagrams({}); // 研发文档不需要图表切换
      }
    } catch (err: unknown) {
      if (err instanceof Error) message.error(err.message);
      setDocContent('生成失败');
    } finally {
      setDocLoading(false);
    }
  }, [selectedRepoId, selectedEntry]);

  // 按需加载图表
  const loadDiagram = useCallback(async (diagramType: 'flowchart' | 'sequence' | 'swimlane') => {
    if (!selectedRepoId || !selectedEntry) return;
    
    // 如果已经加载过，直接返回
    if (docDiagrams[diagramType]) {
      return;
    }
    
    setDiagramLoading(true);
    try {
      const allDiagrams = await generateProductDocDiagrams(selectedRepoId, selectedEntry);
      setDocDiagrams(allDiagrams);
    } catch (err: unknown) {
      if (err instanceof Error) message.error('图表加载失败: ' + err.message);
    } finally {
      setDiagramLoading(false);
    }
  }, [selectedRepoId, selectedEntry, docDiagrams]);

  // 切换图表类型
  const handleDiagramTypeChange = useCallback((type: 'flowchart' | 'sequence' | 'swimlane') => {
    setSelectedDiagramType(type);
    loadDiagram(type);
  }, [loadDiagram]);

  // Render / update G6 graph
  useEffect(() => {
    if (!callTree?.root || !graphContainerRef.current) return;

    // Register custom node once
    if (!customNodeRegistered) {
      registerCustomNode();
      customNodeRegistered = true;
    }

    // Reset counter and transform data
    nodeIdCounter = 0;
    const treeData = transformNode(callTree.root);

    // Destroy previous graph
    if (graphRef.current) {
      graphRef.current.destroy();
      graphRef.current = null;
    }

    const container = graphContainerRef.current;
    const width = container.clientWidth || 800;
    const height = container.clientHeight || 600;

    const graph = new G6.TreeGraph({
      container,
      width,
      height,
      fitView: true,
      fitViewPadding: [40, 40, 40, 40],
      animate: true,
      animateCfg: { duration: 300 },
      modes: {
        default: ['drag-canvas', 'zoom-canvas', 'drag-node'],
      },
      plugins: [],
      defaultNode: {
        type: 'call-node',
      },
      defaultEdge: {
        type: 'cubic-horizontal',
        style: {
          stroke: '#c0c0c0',
          lineWidth: 1,
          endArrow: {
            path: G6.Arrow.triangle(6, 6, 0),
            fill: '#c0c0c0',
          },
        },
      },
      layout: {
        type: 'compactBox',
        direction: 'LR',
        getId: (d: G6Node) => d.id,
        getHeight: () => NODE_HEIGHT,
        getWidth: (d: G6Node) => {
          const textW = G6.Util.getTextSize(d.label ?? '', 12)[0];
          const bw = (d.boundaries?.length ?? 0) * 12;
          return Math.max(NODE_MIN_WIDTH, textW + bw + 40);
        },
        getVGap: () => 12,
        getHGap: () => 60,
      },
    });

    // Node click → show detail drawer
    graph.on('node:click', (evt) => {
      const model = evt.item?.getModel() as G6Node | undefined;
      if (model) {
        setDetailNode(model);
        setDetailDrawerOpen(true);
      }
    });

    // Collapse / expand on dblclick
    graph.on('node:dblclick', (evt) => {
      const item = evt.item;
      if (!item) return;
      const model = item.getModel();
      if (model.children && (model.children as G6Node[]).length > 0) {
        graph.updateItem(item, { collapsed: !model.collapsed });
        graph.layout();
      }
    });

    graph.data(treeData);
    graph.render();
    graph.fitView();

    graphRef.current = graph;

    // Resize handler
    const onResize = () => {
      if (!graphRef.current || graphRef.current.get('destroyed')) return;
      const w = container.clientWidth || 800;
      const h = container.clientHeight || 600;
      graphRef.current.changeSize(w, h);
    };
    window.addEventListener('resize', onResize);

    return () => {
      window.removeEventListener('resize', onResize);
    };
  }, [callTree, showSource]);

  // ─── Group entry points ──────────────────────────────────────────────────

  const grouped = entryPoints.reduce<Record<string, EntryPoint[]>>((acc, ep) => {
    const type = ep.endpointType || 'OTHER';
    if (!acc[type]) acc[type] = [];
    acc[type].push(ep);
    return acc;
  }, {});

  const groupOrder = ['CONTROLLER', 'KAFKA', 'ROCKETMQ', 'RABBITMQ', 'MQ', 'GRPC', 'SCHEDULED', 'LISTENER', 'OTHER'];
  const groupLabels: Record<string, string> = {
    CONTROLLER: 'HTTP 接口',
    KAFKA: 'Kafka 消费者',
    ROCKETMQ: 'RocketMQ 消费者',
    RABBITMQ: 'RabbitMQ 消费者',
    MQ: 'MQ 消费者',
    GRPC: 'gRPC 服务',
    SCHEDULED: '定时任务',
    LISTENER: '消息监听',
    OTHER: '其他',
  };

  // Filter entry points by search
  const filterEntries = (eps: EntryPoint[]) => {
    if (!searchText) return eps;
    const lower = searchText.toLowerCase();
    // 仅按「类简称 + 方法名 + URL」匹配，不匹配完整包路径，
    // 避免搜关键词时命中同名业务包下的无关 Controller。
    const simpleClass = (cn: string) => {
      const c = cn.split('$')[0];                 // 去内部类
      return (c.split('.').pop() ?? c).toLowerCase();
    };
    const methodName = (fm: string) => {
      const afterColon = fm.includes(':') ? fm.slice(fm.lastIndexOf(':') + 1) : fm;
      return (afterColon.split('(')[0] ?? '').toLowerCase();
    };
    return eps.filter(
      (ep) =>
        (ep.urlPath ?? '').toLowerCase().includes(lower) ||
        simpleClass(ep.className ?? '').includes(lower) ||
        methodName(ep.fullMethod ?? '').includes(lower),
    );
  };

  // 把一组入口点按所属类（文件）分组，返回 [类简称, 全名, 方法列表][]，类名排序稳定
  const groupByClass = (eps: EntryPoint[]): Array<[string, string, EntryPoint[]]> => {
    const map = new Map<string, EntryPoint[]>();
    for (const ep of eps) {
      const cls = ep.className ?? '(未知类)';
      (map.get(cls) ?? map.set(cls, []).get(cls)!).push(ep);
    }
    return Array.from(map.entries())
      .map(([cls, list]) => {
        const short = (cls.split('$')[0].split('.').pop()) ?? cls;
        return [short, cls, list] as [string, string, EntryPoint[]];
      })
      .sort((a, b) => a[0].localeCompare(b[0]));
  };

  const toggleClass = (cls: string) => {
    setCollapsedClasses((prev) => {
      const next = new Set(prev);
      if (next.has(cls)) next.delete(cls); else next.add(cls);
      return next;
    });
  };

  // 从完整类名派生所属 module：取「分层包段」(controller/listener/handler/...) 之前的最后一段，
  // 如 a.b.goods.controller.backend.X -> goods，a.b.order.task.Y -> order。
  // 这样多 module 项目里同名类（每个 module 都有的 HealthController）能按 module 区分。
  const moduleOf = (className: string): string => {
    const segs = (className ?? '').split('$')[0].split('.');
    if (segs.length < 2) return className || '(默认)';
    const layerWords = new Set([
      'controller', 'listener', 'handler', 'task', 'job', 'schedule',
      'scheduled', 'service', 'web', 'rest', 'api', 'rpc', 'grpc', 'mq', 'consumer',
    ]);
    const layerIdx = segs.findIndex((s) => layerWords.has(s.toLowerCase()));
    if (layerIdx > 0) return segs[layerIdx - 1];
    // 没有可识别的分层段：退而取倒数第二段（类名前一段）
    return segs[segs.length - 2];
  };

  // 把一组入口点先按 module 分组，返回 [module, 方法列表][]，按 module 名排序
  const groupByModule = (eps: EntryPoint[]): Array<[string, EntryPoint[]]> => {
    const map = new Map<string, EntryPoint[]>();
    for (const ep of eps) {
      const mod = moduleOf(ep.className ?? '');
      (map.get(mod) ?? map.set(mod, []).get(mod)!).push(ep);
    }
    return Array.from(map.entries()).sort((a, b) => a[0].localeCompare(b[0]));
  };

  const toggleModule = (mod: string) => {
    setCollapsedModules((prev) => {
      const next = new Set(prev);
      if (next.has(mod)) next.delete(mod); else next.add(mod);
      return next;
    });
  };

  // ─── Toolbar actions ─────────────────────────────────────────────────────

  const handleZoomIn = () => graphRef.current?.zoom(1.2, undefined, true);
  const handleZoomOut = () => graphRef.current?.zoom(0.8, undefined, true);
  const handleFitView = () => graphRef.current?.fitView();

  return (
    <div className="callgraph-page">
      {/* ── Left Panel ── */}
      <div className="callgraph-left">
        <div className="left-header">
          <Select
            style={{ width: '100%', marginBottom: 8 }}
            placeholder="选择仓库"
            value={selectedRepoId}
            onChange={(val) => setSelectedRepoId(val)}
            options={repos
              .filter((r) => r.status === 'ANALYZED' || r.status === 'READY')
              .map((r) => ({ label: r.name, value: r.id }))}
            allowClear
          />
          {selectedRepoId && (
            <Input.Search
              placeholder="搜索入口点..."
              value={searchText}
              onChange={(e) => setSearchText(e.target.value)}
              allowClear
              size="small"
            />
          )}
        </div>

        <div className="entry-list">
          {loadingEntries ? (
            <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>
          ) : !selectedRepoId ? (
            <Empty description="请先选择仓库" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          ) : entryPoints.length === 0 ? (
            <Empty description="暂无入口点，请先分析仓库" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          ) : (
            groupOrder.map((type) => {
              const eps = filterEntries(grouped[type] ?? []);
              if (eps.length === 0) return null;
              const moduleGroups = groupByModule(eps);
              return (
                <div key={type}>
                  <div className="entry-group-title">
                    <Tag color={ENDPOINT_TYPE_COLORS[type] ?? '#8c8c8c'} style={{ fontSize: 11 }}>
                      {groupLabels[type] ?? type}
                    </Tag>
                    <span style={{ marginLeft: 4, fontSize: 11, color: '#bfbfbf' }}>
                      {moduleGroups.length} 个模块 · {eps.length} 个接口
                    </span>
                  </div>
                  {moduleGroups.map(([mod, modEps]) => {
                    const modKey = `${type}::${mod}`;
                    const modCollapsed = collapsedModules.has(modKey);
                    const classGroups = groupByClass(modEps);
                    return (
                      <div key={modKey} className="entry-module-group">
                        <div className="entry-module-header" onClick={() => toggleModule(modKey)}>
                          {modCollapsed ? <RightOutlined /> : <DownOutlined />}
                          <span className="entry-module-name" title={mod}>📦 {mod}</span>
                          <span className="entry-module-count">{classGroups.length} 类 / {modEps.length}</span>
                        </div>
                        {!modCollapsed && classGroups.map(([shortClass, fullClass, classEps]) => {
                          const collapsed = collapsedClasses.has(fullClass);
                          return (
                            <div key={fullClass} className="entry-class-group">
                              <div className="entry-class-header" onClick={() => toggleClass(fullClass)}>
                                {collapsed ? <RightOutlined /> : <DownOutlined />}
                                <span className="entry-class-name" title={fullClass}>{shortClass}</span>
                                <span className="entry-class-count">{classEps.length}</span>
                              </div>
                              {!collapsed && classEps.map((ep) => (
                                <div
                                  key={ep.id}
                                  className={`entry-item${selectedEntry === ep.fullMethod ? ' active' : ''}`}
                                  onClick={() => loadCallTree(ep.fullMethod)}
                                >
                                  <div className="entry-method">
                                    {ep.httpMethod && (
                                      <Tag
                                        color={
                                          ep.httpMethod === 'GET' ? 'green' :
                                          ep.httpMethod === 'POST' ? 'blue' :
                                          ep.httpMethod === 'PUT' ? 'orange' :
                                          ep.httpMethod === 'DELETE' ? 'red' : 'default'
                                        }
                                        style={{ fontSize: 10, marginRight: 4 }}
                                      >
                                        {ep.httpMethod}
                                      </Tag>
                                    )}
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
                </div>
              );
            })
          )}
        </div>
      </div>

      {/* ── Right Panel ── */}
      <div className="callgraph-right">
        {loadingTree ? (
          <div className="graph-placeholder"><Spin size="large" tip="加载调用树..." /></div>
        ) : !callTree ? (
          <div className="graph-placeholder">
            <Empty description="点击左侧入口点查看调用链" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          </div>
        ) : (
          <>
            {/* Toolbar */}
            <div className="graph-toolbar">
              <Tooltip title="放大">
                <Button size="small" icon={<ZoomInOutlined />} onClick={handleZoomIn} />
              </Tooltip>
              <Tooltip title="缩小">
                <Button size="small" icon={<ZoomOutOutlined />} onClick={handleZoomOut} />
              </Tooltip>
              <Tooltip title="适应画布">
                <Button size="small" icon={<ExpandOutlined />} onClick={handleFitView} />
              </Tooltip>
              <Tooltip title="日志诊断">
                <Button size="small" type="primary" danger icon={<BugOutlined />} onClick={() => setLogDrawerOpen(true)}>
                  日志诊断
                </Button>
              </Tooltip>
              <Tooltip title="生成可运行代码">
                <Button size="small" icon={<CodeOutlined />} onClick={handleGenerateCode} loading={generatingCode}>
                  生成代码
                </Button>
              </Tooltip>
              <Tooltip title="产品文档（业务功能说明）">
                <Button size="small" icon={<FileTextOutlined />} onClick={() => handleGenerateDoc('product')}>
                  产品文档
                </Button>
              </Tooltip>
              <Tooltip title="研发文档（技术逻辑+伪代码）">
                <Button size="small" icon={<FileTextOutlined />} onClick={() => handleGenerateDoc('dev')}>
                  研发文档
                </Button>
              </Tooltip>
            </div>

            {/* Stats */}
            <div className="graph-stats">
              <span>节点: {callTree.totalNodes}</span>
              <span style={{ margin: '0 8px' }}>|</span>
              <span>深度: {callTree.maxDepth}</span>
              {callTree.hasCycle && (
                <>
                  <span style={{ margin: '0 8px' }}>|</span>
                  <Tag color="warning" style={{ margin: 0 }}>存在循环</Tag>
                </>
              )}
              {callTree.warnings && callTree.warnings.length > 0 && (
                <>
                  <span style={{ margin: '0 8px' }}>|</span>
                  <Tooltip title={
                    <div style={{ maxWidth: 400 }}>
                      <div style={{ marginBottom: 8, fontWeight: 600 }}>以下方法在多个仓库中有相同签名定义：</div>
                      {callTree.warnings.map((w, i) => (
                        <div key={i} style={{ marginBottom: 6, fontSize: 12 }}>
                          <div style={{ color: '#ffe58f' }}>{w.fullMethod.split(':').pop()}</div>
                          <div style={{ paddingLeft: 8 }}>
                            {w.locations.map((loc, j) => (
                              <div key={j}>• {loc.repoName}</div>
                            ))}
                          </div>
                        </div>
                      ))}
                      <div style={{ marginTop: 8, color: '#d9d9d9', fontSize: 11 }}>
                        静态分析无法确定调用指向哪个仓库的实现，请人工确认
                      </div>
                    </div>
                  }>
                    <Tag color="warning" style={{ margin: 0, cursor: 'pointer' }}>
                      歧义: {callTree.warnings.length}
                    </Tag>
                  </Tooltip>
                </>
              )}
            </div>

            {/* Graph */}
            <div className="graph-container" ref={graphContainerRef} />

            {/* Legend */}
            <div style={{
              position: 'absolute', bottom: 12, left: 12,
              background: 'rgba(255,255,255,0.92)', borderRadius: 6,
              padding: '8px 14px', fontSize: 11, color: '#595959',
              boxShadow: '0 2px 8px rgba(0,0,0,0.08)', zIndex: 10,
              display: 'flex', gap: 12, flexWrap: 'wrap',
            }}>
              <span><AimOutlined style={{ marginRight: 4 }} />单击查看源码</span>
              <span><CodeOutlined style={{ marginRight: 4 }} />双击展开/折叠</span>
              <span style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                边界:
                {Object.entries(BOUNDARY_COLORS).map(([k, c]) => (
                  <Tooltip key={k} title={k}>
                    <span className={`boundary-dot ${k}`} style={{ background: c }} />
                  </Tooltip>
                ))}
              </span>
            </div>
          </>
        )}
      </div>

      {/* ── Node Detail Drawer ── */}
      <Drawer
        title={
          <span style={{ fontSize: 13 }}>
            {detailNode?.diagStatus === 'ERROR' ? '❌ ' : detailNode?.diagStatus === 'OK' ? '✅ ' : detailNode?.diagStatus === 'UNKNOWN' ? '❓ ' : ''}
            {detailNode?.label ?? '节点详情'}
          </span>
        }
        placement="right"
        width={560}
        open={detailDrawerOpen}
        onClose={() => setDetailDrawerOpen(false)}
        extra={
          <Space>
            <Button size="small" icon={<CodeOutlined />} onClick={() => { if (detailNode) showSource(detailNode.fullMethod); }}>
              查看源码
            </Button>
            {detailNode?.boundaries?.some(b => b.boundaryType === 'HTTP' || b.boundaryType === 'GRPC') && (
              <Button size="small" icon={<ApiOutlined />} onClick={() => { if (detailNode) openMockEditor(detailNode.fullMethod); }}>
                Mock
              </Button>
            )}
          </Space>
        }
      >
        {detailNode && (
          <div style={{ fontSize: 13 }}>
            {/* 方法签名 */}
            <div style={{ padding: '8px 12px', background: '#fafafa', borderRadius: 6, marginBottom: 12, fontFamily: 'monospace', fontSize: 12, wordBreak: 'break-all' }}>
              {detailNode.fullMethod}
            </div>

            {/* 调用类型 */}
            {detailNode.callType && (
              <div style={{ marginBottom: 12 }}>
                <span style={{ color: '#8c8c8c' }}>调用类型: </span>
                <Tag>{detailNode.callType}</Tag>
                {detailNode.lineNumber && <span style={{ color: '#8c8c8c', marginLeft: 8 }}>行号: {detailNode.lineNumber}</span>}
              </div>
            )}

            {/* 诊断状态 */}
            {detailNode.diagStatus && (
              <div style={{
                padding: '10px 12px', borderRadius: 6, marginBottom: 12,
                background: detailNode.diagStatus === 'ERROR' ? '#fff2f0' : detailNode.diagStatus === 'OK' ? '#f6ffed' : '#fffbe6',
                border: `1px solid ${detailNode.diagStatus === 'ERROR' ? '#ffccc7' : detailNode.diagStatus === 'OK' ? '#b7eb8f' : '#ffe58f'}`,
              }}>
                <div style={{ fontWeight: 600, marginBottom: 4 }}>
                  {detailNode.diagStatus === 'OK' ? '✅ 正常' : detailNode.diagStatus === 'ERROR' ? '❌ 异常' : '❓ 未识别'}
                </div>
                {detailNode.diagMessage && (
                  <div style={{ fontSize: 12, color: '#cf1322', whiteSpace: 'pre-wrap' }}>{detailNode.diagMessage}</div>
                )}
              </div>
            )}

            {/* 递归标记 */}
            {detailNode.isRecursive && (
              <div style={{ padding: '8px 12px', background: '#fff2f0', borderRadius: 6, marginBottom: 12, color: '#ff4d4f' }}>
                ⚠️ 递归调用 — 此节点在调用链中形成环
              </div>
            )}

            {/* 歧义标记 */}
            {detailNode.ambiguous && (
              <div style={{ padding: '8px 12px', background: '#fff7e6', borderRadius: 6, marginBottom: 12, color: '#d48806', borderLeft: '3px solid #faad14' }}>
                <div style={{ fontWeight: 600, marginBottom: 4 }}>
                  ⚠️ 此节点在多个仓库中非唯一性
                </div>
                <div style={{ fontSize: 12 }}>
                  此方法签名在多个仓库中有相同定义。静态分析仅靠签名匹配，无法确定调用真正指向哪个仓库的实现，请人工确认。
                </div>
              </div>
            )}

            {/* 边界点详情 */}
            {detailNode.boundaries && detailNode.boundaries.length > 0 && (
              <div style={{ marginBottom: 12 }}>
                <div style={{ fontWeight: 600, marginBottom: 8 }}>边界点 ({detailNode.boundaries.length})</div>
                {detailNode.boundaries.map((b, i) => {
                  const color = BOUNDARY_COLORS[b.boundaryType] ?? '#999';
                  const contextLines = (b.context || '').split('\n');
                  return (
                    <div key={i} style={{ padding: '8px 12px', background: '#fafafa', borderRadius: 6, marginBottom: 6, borderLeft: `3px solid ${color}` }}>
                      <div style={{ fontWeight: 500 }}>
                        <Tag color={color} style={{ marginRight: 6 }}>{b.boundaryType}</Tag>
                        {contextLines[0]}
                        {b.lineNumber ? <span style={{ color: '#999', marginLeft: 8 }}>(行 {b.lineNumber})</span> : null}
                      </div>
                      {contextLines.length > 1 && (
                        <div style={{ marginTop: 6, padding: '6px 8px', background: '#f0f0f0', borderRadius: 4, fontFamily: 'monospace', fontSize: 11, color: '#555' }}>
                          {contextLines.slice(1).filter(l => l.trim()).map((line, j) => (
                            <div key={j}>{line}</div>
                          ))}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            )}

            {/* 无边界点时 */}
            {(!detailNode.boundaries || detailNode.boundaries.length === 0) && !detailNode.diagStatus && !detailNode.isRecursive && (
              <div style={{ color: '#999', textAlign: 'center', padding: 20 }}>
                普通业务方法，无特殊标记
              </div>
            )}
          </div>
        )}
      </Drawer>

      {/* ── Source Code Drawer ── */}
      <Drawer
        title={
          <span style={{ fontSize: 13 }}>
            <CodeOutlined style={{ marginRight: 8 }} />
            {sourceMethod.length > 80 ? sourceMethod.slice(0, 78) + '…' : sourceMethod}
          </span>
        }
        placement="bottom"
        height="55%"
        open={sourceDrawerOpen}
        onClose={() => setSourceDrawerOpen(false)}
        destroyOnClose
      >
        {loadingSource ? (
          <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>
        ) : (
          <div style={{ height: '100%', display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
            {/* 上半部分：方法信息（可滚动，最多占 40%） */}
            <div style={{ maxHeight: '40%', overflow: 'auto', flexShrink: 0 }}>
            {/* 方法签名 */}
            {sourceDetail?.methodSignature && (
              <div style={{ padding: '8px 12px', background: '#e8f4fd', borderRadius: 6, marginBottom: 8, fontFamily: 'monospace', fontSize: 13, color: '#0050b3', wordBreak: 'break-all' }}>
                📋 {sourceDetail.methodSignature}
              </div>
            )}

            {/* 入参实体类字段 */}
            {sourceDetail?.paramClasses && sourceDetail.paramClasses.length > 0 && (
              <div style={{ padding: '8px 12px', background: '#f6ffed', borderRadius: 6, marginBottom: 8, fontSize: 12, borderLeft: '3px solid #52c41a' }}>
                <div style={{ fontWeight: 600, marginBottom: 6, color: '#389e0d' }}>📦 入参实体类字段</div>
                {sourceDetail.paramClasses.map((pc, i) => (
                  <div key={i} style={{ marginBottom: i < sourceDetail.paramClasses.length - 1 ? 8 : 0 }}>
                    <div style={{ fontWeight: 600, color: '#531dab', marginBottom: 2 }}>{pc.shortName}</div>
                    {pc.fields.map((f, j) => {
                      const isRequired = f.startsWith('* ');
                      const fieldText = isRequired ? f.substring(2) : f.startsWith('  ') ? f.substring(2) : f;
                      const commentIdx = fieldText.indexOf('//');
                      const annoIdx = fieldText.indexOf('@');
                      const mainEnd = annoIdx > 0 && (commentIdx < 0 || annoIdx < commentIdx) ? annoIdx : (commentIdx > 0 ? commentIdx : fieldText.length);
                      const fieldPart = fieldText.substring(0, mainEnd).trim();
                      const restPart = fieldText.substring(mainEnd);
                      return (
                        <div key={j} style={{ fontFamily: 'monospace', paddingLeft: 12, lineHeight: 1.8, display: 'flex', alignItems: 'baseline', gap: 4 }}>
                          <span style={{ color: isRequired ? '#ff4d4f' : '#d9d9d9', fontWeight: 600, width: 10, flexShrink: 0 }}>
                            {isRequired ? '*' : ' '}
                          </span>
                          <span style={{ color: '#333' }}>{fieldPart}</span>
                          {restPart && <span style={{ color: '#8c8c8c', fontSize: 11 }}>{restPart}</span>}
                        </div>
                      );
                    })}
                  </div>
                ))}
              </div>
            )}

            {/* 调用链上游上下文 */}
            {sourceDetail?.chainContext && sourceDetail.chainContext.length > 0 && (
              <div style={{ padding: '8px 12px', background: '#f0f5ff', borderRadius: 6, marginBottom: 8, fontSize: 12, borderLeft: '3px solid #1890ff' }}>
                <div style={{ fontWeight: 600, marginBottom: 4, color: '#1890ff' }}>🔗 调用链上游传递的值</div>
                {sourceDetail.chainContext.map((v, i) => {
                  const [name, ...valueParts] = v.split(' = ');
                  const value = valueParts.join(' = ');
                  return (
                    <div key={i} style={{ fontFamily: 'monospace', padding: '2px 0' }}>
                      <span style={{ color: '#531dab' }}>{name}</span>
                      <span style={{ color: '#999' }}> = </span>
                      <span style={{ color: '#389e0d' }}>{value}</span>
                    </div>
                  );
                })}
              </div>
            )}

            {/* 当前节点枚举值 */}
            {sourceDetail?.enumValues && sourceDetail.enumValues.length > 0 && (
              <div style={{ padding: '8px 12px', background: '#fff7e6', borderRadius: 6, marginBottom: 8, fontSize: 12 }}>
                <div style={{ fontWeight: 600, marginBottom: 4, color: '#d48806' }}>📌 常量/枚举值解析</div>
                {sourceDetail.enumValues.map((v, i) => {
                  const [name, ...valueParts] = v.split(' = ');
                  const value = valueParts.join(' = ');
                  return (
                    <div key={i} style={{ fontFamily: 'monospace', padding: '2px 0' }}>
                      <span style={{ color: '#531dab' }}>{name}</span>
                      <span style={{ color: '#999' }}> = </span>
                      <span style={{ color: '#389e0d' }}>{value}</span>
                    </div>
                  );
                })}
              </div>
            )}

            {/* 上半部分结束 */}
            </div>

            {/* 源码（占剩余空间） */}
            <div style={{ flex: 1, overflow: 'hidden', minHeight: '200px' }}>
              <JavaCodeViewer
                code={sourceCode}
                startLine={sourceDetail?.startLine ?? 1}
                maxHeight="100%"
              />
            </div>
          </div>
        )}
      </Drawer>

      {/* ── Log Analysis Drawer ── */}
      <Drawer
        title={<span><BugOutlined style={{ marginRight: 8 }} />日志诊断</span>}
        placement="right"
        width={560}
        open={logDrawerOpen}
        onClose={() => setLogDrawerOpen(false)}
      >
        <div style={{ marginBottom: 12 }}>
          <div style={{ fontWeight: 500, marginBottom: 6 }}>粘贴线上日志：</div>
          <Input.TextArea
            rows={10}
            value={logText}
            onChange={e => setLogText(e.target.value)}
            placeholder="粘贴包含异常堆栈的日志内容..."
            style={{ fontFamily: 'monospace', fontSize: 12 }}
          />
        </div>
        <Button
          type="primary"
          danger
          icon={<BugOutlined />}
          onClick={handleAnalyzeLog}
          loading={logAnalyzing}
          disabled={!selectedEntry}
          block
        >
          分析日志
        </Button>

        {logResult && (
          <div style={{ marginTop: 16 }}>
            <div style={{ fontWeight: 600, marginBottom: 8 }}>{logResult.summary}</div>
            {logResult.extractedException && (
              <div style={{ padding: '8px 12px', background: '#fff2f0', borderRadius: 6, marginBottom: 8, fontSize: 12, color: '#cf1322' }}>
                异常: {logResult.extractedException}
              </div>
            )}
            {logResult.extractedUrl && (
              <div style={{ padding: '8px 12px', background: '#e6f7ff', borderRadius: 6, marginBottom: 8, fontSize: 12 }}>
                URL: {logResult.extractedUrl}
              </div>
            )}
            <div style={{ fontSize: 12, color: '#666', marginBottom: 8 }}>
              节点状态（点击 ❌ 节点可查看详情，点击 <ApiOutlined /> 可配置 Mock）：
            </div>
            <div style={{ maxHeight: 300, overflow: 'auto' }}>
              {logResult.nodeStatuses.map((ns, i) => {
                const icon = ns.status === 'OK' ? '✅' : ns.status === 'ERROR' ? '❌' : '❓';
                const shortMethod = ns.fullMethod.split(':').pop()?.split('(')[0] ?? ns.fullMethod;
                const shortClass = ns.fullMethod.split(':')[0]?.split('.').pop() ?? '';
                return (
                  <div key={i} style={{
                    padding: '6px 8px', borderBottom: '1px solid #f5f5f5',
                    background: ns.status === 'ERROR' ? '#fff2f0' : 'transparent',
                    display: 'flex', alignItems: 'flex-start', gap: 8,
                  }}>
                    <span>{icon}</span>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ fontSize: 12, fontWeight: 500 }}>{shortClass}.{shortMethod}</div>
                      {ns.errorMessage && (
                        <div style={{ fontSize: 11, color: '#cf1322', marginTop: 2, wordBreak: 'break-all' }}>
                          {ns.errorMessage}
                        </div>
                      )}
                    </div>
                    {ns.status === 'ERROR' && (
                      <Tooltip title="配置 Mock">
                        <Button size="small" icon={<ApiOutlined />} onClick={() => openMockEditor(ns.fullMethod)} />
                      </Tooltip>
                    )}
                  </div>
                );
              })}
            </div>
          </div>
        )}
      </Drawer>

      {/* ── Mock Editor Modal ── */}
      <Modal
        title={<span><ApiOutlined style={{ marginRight: 8 }} />配置 Mock 数据</span>}
        open={mockModalOpen}
        onOk={handleSaveMock}
        onCancel={() => setMockModalOpen(false)}
        okText="保存"
        cancelText="取消"
        width={600}
      >
        <div style={{ fontSize: 12, color: '#666', marginBottom: 12 }}>
          方法: {mockMethod.length > 80 ? mockMethod.slice(0, 78) + '…' : mockMethod}
        </div>
        <div style={{ marginBottom: 12 }}>
          <div style={{ fontWeight: 500, marginBottom: 4 }}>Mock 请求入参 (JSON)：</div>
          <Input.TextArea
            rows={6}
            value={mockRequest}
            onChange={e => setMockRequest(e.target.value)}
            style={{ fontFamily: 'monospace', fontSize: 12 }}
          />
        </div>
        <div>
          <div style={{ fontWeight: 500, marginBottom: 4 }}>Mock 返回值 (JSON)：</div>
          <Input.TextArea
            rows={6}
            value={mockResponse}
            onChange={e => setMockResponse(e.target.value)}
            style={{ fontFamily: 'monospace', fontSize: 12 }}
          />
        </div>
      </Modal>

      {/* ── Generated Code Drawer ── */}
      <Drawer
        title={<span><CodeOutlined style={{ marginRight: 8 }} />调用链展平代码（可直接运行）</span>}
        placement="bottom"
        height="60%"
        open={codeDrawerOpen}
        onClose={() => setCodeDrawerOpen(false)}
        extra={
          <Button size="small" onClick={() => {
            navigator.clipboard.writeText(generatedCode);
            message.success('已复制到剪贴板');
          }}>
            复制代码
          </Button>
        }
      >
        <div style={{ height: '100%' }}>
          <JavaCodeViewer code={generatedCode} maxHeight="100%" />
        </div>
      </Drawer>

      {/* ── Doc Drawer ── */}
      <Drawer
        title={<span><FileTextOutlined style={{ marginRight: 8 }} />{docType === 'product' ? '产品功能文档' : '研发技术文档'}</span>}
        placement="right"
        width={700}
        open={docDrawerOpen}
        onClose={() => setDocDrawerOpen(false)}
        extra={
          <Space>
            <Button size="small" type={docType === 'product' ? 'primary' : 'default'} onClick={() => handleGenerateDoc('product')}>产品视角</Button>
            <Button size="small" type={docType === 'dev' ? 'primary' : 'default'} onClick={() => handleGenerateDoc('dev')}>研发视角</Button>
            {docType === 'product' && (
              <Select
                size="small"
                value={selectedDiagramType}
                onChange={handleDiagramTypeChange}
                style={{ width: 120 }}
                loading={diagramLoading}
                options={[
                  { label: '📊 流程图', value: 'flowchart' },
                  { label: '⏱️ 时序图', value: 'sequence' },
                  { label: '🏊 泳道图', value: 'swimlane' },
                ]}
              />
            )}
            <Button size="small" onClick={() => {
              navigator.clipboard.writeText(docContent);
              message.success('已复制到剪贴板');
            }}>复制</Button>
          </Space>
        }
      >
        {docLoading ? (
          <div style={{ textAlign: 'center', padding: 40 }}><Spin size="large" /></div>
        ) : (
          <div className="markdown-body" style={{ padding: '0 8px' }}>
            <ReactMarkdown
              remarkPlugins={[remarkGfm]}
              components={{
                code: ({ className, children }) => {
                  const match = /language-mermaid/.exec(className || '');
                  if (match) {
                    // 如果是产品文档且有图表数据，使用选中的图表类型
                    if (docType === 'product' && docDiagrams[selectedDiagramType]) {
                      // 从图表数据中提取纯 Mermaid 代码（去掉 ```mermaid 标记）
                      let mermaidCode = docDiagrams[selectedDiagramType];
                      if (mermaidCode.startsWith('```mermaid')) {
                        mermaidCode = mermaidCode.replace(/^```mermaid\n/, '').replace(/\n```$/, '');
                      }
                      return <MermaidBlock code={mermaidCode} />;
                    }
                    return <MermaidBlock code={String(children).trim()} />;
                  }
                  // Inline code
                  if (!className) {
                    return <code style={{ background: '#f0f0f0', padding: '2px 6px', borderRadius: 3, fontSize: '0.9em' }}>{children}</code>;
                  }
                  // Regular code block
                  return <code className={className}>{children}</code>;
                },
                pre: ({ children }) => <>{children}</>,
                // Support HTML details/summary for collapsible sections
                details: ({ children }) => <details style={{ marginBottom: 16 }}>{children}</details>,
                summary: ({ children }) => <summary style={{ cursor: 'pointer', padding: '8px 0', fontWeight: 500, fontSize: 14, color: '#1890ff' }}>{children}</summary>,
              }}
            >{docContent}</ReactMarkdown>
          </div>
        )}
      </Drawer>
    </div>
  );
}
