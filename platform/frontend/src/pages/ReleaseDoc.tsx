import { useEffect, useState, useCallback } from 'react';
import { Select, Button, Spin, message, Empty, Tag, Checkbox } from 'antd';
import { FileTextOutlined, CopyOutlined, RocketOutlined } from '@ant-design/icons';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { fetchRepos, type RepoEntity } from '../api';
import axios from 'axios';

const api = axios.create({ baseURL: '/api', timeout: 120_000 });

interface RepoSelection {
  repoId: number;
  repoName: string;
  branch: string;
  baseBranch: string;
  branches: string[];
  selected: boolean;
}

export default function ReleaseDoc() {
  const [repos, setRepos] = useState<RepoEntity[]>([]);
  const [selections, setSelections] = useState<RepoSelection[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadingBranches, setLoadingBranches] = useState(false);
  const [markdown, setMarkdown] = useState('');
  const [generating, setGenerating] = useState(false);

  // Load repos
  useEffect(() => {
    fetchRepos().then(r => {
      setRepos(r);
      // Initialize selections with all analyzed repos
      setSelections(r
        .filter(repo => repo.status === 'ANALYZED' || repo.status === 'READY')
        .map(repo => ({
          repoId: repo.id,
          repoName: repo.name,
          branch: '',
          baseBranch: 'master',
          branches: [],
          selected: false,
        }))
      );
    }).catch(() => message.error('加载仓库失败'));
  }, []);

  // Load branches for a repo
  const loadBranches = useCallback(async (repoId: number) => {
    try {
      const res = await api.get<{ success: boolean; data: string[] }>(`/release-doc/branches/${repoId}`);
      if (res.data.success) {
        setSelections(prev => prev.map(s =>
          s.repoId === repoId ? { ...s, branches: res.data.data } : s
        ));
      }
    } catch { /* ignore */ }
  }, []);

  // When a repo is checked, load its branches
  const handleCheck = (repoId: number, checked: boolean) => {
    setSelections(prev => prev.map(s =>
      s.repoId === repoId ? { ...s, selected: checked } : s
    ));
    if (checked) loadBranches(repoId);
  };

  // Select all
  const handleSelectAll = () => {
    setLoadingBranches(true);
    const updated = selections.map(s => ({ ...s, selected: true }));
    setSelections(updated);
    Promise.all(updated.map(s => loadBranches(s.repoId)))
      .finally(() => setLoadingBranches(false));
  };

  // Generate document
  const handleGenerate = async () => {
    const selected = selections.filter(s => s.selected && s.branch);
    if (selected.length === 0) {
      message.warning('请选择至少一个仓库并指定分支');
      return;
    }
    setGenerating(true);
    setMarkdown('');
    try {
      const res = await api.post<{ success: boolean; data: { markdown: string }; error: { message: string } | null }>(
        '/release-doc/generate',
        { selections: selected.map(s => ({ repoId: s.repoId, branch: s.branch, baseBranch: s.baseBranch })) }
      );
      if (res.data.success) {
        setMarkdown(res.data.data.markdown);
      } else {
        message.error(res.data.error?.message ?? '生成失败');
      }
    } catch (e: unknown) {
      message.error(e instanceof Error ? e.message : '生成失败');
    } finally {
      setGenerating(false);
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden' }}>
      {/* Header */}
      <div style={{ padding: '12px 16px', borderBottom: '1px solid #f0f0f0', display: 'flex', alignItems: 'center', gap: 12 }}>
        <RocketOutlined style={{ fontSize: 18, color: '#fa8c16' }} />
        <span style={{ fontSize: 16, fontWeight: 600 }}>上线文档生成</span>
        <span style={{ fontSize: 12, color: '#8c8c8c' }}>从 git diff 提取变更，生成结构化上线文档</span>
        <div style={{ flex: 1 }} />
        <Button size="small" onClick={handleSelectAll} loading={loadingBranches}>全选仓库</Button>
        <Button type="primary" icon={<FileTextOutlined />} onClick={handleGenerate} loading={generating}
          disabled={!selections.some(s => s.selected && s.branch)}>
          生成文档
        </Button>
      </div>

      <div style={{ display: 'flex', flex: 1, overflow: 'hidden' }}>
        {/* Left: Repo/Branch selection */}
        <div style={{ width: 380, borderRight: '1px solid #f0f0f0', overflow: 'auto', padding: '16px' }}>
          <div style={{ fontSize: 14, fontWeight: 700, marginBottom: 14, color: '#262626', display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ fontSize: 16 }}>📦</span> 选择仓库与分支
          </div>

          {selections.map(sel => (
            <div key={sel.repoId} style={{
              padding: '12px 14px', marginBottom: 6, borderRadius: 10,
              border: sel.selected ? '2px solid #1890ff' : '1px solid #e8e8e8',
              background: sel.selected ? '#f0f7ff' : '#fafafa',
              transition: 'all 0.2s',
              cursor: 'pointer',
            }} onClick={() => !sel.selected && handleCheck(sel.repoId, true)}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <Checkbox checked={sel.selected} onChange={e => { e.stopPropagation(); handleCheck(sel.repoId, e.target.checked); }} />
                <div style={{ flex: 1 }}>
                  <span style={{ fontWeight: 600, fontSize: 14, color: '#262626' }}>{sel.repoName}</span>
                  <Tag color={sel.selected ? 'blue' : 'default'} style={{ marginLeft: 8, fontSize: 10 }}>
                    {repos.find(r => r.id === sel.repoId)?.status ?? ''}
                  </Tag>
                </div>
              </div>

              {sel.selected && (
                <div style={{ marginTop: 10, paddingLeft: 30, display: 'flex', flexDirection: 'column', gap: 8 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span style={{ fontSize: 12, color: '#595959', width: 64, flexShrink: 0 }}>功能分支</span>
                    <Select size="small" style={{ flex: 1 }} placeholder="选择要上线的分支"
                      value={sel.branch || undefined}
                      onChange={v => setSelections(prev => prev.map(s => s.repoId === sel.repoId ? { ...s, branch: v } : s))}
                      options={sel.branches.map(b => ({ label: b, value: b }))}
                      showSearch filterOption={(input, option) => (option?.label ?? '').toLowerCase().includes(input.toLowerCase())}
                      loading={sel.branches.length === 0}
                      notFoundContent={<span style={{ color: '#bfbfbf', fontSize: 12 }}>加载中...</span>}
                    />
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span style={{ fontSize: 12, color: '#595959', width: 64, flexShrink: 0 }}>对比基线</span>
                    <Select size="small" style={{ flex: 1 }}
                      value={sel.baseBranch}
                      showSearch
                      filterOption={(input, option) => (option?.label ?? '').toLowerCase().includes(input.toLowerCase())}
                      onChange={v => setSelections(prev => prev.map(s => s.repoId === sel.repoId ? { ...s, baseBranch: v } : s))}
                      options={[
                        { label: 'master', value: 'master' },
                        { label: 'main', value: 'main' },
                        { label: 'develop', value: 'develop' },
                        ...sel.branches.filter(b => !['master', 'main', 'develop'].includes(b)).map(b => ({ label: b, value: b })),
                      ]}
                    />
                  </div>
                </div>
              )}
            </div>
          ))}

          {selections.length === 0 && (
            <Empty description="暂无已分析的仓库" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          )}
        </div>

        {/* Right: Document output */}
        <div style={{ flex: 1, overflow: 'auto', padding: '16px 24px' }}>
          {generating ? (
            <div style={{ textAlign: 'center', padding: 60 }}>
              <Spin size="large" />
              <div style={{ marginTop: 16, color: '#8c8c8c' }}>正在分析 git diff 并生成文档...</div>
              <div style={{ marginTop: 8, color: '#bfbfbf', fontSize: 12 }}>
                对比分支差异、解析配置/SQL/接口/依赖变更
              </div>
            </div>
          ) : markdown ? (
            <div>
              <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 12 }}>
                <Button icon={<CopyOutlined />} size="small" onClick={() => {
                  navigator.clipboard.writeText(markdown);
                  message.success('已复制 Markdown 到剪贴板');
                }}>复制 Markdown</Button>
              </div>
              <div className="markdown-body" style={{ fontSize: 14, lineHeight: 1.7 }}>
                <ReactMarkdown remarkPlugins={[remarkGfm]}>{markdown}</ReactMarkdown>
              </div>
            </div>
          ) : (
            <div style={{ textAlign: 'center', padding: 80 }}>
              <RocketOutlined style={{ fontSize: 48, color: '#d9d9d9' }} />
              <div style={{ marginTop: 16, color: '#8c8c8c', fontSize: 14 }}>
                选择仓库和分支后，点击"生成文档"
              </div>
              <div style={{ marginTop: 8, color: '#bfbfbf', fontSize: 12 }}>
                系统会自动从 git diff 中提取配置变更、SQL、接口、依赖等信息
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
