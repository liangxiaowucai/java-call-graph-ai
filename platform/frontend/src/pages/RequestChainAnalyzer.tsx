import { useState, useEffect } from 'react';
import { Card, Button, Input, message, Spin, Tag, Typography, Space, Upload, Collapse, Tabs, Modal, Descriptions } from 'antd';
import { ThunderboltOutlined, CopyOutlined, UploadOutlined, WarningOutlined, CheckCircleOutlined, ApiOutlined } from '@ant-design/icons';
import type { UploadFile } from 'antd/es/upload/interface';
import JavaCodeViewer from '../components/JavaCodeViewer';
import axios from 'axios';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import mermaid from 'mermaid';
import Prism from 'prismjs';
import 'prismjs/components/prism-java';
import '../markdown.css';

const { TextArea } = Input;
const { Title, Text, Paragraph } = Typography;

// 初始化 Mermaid
mermaid.initialize({
  startOnLoad: true,
  theme: 'default',
  securityLevel: 'loose',
  fontSize: 14,
});

interface ApiCall {
  url: string;
  method?: string;
  requestMethod?: string;
  requestBody?: string;
  responseBody?: string;
  requestHeaders?: Record<string, string>;
  status: number;
  recommendedRepo?: any;
  diagnosis?: string;
  callTree?: any;
  downstreamMethods?: string[];
  httpCalls?: string[];
  relationshipToNext?: any;
}

interface AnalysisResult {
  summary: string;
  apiCalls: ApiCall[];
  dataFlowIssues: any[];
  recommendedRepos: any[];
  report: string;
}

// 边界详情模态框组件
function BoundaryDetailModal({ visible, boundary, onClose }: any) {
  if (!boundary) return null;

  const { boundaryType, context } = boundary;
  
  const parseContext = () => {
    if (!context) return {};
    const lines = context.split('\n').filter((l: string) => l.trim());
    const data: any = {};
    
    lines.forEach((line: string) => {
      if (line.includes('📌 URL:')) {
        data.url = line.replace('📌 URL:', '').trim();
      } else if (line.includes(' = ')) {
        const [key, value] = line.split(' = ', 2);
        data[key.trim()] = value.trim();
      } else if (line.includes(':') && !line.includes('📌')) {
        // 处理其他 key: value 格式
        const colonIdx = line.indexOf(':');
        const key = line.substring(0, colonIdx).trim();
        const value = line.substring(colonIdx + 1).trim();
        if (key && value) {
          data[key] = value;
        }
      }
    });
    
    return data;
  };

  const data = parseContext();

  return (
    <Modal
      title={`${getBoundaryIcon(boundaryType)} ${boundaryType} 配置详情`}
      open={visible}
      onCancel={onClose}
      footer={[
        <Button key="close" onClick={onClose}>关闭</Button>
      ]}
      width={700}
    >
      <Descriptions bordered column={1} size="small">
        {boundaryType === 'HTTP' && (
          <>
            {data.url && (
              <Descriptions.Item label="完整URL">
                <Text code copyable style={{ fontSize: 12, wordBreak: 'break-all' }}>{data.url}</Text>
              </Descriptions.Item>
            )}
            {!data.url && context && (
              <Descriptions.Item label="调用信息">
                <Text style={{ fontSize: 12, whiteSpace: 'pre-wrap' }}>{context}</Text>
              </Descriptions.Item>
            )}
            {data['server.port'] && (
              <Descriptions.Item label="端口">
                <Text code>{data['server.port']}</Text>
              </Descriptions.Item>
            )}
            {data['server.servlet.context-path'] && (
              <Descriptions.Item label="上下文路径">
                <Text code>{data['server.servlet.context-path']}</Text>
              </Descriptions.Item>
            )}
            {Object.entries(data).map(([key, value]) => {
              if (!['url', 'server.port', 'server.servlet.context-path'].includes(key)) {
                return (
                  <Descriptions.Item key={key} label={key}>
                    <Text code style={{ fontSize: 11, wordBreak: 'break-all' }}>{String(value)}</Text>
                  </Descriptions.Item>
                );
              }
              return null;
            })}
          </>
        )}
        {boundaryType === 'DB' && (
          <Descriptions.Item label="SQL/操作">
            <Text code style={{ fontSize: 11, whiteSpace: 'pre-wrap', display: 'block', maxHeight: 400, overflow: 'auto' }}>{context}</Text>
          </Descriptions.Item>
        )}
        {!['HTTP', 'DB'].includes(boundaryType) && (
          <Descriptions.Item label="详细信息">
            <Text style={{ fontSize: 11, whiteSpace: 'pre-wrap' }}>{context}</Text>
          </Descriptions.Item>
        )}
      </Descriptions>
    </Modal>
  );
}

function getBoundaryIcon(type: string): string {
  switch (type) {
    case 'DB': return '🗄️';
    case 'HTTP': return '🌐';
    case 'GRPC':
    case 'RPC': return '📡';
    case 'CACHE':
    case 'REDIS': return '⚡';
    case 'MQ': return '📬';
    default: return '🔌';
  }
}

// 完整调用链树：递归渲染，默认全部展开。方法名/边界/错误码均可点击跳转源码
function CallChainTreeView({ node, depth = 0, repoId, onJump }: { node: any; depth?: number; repoId?: number; onJump?: (fullMethod: string, line?: number) => void }) {
  if (!node) return null;
  const cls = (node.className || node.fullMethod?.split(':')[0] || '').split('.').pop() || '';
  const method = node.methodName || node.fullMethod?.split(':')[1]?.split('(')[0] || '';
  const ct = node.callType || '';
  const ctLabelMap: Record<string, string> = { INT: '接口', _ITF: '实现', _LM: '异步λ', VIR: '多态', STA: '静态', SPE: '私有', IMPL: '实现', DYN: '动态' };
  const cleanUrl = (ctx?: string) => {
    if (!ctx) return '';
    const u = ctx.match(/📌 URL:\s*(.+)/)?.[1]?.trim() || ctx.split('\n')[0].trim();
    return u.replace(/\s*\(\$\{[^}]*\}\)\s*$/, '').trim();
  };
  // 边界 context 第二行（📝 源码行）
  const ctxSrc = (ctx?: string) => {
    if (!ctx) return '';
    const m = ctx.match(/📝\s*(.+)/);
    return m ? m[1].trim() : '';
  };
  const jump = (line?: number) => onJump && node.fullMethod && onJump(node.fullMethod, line);
  const httpB = (node.boundaries || []).filter((b: any) => ['HTTP', 'RPC', 'GRPC'].includes(b.boundaryType));
  const ioB = (node.boundaries || []).filter((b: any) => ['DB', 'CACHE', 'REDIS', 'MQ'].includes(b.boundaryType));
  // 错误码（node.exceptions 现为 [{code,msg,line,...}]）
  let errs: any[] = [];
  if (node.exceptions) { try { const v = JSON.parse(node.exceptions); if (Array.isArray(v)) errs = v; } catch { /* */ } }

  return (
    <div style={{ marginLeft: depth > 0 ? 14 : 0, borderLeft: depth > 0 ? '1px dashed #d0d7de' : 'none', paddingLeft: depth > 0 ? 10 : 0 }}>
      <div style={{ padding: '3px 0', display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 4 }}>
        <span style={{ color: '#999', fontSize: 11 }}>›</span>
        <span
          style={{ fontFamily: 'monospace', fontSize: 12.5, cursor: 'pointer' }}
          onClick={() => jump()}
          title="点击查看该方法源码"
        >
          <b style={{ color: '#1677ff' }}>{cls}</b>
          <span style={{ color: '#555' }}>.{method}()</span>
        </span>
        {ct && <Tag color={ct === '_LM' ? 'purple' : 'default'} style={{ fontSize: 10, lineHeight: '16px', margin: 0 }}>{ctLabelMap[ct] || ct}</Tag>}
        {httpB.map((b: any, i: number) => (
          <Tag key={'h' + i} color="red" style={{ fontSize: 11, margin: 0, cursor: 'pointer' }} onClick={() => jump(b.lineNumber)}>🌐 {cleanUrl(b.context)}</Tag>
        ))}
        {ioB.map((b: any, i: number) => (
          <Tag key={'d' + i} color="green" style={{ fontSize: 11, margin: 0, cursor: 'pointer' }} onClick={() => jump(b.lineNumber)} title={ctxSrc(b.context)}>
            {b.boundaryType === 'DB' ? '🗄️' : b.boundaryType === 'MQ' ? '📬' : '⚡'} {(b.context || '').split('\n')[0]}
          </Tag>
        ))}
        {errs.map((e: any, i: number) => (
          <Tag key={'e' + i} color="volcano" style={{ fontSize: 11, margin: 0, cursor: 'pointer' }} onClick={() => jump(e.line)} title={e.codeText}>
            ❗{e.code ? 'code=' + e.code : ''}{e.msg ? ' ' + e.msg : ''}
          </Tag>
        ))}
        {node.isRecursive && <Tag color="gold" style={{ fontSize: 10, margin: 0 }}>♻ 递归</Tag>}
        {node.isLazyLoad && <Tag color="orange" style={{ fontSize: 10, margin: 0 }}>未展开</Tag>}
      </div>
      {/* 缓存/DB 操作的源码行直接展示一行，便于一眼看清 key/SQL */}
      {ioB.filter((b: any) => ctxSrc(b.context)).map((b: any, i: number) => (
        <div key={'src' + i} style={{ marginLeft: 18, fontSize: 11, color: '#888', fontFamily: 'monospace', cursor: 'pointer' }} onClick={() => jump(b.lineNumber)}>
          📝 {ctxSrc(b.context)}
        </div>
      ))}
      {(node.children || []).map((c: any, i: number) => (
        <CallChainTreeView key={i} node={c} depth={depth + 1} repoId={repoId} onJump={onJump} />
      ))}
    </div>
  );
}

