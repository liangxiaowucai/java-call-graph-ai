import { useEffect, useState, useCallback, useRef } from 'react';
import { Select, Input, Button, Tag, Spin, Empty, Card, message, Drawer, Checkbox, Radio } from 'antd';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {
  SearchOutlined, SendOutlined, RobotOutlined, UserOutlined,
  SettingOutlined, LinkOutlined, CodeOutlined, EditOutlined,
} from '@ant-design/icons';
import JavaCodeViewer from '../components/JavaCodeViewer';
import ThinkingPanel from '../components/ThinkingPanel';
import {
  fetchRepos, fetchQAStatus, searchQAEndpoints,
  smartAskSSE, askSSE,
  fetchCallTree, fetchMethodSourceDetail,
  type RepoEntity, type EndpointSearchResult, type MatchedEndpoint,
  type CallTree, type MethodSourceDetail, type IntentResult, type IntentConfirmation,
  type ToolCallStep,
} from '../api';

const ENDPOINT_COLORS: Record<string, string> = {
  CONTROLLER: 'blue', KAFKA: 'orange', ROCKETMQ: 'magenta',
  RABBITMQ: 'cyan', GRPC: 'green', SCHEDULED: 'purple',
};

interface ChatMessage {
  role: 'user' | 'assistant' | 'system';
  content: string;
  cached?: boolean;
  typing?: boolean;
  fullContent?: string;
  thinkingSteps?: ThinkingStep[];
  matchedEndpoints?: MatchedEndpoint[];
  needsConfirmation?: boolean;    // 低置信度，等待用户勾选接口
  pendingQuestion?: string;       // 待确认时保存原始问题
  pendingRepoIds?: number[];      // 待确认时保存仓库列表
  intentResult?: IntentResult;    // AI提取的意图，等待用户确认
  needsIntentConfirmation?: boolean; // 意图置信度不足，等待用户确认意图
}

interface ThinkingStep {
  label: string;
  content: string;
  status: 'pending' | 'done';
}

