import { useEffect, useState, useCallback } from 'react';
import type React from 'react';
import { Select, Button, Spin, message, Empty, Tag, Checkbox } from 'antd';
import { FileTextOutlined, CopyOutlined, RocketOutlined, DiffOutlined } from '@ant-design/icons';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeRaw from 'rehype-raw';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { vscDarkPlus } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { fetchRepos, fetchReleaseFileDiff, type RepoEntity, type FileDiffResult } from '../api';
import DiffViewer from '../components/DiffViewer';
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

interface FileChange {
  repo: string;
  file: string;
  status: string;
  additions: number;
  deletions: number;
}

export default function ReleaseDoc() {
  const [repos, setRepos] = useState<RepoEntity[]>([]);
  const [selections, setSelections] = useState<RepoSelection[]>([]);
  const [loadingBranches, setLoadingBranches] = useState(false);
  const [markdown, setMarkdown] = useState('');
  const [generating, setGenerating] = useState(false);
  const [fileChanges, setFileChanges] = useState<FileChange[]>([]);
  // 文件 diff - 内嵌显示（不用 Drawer）
  const [selectedFile, setSelectedFile] = useState<FileChange | null>(null);
  const [diffLoading, setDiffLoading] = useState(false);
  const [diffData, setDiffData] = useState<FileDiffResult | null>(null);
  // 目录树展开状态
  const [expandedPaths, setExpandedPaths] = useState<Set<string>>(new Set());

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
    setFileChanges([]);
    try {
      const res = await api.post<{ success: boolean; data: { markdown: string; otherChanges: FileChange[] }; error: { message: string } | null }>(
        '/release-doc/generate',
        { selections: selected.map(s => ({ repoId: s.repoId, branch: s.branch, baseBranch: s.baseBranch })) }
      );
      if (res.data.success) {
        setMarkdown(res.data.data.markdown);
        setFileChanges(res.data.data.otherChanges ?? []);
      } else {
        message.error(res.data.error?.message ?? '生成失败');
      }
    } catch (e: unknown) {
      message.error(e instanceof Error ? e.message : '生成失败');
    } finally {
      setGenerating(false);
    }
  };

  // 打开文件 diff — 内嵌显示
  const openDiff = async (fc: FileChange) => {
    const sel = selections.find(s => s.repoName === fc.repo && s.selected);
    if (!sel) { message.warning('找不到该文件对应的仓库分支信息'); return; }
    if (selectedFile?.file === fc.file && selectedFile?.repo === fc.repo) {
      setSelectedFile(null); setDiffData(null); return; // 再次点击收起
    }
    setSelectedFile(fc);
    setDiffLoading(true);
    setDiffData(null);
    try {
      const data = await fetchReleaseFileDiff(sel.repoId, fc.file, sel.baseBranch, sel.branch);
      setDiffData(data);
    } catch (e: unknown) {
      message.error(e instanceof Error ? e.message : '获取 diff 失败');
    } finally {
      setDiffLoading(false);
    }
  };

  // 把扁平的 fileChanges 按路径层级构建目录树
  type FileNode = { name: string; fullPath: string; children: Map<string, FileNode>; file?: FileChange };
  const buildFileTree = (files: FileChange[]): Map<string, FileNode> => {
    const root = new Map<string, FileNode>();
    files.forEach(fc => {
      const parts = fc.file.split('/');
      let cur = root;
      parts.forEach((part, idx) => {
        if (!cur.has(part)) {
          cur.set(part, { name: part, fullPath: parts.slice(0, idx + 1).join('/'), children: new Map() });
        }
        const node = cur.get(part)!;
        if (idx === parts.length - 1) node.file = fc;
        cur = node.children;
      });
    });
    return root;
  };

  const togglePath = (p: string) => setExpandedPaths(prev => {
    const n = new Set(prev); n.has(p) ? n.delete(p) : n.add(p); return n;
  });

  const statusColor = (s: string) => s === '新增' ? '#22863a' : s === '删除' ? '#cb2431' : '#0366d6';

  const renderTree = (nodes: Map<string, FileNode>, depth = 0): React.ReactNode =>
    [...nodes.entries()].sort((a, b) => {
      const aIsDir = a[1].children.size > 0; const bIsDir = b[1].children.size > 0;
      if (aIsDir !== bIsDir) return aIsDir ? -1 : 1;
      return a[0].localeCompare(b[0]);
    }).map(([, node]) => {
      const isDir = node.children.size > 0 || !node.file;
      const isSelected = selectedFile?.file === node.file?.file && selectedFile?.repo === node.file?.repo;
      const expanded = expandedPaths.has(node.fullPath);
      if (isDir) {
        return (
          <div key={node.fullPath}>
            <div onClick={() => togglePath(node.fullPath)}
              style={{ padding: `2px 4px 2px ${depth * 14 + 4}px`, fontSize: 12, display: 'flex',
                alignItems: 'center', gap: 4, cursor: 'pointer', userSelect: 'none', color: '#595959' }}>
              <span style={{ fontSize: 9, color: '#bfbfbf' }}>{expanded ? '▼' : '▶'}</span>
              <span style={{ color: '#b09050' }}>📁</span>
              <span style={{ flex: 1 }}>{node.name}</span>
              <span style={{ color: '#bfbfbf', fontSize: 10 }}>{[...node.children.values()].filter(c => c.file).length || ''}</span>
            </div>
            {expanded && renderTree(node.children, depth + 1)}
          </div>
        );
      }
      const fc = node.file!;
      return (
        <div key={node.fullPath} onClick={() => openDiff(fc)}
          style={{ padding: `2px 4px 2px ${depth * 14 + 4}px`, fontSize: 12, display: 'flex',
            alignItems: 'center', gap: 4, cursor: 'pointer',
            background: isSelected ? '#e6f4ff' : undefined,
            borderLeft: isSelected ? '2px solid #1890ff' : '2px solid transparent' }}>
          <span style={{ fontSize: 9, color: '#bfbfbf', marginLeft: 4 }}>─</span>
          <span style={{ color: statusColor(fc.status), fontSize: 10, fontWeight: 700, width: 12 }}>
            {fc.status === '新增' ? 'A' : fc.status === '删除' ? 'D' : 'M'}
          </span>
          <span style={{ flex: 1, color: isSelected ? '#1890ff' : '#262626',
            fontWeight: isSelected ? 600 : 400, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            {node.name}
          </span>
          <span style={{ fontSize: 10, fontFamily: 'monospace', flexShrink: 0 }}>
            <span style={{ color: '#22863a' }}>+{fc.additions}</span>{' '}
            <span style={{ color: '#cb2431' }}>-{fc.deletions}</span>
          </span>
        </div>
      );
    });

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
              {fileChanges.length > 0 && (
                <div style={{ marginBottom: 20 }}>
                  <div style={{ fontSize: 15, fontWeight: 700, marginBottom: 8, color: '#262626', display: 'flex', alignItems: 'center', gap: 6 }}>
                    <DiffOutlined style={{ color: '#1890ff' }} /> 变更文件（点击文件查看 diff）
                  </div>
                  {/* 左右双栏：目录树 + diff 预览 */}
                  <div style={{ display: 'flex', border: '1px solid #e8e8e8', borderRadius: 8, overflow: 'hidden', minHeight: 300 }}>
                    {/* 左侧：按仓库+路径分组的目录树 */}
                    <div style={{ width: 320, flexShrink: 0, borderRight: '1px solid #e8e8e8', overflow: 'auto', background: '#fafafa', maxHeight: 520 }}>
                      {/* 按仓库分组 */}
                      {Array.from(new Set(fileChanges.map(f => f.repo))).map(repo => {
                        const repoFiles = fileChanges.filter(f => f.repo === repo);
                        const tree = buildFileTree(repoFiles);
                        const repoKey = `repo:${repo}`;
                        const expanded = expandedPaths.has(repoKey) || !expandedPaths.size;
                        return (
                          <div key={repo}>
                            <div onClick={() => togglePath(repoKey)}
                              style={{ padding: '6px 8px', fontWeight: 600, fontSize: 12, color: '#262626',
                                background: '#f0f0f0', borderBottom: '1px solid #e8e8e8', cursor: 'pointer',
                                display: 'flex', alignItems: 'center', gap: 4, userSelect: 'none' }}>
                              <span style={{ fontSize: 9, color: '#8c8c8c' }}>{expanded ? '▼' : '▶'}</span>
                              <span>📦</span>
                              <span style={{ flex: 1 }}>{repo}</span>
                              <span style={{ color: '#8c8c8c', fontWeight: 400 }}>{repoFiles.length} 个文件</span>
                            </div>
                            {expanded && renderTree(tree, 0)}
                          </div>
                        );
                      })}
                    </div>
                    {/* 右侧：diff 预览 */}
                    <div style={{ flex: 1, overflow: 'auto', maxHeight: 520, background: '#fff' }}>
                      {selectedFile ? (
                        diffLoading ? (
                          <div style={{ textAlign: 'center', padding: 40 }}>
                            <Spin /><div style={{ color: '#8c8c8c', marginTop: 8, fontSize: 12 }}>加载 diff...</div>
                          </div>
                        ) : diffData ? (
                          <div>
                            <div style={{ padding: '8px 12px', background: '#f5f5f5', borderBottom: '1px solid #e8e8e8',
                              display: 'flex', alignItems: 'center', gap: 8, fontSize: 12 }}>
                              <Tag color={selectedFile.status === '新增' ? 'green' : selectedFile.status === '删除' ? 'red' : 'blue'}>
                                {selectedFile.status}
                              </Tag>
                              <span style={{ fontFamily: 'monospace', color: '#262626', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                                {selectedFile.file}
                              </span>
                              <span style={{ fontFamily: 'monospace', flexShrink: 0 }}>
                                <span style={{ color: '#22863a' }}>+{diffData.additions}</span>{' '}
                                <span style={{ color: '#cb2431' }}>-{diffData.deletions}</span>
                              </span>
                            </div>
                            <DiffViewer diff={diffData.diff} path={diffData.path} maxHeight="460px" />
                          </div>
                        ) : (
                          <div style={{ textAlign: 'center', padding: 40, color: '#8c8c8c', fontSize: 12 }}>无差异内容</div>
                        )
                      ) : (
                        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: '100%', color: '#bfbfbf', fontSize: 13, gap: 8 }}>
                          <DiffOutlined style={{ fontSize: 32 }} />
                          <span>点击左侧文件查看 diff</span>
                        </div>
                      )}
                    </div>
                  </div>
                </div>
              )}
              <div className="markdown-body" style={{ fontSize: 14, lineHeight: 1.8 }}>
                <ReactMarkdown
                  remarkPlugins={[remarkGfm]}
                  rehypePlugins={[rehypeRaw]}
                  components={{
                    // SQL/代码块语法高亮
                    code({ className, children, ...props }: { className?: string; children?: React.ReactNode; inline?: boolean }) {
                      const match = /language-(\w+)/.exec(className ?? '');
                      const isInline = !match;
                      return isInline ? (
                        <code style={{
                          background: '#f0f0f0', padding: '2px 5px',
                          borderRadius: 3, fontSize: 13, fontFamily: 'monospace',
                          color: '#c7254e',
                        }} {...props}>{children}</code>
                      ) : (
                        <div style={{ position: 'relative' }}>
                          <Button
                            size="small"
                            icon={<CopyOutlined />}
                            style={{ position: 'absolute', top: 6, right: 6, zIndex: 2, opacity: 0.85 }}
                            onClick={() => {
                              navigator.clipboard.writeText(String(children).replace(/\n$/, ''));
                              message.success('已复制');
                            }}
                          >复制</Button>
                          <SyntaxHighlighter
                            style={vscDarkPlus}
                            language={match[1]}
                            PreTag="div"
                            customStyle={{ borderRadius: 8, fontSize: 12, margin: '8px 0' }}
                          >{String(children).replace(/\n$/, '')}</SyntaxHighlighter>
                        </div>
                      );
                    },
                    // 表格样式
                    table({ children }) {
                      return (
                        <table style={{
                          width: '100%', borderCollapse: 'collapse',
                          margin: '12px 0', fontSize: 13,
                        }}>{children}</table>
                      );
                    },
                    th({ children }) {
                      return <th style={{ border: '1px solid #e8e8e8', padding: '8px 12px', background: '#fafafa', fontWeight: 600 }}>{children}</th>;
                    },
                    td({ children }) {
                      return <td style={{ border: '1px solid #e8e8e8', padding: '8px 12px' }}>{children}</td>;
                    },
                    // 引用块（AI 摘要）
                    blockquote({ children }) {
                      return (
                        <blockquote style={{
                          margin: '12px 0', padding: '12px 16px',
                          background: '#fffbe6', borderLeft: '4px solid #faad14',
                          borderRadius: '0 6px 6px 0', color: '#595959',
                        }}>{children}</blockquote>
                      );
                    },
                    // 标题
                    h2({ children }) {
                      return (
                        <h2 style={{
                          fontSize: 18, fontWeight: 700, marginTop: 28, marginBottom: 12,
                          paddingBottom: 8, borderBottom: '2px solid #f0f0f0', color: '#262626',
                        }}>{children}</h2>
                      );
                    },
                    h3({ children }) {
                      return (
                        <h3 style={{
                          fontSize: 15, fontWeight: 600, marginTop: 18, marginBottom: 8,
                          color: '#434343',
                        }}>{children}</h3>
                      );
                    },
                    // 水平线作为区块分隔
                    hr() {
                      return <hr style={{ margin: '24px 0', border: 'none', borderTop: '1px solid #f0f0f0' }} />;
                    },
                  }}
                >{markdown}</ReactMarkdown>
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