// 调用链方法源码查看器：展开某方法时按需从后端拉取完整源码
function MethodSourceViewer({ repoId, methods }: { repoId: number; methods: { fullMethod: string; label: string; callType: string }[] }) {
  const [sources, setSources] = useState<Record<string, string>>({});
  const [loadingKey, setLoadingKey] = useState<string | null>(null);

  const loadSource = async (fm: string) => {
    if (sources[fm] !== undefined) return;
    setLoadingKey(fm);
    try {
      const resp = await axios.get(`/api/repos/${repoId}/source`, { params: { method: fm } });
      const src = resp.data?.data ?? resp.data;
      setSources((prev) => ({ ...prev, [fm]: typeof src === 'string' ? src : JSON.stringify(src) }));
    } catch {
      setSources((prev) => ({ ...prev, [fm]: '// 未找到源码（接口/抽象方法或第三方库方法）' }));
    } finally {
      setLoadingKey(null);
    }
  };

  return (
    <Collapse
      accordion
      onChange={(key) => {
        const k = Array.isArray(key) ? key[0] : key;
        if (k) loadSource(String(k));
      }}
      items={methods.map((m) => ({
        key: m.fullMethod,
        label: (
          <span style={{ fontFamily: 'monospace', fontSize: 12 }}>
            {m.label}
            {m.callType && <Tag style={{ marginLeft: 8 }}>{m.callType}</Tag>}
          </span>
        ),
        children: loadingKey === m.fullMethod
          ? <Spin size="small" />
          : (
            sources[m.fullMethod]
              ? <JavaCodeViewer code={sources[m.fullMethod]} maxHeight="420px" />
              : <div style={{ color: '#999', fontSize: 12, padding: 8 }}>展开以加载源码…</div>
          ),
      }))}
    />
  );
}

