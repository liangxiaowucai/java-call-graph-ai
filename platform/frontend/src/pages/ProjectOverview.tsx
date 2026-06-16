import { useEffect, useState, useCallback, useRef } from 'react';
import { Select, Button, Empty, Spin, message, Alert } from 'antd';
import { SyncOutlined, LoadingOutlined } from '@ant-design/icons';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import mermaid from 'mermaid';
import { fetchRepos, fetchRepoOverview, regenerateOverview, fetchBuildLogs, type RepoEntity } from '../api';

mermaid.initialize({ startOnLoad: false, theme: 'default', securityLevel: 'loose' });

function MermaidBlock({ code }: { code: string }) {
  const ref = useRef<HTMLDivElement>(null);
  const [svg, setSvg] = useState('');

  useEffect(() => {
    const id = 'mermaid-' + Math.random().toString(36).slice(2);
    mermaid.parse(code)
      .then(() => mermaid.render(id, code))
      .then(({ svg }) => setSvg(svg))
      .catch(() => setSvg(''));
  }, [code]);

  return svg ? (
    <div ref={ref} dangerouslySetInnerHTML={{ __html: svg }} style={{ overflow: 'auto', margin: '8px 0' }} />
  ) : (
    <pre style={{ background: '#f5f5f5', padding: 12, borderRadius: 6, fontSize: 12 }}>{code}</pre>
  );
}

export default function ProjectOverview() {
  const [repos, setRepos] = useState<RepoEntity[]>([]);
  const [selectedRepoId, setSelectedRepoId] = useState<number | null>(null);
  const [overview, setOverview] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [progressLogs, setProgressLogs] = useState<string[]>([]);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    fetchRepos().then(r => setRepos(r.filter(repo => repo.status === 'READY'))).catch(() => {});
  }, []);

  const loadOverview = useCallback(async (repoId: number) => {
    setLoading(true);
    try {
      const data = await fetchRepoOverview(repoId);
      setOverview(data);
    } catch {
      message.error('加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  // 轮询构建日志获取进度
  const startPolling = useCallback((repoId: number) => {
    let logIndex = 0;
    setProgressLogs([]);
    pollRef.current = setInterval(async () => {
      try {
        const logs = await fetchBuildLogs(repoId, logIndex);
        if (logs.lines.length > 0) {
          // 只取文档生成相关的日志
          const docLogs = logs.lines.filter(l => l.includes('📖') || l.includes('[1/3]') || l.includes('[2/3]') || l.includes('[3/3]') || l.includes('✅') || l.includes('⚠️'));
          if (docLogs.length > 0) {
            setProgressLogs(prev => [...prev, ...docLogs]);
          }
          logIndex += logs.lines.length;
        }
        if (logs.finished) {
          if (pollRef.current) clearInterval(pollRef.current);
          pollRef.current = null;
          setGenerating(false);
          // 重新加载文档
          await loadOverview(repoId);
        }
      } catch {
        // ignore
      }
    }, 2000);
  }, [loadOverview]);

  const handleRegenerate = useCallback(async () => {
    if (!selectedRepoId) return;
    setGenerating(true);
    setProgressLogs([]);
    setOverview(null);
    try {
      await regenerateOverview(selectedRepoId);
      message.info('文档正在后台生成，请等待...');
      startPolling(selectedRepoId);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : '生成失败';
      message.error(msg);
      setGenerating(false);
    }
  }, [selectedRepoId, startPolling]);

  // 清理轮询
  useEffect(() => {
    return () => {
      if (pollRef.current) clearInterval(pollRef.current);
    };
  }, []);

  useEffect(() => {
    if (selectedRepoId) {
      if (pollRef.current) { clearInterval(pollRef.current); pollRef.current = null; }
      setGenerating(false);
      setProgressLogs([]);
      loadOverview(selectedRepoId);
    }
  }, [selectedRepoId, loadOverview]);

  return (
    <div style={{ height: 'calc(100vh - 32px)', display: 'flex', flexDirection: 'column' }}>
      <div style={{ padding: '12px 16px', borderBottom: '1px solid #f0f0f0', background: '#fff', display: 'flex', alignItems: 'center', gap: 12 }}>
        <Select
          style={{ width: 240 }}
          placeholder="选择仓库"
          value={selectedRepoId}
          onChange={v => { setSelectedRepoId(v); setOverview(null); }}
          options={repos.map(r => ({ label: r.name, value: r.id }))}
        />
        {selectedRepoId && (
          <Button
            icon={generating ? <LoadingOutlined /> : <SyncOutlined />}
            onClick={handleRegenerate}
            loading={generating}
            type={generating ? 'default' : 'primary'}
          >
            {generating ? '生成中...' : 'AI 生成文档'}
          </Button>
        )}
      </div>

      {/* 生成进度 */}
      {generating && progressLogs.length > 0 && (
        <div style={{ padding: '8px 16px', background: '#fffbe6', borderBottom: '1px solid #ffe58f' }}>
          <div style={{ fontSize: 12, color: '#ad8b00', fontWeight: 600, marginBottom: 4 }}>📖 文档生成进度</div>
          {progressLogs.slice(-5).map((log, i) => (
            <div key={i} style={{ fontSize: 12, color: '#666', lineHeight: 1.6 }}>{log}</div>
          ))}
        </div>
      )}

      {generating && progressLogs.length === 0 && (
        <Alert message="文档正在后台生成，可能需要几分钟..." type="info" showIcon style={{ margin: '0 16px', marginTop: 8 }} />
      )}

      {/* 文档内容 */}
      <div style={{ flex: 1, overflow: 'auto', padding: '16px 24px', background: '#fafafa' }}>
        {!selectedRepoId ? (
          <Empty description="请选择仓库查看项目概览" style={{ marginTop: 80 }} />
        ) : loading ? (
          <div style={{ textAlign: 'center', padding: 80 }}><Spin size="large" /></div>
        ) : !overview && !generating ? (
          <Empty description="暂无概览文档，点击「AI 生成文档」创建" style={{ marginTop: 80 }} />
        ) : overview ? (
          <div style={{ maxWidth: 960, margin: '0 auto', background: '#fff', padding: '24px 32px', borderRadius: 8, boxShadow: '0 1px 3px rgba(0,0,0,0.06)' }}>
            <div className="markdown-body">
              <ReactMarkdown
                remarkPlugins={[remarkGfm]}
                components={{
                  code: ({ className, children }) => {
                    const match = /language-mermaid/.exec(className || '');
                    if (match) {
                      return <MermaidBlock code={String(children).trim()} />;
                    }
                    return <code className={className}>{children}</code>;
                  },
                  pre: ({ children }) => <>{children}</>,
                }}
              >{overview}</ReactMarkdown>
            </div>
          </div>
        ) : null}
      </div>
    </div>
  );
}