export default function QAChat() {
  const [repos, setRepos] = useState<RepoEntity[]>([]);
  const [selectedRepoId, setSelectedRepoId] = useState<number | null>(null);
  const [configured, setConfigured] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [endpoints, setEndpoints] = useState<EndpointSearchResult[]>([]);
  const [searching, setSearching] = useState(false);
  const [selectedMethods, setSelectedMethods] = useState<string[]>([]);
  const [chatMessages, setChatMessages] = useState<ChatMessage[]>([]);
  const [inputValue, setInputValue] = useState('');
  const [asking, setAsking] = useState(false);
  const chatEndRef = useRef<HTMLDivElement>(null);
  const [detailDrawerOpen, setDetailDrawerOpen] = useState(false);
  const [detailMethod, setDetailMethod] = useState('');
  const [detailTree, setDetailTree] = useState<CallTree | null>(null);
  const [detailSource, setDetailSource] = useState<MethodSourceDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  // 候选接口勾选状态：消息索引 → 已勾选的 fullMethod 列表
  const [candidateSelections, setCandidateSelections] = useState<Record<number, string[]>>({});
  // 意图确认状态：消息索引 → 用户选择的意图类型
  const [intentSelections, setIntentSelections] = useState<Record<number, string>>({});
  // 意图补充说明：消息索引 → 用户输入的补充说明
  const [intentClarifications, setIntentClarifications] = useState<Record<number, string>>({});

  const openMethodDetail = useCallback(async (method: string) => {
    if (!selectedRepoId) return;
    setDetailMethod(method);
    setDetailDrawerOpen(true);
    setDetailLoading(true);
    setDetailTree(null);
    setDetailSource(null);
    try {
      const [tree, source] = await Promise.all([
        fetchCallTree(selectedRepoId, method).catch(() => null),
        fetchMethodSourceDetail(selectedRepoId, method, selectedMethods[0] ?? undefined).catch(() => null),
      ]);
      setDetailTree(tree);
      setDetailSource(source);
    } finally {
      setDetailLoading(false);
    }
  }, [selectedRepoId, selectedMethods]);

  useEffect(() => {
    fetchRepos().then(r => setRepos(r.filter(repo => repo.status === 'READY'))).catch(() => {});
    fetchQAStatus().then(s => setConfigured(s.configured)).catch(() => {});
  }, []);

  const handleSearch = useCallback(async () => {
    if (!selectedRepoId) { message.warning('请先选择仓库'); return; }
    setSearching(true);
    try {
      const results = await searchQAEndpoints(selectedRepoId, keyword);
      setEndpoints(results);
    } catch { message.error('搜索失败'); }
    finally { setSearching(false); }
  }, [selectedRepoId, keyword]);

  const toggleMethod = (method: string) => {
    setSelectedMethods(prev =>
      prev.includes(method) ? prev.filter(m => m !== method) : [...prev, method]
    );
  };

  const handleAsk = useCallback(async () => {
    if (!inputValue.trim()) { message.warning('请输入问题'); return; }
    const question = inputValue.trim();
    setInputValue('');
    setChatMessages(prev => [...prev, { role: 'user', content: question }]);

    // 未选接口：走智能问答流程（AI理解 → 提取关键词 → 搜索call → AI回答）
    if (!selectedRepoId || selectedMethods.length === 0) {
      setAsking(true);
      const allRepos = repos.filter(r => r.status === 'READY');
      if (allRepos.length === 0) {
        setChatMessages(prev => [...prev, { role: 'assistant', content: '⚠️ 暂无已分析的仓库，请先在「仓库管理」中添加并分析仓库。' }]);
        setAsking(false);
        return;
      }

      // 添加思考过程占位消息
      const thinkingMsg: ChatMessage = {
        role: 'assistant', content: '',
        thinkingSteps: [
          { label: '🧠 理解问题', content: '正在分析您的问题...', status: 'pending' },
          { label: '🔍 提取关键词', content: '', status: 'pending' },
          { label: '📡 搜索调用链', content: '', status: 'pending' },
          { label: '🤖 AI 回答', content: '', status: 'pending' },
        ],
      };
      setChatMessages(prev => [...prev, thinkingMsg]);

      const repoIds = allRepos.map(r => r.id);

      // ── SSE 智能问答 ──
      const stopSse = smartAskSSE(repoIds, question, {
        onThinking: (step: ToolCallStep) => {
          setChatMessages(prev => prev.map((m, i) => {
            if (i !== prev.length - 1) return m;
            const existing = m.thinkingSteps ?? [];
            // 避免重复追加同一步骤
            if (existing.some(s => s.label === step.content)) return m;
            return {
              ...m,
              thinkingSteps: [
                ...existing.filter(s => s.status === 'done'),
                { label: step.content, content: `Round ${step.round}`, status: 'pending' as const },
              ],
            };
          }));
        },
        onMatched: (data) => {
          setChatMessages(prev => prev.map((m, i) =>
            i === prev.length - 1 ? {
              ...m,
              thinkingSteps: [
                { label: '🧠 理解问题', content: question, status: 'done' as const },
                { label: '🔍 提取关键词', content: data.keywords.join('、') || '无', status: 'done' as const },
                { label: '📡 搜索调用链', content: `找到 ${data.matchedEndpoints.length} 个相关接口`, status: 'done' as const },
                { label: '🤖 AI 逐层分析中...', content: '', status: 'pending' as const },
              ],
              matchedEndpoints: data.matchedEndpoints,
            } : m
          ));
        },
        onConfirmation: (data) => {
          // 需要用户确认（意图/接口）
          setAsking(false);
          if (data.needsIntentConfirmation && data.intentResult) {
            setChatMessages(prev => prev.map((m, i) =>
              i === prev.length - 1 ? {
                ...m, content: data.answer,
                thinkingSteps: [
                  { label: '🧠 理解问题', content: question, status: 'done' as const },
                  { label: '🤔 请确认意图', content: '需要确认您的问题方向', status: 'done' as const },
                ],
                intentResult: data.intentResult!,
                needsIntentConfirmation: true,
                pendingQuestion: question,
                pendingRepoIds: repoIds,
              } : m
            ));
          } else if (data.needsConfirmation) {
            setChatMessages(prev => prev.map((m, i) =>
              i === prev.length - 1 ? {
                ...m, content: data.answer,
                thinkingSteps: [
                  { label: '🧠 理解问题', content: question, status: 'done' as const },
                  { label: '🔍 提取关键词', content: data.keywords.join('、') || '无', status: 'done' as const },
                  { label: '🤔 请确认接口', content: `找到 ${data.matchedEndpoints.length} 个候选，请选择后继续`, status: 'done' as const },
                ],
                matchedEndpoints: data.matchedEndpoints,
                needsConfirmation: true,
                pendingQuestion: question,
                pendingRepoIds: repoIds,
              } : m
            ));
          }
        },
        onAnswer: (content) => {
          // 打字机效果：逐字追加
          setChatMessages(prev => prev.map((m, i) =>
            i === prev.length - 1 ? { ...m, content, typing: false } : m
          ));
        },
        onDone: () => {
          setAsking(false);
          // 标记所有 thinking 步骤完成
          setChatMessages(prev => prev.map((m, i) =>
            i === prev.length - 1 ? {
              ...m,
              thinkingSteps: m.thinkingSteps?.map(s => ({ ...s, status: 'done' as const })),
            } : m
          ));
        },
        onError: (errMsg) => {
          setAsking(false);
          setChatMessages(prev => prev.map((m, i) =>
            i === prev.length - 1 ? { ...m, content: '❌ ' + errMsg, thinkingSteps: undefined } : m
          ));
        },
      });

      // 保存 stop 函数以备用（组件卸载时取消）
      void stopSse;
      return;
    }

    // 已选接口，改用 SSE 问答
    setAsking(true);
    setChatMessages(prev => [...prev, {
      role: 'assistant', content: '',
      thinkingSteps: [
        { label: '🤖 AI 逐层分析中...', content: '', status: 'pending' as const },
      ],
    }]);

    const history = chatMessages
      .filter(m => m.role !== 'system')
      .slice(-6)
      .map(m => ({ role: m.role, content: m.content }));

    askSSE(selectedRepoId, selectedMethods, question, history, {
      onThinking: (step: ToolCallStep) => {
        setChatMessages(prev => prev.map((m, i) => {
          if (i !== prev.length - 1) return m;
          const existing = m.thinkingSteps ?? [];
          if (existing.some(s => s.label === step.content)) return m;
          return {
            ...m,
            thinkingSteps: [
              ...existing.filter(s => s.status === 'done'),
              { label: step.content, content: `Round ${step.round}`, status: 'pending' as const },
            ],
          };
        }));
      },
      onAnswer: (content) => {
        setChatMessages(prev => prev.map((m, i) =>
          i === prev.length - 1 ? { ...m, content, typing: false } : m
        ));
      },
      onDone: () => {
        setAsking(false);
        setChatMessages(prev => prev.map((m, i) =>
          i === prev.length - 1 ? {
            ...m,
            thinkingSteps: m.thinkingSteps?.map(s => ({ ...s, status: 'done' as const })),
          } : m
        ));
      },
      onError: (errMsg) => {
        setAsking(false);
        setChatMessages(prev => prev.map((m, i) =>
          i === prev.length - 1 ? { ...m, content: '❌ ' + errMsg, thinkingSteps: undefined } : m
        ));
      },
    });
  }, [selectedRepoId, selectedMethods, inputValue, chatMessages, repos]);

  // 用户确认候选接口后，携带 confirmedMethods 重新发起智能问答
  const handleConfirmEndpoints = useCallback(async (msgIndex: number, question: string, repoIds: number[], confirmed: string[]) => {
    if (confirmed.length === 0) { message.warning('请至少选择一个接口'); return; }

    // 关闭该消息的确认状态
    setChatMessages(prev => prev.map((m, i) => i === msgIndex ? { ...m, needsConfirmation: false } : m));
    setCandidateSelections(prev => { const next = { ...prev }; delete next[msgIndex]; return next; });

    // 展示用户确认动作
    const names = confirmed.map(m => {
      const cls = m.split(':')[0]?.split('.').pop() ?? '';
      const fn = m.split(':').pop()?.split('(')[0] ?? '';
      return `${cls}.${fn}`;
    }).join('、');
    setChatMessages(prev => [...prev, { role: 'user', content: `确认分析接口：${names}` }]);

    setAsking(true);
    setChatMessages(prev => [...prev, {
      role: 'assistant', content: '',
      thinkingSteps: [
        { label: '🎯 已确认接口', content: `共 ${confirmed.length} 个`, status: 'done' as const },
        { label: '🤖 AI 逐层分析中...', content: '', status: 'pending' as const },
      ],
    }]);

    smartAskSSE(repoIds, question, {
      onThinking: (step: ToolCallStep) => {
        setChatMessages(prev => prev.map((m, i) => {
          if (i !== prev.length - 1) return m;
          const existing = m.thinkingSteps ?? [];
          if (existing.some(s => s.label === step.content)) return m;
          return { ...m, thinkingSteps: [...existing.filter(s => s.status === 'done'), { label: step.content, content: `Round ${step.round}`, status: 'pending' as const }] };
        }));
      },
      onAnswer: (content) => {
        setChatMessages(prev => prev.map((m, i) =>
          i === prev.length - 1 ? { ...m, content, typing: false } : m
        ));
      },
      onDone: () => {
        setAsking(false);
        setChatMessages(prev => prev.map((m, i) =>
          i === prev.length - 1 ? { ...m, thinkingSteps: m.thinkingSteps?.map(s => ({ ...s, status: 'done' as const })) } : m
        ));
      },
      onError: (errMsg) => {
        setAsking(false);
        setChatMessages(prev => prev.map((m, i) =>
          i === prev.length - 1 ? { ...m, content: '❌ ' + errMsg, thinkingSteps: undefined } : m
        ));
      },
    }, confirmed);
  }, []);

  // 用户确认意图后，携带 confirmedIntent 重新发起智能问答
  const handleConfirmIntent = useCallback(async (
    msgIndex: number,
    question: string,
    repoIds: number[],
    intentResult: IntentResult,
    selectedType: string,
    clarification: string,
  ) => {
    // 关闭该消息的意图确认状态
    setChatMessages(prev => prev.map((m, i) => i === msgIndex ? { ...m, needsIntentConfirmation: false } : m));
    setIntentSelections(prev => { const next = { ...prev }; delete next[msgIndex]; return next; });
    setIntentClarifications(prev => { const next = { ...prev }; delete next[msgIndex]; return next; });

    // 意图标签映射
    const labelMap: Record<string, string> = {
      DEBUG: '排错', INTEGRATION: '对接指南', DATA_FLOW: '数据流向', UNDERSTAND: '功能理解', CONFIG: '配置查询',
    };
    const intentLabel = labelMap[selectedType] ?? intentResult.intentLabel;
    const confirmedIntent: IntentConfirmation = { intentType: selectedType, intentLabel, clarification: clarification || undefined };

    // 展示用户确认动作
    const userMsg = clarification
      ? `确认意图：${intentLabel} · ${clarification}`
      : `确认意图：${intentLabel}`;
    setChatMessages(prev => [...prev, { role: 'user', content: userMsg }]);

    setAsking(true);
    setChatMessages(prev => [...prev, {
      role: 'assistant', content: '',
      thinkingSteps: [
        { label: `🎯 ${intentLabel}`, content: '意图已确认', status: 'done' as const },
        { label: '🔍 搜索调用链', content: '', status: 'pending' as const },
        { label: '🤖 AI 逐层分析中...', content: '', status: 'pending' as const },
      ],
    }]);

    smartAskSSE(repoIds, question, {
      onThinking: (step: ToolCallStep) => {
        setChatMessages(prev => prev.map((m, i) => {
          if (i !== prev.length - 1) return m;
          const existing = m.thinkingSteps ?? [];
          if (existing.some(s => s.label === step.content)) return m;
          return { ...m, thinkingSteps: [...existing.filter(s => s.status === 'done'), { label: step.content, content: `Round ${step.round}`, status: 'pending' as const }] };
        }));
      },
      onMatched: (data) => {
        setChatMessages(prev => prev.map((m, i) =>
          i === prev.length - 1 ? {
            ...m, matchedEndpoints: data.matchedEndpoints,
            thinkingSteps: [
              { label: `🎯 ${intentLabel}`, content: '意图已确认', status: 'done' as const },
              { label: '🔍 搜索调用链', content: `找到 ${data.matchedEndpoints.length} 个接口`, status: 'done' as const },
              { label: '🤖 AI 逐层分析中...', content: '', status: 'pending' as const },
            ],
          } : m
        ));
      },
      onConfirmation: (data) => {
        setAsking(false);
        if (data.needsConfirmation) {
          setChatMessages(prev => prev.map((m, i) =>
            i === prev.length - 1 ? {
              ...m, content: data.answer,
              thinkingSteps: [
                { label: `🎯 ${intentLabel}`, content: '意图已确认', status: 'done' as const },
                { label: '🤔 请确认接口', content: `找到 ${data.matchedEndpoints.length} 个候选，请选择后继续`, status: 'done' as const },
              ],
              matchedEndpoints: data.matchedEndpoints,
              needsConfirmation: true,
              pendingQuestion: question,
              pendingRepoIds: repoIds,
            } : m
          ));
        }
      },
      onAnswer: (content) => {
        setChatMessages(prev => prev.map((m, i) =>
          i === prev.length - 1 ? { ...m, content, typing: false } : m
        ));
      },
      onDone: () => {
        setAsking(false);
        setChatMessages(prev => prev.map((m, i) =>
          i === prev.length - 1 ? { ...m, thinkingSteps: m.thinkingSteps?.map(s => ({ ...s, status: 'done' as const })) } : m
        ));
      },
      onError: (errMsg) => {
        setAsking(false);
        setChatMessages(prev => prev.map((m, i) =>
          i === prev.length - 1 ? { ...m, content: '❌ ' + errMsg, thinkingSteps: undefined } : m
        ));
      },
    }, undefined, confirmedIntent);
  }, []);

  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [chatMessages]);

  // 渲染调用树文本
  const renderTreeText = (node: CallTree['root'], prefix: string, isLast: boolean): React.ReactNode => {
    if (!node) return null;
    const connector = isLast ? '└── ' : '├── ';
    const cls = node.className?.split('.').pop() ?? '';
    const lines: React.ReactNode[] = [];
    lines.push(<div key={node.fullMethod}>{prefix}{connector}{cls}.{node.methodName}{node.isRecursive ? ' ↻' : ''}{node.isLazyLoad ? ' ...' : ''}</div>);
    if (node.children) {
      node.children.forEach((child, i) => {
        const childPrefix = prefix + (isLast ? '    ' : '│   ');
        const childNodes = renderTreeText(child, childPrefix, i === node.children.length - 1);
        if (childNodes) lines.push(childNodes);
      });
    }
    return <>{lines}</>;
  };

  if (!configured) {
    return (
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%' }}>
        <Card style={{ maxWidth: 400, textAlign: 'center' }}>
          <SettingOutlined style={{ fontSize: 48, color: '#d9d9d9', marginBottom: 16 }} />
          <h3>请先配置 Claude API</h3>
          <p style={{ color: '#999' }}>在「系统配置」页面设置 API URL 和 API Key</p>
        </Card>
      </div>
    );
  }

  return (
    <>
    <div style={{ display: 'flex', height: 'calc(100vh - 32px)', gap: 0 }}>
      {/* 左侧：接口搜索与选择 */}
      <div style={{ width: 360, minWidth: 360, flexShrink: 0, borderRight: '1px solid #f0f0f0', display: 'flex', flexDirection: 'column', background: '#fff' }}>
        <div style={{ padding: 12, borderBottom: '1px solid #f0f0f0' }}>
          <Select
            style={{ width: '100%', marginBottom: 8 }}
            placeholder="选择仓库"
            value={selectedRepoId}
            onChange={v => { setSelectedRepoId(v); setEndpoints([]); setSelectedMethods([]); setChatMessages([]); }}
            options={repos.map(r => ({ label: r.name, value: r.id }))}
          />
          <Input.Search
            placeholder="搜索接口（URL、方法名、注释）"
            value={keyword}
            onChange={e => setKeyword(e.target.value)}
            onSearch={handleSearch}
            enterButton={<SearchOutlined />}
            loading={searching}
          />
        </div>

        <div style={{ flex: 1, overflow: 'auto', padding: '4px 0' }}>
          {endpoints.length === 0 ? (
            <Empty description={selectedRepoId ? '输入关键词搜索接口' : '请先选择仓库'} image={Empty.PRESENTED_IMAGE_SIMPLE} style={{ marginTop: 60 }} />
          ) : (
            endpoints.map(ep => {
              const selected = selectedMethods.includes(ep.fullMethod);
              return (
                <div
                  key={ep.id}
                  onClick={() => toggleMethod(ep.fullMethod)}
                  style={{
                    padding: '8px 12px', cursor: 'pointer',
                    background: selected ? '#e6f7ff' : 'transparent',
                    borderLeft: selected ? '3px solid #1890ff' : '3px solid transparent',
                    borderBottom: '1px solid #fafafa',
                  }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                    <Tag color={ENDPOINT_COLORS[ep.endpointType] ?? 'default'} style={{ fontSize: 10 }}>
                      {ep.endpointType}
                    </Tag>
                    {ep.httpMethod && <Tag style={{ fontSize: 10 }}>{ep.httpMethod}</Tag>}
                    <span style={{ fontSize: 12, fontWeight: 500, flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {ep.urlPath || ep.methodName}
                    </span>
                  </div>
                  <div style={{ fontSize: 11, color: '#8c8c8c', marginTop: 2 }}>
                    {ep.className}.{ep.methodName}
                  </div>
                </div>
              );
            })
          )}
        </div>

        {selectedMethods.length > 0 && (
          <div style={{ padding: 8, borderTop: '1px solid #f0f0f0', fontSize: 11, color: '#666' }}>
            已选 {selectedMethods.length} 个接口
          </div>
        )}
      </div>

      {/* 右侧：对话区 */}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', background: '#fafafa' }}>
        {/* 预设问题按钮 - 已移除，改用智能问答直接提问 */}

        {/* 消息列表 */}
        <div style={{ flex: 1, overflow: 'auto', padding: 16 }}>
          {chatMessages.length === 0 ? (
            <div style={{ textAlign: 'center', marginTop: 80, color: '#bfbfbf' }}>
              <RobotOutlined style={{ fontSize: 48, marginBottom: 16 }} />
              <div style={{ fontSize: 16 }}>直接输入问题开始</div>
              <div style={{ fontSize: 12, marginTop: 8 }}>系统会自动搜索相关接口并引导你选择</div>
            </div>
          ) : (
            chatMessages.map((msg, i) => (
              <div key={i} style={{
                display: 'flex', gap: 8, marginBottom: 16,
                flexDirection: msg.role === 'user' ? 'row-reverse' : 'row',
              }}>
                <div style={{
                  width: 32, height: 32, borderRadius: '50%', flexShrink: 0,
                  background: msg.role === 'user' ? '#1890ff' : '#f0f0f0',
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  color: msg.role === 'user' ? '#fff' : '#666', fontSize: 14,
                }}>
                  {msg.role === 'user' ? <UserOutlined /> : <RobotOutlined />}
                </div>
                <div style={{
                  maxWidth: '75%', padding: '10px 14px', borderRadius: 12,
                  background: msg.role === 'user' ? '#1890ff' : '#fff',
                  color: msg.role === 'user' ? '#fff' : '#333',
                  fontSize: 13, lineHeight: 1.7,
                  boxShadow: '0 1px 2px rgba(0,0,0,0.06)',
                }}>
                  {/* AI 思考过程展示 — DeepSeek 风格可折叠面板 */}
                  {msg.thinkingSteps && (
                    <ThinkingPanel
                      steps={msg.thinkingSteps}
                      isDone={!msg.typing && msg.content.length > 0 && !msg.needsConfirmation && !msg.needsIntentConfirmation}
                    />
                  )}

                  {/* 意图确认卡（置信度不足时展示） */}
                  {msg.needsIntentConfirmation && msg.intentResult && (
                    <div style={{ marginBottom: 10, padding: '10px 14px', background: '#f0f5ff', border: '1px solid #adc6ff', borderRadius: 8, fontSize: 12 }}>
                      <div style={{ fontWeight: 600, marginBottom: 6, color: '#2f54eb' }}>🧠 我的理解，请确认：</div>
                      <div style={{ marginBottom: 8, color: '#333', fontSize: 13 }}>{msg.intentResult.summary}</div>
                      {msg.intentResult.entity && (
                        <div style={{ marginBottom: 6, color: '#666' }}>
                          核心实体：<Tag color="blue">{msg.intentResult.entity}</Tag>
                        </div>
                      )}
                      {msg.intentResult.focusOn.length > 0 && (
                        <div style={{ marginBottom: 8, color: '#666' }}>
                          搜索重点：{msg.intentResult.focusOn.map((f, fi) => <Tag key={fi}>{f}</Tag>)}
                        </div>
                      )}
                      <div style={{ marginBottom: 8 }}>
                        <div style={{ fontSize: 11, color: '#999', marginBottom: 4 }}>意图类型（可切换）：</div>
                        <Radio.Group
                          value={intentSelections[i] ?? msg.intentResult.intentType}
                          onChange={e => setIntentSelections(prev => ({ ...prev, [i]: e.target.value }))}
                          size="small"
                        >
                          {[
                            { value: 'DEBUG', label: '排错' },
                            { value: 'INTEGRATION', label: '对接指南' },
                            { value: 'DATA_FLOW', label: '数据流向' },
                            { value: 'UNDERSTAND', label: '功能理解' },
                            { value: 'CONFIG', label: '配置查询' },
                          ].map(opt => (
                            <Radio.Button key={opt.value} value={opt.value} style={{ fontSize: 11, marginBottom: 4 }}>
                              {opt.label}
                            </Radio.Button>
                          ))}
                        </Radio.Group>
                      </div>
                      <Input
                        size="small"
                        placeholder="（可选）补充说明，让搜索更准确..."
                        prefix={<EditOutlined style={{ color: '#aaa' }} />}
                        value={intentClarifications[i] ?? ''}
                        onChange={e => setIntentClarifications(prev => ({ ...prev, [i]: e.target.value }))}
                        style={{ marginBottom: 8 }}
                      />
                      <Button
                        type="primary"
                        size="small"
                        disabled={asking}
                        onClick={() => handleConfirmIntent(
                          i,
                          msg.pendingQuestion!,
                          msg.pendingRepoIds!,
                          msg.intentResult!,
                          intentSelections[i] ?? msg.intentResult!.intentType,
                          intentClarifications[i] ?? '',
                        )}
                      >
                        确认，开始搜索
                      </Button>
                    </div>
                  )}

                  {/* 候选接口勾选确认（低置信度场景） */}
                  {msg.needsConfirmation && msg.matchedEndpoints && msg.matchedEndpoints.length > 0 && (
                    <div style={{ marginBottom: 10, padding: '8px 12px', background: '#fffbe6', border: '1px solid #ffe58f', borderRadius: 8, fontSize: 12 }}>
                      <div style={{ fontWeight: 600, marginBottom: 8, color: '#d48806' }}>🔍 找到以下候选接口，请勾选要分析的接口：</div>
                      {msg.matchedEndpoints.map((ep, ei) => (
                        <div key={ei} style={{ marginBottom: 6 }}>
                          <Checkbox
                            checked={(candidateSelections[i] ?? []).includes(ep.fullMethod)}
                            onChange={e => {
                              const cur = candidateSelections[i] ?? [];
                              setCandidateSelections(prev => ({
                                ...prev,
                                [i]: e.target.checked
                                  ? [...cur, ep.fullMethod]
                                  : cur.filter(m => m !== ep.fullMethod),
                              }));
                            }}
                          >
                            <Tag color={ENDPOINT_COLORS[ep.endpointType] ?? 'default'} style={{ fontSize: 10 }}>{ep.endpointType}</Tag>
                            {ep.httpMethod && <Tag style={{ fontSize: 10 }}>{ep.httpMethod}</Tag>}
                            <span style={{ fontWeight: 500 }}>{ep.urlPath || `${ep.className}.${ep.methodName}`}</span>
                            <span style={{ color: '#999', marginLeft: 4 }}>({ep.repoName})</span>
                          </Checkbox>
                        </div>
                      ))}
                      <Button
                        type="primary"
                        size="small"
                        style={{ marginTop: 8 }}
                        disabled={asking || (candidateSelections[i] ?? []).length === 0}
                        onClick={() => handleConfirmEndpoints(i, msg.pendingQuestion!, msg.pendingRepoIds!, candidateSelections[i] ?? [])}
                      >
                        确认分析（已选 {(candidateSelections[i] ?? []).length} 个）
                      </Button>
                    </div>
                  )}

                  {/* 已分析的匹配接口（仅展示，无交互） */}
                  {!msg.needsConfirmation && msg.matchedEndpoints && msg.matchedEndpoints.length > 0 && (
                    <div style={{ marginBottom: 10, padding: '6px 10px', background: '#e6f7ff', borderRadius: 6, fontSize: 11 }}>
                      <div style={{ fontWeight: 600, marginBottom: 4, color: '#1890ff' }}>🎯 匹配到的接口：</div>
                      {msg.matchedEndpoints.slice(0, 5).map((ep, ei) => (
                        <div key={ei} style={{ display: 'flex', alignItems: 'center', gap: 4, marginBottom: 2 }}>
                          <Tag color={ENDPOINT_COLORS[ep.endpointType] ?? 'default'} style={{ fontSize: 10 }}>{ep.endpointType}</Tag>
                          {ep.httpMethod && <Tag style={{ fontSize: 10 }}>{ep.httpMethod}</Tag>}
                          <span>{ep.urlPath || `${ep.className}.${ep.methodName}`}</span>
                          <span style={{ color: '#999' }}>({ep.repoName})</span>
                        </div>
                      ))}
                      {msg.matchedEndpoints.length > 5 && <div style={{ color: '#999' }}>…还有 {msg.matchedEndpoints.length - 5} 个</div>}
                    </div>
                  )}

                  {msg.role === 'assistant' ? (
                    <div className="markdown-body">
                      <ReactMarkdown
                        remarkPlugins={[remarkGfm]}
                        components={{
                          code: ({ children, className }) => {
                            const text = String(children);
                            if (!className && text.match(/^[A-Z]\w+\.\w+$/)) {
                              return (
                                <code
                                  style={{ cursor: 'pointer', color: '#1890ff', textDecoration: 'underline' }}
                                  onClick={() => {
                                    const match = selectedMethods.find(m => {
                                      const short = m.split(':').pop()?.split('(')[0] ?? '';
                                      const cls = m.split(':')[0]?.split('.').pop() ?? '';
                                      return text === cls + '.' + short;
                                    });
                                    if (match && selectedRepoId) {
                                      openMethodDetail(match);
                                    }
                                  }}
                                >{text}</code>
                              );
                            }
                            return <code className={className}>{children}</code>;
                          }
                        }}
                      >{msg.content}</ReactMarkdown>
                      {msg.typing && <span className="typing-cursor">▌</span>}
                    </div>
                  ) : (
                    <span style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{msg.content}</span>
                  )}
                  {msg.cached && <Tag color="default" style={{ fontSize: 10, marginLeft: 8 }}>缓存</Tag>}

                  {/* 引用面板：AI 回答下方展示涉及的接口 */}
                  {msg.role === 'assistant' && !msg.typing && selectedMethods.length > 0 && i === chatMessages.length - 1 && (
                    <div style={{ marginTop: 8, paddingTop: 8, borderTop: '1px solid #f0f0f0' }}>
                      <div style={{ fontSize: 11, color: '#999', marginBottom: 4 }}>📎 涉及的接口：</div>
                      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                        {selectedMethods.map((method, mi) => {
                          const cls = method.split(':')[0]?.split('.').pop() ?? '';
                          const fn = method.split(':').pop()?.split('(')[0] ?? '';
                          return (
                            <Tag
                              key={mi}
                              color="blue"
                              style={{ cursor: 'pointer', fontSize: 11 }}
                              onClick={() => {
                                if (selectedRepoId) {
                                  openMethodDetail(method);
                                }
                              }}
                            >
                              <LinkOutlined style={{ marginRight: 2 }} />
                              {cls}.{fn}
                            </Tag>
                          );
                        })}
                      </div>
                    </div>
                  )}
                </div>
              </div>
            ))
          )}
          {asking && !chatMessages.some(m => m.thinkingSteps && m.thinkingSteps.some(s => s.status === 'pending')) && (
            <div style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
              <div style={{ width: 32, height: 32, borderRadius: '50%', background: '#f0f0f0', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                <RobotOutlined style={{ color: '#666' }} />
              </div>
              <div style={{ padding: '10px 14px', background: '#fff', borderRadius: 12 }}>
                <Spin size="small" /> <span style={{ marginLeft: 8, color: '#999' }}>思考中...</span>
              </div>
            </div>
          )}
          <div ref={chatEndRef} />
        </div>

        {/* 输入框 */}
        <div style={{ padding: '12px 16px', borderTop: '1px solid #f0f0f0', background: '#fff' }}>
          <div style={{ display: 'flex', gap: 8, alignItems: 'flex-end' }}>
            <Input.TextArea
              value={inputValue}
              onChange={e => setInputValue(e.target.value)}
              onKeyDown={e => {
                if (e.key === 'Enter' && !e.ctrlKey && !e.metaKey && !e.shiftKey) {
                  e.preventDefault();
                  if (!inputValue.trim()) { message.warning('请输入问题'); return; }
                  handleAsk();
                }
              }}
              placeholder={selectedMethods.length > 0 ? '基于选中的接口提问... (Enter 发送，Ctrl+Enter 换行)' : '输入问题，自动搜索相关接口... (Enter 发送，Ctrl+Enter 换行)'}
              disabled={asking}
              autoSize={{ minRows: 1, maxRows: 6 }}
              style={{ flex: 1, resize: 'none' }}
            />
            <Button
              type="primary"
              icon={<SendOutlined />}
              onClick={handleAsk}
              loading={asking}
              disabled={asking}
              size="large"
              style={{ height: 'auto', minHeight: 40 }}
            />
          </div>
        </div>
      </div>
    </div>

      {/* 方法详情 Drawer（不离开问答页面） */}
      <Drawer
      title={
        <span style={{ fontSize: 13 }}>
          <CodeOutlined style={{ marginRight: 8 }} />
          {detailMethod.length > 60 ? detailMethod.slice(0, 58) + '…' : detailMethod}
        </span>
      }
      placement="right"
      width={700}
      open={detailDrawerOpen}
      onClose={() => setDetailDrawerOpen(false)}
    >
      {detailLoading ? (
        <div style={{ textAlign: 'center', padding: 40 }}><Spin size="large" /></div>
      ) : (
        <div>
          {/* 调用链概览 */}
          {detailTree?.root && (
            <div style={{ marginBottom: 16 }}>
              <div style={{ fontWeight: 600, marginBottom: 8 }}>📊 调用链概览</div>
              <div style={{ background: '#fafafa', borderRadius: 6, padding: 12, fontSize: 12, fontFamily: 'monospace', maxHeight: 200, overflow: 'auto' }}>
                {renderTreeText(detailTree.root, '', true)}
              </div>
              <div style={{ fontSize: 11, color: '#999', marginTop: 4 }}>
                节点: {detailTree.totalNodes} | 深度: {detailTree.maxDepth}
                {detailTree.hasCycle && <Tag color="warning" style={{ marginLeft: 8 }}>存在循环</Tag>}
              </div>
            </div>
          )}

          {/* 方法签名 */}
          {detailSource?.methodSignature && (
            <div style={{ padding: '8px 12px', background: '#e8f4fd', borderRadius: 6, marginBottom: 8, fontFamily: 'monospace', fontSize: 13, color: '#0050b3', wordBreak: 'break-all' }}>
              📋 {detailSource.methodSignature}
            </div>
          )}

          {/* 入参实体类 */}
          {detailSource?.paramClasses && detailSource.paramClasses.length > 0 && (
            <div style={{ padding: '8px 12px', background: '#f6ffed', borderRadius: 6, marginBottom: 8, fontSize: 12, borderLeft: '3px solid #52c41a' }}>
              <div style={{ fontWeight: 600, marginBottom: 4, color: '#389e0d' }}>📦 入参实体类</div>
              {detailSource.paramClasses.map((pc, i) => (
                <div key={i} style={{ marginBottom: 4 }}>
                  <div style={{ fontWeight: 600, color: '#531dab' }}>{pc.shortName}</div>
                  {pc.fields.map((f, j) => {
                    const isReq = f.startsWith('* ');
                    const text = isReq ? f.substring(2) : f.startsWith('  ') ? f.substring(2) : f;
                    return (
                      <div key={j} style={{ fontFamily: 'monospace', paddingLeft: 12 }}>
                        <span style={{ color: isReq ? '#ff4d4f' : '#d9d9d9', fontWeight: 600 }}>{isReq ? '* ' : '  '}</span>
                        {text}
                      </div>
                    );
                  })}
                </div>
              ))}
            </div>
          )}

          {/* 枚举值 */}
          {detailSource?.enumValues && detailSource.enumValues.length > 0 && (
            <div style={{ padding: '8px 12px', background: '#fff7e6', borderRadius: 6, marginBottom: 8, fontSize: 12 }}>
              <div style={{ fontWeight: 600, marginBottom: 4, color: '#d48806' }}>📌 枚举值</div>
              {detailSource.enumValues.map((v, i) => (
                <div key={i} style={{ fontFamily: 'monospace' }}>{v}</div>
              ))}
            </div>
          )}

          {/* 源码 */}
          {detailSource?.sourceCode && (
            <div>
              <div style={{ fontWeight: 600, marginBottom: 8 }}>💻 源码</div>
              <JavaCodeViewer code={detailSource.sourceCode} maxHeight="400px" />
            </div>
          )}

          {!detailSource?.sourceCode && !detailTree && (
            <Empty description="未找到该方法的详细信息" />
          )}
        </div>
      )}
    </Drawer>
    </>
  );
}