export default function RequestChainAnalyzer() {
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<AnalysisResult | null>(null);
  const [inputText, setInputText] = useState('');
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [progressLogs, setProgressLogs] = useState<{ stage: string; detail: string }[]>([]);
  const [aiText, setAiText] = useState('');
  const [selectedBoundary, setSelectedBoundary] = useState<any>(null);
  // 源码跳转弹窗
  const [srcModal, setSrcModal] = useState<{ open: boolean; title: string; code: string; startLine: number; highlight: number[]; loading: boolean }>(
    { open: false, title: '', code: '', startLine: 1, highlight: [], loading: false });

  // 让外壳内容容器在本页改为「窗口滚动」模式，便于 GoFullPage 等全页截图插件完整抓取
  // （其它页面仍保持固定高度内滚布局，卸载时恢复）
  useEffect(() => {
    const el = document.querySelector('.site-layout-content');
    el?.classList.add('page-scroll-natural');
    return () => el?.classList.remove('page-scroll-natural');
  }, []);

  // 打开源码弹窗并按需加载（引用跳转）
  const openSource = async (repoId: number, fullMethod: string, line?: number) => {
    if (!repoId || !fullMethod) return;
    setSrcModal({ open: true, title: fullMethod, code: '', startLine: 1, highlight: line ? [line] : [], loading: true });
    try {
      const resp = await axios.get(`/api/repos/${repoId}/source-detail`, { params: { method: fullMethod } });
      const d = resp.data?.data ?? resp.data;
      setSrcModal({
        open: true,
        title: fullMethod,
        code: d?.sourceCode || d?.source || '// 未找到源码',
        startLine: d?.startLine || 1,
        highlight: line ? [line] : [],
        loading: false,
      });
    } catch {
      // 退回到不带行号的源码接口
      try {
        const resp2 = await axios.get(`/api/repos/${repoId}/source`, { params: { method: fullMethod } });
        const src = resp2.data?.data ?? resp2.data;
        setSrcModal({ open: true, title: fullMethod, code: typeof src === 'string' ? src : '// 未找到源码', startLine: 1, highlight: [], loading: false });
      } catch {
        setSrcModal({ open: true, title: fullMethod, code: '// 未找到源码（接口/第三方库方法）', startLine: 1, highlight: [], loading: false });
      }
    }
  };

  // 渲染 Mermaid 图表
  useEffect(() => {
    if (result) {
      setTimeout(() => {
        mermaid.contentLoaded();
      }, 100);
    }
  }, [result]);

  // 自定义代码块渲染器：mermaid 渲染图，其它语言用 Prism 高亮
  const renderCodeBlock = ({ inline, className, children, ...props }: any) => {
    const match = /language-(\w+)/.exec(className || '');
    const language = match ? match[1] : '';
    const value = String(children).replace(/\n$/, '');

    if (language === 'mermaid') {
      return (
        <div className="mermaid" style={{ textAlign: 'center', margin: '16px 0' }}>
          {value}
        </div>
      );
    }

    // 行内代码
    const isInline = inline || (!match && !value.includes('\n'));
    if (isInline) {
      return (
        <code style={{ background: '#eef1f5', color: '#c7254e', padding: '1px 5px', borderRadius: 3, fontSize: 12.5 }}>
          {children}
        </code>
      );
    }

    // 块级代码：Prism 高亮（无对应语法时回退 java）
    const grammar = (language && (Prism.languages as any)[language]) || Prism.languages.java;
    let html = '';
    try {
      html = Prism.highlight(value, grammar, language || 'java');
    } catch {
      html = value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }
    return (
      <pre style={{ background: '#0d1117', borderRadius: 6, padding: 12, overflowX: 'auto', margin: '8px 0' }}>
        {language && <div style={{ color: '#8b949e', fontSize: 11, marginBottom: 6 }}>{language}</div>}
        <code
          className={`language-${language || 'java'}`}
          style={{ color: '#c9d1d9', fontSize: 12.5, fontFamily: 'monospace', background: 'transparent' }}
          dangerouslySetInnerHTML={{ __html: html }}
        />
      </pre>
    );
  };

  const detectInputType = (text: string): 'json' | 'curl' | 'unknown' => {
    const trimmed = text.trim();
    if (trimmed.startsWith('curl ')) {
      return 'curl';
    }
    if ((trimmed.startsWith('{') && trimmed.endsWith('}')) || 
        (trimmed.startsWith('[') && trimmed.endsWith(']'))) {
      return 'json';
    }
    return 'unknown';
  };

  // 从 Markdown 报告中提取指定章节
  const extractSection = (markdown: string, sectionTitle: string, nextSectionPrefix: string = '##'): string => {
    const lines = markdown.split('\n');
    let inSection = false;
    const sectionLines: string[] = [];
    
    for (const line of lines) {
      if (line.startsWith(sectionTitle)) {
        inSection = true;
        sectionLines.push(line);
        continue;
      }
      
      if (inSection) {
        if (line.startsWith(nextSectionPrefix) && line !== sectionTitle) {
          break;
        }
        sectionLines.push(line);
      }
    }
    
    return sectionLines.join('\n');
  };

  // 详细分析报告精简：去掉与上方卡片重复的章节
  // （# 请求链分析报告 标题 / ## 请求详情 / #### 🔗 外部调用分析 / #### 📝 常量与异常 / ⚠️ 抛出/捕获的异常）
  // 仅保留剩余内容（如 ## 📊 性能总览 等）
  const filterDetailReport = (markdown?: string): string => {
    if (!markdown) return '';
    const blockedHeadings = ['请求详情', '外部调用分析', '常量与异常'];
    const lines = markdown.split('\n');
    const out: string[] = [];
    let skipLevel = 0; // 0=不跳过；>0=正在跳过该层级标题及其所有子内容
    for (const line of lines) {
      const h = /^(#{1,6})\s+(.*)$/.exec(line);
      if (h) {
        const level = h[1].length;
        const text = h[2];
        // H1 主标题直接丢弃，但不进入跳过模式
        if (level === 1) continue;
        if (skipLevel > 0) {
          if (level <= skipLevel) {
            // 遇到同级或更高级标题：若仍是被屏蔽章节则继续跳过，否则结束跳过并保留本行
            if (blockedHeadings.some((k) => text.includes(k))) {
              skipLevel = level;
              continue;
            }
            skipLevel = 0;
          } else {
            continue; // 子层级，继续跳过
          }
        }
        if (blockedHeadings.some((k) => text.includes(k))) {
          skipLevel = level;
          continue;
        }
        out.push(line);
        continue;
      }
      if (skipLevel > 0) continue;
      out.push(line);
    }
    return out.join('\n').replace(/\n{3,}/g, '\n\n').trim();
  };

  const handleAnalyze = async () => {
    if (!inputText.trim()) {
      message.warning('请输入 JSON 数据或 cURL 命令');
      return;
    }

    const inputType = detectInputType(inputText);
    
    if (inputType === 'unknown') {
      message.error('无法识别输入格式，请输入有效的 JSON 或 cURL 命令');
      return;
    }

    setLoading(true);
    setProgressLogs([]);
    setAiText('');
    setResult(null);

    const appendLog = (stage: string, detail: string) =>
      setProgressLogs((prev) => [...prev, { stage, detail }]);

    try {
      const resp = await fetch('/api/debug/analyze-stream', {
        method: 'POST',
        headers: { 'Content-Type': 'text/plain' },
        body: inputText,
      });

      if (!resp.ok || !resp.body) {
        throw new Error('HTTP ' + resp.status);
      }

      const reader = resp.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let aiBuffer = '';

      const handleEvent = (eventName: string, dataStr: string) => {
        let data: any = null;
        try { data = JSON.parse(dataStr); } catch { data = dataStr; }
        if (eventName === 'progress') {
          appendLog(data.stage || 'info', data.detail || '');
        } else if (eventName === 'result') {
          setResult(data);
        } else if (eventName === 'ai') {
          aiBuffer += (data.token || '');
          setAiText(aiBuffer);
        } else if (eventName === 'ai-done') {
          if (data.text) { aiBuffer = data.text; setAiText(aiBuffer); }
        } else if (eventName === 'error') {
          message.error('分析失败: ' + (data.message || '未知错误'));
        }
      };

      // 解析 SSE：事件以空行分隔，每个事件含 event: 与 data: 行
      // eslint-disable-next-line no-constant-condition
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let idx;
        while ((idx = buffer.indexOf('\n\n')) >= 0) {
          const rawEvent = buffer.slice(0, idx);
          buffer = buffer.slice(idx + 2);
          let eventName = 'message';
          const dataLines: string[] = [];
          rawEvent.split('\n').forEach((line) => {
            if (line.startsWith('event:')) eventName = line.slice(6).trim();
            else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
          });
          if (dataLines.length > 0) handleEvent(eventName, dataLines.join('\n'));
        }
      }

      // 把 AI 文本并入报告末尾
      if (aiBuffer.trim()) {
        setResult((prev) => prev ? { ...prev, report: (prev.report || '') + '\n\n## 🤖 AI 智能分析\n\n' + aiBuffer } : prev);
      }
      message.success('分析完成');
    } catch (err: any) {
      message.error('分析失败: ' + (err.message || err));
      console.error('分析错误:', err);
    } finally {
      setLoading(false);
    }
  };

  const handlePasteExample = (type: 'json' | 'curl') => {
    if (type === 'json') {
      setInputText(`{
  "sessionId": ${Date.now()},
  "userAction": "测试用户操作",
  "timestamp": ${Date.now()},
  "url": "http://localhost:5173",
  "requestChain": [
    {
      "timestamp": ${Date.now()},
      "seq": 1,
      "method": "GET",
      "url": "/api/repos",
      "fullUrl": "http://localhost:5173/api/repos",
      "requestBody": null,
      "responseStatus": 200,
      "responseBody": "{}",
      "duration": 50,
      "success": true
    }
  ],
  "dataFlow": []
}`);
    } else {
      setInputText(`curl 'http://localhost:5173/api/repos' \\
  -H 'Accept: application/json' \\
  -H 'Authorization: Bearer your-token-here'`);
    }
  };

  const handleFileUpload = async (file: File) => {
    try {
      const text = await file.text();
      setInputText(text);
      message.success('文件内容已加载到输入框');
      setFileList([]);
      return false;
    } catch (err) {
      message.error('文件读取失败: ' + (err instanceof Error ? err.message : '未知错误'));
      return false;
    }
  };

  // 生成交互式时序图
  const generateInteractiveMermaid = (call: ApiCall): string => {
    const lines = ['sequenceDiagram'];
    lines.push('    participant Client as 客户端');
    
    if (!call.callTree || !call.callTree.root) {
      const controllerName = call.method?.split(':')[0]?.split('.').pop() || 'API';
      lines.push(`    participant API as ${controllerName}`);
      lines.push(`    Client->>+API: ${call.requestMethod || 'GET'} ${call.url?.split('?')[0] || ''}`);
      lines.push(`    API-->>-Client: ${call.status}`);
      return lines.join('\n');
    }

    const participants = new Map<string, string>();
    const sequence: string[] = [];
    let participantId = 0;
    const boundaryMap = new Map<string, any[]>();

    // 折叠接口→实现桥接：当节点只有 1 个同名子节点且子边是接口分发(_ITF/IMPL/INT)时，
    // 合并为实现节点，消除时序图里重复的同名 participant（如 Service 接口 + ServiceImpl）
    const collapseBridges = (node: any): any => {
      if (!node) return node;
      let n = node;
      while (n.children && n.children.length === 1) {
        const child = n.children[0];
        const sameMethod = (child.methodName || '') === (n.methodName || '');
        const bridge = ['_ITF', 'IMPL', 'INT'].includes(child.callType);
        // 合并时把当前节点的边界(如解析出的URL)带到实现节点上，避免丢失
        if (sameMethod && bridge) {
          const mergedBoundaries = [...(n.boundaries || []), ...(child.boundaries || [])];
          n = { ...child, boundaries: mergedBoundaries };
        } else break;
      }
      return { ...n, children: (n.children || []).map(collapseBridges) };
    };

    const processNode = (node: any, depth: number, parentId: string | null, maxDepth: number = 20) => {
      if (depth > maxDepth) return;

      const className = node.className || node.fullMethod?.split(':')[0] || 'Unknown';
      const methodName = node.methodName || node.fullMethod?.split(':')[1]?.split('(')[0] || 'unknown';
      const shortClassName = className.split('.').pop() || className;
      const currentId = `P${participantId++}`;
      
      participants.set(node.fullMethod, currentId);
      
      // 确保 participant 只添加一次
      const participantLabel = `participant ${currentId} as ${shortClassName}`;
      if (!lines.includes(participantLabel)) {
        lines.push(`    ${participantLabel}`);
      }

      // 收集边界信息
      if (node.boundaries && node.boundaries.length > 0) {
        boundaryMap.set(node.fullMethod, node.boundaries);
      }

      if (parentId) {
        const boundaries = node.boundaries?.filter((b: any) => 
          ['HTTP', 'RPC', 'GRPC', 'DB', 'CACHE', 'REDIS', 'MQ'].includes(b.boundaryType)
        ) || [];
        
        // 在方法名后面标注有外部调用
        let methodLabel = methodName;
        if (boundaries.length > 0) {
          const types = boundaries.map((b: any) => getBoundaryIcon(b.boundaryType)).join('');
          methodLabel = `${methodName}() ${types}`;
        } else {
          methodLabel = `${methodName}()`;
        }
        
        sequence.push(`    ${parentId}->>+${currentId}: ${methodLabel}`);
        
        if (boundaries.length > 0) {
          boundaries.forEach((b: any) => {
            const icon = getBoundaryIcon(b.boundaryType);
            if (b.boundaryType === 'HTTP' && b.context) {
              const urlMatch = b.context.match(/📌 URL:\s*(.+)/);
              if (urlMatch) {
                const fullUrl = urlMatch[1].trim();
                // 不截断，完整显示
                sequence.push(`    Note over ${currentId}: ${icon} ${b.boundaryType}<br/>${fullUrl}`);
              } else {
                // 如果没有 URL，显示原始 context
                const displayText = b.context.split('\n')[0].substring(0, 60);
                sequence.push(`    Note over ${currentId}: ${icon} ${displayText}`);
              }
            } else if (b.boundaryType === 'DB' && b.context) {
              // DB 调用：显示 SQL 的前40个字符
              const sql = b.context.split('\n')[0].trim().substring(0, 40);
              sequence.push(`    Note over ${currentId}: ${icon} ${sql}...`);
            } else {
              sequence.push(`    Note over ${currentId}: ${icon} ${b.boundaryType}`);
            }
          });
        }
        
        sequence.push(`    ${currentId}-->>-${parentId}: 返回`);
      }

      if (node.children && node.children.length > 0) {
        node.children.slice(0, 5).forEach((child: any) => {
          processNode(child, depth + 1, currentId, maxDepth);
        });
      }
    };

    const rootNode = collapseBridges(call.callTree.root);
    processNode(rootNode, 0, null);

    const rootId = participants.get(rootNode.fullMethod) || 'P0';
    lines.push(`    Client->>+${rootId}: ${call.requestMethod || 'GET'} ${call.url?.split('?')[0] || ''}`);
    lines.push(...sequence);
    lines.push(`    ${rootId}-->>-Client: ${call.status}`);

    return lines.join('\n');
  };

  // 提取调用链中所有节点的常量与异常（后端 JSON 结构化数据，带源码引用）
  // node.constants: [{value,line,code,file}]   node.exceptions: [{kind,type,line,code,file}]
  const extractConstantsAndExceptions = (callTree: any) => {
    const constants: any[] = [];
    const exceptions: any[] = [];
    const cSeen = new Set<string>();
    const eSeen = new Set<string>();
    const visited = new Set<string>();

    const parseJson = (s: any): any[] => {
      if (!s || typeof s !== 'string') return [];
      try {
        const v = JSON.parse(s);
        return Array.isArray(v) ? v : [];
      } catch {
        // 兼容旧的换行分隔格式
        return s.split('\n').filter((x: string) => x.trim()).map((x: string) => ({ value: x.trim() }));
      }
    };

    const traverse = (node: any) => {
      if (!node || !node.fullMethod || visited.has(node.fullMethod)) return;
      visited.add(node.fullMethod);
      const method = node.methodName || '';
      const cls = (node.className || '').split('.').pop() || '';

      parseJson(node.constants).forEach((c: any) => {
        const key = c.value + '|' + (c.file || '') + (c.line || '');
        if (c.value && !cSeen.has(key)) {
          cSeen.add(key);
          constants.push({ ...c, owner: `${cls}.${method}`, fullMethod: node.fullMethod });
        }
      });
      parseJson(node.exceptions).forEach((e: any) => {
        // node.exceptions 现为业务错误码：{code,msg,line,codeText,file}
        const key = (e.code || '') + '|' + (e.msg || '') + '|' + (e.file || '') + (e.line || '');
        if ((e.code || e.msg) && !eSeen.has(key)) {
          eSeen.add(key);
          exceptions.push({ ...e, owner: `${cls}.${method}`, fullMethod: node.fullMethod });
        }
      });
      if (node.children) node.children.forEach((c: any) => traverse(c));
    };

    if (callTree && callTree.root) traverse(callTree.root);
    return { constants, exceptions };
  };

  // 收集调用链上的方法（折叠接口→实现桥接），用于源码查看
  const collectChainMethods = (callTree: any): { fullMethod: string; label: string; callType: string }[] => {
    const out: { fullMethod: string; label: string; callType: string }[] = [];
    const seen = new Set<string>();
    const collapse = (node: any): any => {
      let n = node;
      while (n && n.children && n.children.length === 1) {
        const child = n.children[0];
        const sameMethod = (child.methodName || '') === (n.methodName || '');
        const bridge = ['_ITF', 'IMPL', 'INT'].includes(child.callType);
        if (sameMethod && bridge) n = child; else break;
      }
      return n;
    };
    const walk = (node: any, depth: number) => {
      const n = collapse(node);
      if (!n || !n.fullMethod || seen.has(n.fullMethod)) return;
      seen.add(n.fullMethod);
      const cls = (n.className || n.fullMethod.split(':')[0] || '').split('.').pop();
      const m = n.methodName || n.fullMethod.split(':')[1]?.split('(')[0] || '';
      out.push({ fullMethod: n.fullMethod, label: `${'　'.repeat(depth)}${cls}.${m}()`, callType: n.callType || '' });
      (n.children || []).forEach((c: any) => walk(c, depth + 1));
    };
    if (callTree && callTree.root) walk(callTree.root, 0);
    return out;
  };

  // 提取外部依赖信息
  const extractDependencies = (callTree: any) => {
    const deps: any = {
      http: [],
      rpc: [],
      db: [],
      cache: [],
      mq: [],
    };

    const traverse = (node: any) => {
      if (node.boundaries && node.boundaries.length > 0) {
        node.boundaries.forEach((raw: any) => {
          const b = { ...raw, ownerFullMethod: node.fullMethod };
          if (b.boundaryType === 'HTTP') {
            deps.http.push(b);
          } else if (['RPC', 'GRPC'].includes(b.boundaryType)) {
            deps.rpc.push(b);
          } else if (b.boundaryType === 'DB') {
            deps.db.push(b);
          } else if (['CACHE', 'REDIS'].includes(b.boundaryType)) {
            deps.cache.push(b);
          } else if (b.boundaryType === 'MQ') {
            deps.mq.push(b);
          }
        });
      }
      if (node.children) {
        node.children.forEach((child: any) => traverse(child));
      }
    };

    if (callTree && callTree.root) {
      traverse(callTree.root);
    }

    return deps;
  };

  // 渲染外部依赖卡片
  const renderDependencyCards = (call: ApiCall) => {
    if (!call.callTree) return null;

    const deps = extractDependencies(call.callTree);
    const totalDeps = deps.http.length + deps.rpc.length + deps.db.length + 
                      deps.cache.length + deps.mq.length;

    if (totalDeps === 0) {
      return <Text type="secondary">无外部依赖</Text>;
    }

    return (
      <Space direction="vertical" style={{ width: '100%' }} size="middle">
        {/* HTTP 调用 */}
        {deps.http.length > 0 && (
          <Card 
            size="small" 
            title={`🌐 HTTP 外部调用 (${deps.http.length})`}
            extra={<Tag color="blue">{deps.http.length}</Tag>}
          >
            {deps.http.map((b: any, i: number) => {
              const urlMatch = b.context?.match(/📌 URL:\s*(.+)/);
              const rawUrl = urlMatch ? urlMatch[1].trim() : b.context || '未知URL';
              const url = rawUrl.replace(/\s*\(\$\{[^}]*\}\)\s*$/, '').trim();
              const cfgMatch = rawUrl.match(/\(\$\{([^}]*)\}\)/);
              const cfgKey = cfgMatch ? cfgMatch[1] : '';
              return (
                <div 
                  key={i} 
                  style={{ marginBottom: i < deps.http.length - 1 ? 12 : 0, paddingBottom: i < deps.http.length - 1 ? 12 : 0, borderBottom: i < deps.http.length - 1 ? '1px solid #f0f0f0' : 'none' }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <Text code copyable style={{ fontSize: 13, color: '#1890ff', wordBreak: 'break-all', flex: 1 }}>{url}</Text>
                    <Button size="small" type="link" onClick={() => openSource(call.recommendedRepo?.repoId, b.ownerFullMethod, b.lineNumber)}>跳转源码</Button>
                  </div>
                  {cfgKey && <div style={{ fontSize: 11, color: '#8c8c8c', marginTop: 2 }}>配置: ${'{' + cfgKey + '}'}</div>}
                  {b.lineNumber && <div style={{ fontSize: 11, color: '#8c8c8c' }}>调用位置: {(b.ownerFullMethod || '').split(':')[0]?.split('.').pop()}:{b.lineNumber}</div>}
                </div>
              );
            })}
          </Card>
        )}

        {/* RPC 调用 */}
        {deps.rpc.length > 0 && (
          <Card 
            size="small" 
            title={`📡 RPC 调用 (${deps.rpc.length})`}
            extra={<Tag color="purple">{deps.rpc.length}</Tag>}
          >
            {deps.rpc.map((b: any, i: number) => (
              <div key={i} style={{ marginBottom: 8 }}>
                <Text code style={{ fontSize: 12 }}>{b.context || 'RPC调用'}</Text>
                <Button 
                  size="small" 
                  type="link" 
                  style={{ marginLeft: 8 }}
                  onClick={() => setSelectedBoundary(b)}
                >
                  查看详情
                </Button>
              </div>
            ))}
          </Card>
        )}

        {/* 数据库操作 */}
        {deps.db.length > 0 && (
          <Card 
            size="small" 
            title={`🗄️ 数据库操作 (${deps.db.length})`}
            extra={<Tag color="green">{deps.db.length}</Tag>}
          >
            {deps.db.slice(0, 5).map((b: any, i: number) => {
              const lines = b.context?.split('\n') || [];
              const preview = lines[0] || 'SQL查询';
              const src = lines.find((l: string) => l.startsWith('📝'))?.replace('📝', '').trim() || '';
              return (
                <div key={i} style={{ marginBottom: 8, display: 'flex', alignItems: 'center', gap: 8 }}>
                  <div style={{ flex: 1 }}>
                    <Text code style={{ fontSize: 11 }}>{preview.substring(0, 80)}</Text>
                    {src && <div style={{ fontSize: 11, color: '#888', fontFamily: 'monospace', marginTop: 2 }}>{src}</div>}
                  </div>
                  <Button size="small" type="link" onClick={() => openSource(call.recommendedRepo?.repoId, b.ownerFullMethod, b.lineNumber)}>源码</Button>
                </div>
              );
            })}
            {deps.db.length > 5 && (
              <Text type="secondary">...还有 {deps.db.length - 5} 个数据库操作</Text>
            )}
          </Card>
        )}

        {/* 缓存操作 */}
        {deps.cache.length > 0 && (
          <Card 
            size="small" 
            title={`⚡ 缓存操作 (${deps.cache.length})`}
            extra={<Tag color="orange">{deps.cache.length}</Tag>}
          >
            {deps.cache.map((b: any, i: number) => {
              const lines = (b.context || '').split('\n');
              const op = lines[0] || 'Cache';
              const src = lines.find((l: string) => l.startsWith('📝'))?.replace('📝', '').trim() || '';
              return (
                <div key={i} style={{ marginBottom: 8, display: 'flex', alignItems: 'center', gap: 8 }}>
                  <div style={{ flex: 1 }}>
                    <Text code style={{ fontSize: 12 }}>{op}</Text>
                    {src && <div style={{ fontSize: 11, color: '#888', fontFamily: 'monospace', marginTop: 2 }}>{src}</div>}
                  </div>
                  <Button size="small" type="link" onClick={() => openSource(call.recommendedRepo?.repoId, b.ownerFullMethod, b.lineNumber)}>源码</Button>
                </div>
              );
            })}
          </Card>
        )}

        {/* MQ 操作 */}
        {deps.mq.length > 0 && (
          <Card 
            size="small" 
            title={`📬 消息队列 (${deps.mq.length})`}
            extra={<Tag color="red">{deps.mq.length}</Tag>}
          >
            {deps.mq.map((b: any, i: number) => (
              <div key={i} style={{ marginBottom: 8 }}>
                <Text code style={{ fontSize: 11 }}>{b.context || 'MQ'}</Text>
              </div>
            ))}
          </Card>
        )}
      </Space>
    );
  };

  // 主渲染函数
  const renderCallGraph = () => {
    if (!result?.apiCalls || result.apiCalls.length === 0) {
      return (
        <div style={{ padding: 24, textAlign: 'center' }}>
          <Text type="secondary">没有捕获到API调用</Text>
        </div>
      );
    }

    return (
      <div style={{ padding: 24, background: '#f5f5f5', borderRadius: 4 }}>
        {result.apiCalls.map((call, index) => (
          <div key={index} style={{ marginBottom: 24 }}>
            {/* 接口基本信息 */}
            <Card
              size="small"
              style={{
                marginBottom: 16,
                borderLeft: call.status < 400 ? '4px solid #52c41a' : '4px solid #ff4d4f',
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 8 }}>
                <Tag color={call.status < 400 ? 'success' : 'error'} style={{ fontSize: 14 }}>
                  {call.status}
                </Tag>
                <Text strong style={{ fontSize: 14 }}>{call.requestMethod || 'GET'}</Text>
                <Text style={{ fontSize: 14, flex: 1 }}>{call.url?.split('?')[0]}</Text>
                {call.recommendedRepo && (
                  <Tag color="blue">{call.recommendedRepo.repoName}</Tag>
                )}
              </div>
              
              {call.method ? (
                <div style={{ fontSize: 12 }}>
                  <Text type="secondary">入口方法：</Text>
                  <Text code style={{ fontSize: 11 }}>{call.method}</Text>
                </div>
              ) : (
                <div style={{ fontSize: 12, marginTop: 8, padding: 12, background: '#fff7e6', borderRadius: 4, border: '1px solid #ffd591' }}>
                  <Text type="warning">⚠️ 未匹配到后端方法</Text>
                  <div style={{ marginTop: 8, fontSize: 11 }}>
                    <Text type="secondary">
                      可能原因：<br/>
                      1. 该API所属仓库尚未添加或正在分析中<br/>
                      2. URL路径标识符配置不正确<br/>
                      3. 这是一个前端直接请求的资源
                    </Text>
                  </div>
                  {call.url?.includes('/api/') && (
                    <div style={{ marginTop: 8 }}>
                      <Text type="secondary" style={{ fontSize: 11 }}>
                        💡 提示：请在"仓库管理"中添加包含此API的代码仓库
                      </Text>
                    </div>
                  )}
                </div>
              )}
            </Card>

            {/* 外部依赖详情（调用链树已移至页面底部统一展示） */}
            {call.callTree && (
              <Card size="small" title="🔗 外部依赖详情" style={{ marginBottom: 16 }}>
                {renderDependencyCards(call)}
              </Card>
            )}

            {/* 数据流推断：把本次提交的入参顺着调用链填充，重建真正发往下游的请求 */}
            {call.callTree && (() => {
              // 1. 入口请求体
              let reqObj: any = {};
              try { reqObj = call.requestBody ? JSON.parse(call.requestBody) : {}; } catch { reqObj = {}; }

              // 1b. 请求头也纳入取值来源（很多字段如 appId/schId 来自 u-app-id 等请求头），做 key 归一化
              const headerMap: Record<string, any> = {};
              const rawHeaders = call.requestHeaders || {};
              Object.keys(rawHeaders).forEach((hk) => {
                const v = rawHeaders[hk];
                const variants = new Set<string>();
                variants.add(hk);
                const noU = hk.replace(/^u-/i, '');              // u-app-id -> app-id
                variants.add(noU);
                // 转驼峰：app-id -> appId
                const camel = noU.toLowerCase().replace(/[-_](\w)/g, (_m, c) => c.toUpperCase());
                variants.add(camel);
                variants.forEach((key) => { if (key) headerMap[key] = v; });
              });

              // 取值：先入参体，再请求头
              const resolveValue = (field: string, key: string) => {
                if (reqObj[field] !== undefined) return { v: reqObj[field], src: '入参体' };
                if (reqObj[key] !== undefined) return { v: reqObj[key], src: '入参体' };
                if (headerMap[field] !== undefined) return { v: headerMap[field], src: '请求头' };
                if (headerMap[key] !== undefined) return { v: headerMap[key], src: '请求头' };
                return { v: undefined, src: '' };
              };

              // 2. 收集 param.put("key", xxx.getField()) 绑定，推断每个出参的实际值
              const ce = extractConstantsAndExceptions(call.callTree);
              const bindings: { key: string; field: string; value: any; src: string; code: string; loc: string }[] = [];
              const bindRe = /\.put\(\s*"([^"]+)"\s*,\s*[\w.]*\.get(\w+)\s*\(/;
              ce.constants.forEach((c: any) => {
                const m = bindRe.exec(c.code || '');
                if (m) {
                  const key = m[1];
                  const field = m[2].charAt(0).toLowerCase() + m[2].slice(1);
                  const rv = resolveValue(field, key);
                  bindings.push({ key, field, value: rv.v, src: rv.src, code: c.code, loc: `${c.file || ''}:${c.line || ''}` });
                }
              });

              // 3. 所有外部调用（HTTP/DB/RPC...），不只第一个
              const deps = extractDependencies(call.callTree);
              const cleanUrl = (ctx?: string) => {
                let u = ctx?.match(/📌 URL:\s*(.+)/)?.[1]?.trim() || (ctx || '').trim();
                const cfg = u.match(/\(\$\{([^}]*)\}\)\s*$/);
                return { url: u.replace(/\s*\(\$\{[^}]*\}\)\s*$/, '').trim(), cfgKey: cfg ? cfg[1] : '' };
              };
              const externalCalls: { type: string; url: string; cfgKey: string }[] = [];
              ['http', 'rpc', 'db', 'cache', 'mq'].forEach((t) => {
                (deps[t] || []).forEach((b: any) => {
                  const { url, cfgKey } = cleanUrl(b.context);
                  if (url) externalCalls.push({ type: t.toUpperCase(), url, cfgKey });
                });
              });

              if (bindings.length === 0 && externalCalls.length === 0) return null;

              const outBody: any = {};
              bindings.forEach((b) => { outBody[b.key] = b.value; });
              const fmt = (v: any) => v === undefined ? '（运行时注入：上下文/默认值）' : (typeof v === 'string' ? v : JSON.stringify(v));

              return (
                <Card size="small" title="🚀 数据流推断：本次请求实际发往下游的调用" style={{ marginBottom: 16 }}>
                  <div style={{ marginBottom: 12 }}>
                    <Text strong style={{ fontSize: 13 }}>① 入口请求体（你提交的）</Text>
                    <pre style={{ margin: '6px 0 0', padding: 10, background: '#f6f8fa', borderRadius: 4, fontSize: 12, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
                      {call.requestBody || '(无)'}
                    </pre>
                  </div>

                  {bindings.length > 0 && (
                    <div style={{ marginBottom: 12 }}>
                      <Text strong style={{ fontSize: 13 }}>② 参数绑定推断（key ← getter ← 实参值）</Text>
                      <div style={{ marginTop: 6 }}>
                        {bindings.map((b, i) => (
                          <div key={i} style={{ marginBottom: 6, padding: '6px 10px', background: '#f0f5ff', borderRadius: 4, borderLeft: '3px solid #2f54eb' }}>
                            <Text code style={{ fontSize: 12 }}>{b.key}</Text>
                            <span style={{ margin: '0 6px', color: '#999' }}>← request.{b.field} =</span>
                            <Text strong style={{ fontSize: 12, color: b.value === undefined ? '#999' : '#389e0d', wordBreak: 'break-all' }}>{fmt(b.value)}</Text>
                            {b.src && <Tag color={b.src === '请求头' ? 'gold' : 'green'} style={{ marginLeft: 6, fontSize: 11 }}>来自{b.src}</Tag>}
                            <div style={{ fontSize: 11, color: '#8c8c8c', marginTop: 2 }}>📎 {b.loc} · <code>{b.code}</code></div>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  {externalCalls.length > 0 && (
                    <div>
                      <Text strong style={{ fontSize: 13 }}>③ 重建的外部调用（共 {externalCalls.length} 个，填入真实值）</Text>
                      {externalCalls.map((ec, i) => {
                        // 构建请求体示例：用绑定到的真实值，没有则用字段名占位
                        const bodyExample: any = {};
                        bindings.forEach((b) => {
                          bodyExample[b.key] = b.value !== undefined ? b.value : `<${b.field}>`;
                        });
                        // 如果 bindings 为空，用入参 body 原样展示
                        const bodyStr = Object.keys(bodyExample).length > 0
                          ? JSON.stringify(bodyExample, null, 2)
                          : (call.requestBody || '{}');
                        return (
                          <div key={i} style={{ marginTop: 6, padding: 10, background: '#0d1117', color: '#c9d1d9', borderRadius: 4, fontSize: 12 }}>
                            <div><Tag color="geekblue">{ec.type}</Tag><span style={{ color: '#7ee787' }}>{ec.type === 'HTTP' ? 'POST' : ec.type}</span> {ec.url}</div>
                            {ec.cfgKey && <div style={{ color: '#8b949e', fontSize: 11, marginTop: 2 }}>配置来源: ${'{'}{ec.cfgKey}{'}'}</div>}
                            {(ec.type === 'HTTP' || ec.type === 'RPC') && (
                              <pre style={{ margin: '8px 0 0', whiteSpace: 'pre-wrap', wordBreak: 'break-all', color: '#adbac7' }}>
                                {bodyStr}
                              </pre>
                            )}
                            {ec.type === 'CACHE' && (
                              <div style={{ marginTop: 4, color: '#8b949e' }}>操作: {ec.url}</div>
                            )}
                          </div>
                        );
                      })}
                    </div>
                  )}
                </Card>
              );
            })()}

            {/* 常量 / 错误码 / 入参 —— 分类展示，均可点击跳转源码 */}
            {call.callTree && (() => {
              const ce = extractConstantsAndExceptions(call.callTree);
              if (ce.constants.length === 0 && ce.exceptions.length === 0) return null;
              // 请求体/响应体值映射
              let reqObj: any = {};
              try { reqObj = call.requestBody ? JSON.parse(call.requestBody) : {}; } catch { /* */ }
              const headerMap: Record<string, any> = {};
              const rh = call.requestHeaders || {};
              Object.keys(rh).forEach((hk) => { const c = hk.replace(/^u-/i, '').toLowerCase().replace(/[-_](\w)/g, (_: any, l: string) => l.toUpperCase()); headerMap[c] = rh[hk]; headerMap[hk] = rh[hk]; });

              // 常量分类：入参绑定（param.put）vs 普通常量
              const paramBindings: any[] = [];
              const otherConsts: any[] = [];
              const bindRe = /\.put\(\s*"([^"]+)"\s*,\s*[\w.]*\.get(\w+)\s*\(/;
              ce.constants.forEach((c: any) => {
                const m = bindRe.exec(c.code || '');
                if (m) {
                  const key = m[1], field = m[2].charAt(0).toLowerCase() + m[2].slice(1);
                  const v = reqObj[field] ?? reqObj[key] ?? headerMap[field] ?? headerMap[key];
                  paramBindings.push({ ...c, paramKey: key, paramField: field, paramValue: v });
                } else {
                  otherConsts.push(c);
                }
              });

              return (
                <Card size="small" title="📝 业务数据（错误码 / 常量 / 入参绑定）" style={{ marginBottom: 16 }}>
                  {/* 业务错误码 code+msg */}
                  {ce.exceptions.length > 0 && (
                    <div style={{ marginBottom: 12 }}>
                      <Text strong style={{ fontSize: 13 }}>❗ 业务错误码 ({ce.exceptions.length})</Text>
                      <div style={{ marginTop: 6 }}>
                        {ce.exceptions.map((e: any, i: number) => (
                          <div key={i} style={{ marginBottom: 6, padding: '5px 10px', background: '#fff2f0', borderRadius: 4, borderLeft: '3px solid #ff4d4f', display: 'flex', alignItems: 'center', gap: 8 }}>
                            <div style={{ flex: 1 }}>
                              {e.code && <Tag color="red" style={{ fontSize: 12 }}>code={e.code}</Tag>}
                              {e.msg && <Tag color="orange" style={{ fontSize: 12 }}>msg="{e.msg}"</Tag>}
                              {e.codeText && <div style={{ fontSize: 11, color: '#888', fontFamily: 'monospace', marginTop: 2 }}>{e.codeText}</div>}
                            </div>
                            <Button size="small" type="link" onClick={() => openSource(call.recommendedRepo?.repoId, e.fullMethod, e.line)}>源码</Button>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* 入参绑定 key→value */}
                  {paramBindings.length > 0 && (
                    <div style={{ marginBottom: 12 }}>
                      <Text strong style={{ fontSize: 13 }}>📥 入参绑定 ({paramBindings.length})</Text>
                      <div style={{ marginTop: 6 }}>
                        {paramBindings.map((p: any, i: number) => (
                          <div key={i} style={{ marginBottom: 4, padding: '4px 10px', background: '#f0f5ff', borderRadius: 4, borderLeft: '3px solid #2f54eb', display: 'flex', alignItems: 'center', gap: 6 }}>
                            <Text code style={{ fontSize: 12 }}>{p.paramKey}</Text>
                            <span style={{ color: '#999', fontSize: 11 }}>← {p.paramField} =</span>
                            <Text strong style={{ fontSize: 12, color: p.paramValue !== undefined ? '#389e0d' : '#999' }}>
                              {p.paramValue !== undefined ? (typeof p.paramValue === 'string' ? p.paramValue : JSON.stringify(p.paramValue)) : '(运行时)'}
                            </Text>
                            <Button size="small" type="link" onClick={() => openSource(call.recommendedRepo?.repoId, p.fullMethod, p.line)}>源码</Button>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* 其他业务常量 */}
                  {otherConsts.length > 0 && (
                    <div>
                      <Text strong style={{ fontSize: 13 }}>🔢 常量 ({otherConsts.length})</Text>
                      <div style={{ marginTop: 6 }}>
                        {otherConsts.map((c: any, i: number) => (
                          <div key={i} style={{ marginBottom: 4, padding: '4px 10px', background: '#f6f8fa', borderRadius: 4, borderLeft: '3px solid #5b8ff9', display: 'flex', alignItems: 'center', gap: 6 }}>
                            <Text code style={{ fontSize: 12 }}>{c.value}</Text>
                            {c.resolvedValue && <Tag color="cyan" style={{ fontSize: 11 }}>= {c.resolvedValue}</Tag>}
                            <Button size="small" type="link" onClick={() => openSource(call.recommendedRepo?.repoId, c.fullMethod, c.line)}>源码</Button>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                </Card>
              );
            })()}

            {/* 如果没有调用树，显示替代信息 */}
            {!call.callTree && call.method && (
              <Card size="small" title="🔗 调用分析" style={{ marginBottom: 16 }}>
                <div style={{ padding: 12, background: '#f0f0f0', borderRadius: 4 }}>
                  <Text type="secondary">
                    正在分析此方法的调用关系，请稍后刷新页面查看完整的时序图和依赖详情。
                  </Text>
                </div>
              </Card>
            )}

            {/* 关系箭头 */}
            {call.relationshipToNext && index < result.apiCalls.length - 1 && (
              <div style={{ padding: '16px 0', textAlign: 'center' }}>
                <div style={{ display: 'inline-block' }}>
                  <div style={{ fontSize: 20 }}>⬇️</div>
                  <Tag color="blue">调用链继续</Tag>
                </div>
              </div>
            )}
          </div>
        ))}
      </div>
    );
  };

  return (
    <div style={{ padding: 24 }}>
      <Card title="🔍 调用链追踪">
        <Paragraph>
          支持三种方式导入数据：上传 JSON 文件、粘贴 JSON 数据或粘贴 cURL 命令。系统将自动识别格式并分析调用链。
        </Paragraph>

        <Space style={{ marginBottom: 12 }} wrap>
          <Upload
            accept=".json,.txt"
            fileList={fileList}
            beforeUpload={handleFileUpload}
            onRemove={() => setFileList([])}
            maxCount={1}
          >
            <Button icon={<UploadOutlined />}>上传 JSON 文件</Button>
          </Upload>
          <Button size="small" onClick={() => handlePasteExample('json')}>
            填充 JSON 示例
          </Button>
          <Button size="small" onClick={() => handlePasteExample('curl')}>
            填充 cURL 示例
          </Button>
          <Button 
            size="small" 
            icon={<CopyOutlined />}
            onClick={() => {
              navigator.clipboard.readText().then(text => {
                setInputText(text);
                message.success('已从剪贴板粘贴');
              }).catch(() => {
                message.error('无法读取剪贴板');
              });
            }}
          >
            从剪贴板粘贴
          </Button>
        </Space>

        <TextArea
          value={inputText}
          onChange={(e) => setInputText(e.target.value)}
          placeholder={`支持两种格式：

1. JSON 格式（从 Chrome 插件获取）
2. cURL 命令（从浏览器开发者工具复制）`}
          rows={12}
          style={{ marginBottom: 16, fontFamily: 'monospace' }}
        />

        <Button
          type="primary"
          icon={<ThunderboltOutlined />}
          onClick={handleAnalyze}
          loading={loading}
          size="large"
          block
        >
          开始分析
        </Button>
      </Card>

      {loading && (
        <Card style={{ marginTop: 16 }} title={<span><Spin size="small" /> &nbsp;实时分析中…</span>}>
          <div style={{ maxHeight: 240, overflowY: 'auto', fontFamily: 'monospace', fontSize: 12, background: '#0d1117', color: '#c9d1d9', padding: 12, borderRadius: 6 }}>
            {progressLogs.length === 0 && <div style={{ color: '#8b949e' }}>正在连接分析引擎…</div>}
            {progressLogs.map((p, i) => {
              const icon = p.stage === 'match' ? '🔍' : p.stage === 'tree' ? '🌳'
                : p.stage === 'data' ? '📦' : p.stage === 'url' ? '🔗'
                : p.stage === 'ai' ? '🤖' : p.stage === 'report' ? '📝'
                : p.stage === 'error' ? '❌' : '·';
              const color = p.detail.startsWith('✓') ? '#3fb950' : p.detail.startsWith('✗') ? '#f85149'
                : p.stage === 'url' ? '#58a6ff' : '#c9d1d9';
              return (
                <div key={i} style={{ color, marginBottom: 2 }}>
                  <span style={{ opacity: 0.7 }}>{icon} </span>{p.detail}
                </div>
              );
            })}
          </div>
          {aiText && (
            <div style={{ marginTop: 12 }}>
              <Text strong style={{ fontSize: 13 }}>🤖 AI 分析（实时生成中）</Text>
              <div className="markdown-body" style={{ marginTop: 6, padding: 12, background: '#f6f8fa', borderRadius: 6, fontSize: 13, maxHeight: 360, overflowY: 'auto' }}>
                <ReactMarkdown remarkPlugins={[remarkGfm]} components={{ code: renderCodeBlock }}>
                  {aiText}
                </ReactMarkdown>
              </div>
            </div>
          )}
        </Card>
      )}

      {result && !loading && (
        <div style={{ marginTop: 16 }}>
          {/* 分析过程回放（可折叠，证明分析依据） */}
          {progressLogs.length > 0 && (
            <Collapse size="small" style={{ marginBottom: 16 }} items={[{
              key: 'trace',
              label: `🧭 分析过程回放（${progressLogs.length} 步，点击展开查看每一步取到的数据）`,
              children: (
                <div style={{ fontFamily: 'monospace', fontSize: 12 }}>
                  {progressLogs.map((p, i) => (
                    <div key={i} style={{ marginBottom: 2 }}>{p.detail}</div>
                  ))}
                </div>
              ),
            }]} />
          )}
          <Card title="📊 分析概览">
            <pre style={{ whiteSpace: 'pre-wrap', fontSize: 14 }}>{result.summary}</pre>
          </Card>

          {/* 性能总览 - 从 report 中提取 */}
          {result.report && result.report.includes('## 📊 性能总览') && (
            <Card title="⚡ 性能总览" style={{ marginTop: 16 }}>
              <div className="markdown-body">
                <ReactMarkdown remarkPlugins={[remarkGfm]}>
                  {extractSection(result.report, '## 📊 性能总览', '##')}
                </ReactMarkdown>
              </div>
            </Card>
          )}

          {/* 智能提示：如果有未匹配的API */}
          {result.apiCalls && result.apiCalls.some(c => !c.method && c.url?.includes('/api/')) && (
            <Card 
              title={
                <span>
                  <WarningOutlined style={{ color: '#faad14', marginRight: 8 }} />
                  <span>操作建议</span>
                </span>
              }
              style={{ marginTop: 16, borderLeft: '4px solid #faad14' }}
            >
              <Space direction="vertical" style={{ width: '100%' }}>
                <Text>
                  检测到有API请求未匹配到后端方法。要查看完整的调用链分析（时序图、SQL分析、性能评分等），请：
                </Text>
                <ol style={{ margin: '8px 0', paddingLeft: 20 }}>
                  <li>前往<a href="/#/repo-manager" target="_blank">仓库管理</a>页面</li>
                  <li>添加包含这些API的代码仓库（设置正确的URL路径标识符）</li>
                  <li>等待分析完成后，重新分析此请求链</li>
                </ol>
                <div>
                  <Text strong>未匹配的API：</Text>
                  <ul style={{ margin: '4px 0', paddingLeft: 20 }}>
                    {result.apiCalls
                      .filter(c => !c.method && c.url?.includes('/api/'))
                      .map((c, i) => (
                        <li key={i}>
                          <Text code>{c.url?.split('?')[0]}</Text>
                        </li>
                      ))
                    }
                  </ul>
                </div>
              </Space>
            </Card>
          )}

          <Card title="📊 调用链可视化" style={{ marginTop: 16 }}>
            {renderCallGraph()}
          </Card>

          {/* AI分析报告 - 需要后端支持 */}
          {result.apiCalls && result.apiCalls.some(c => c.diagnosis) && (
            <Card title="🤖 AI 深度分析报告" style={{ marginTop: 16 }}>
              {result.apiCalls.map((call, index) => (
                call.diagnosis && (
                  <Card 
                    key={index}
                    size="small" 
                    style={{ marginBottom: 16 }}
                    title={
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        <Tag color={call.status < 400 ? 'success' : 'error'}>{call.status}</Tag>
                        <Text>{call.url}</Text>
                      </div>
                    }
                  >
                    <div className="markdown-body">
                      <ReactMarkdown remarkPlugins={[remarkGfm]}>
                        {call.diagnosis}
                      </ReactMarkdown>
                    </div>
                  </Card>
                )
              ))}
            </Card>
          )}

          <Collapse style={{ marginTop: 16 }} defaultActiveKey={result.apiCalls?.some(c => !c.callTree) ? ['1'] : []}>
            <Collapse.Panel 
              header={
                <span style={{ fontSize: 14, fontWeight: 500 }}>
                  📄 查看详细分析报告
                  {result.report?.includes('SQL') && <Tag color="blue" style={{ marginLeft: 8 }}>含SQL分析</Tag>}
                  {result.report?.includes('常量') && <Tag color="green" style={{ marginLeft: 8 }}>含常量信息</Tag>}
                  {result.report?.includes('AI') && <Tag color="purple" style={{ marginLeft: 8 }}>含AI分析</Tag>}
                </span>
              } 
              key="1"
            >
              <div className="markdown-body" style={{ background: '#fafafa', padding: 16, borderRadius: 4 }}>
                <ReactMarkdown remarkPlugins={[remarkGfm]} components={{ code: renderCodeBlock }}>
                  {filterDetailReport(result.report)}
                </ReactMarkdown>
              </div>
            </Collapse.Panel>
          </Collapse>

          {/* 完整调用链 - 移至最后统一展示，默认全部展开，方法名/边界/错误码可点击跳源码 */}
          {result.apiCalls && result.apiCalls.some(c => c.callTree && c.callTree.root) && (
            <Card title="🌲 完整调用链（已全部展开，点方法名/标签查看源码）" style={{ marginTop: 16 }}>
              {result.apiCalls.map((call, index) => (
                call.callTree && call.callTree.root ? (
                  <Card
                    key={index}
                    size="small"
                    title={
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        <Tag color={call.status < 400 ? 'success' : 'error'}>{call.status}</Tag>
                        <Text strong style={{ fontSize: 13 }}>{call.requestMethod || 'GET'}</Text>
                        <Text style={{ fontSize: 13 }}>{call.url?.split('?')[0]}</Text>
                      </div>
                    }
                    style={{ marginBottom: 16 }}
                  >
                    <div>
                      <CallChainTreeView
                        node={call.callTree.root}
                        repoId={call.recommendedRepo?.repoId}
                        onJump={(fm, line) => openSource(call.recommendedRepo?.repoId, fm, line)}
                      />
                    </div>
                  </Card>
                ) : null
              ))}
            </Card>
          )}
        </div>
      )}

      {/* 边界详情模态框 */}
      <BoundaryDetailModal 
        visible={!!selectedBoundary}
        boundary={selectedBoundary}
        onClose={() => setSelectedBoundary(null)}
      />

      {/* 源码跳转弹窗（常量/异常引用点击） */}
      <Modal
        title={<span style={{ fontFamily: 'monospace', fontSize: 13 }}>📄 {srcModal.title?.split(':')[0]?.split('.').pop()}:{srcModal.title?.split(':')[1]?.split('(')[0]}</span>}
        open={srcModal.open}
        onCancel={() => setSrcModal((s) => ({ ...s, open: false }))}
        footer={null}
        width={1000}
        styles={{ body: { padding: 0 } }}
      >
        {srcModal.loading
          ? <Spin style={{ padding: 24 }} />
          : <JavaCodeViewer code={srcModal.code} startLine={srcModal.startLine} highlightLines={srcModal.highlight} maxHeight="700px" />}
      </Modal>
    </div>
  );
}
