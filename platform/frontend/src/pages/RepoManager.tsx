import { useEffect, useState, useCallback, useRef } from 'react';
import {
  Card, Table, Button, Form, Input, Select, Tag, Space, Modal, message, Tooltip, Spin, Upload, List, Dropdown,
} from 'antd';
import {
  PlusOutlined, SyncOutlined, ThunderboltOutlined, DeleteOutlined,
  ReloadOutlined, BranchesOutlined, FileTextOutlined, UndoOutlined, UploadOutlined, DownOutlined, ContainerOutlined, SettingOutlined, EditOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import {
  fetchRepos, cloneRepo, pullRepo, analyzeRepo, deleteRepo, updateRepo, fetchBranches, fetchBuildLogs, resetRepo,
  uploadJarToRepo, uploadNewRepo, fetchRepoJars, fetchRepoConfigs, updateRepoConfig, uploadRepoConfigFile,
  fetchEmbeddingStatus, rebuildEmbeddingSSE, continueEmbeddingSSE,
  type RepoEntity, type CloneRequest, type RepoConfigItem,
} from '../api';

const statusColorMap: Record<string, string> = {
  CREATED: 'default',
  CLONING: 'processing',
  CLONED: 'cyan',
  BUILDING: 'processing',
  BUILD_FAILED: 'error',
  ANALYZING: 'processing',
  QUEUED: 'warning',
  ANALYZED: 'success',
  READY: 'success',
  ERROR: 'error',
};

const statusLabelMap: Record<string, string> = {
  CREATED: '已创建',
  CLONING: '克隆中',
  CLONED: '已克隆',
  BUILDING: '构建中',
  BUILD_FAILED: '构建失败',
  ANALYZING: '分析中',
  QUEUED: '排队中',
  ANALYZED: '已分析',
  READY: '就绪',
  ERROR: '错误',
};

export default function RepoManager() {
  const [repos, setRepos] = useState<RepoEntity[]>([]);
  const [loading, setLoading] = useState(false);
  const [showForm, setShowForm] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [actionLoading, setActionLoading] = useState<Record<string, boolean>>({});
  const [branches, setBranches] = useState<string[]>([]);
  const [loadingBranches, setLoadingBranches] = useState(false);
  const [editModalOpen, setEditModalOpen] = useState(false);
  const [editBranches, setEditBranches] = useState<string[]>([]);
  const [editBranchLoading, setEditBranchLoading] = useState(false);
  const [editingRepo, setEditingRepo] = useState<RepoEntity | null>(null);
  const [editForm] = Form.useForm();
  const [form] = Form.useForm<CloneRequest>();
  const [logModalOpen, setLogModalOpen] = useState(false);
  const [logLines, setLogLines] = useState<string[]>([]);
  const [logFinished, setLogFinished] = useState(true);
  const logRef = useRef<HTMLPreElement>(null);
  const [uploadModalOpen, setUploadModalOpen] = useState(false);
  const [uploadRepoId, setUploadRepoId] = useState<number | null>(null);
  const [uploadNewModalOpen, setUploadNewModalOpen] = useState(false);
  const [uploadNewName, setUploadNewName] = useState('');
  const [uploading, setUploading] = useState(false);
  const uploadFilesRef = useRef<File[]>([]);
  const [configModalOpen, setConfigModalOpen] = useState(false);
  const [configRepoId, setConfigRepoId] = useState<number | null>(null);
  const [configRepoName, setConfigRepoName] = useState('');
  const [configItems, setConfigItems] = useState<RepoConfigItem[]>([]);
  const [configSearch, setConfigSearch] = useState('');
  const [configLoading, setConfigLoading] = useState(false);
  const [editingKey, setEditingKey] = useState<string | null>(null);
  const [editingValue, setEditingValue] = useState('');
  const [jarModalOpen, setJarModalOpen] = useState(false);
  const [jarModalRepoName, setJarModalRepoName] = useState('');
  const [jarList, setJarList] = useState<{name: string; source: string; size: string}[]>([]);
  const [loadingJars, setLoadingJars] = useState(false);
  // 向量状态缓存：repoId → EmbeddingStatus
  const [embeddingStatusMap, setEmbeddingStatusMap] = useState<Record<number, { done: number; total: number; status: string; qdrantAvailable: boolean; pending: number; failed: number }>>({});
  const [rebuildingEmbedding, setRebuildingEmbedding] = useState<Record<number, boolean>>({});
  // 向量建设 SSE 日志 Modal
  const [embeddingLogVisible, setEmbeddingLogVisible] = useState(false);
  const [embeddingLogLines, setEmbeddingLogLines] = useState<string[]>([]);
  const [embeddingLogDone, setEmbeddingLogDone] = useState(false);
  const embeddingAbortRef = useRef<AbortController | null>(null);
  const embeddingLogEndRef = useRef<HTMLDivElement | null>(null);

  const load = async () => {
    setLoading(true);
    try {
      const data = await fetchRepos();
      setRepos(data);
      // 同时拉取 READY 仓库的向量状态
      const readyRepos = data.filter(r => r.status === 'READY');
      const statuses = await Promise.allSettled(readyRepos.map(r => fetchEmbeddingStatus(r.id)));
      const newMap: Record<number, { done: number; total: number; status: string; qdrantAvailable: boolean; pending: number; failed: number }> = {};
      statuses.forEach((result, i) => {
        if (result.status === 'fulfilled') {
          const s = result.value;
          newMap[readyRepos[i].id] = { done: s.done, total: s.total, status: s.status, qdrantAvailable: s.qdrantAvailable, pending: s.pending, failed: s.failed };
        }
      });
      setEmbeddingStatusMap(newMap);
    } catch {
      message.error('加载仓库列表失败');
    } finally {
      setLoading(false);
    }
  };

  const handleRebuildEmbedding = async (repoId: number) => {
    setRebuildingEmbedding(prev => ({ ...prev, [repoId]: true }));
    setEmbeddingLogLines([]);
    setEmbeddingLogDone(false);
    setEmbeddingLogVisible(true);

    const abortCtrl = rebuildEmbeddingSSE(repoId, {
      onLog: (line) => {
        setEmbeddingLogLines(prev => [...prev, line]);
        setTimeout(() => embeddingLogEndRef.current?.scrollIntoView({ behavior: 'smooth' }), 50);
      },
      onDone: () => {
        setEmbeddingLogDone(true);
        setRebuildingEmbedding(prev => ({ ...prev, [repoId]: false }));
        // 刷新向量状态
        fetchEmbeddingStatus(repoId).then(s => {
          setEmbeddingStatusMap(prev => ({ ...prev, [repoId]: { done: s.done, total: s.total, status: s.status, qdrantAvailable: s.qdrantAvailable, pending: s.pending, failed: s.failed } }));
        }).catch(() => {});
      },
      onError: (msg) => {
        setEmbeddingLogLines(prev => [...prev, '❌ ' + msg]);
        setEmbeddingLogDone(true);
        setRebuildingEmbedding(prev => ({ ...prev, [repoId]: false }));
      },
    });
    embeddingAbortRef.current = abortCtrl;
  };

  const handleContinueEmbedding = (repoId: number) => {
    setRebuildingEmbedding(prev => ({ ...prev, [repoId]: true }));
    setEmbeddingLogLines([]);
    setEmbeddingLogDone(false);
    setEmbeddingLogVisible(true);

    const abortCtrl = continueEmbeddingSSE(repoId, {
      onLog: (line) => {
        setEmbeddingLogLines(prev => [...prev, line]);
        setTimeout(() => embeddingLogEndRef.current?.scrollIntoView({ behavior: 'smooth' }), 50);
      },
      onDone: () => {
        setEmbeddingLogDone(true);
        setRebuildingEmbedding(prev => ({ ...prev, [repoId]: false }));
        fetchEmbeddingStatus(repoId).then(s => {
          setEmbeddingStatusMap(prev => ({ ...prev, [repoId]: { done: s.done, total: s.total, status: s.status, qdrantAvailable: s.qdrantAvailable, pending: s.pending, failed: s.failed } }));
        }).catch(() => {});
      },
      onError: (msg) => {
        setEmbeddingLogLines(prev => [...prev, '❌ ' + msg]);
        setEmbeddingLogDone(true);
        setRebuildingEmbedding(prev => ({ ...prev, [repoId]: false }));
      },
    });
    embeddingAbortRef.current = abortCtrl;
  };

  useEffect(() => { load(); }, []);

  const fetchBranchList = useCallback(async () => {
    const gitUrl = form.getFieldValue('gitUrl')?.trim();
    const token = form.getFieldValue('token')?.trim();
    const repoType = form.getFieldValue('repoType') || 'GITLAB';
    if (!gitUrl) { message.warning('请先输入 Git 仓库地址'); return; }
    // Token 留空时由后端回退到系统全局 Git Token（系统配置 → Git 全局配置）
    setLoadingBranches(true);
    setBranches([]);
    try {
      const list = await fetchBranches(gitUrl, token || undefined, repoType);
      setBranches(list);
      if (list.length > 0) {
        // 自动选择 main 或 master 或第一个
        const defaultBranch = list.includes('main') ? 'main' : list.includes('master') ? 'master' : list[0];
        form.setFieldValue('branch', defaultBranch);
      }
      message.success(`获取到 ${list.length} 个分支`);
    } catch (err: unknown) {
      if (err instanceof Error) message.error(err.message);
    } finally {
      setLoadingBranches(false);
    }
  }, [form]);

  const setAction = (key: string, val: boolean) =>
    setActionLoading((prev) => ({ ...prev, [key]: val }));

  const showLogs = useCallback((repoId: number) => {
    setLogLines([]);
    setLogFinished(false);
    setLogModalOpen(true);

    let idx = 0;
    const poll = setInterval(async () => {
      try {
        const data = await fetchBuildLogs(repoId, idx);
        if (data.lines.length > 0) {
          setLogLines(prev => [...prev, ...data.lines]);
          idx = data.total;
          // 自动滚动到底部
          setTimeout(() => {
            if (logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight;
          }, 50);
        }
        if (data.finished) {
          setLogFinished(true);
          clearInterval(poll);
        }
      } catch { /* ignore */ }
    }, 1000);

    // 最多轮询 15 分钟
    setTimeout(() => clearInterval(poll), 900000);

    // 关闭时清理
    return () => clearInterval(poll);
  }, []);

  const showJars = useCallback(async (id: number, name: string) => {
    setJarModalRepoName(name);
    setJarList([]);
    setJarModalOpen(true);
    setLoadingJars(true);
    try {
      const list = await fetchRepoJars(id);
      setJarList(list);
    } catch {
      message.error('获取 jar 列表失败');
    } finally {
      setLoadingJars(false);
    }
  }, []);

  const handleClone = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      await cloneRepo(values);
      message.success('仓库克隆任务已提交');
      setShowForm(false);
      form.resetFields();
      setBranches([]);
      load();
    } catch (err: unknown) {
      if (err instanceof Error) message.error(err.message);
    } finally {
      setSubmitting(false);
    }
  };

  const handlePull = async (id: number) => {
    const key = `pull-${id}`;
    setAction(key, true);
    try {
      await pullRepo(id);
      message.success('拉取成功');
      load();
    } catch (err: unknown) {
      if (err instanceof Error) message.error(err.message);
    } finally {
      setAction(key, false);
    }
  };

  const handleAnalyze = async (id: number, forceRebuild = false) => {
    // 防止重复点击
    const repo = repos.find(r => r.id === id);
    if (repo && (repo.status === 'ANALYZING' || repo.status === 'BUILDING' || repo.status === 'QUEUED')) {
      message.warning(repo.status === 'QUEUED' ? '该仓库正在排队中' : '该仓库正在分析中，请等待完成');
      return;
    }
    const key = `analyze-${id}`;
    setAction(key, true);
    try {
      await analyzeRepo(id, forceRebuild);
      message.success(forceRebuild ? '重新编译+分析任务已提交' : '分析任务已提交，编译+分析可能需要几分钟');
      // 立即更新本地状态，禁用按钮
      setRepos(prev => prev.map(r => r.id === id ? { ...r, status: 'ANALYZING' } : r));
      // 不调 load()，避免覆盖乐观更新。由轮询来刷新。
      // 自动轮询状态
      const poll = setInterval(async () => {
        try {
          const repos = await fetchRepos();
          setRepos(repos);
          const repo = repos.find(r => r.id === id);
          if (repo && repo.status !== 'ANALYZING' && repo.status !== 'BUILDING' && repo.status !== 'QUEUED') {
            clearInterval(poll);
            if (repo.status === 'READY') {
              message.success(`仓库「${repo.name}」分析完成`);
            } else if (repo.status === 'ERROR') {
              message.error(`仓库「${repo.name}」分析失败`);
            }
          }
        } catch { /* ignore */ }
      }, 5000);
      // 最多轮询 10 分钟
      setTimeout(() => clearInterval(poll), 600000);
    } catch (err: unknown) {
      if (err instanceof Error) message.error(err.message);
    } finally {
      setAction(key, false);
    }
  };

  const handleDelete = (id: number, name: string) => {
    Modal.confirm({
      title: '确认删除',
      content: `确定要删除仓库「${name}」吗？此操作不可恢复。`,
      okText: '删除',
      okType: 'danger',
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteRepo(id);
          message.success('已删除');
          load();
        } catch (err: unknown) {
          if (err instanceof Error) message.error(err.message);
        }
      },
    });
  };

  const openConfigModal = useCallback(async (repoId: number, repoName: string) => {
    setConfigRepoId(repoId);
    setConfigRepoName(repoName);
    setConfigSearch('');
    setEditingKey(null);
    setConfigModalOpen(true);
    setConfigLoading(true);
    try {
      const items = await fetchRepoConfigs(repoId);
      setConfigItems(items);
    } catch {
      message.error('加载配置失败');
    } finally {
      setConfigLoading(false);
    }
  }, []);

  const searchConfigs = useCallback(async (search: string) => {
    if (!configRepoId) return;
    setConfigLoading(true);
    try {
      const items = await fetchRepoConfigs(configRepoId, search || undefined);
      setConfigItems(items);
    } catch {
      message.error('搜索失败');
    } finally {
      setConfigLoading(false);
    }
  }, [configRepoId]);

  const saveConfigValue = useCallback(async (key: string, value: string) => {
    if (!configRepoId) return;
    try {
      await updateRepoConfig(configRepoId, key, value);
      message.success('已保存');
      setEditingKey(null);
      // 刷新列表
      const items = await fetchRepoConfigs(configRepoId, configSearch || undefined);
      setConfigItems(items);
    } catch {
      message.error('保存失败');
    }
  }, [configRepoId, configSearch]);

  const handleUploadToRepo = async () => {
    if (!uploadRepoId || uploadFilesRef.current.length === 0) {
      message.warning('请选择 jar 文件');
      return;
    }
    setUploading(true);
    try {
      const saved = await uploadJarToRepo(uploadRepoId, uploadFilesRef.current);
      message.success(`上传成功: ${saved.join(', ')}`);
      setUploadModalOpen(false);
      uploadFilesRef.current = [];
      load();
    } catch (err: unknown) {
      if (err instanceof Error) message.error(err.message);
    } finally {
      setUploading(false);
    }
  };

  const handleUploadNewRepo = async () => {
    if (uploadFilesRef.current.length === 0) {
      message.warning('请选择 jar 文件');
      return;
    }
    setUploading(true);
    try {
      await uploadNewRepo(uploadFilesRef.current, uploadNewName || 'uploaded');
      message.success('上传成功，已创建仓库');
      setUploadNewModalOpen(false);
      setUploadNewName('');
      uploadFilesRef.current = [];
      load();
    } catch (err: unknown) {
      if (err instanceof Error) message.error(err.message);
    } finally {
      setUploading(false);
    }
  };

  const columns: ColumnsType<RepoEntity> = [
    {
      title: '仓库',
      dataIndex: 'name',
      key: 'name',
      width: 140,
      fixed: 'left',
      render: (name: string, record: RepoEntity) => (
        <div>
          <div style={{ fontWeight: 600, fontSize: 13 }}>{name}</div>
          <Tooltip title={record.gitUrl}>
            <div style={{ fontSize: 11, color: '#8c8c8c', maxWidth: 120, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {record.gitUrl.replace(/^https?:\/\//, '').split('/').slice(-1)[0].replace('.git', '')}
            </div>
          </Tooltip>
        </div>
      ),
    },
    {
      title: '分支',
      dataIndex: 'branch',
      key: 'branch',
      width: 80,
      render: (b: string) => <Tag>{b}</Tag>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 80,
      render: (s: string) => (
        <Tag color={statusColorMap[s] ?? 'default'}>
          {statusLabelMap[s] ?? s}
        </Tag>
      ),
    },
    {
      title: '最后同步',
      dataIndex: 'lastSyncTime',
      key: 'lastSyncTime',
      width: 140,
      render: (t: string | null) => t ? <span style={{ fontSize: 12 }}>{new Date(t).toLocaleString('zh-CN')}</span> : '-',
    },
    {
      title: '向量索引',
      key: 'embedding',
      width: 160,
      render: (_: unknown, record: RepoEntity) => {
        if (record.status !== 'READY') return <span style={{ fontSize: 11, color: '#bfbfbf' }}>—</span>;
        const emb = embeddingStatusMap[record.id];
        if (!emb) return <span style={{ fontSize: 11, color: '#bfbfbf' }}>加载中...</span>;
        if (!emb.qdrantAvailable) return <Tooltip title="Qdrant 未启动，向量检索不可用"><span style={{ fontSize: 11, color: '#ff7875' }}>⚠️ 向量不可用</span></Tooltip>;
        if (emb.status === 'DONE') {
          return (
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
              <span style={{ fontSize: 11, color: '#52c41a' }}>✅ {emb.total.toLocaleString()} 个方法</span>
              <Button size="small" style={{ fontSize: 10, height: 20, padding: '0 6px' }}
                loading={!!rebuildingEmbedding[record.id]}
                onClick={() => handleRebuildEmbedding(record.id)}>重建</Button>
              <Button size="small" icon={<FileTextOutlined />} style={{ fontSize: 10, height: 20, padding: '0 6px' }}
                onClick={() => showLogs(record.id)}>日志</Button>
            </div>
          );
        }
        if (emb.status === 'IN_PROGRESS' || emb.pending > 0) {
          return (
            <div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 2 }}>
                <span style={{ fontSize: 11, color: '#faad14' }}>⏳ 建设中 {Math.round(emb.done * 100 / Math.max(emb.total, 1))}%</span>
                <Button size="small" style={{ fontSize: 10, height: 18, padding: '0 4px' }}
                  loading={!!rebuildingEmbedding[record.id]}
                  onClick={() => handleContinueEmbedding(record.id)}>继续</Button>
                <Button size="small" style={{ fontSize: 10, height: 18, padding: '0 4px' }}
                  onClick={() => handleRebuildEmbedding(record.id)}>重建</Button>
              </div>
              <div style={{ width: 120, height: 4, background: '#f0f0f0', borderRadius: 2 }}>
                <div style={{ width: `${Math.round(emb.done * 100 / Math.max(emb.total, 1))}%`, height: 4, background: '#faad14', borderRadius: 2, transition: 'width 0.5s' }} />
              </div>
            </div>
          );
        }
        if (emb.status === 'PARTIAL' || (emb.done > 0 && emb.pending === 0 && emb.failed > 0)) {
          return (
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
              <span style={{ fontSize: 11, color: '#faad14' }}>⚠️ {emb.done.toLocaleString()} 完成 / {emb.failed} 失败</span>
              <Button size="small" style={{ fontSize: 10, height: 18, padding: '0 4px' }}
                loading={!!rebuildingEmbedding[record.id]}
                onClick={() => handleContinueEmbedding(record.id)}>继续</Button>
              <Button size="small" style={{ fontSize: 10, height: 18, padding: '0 4px' }}
                onClick={() => handleRebuildEmbedding(record.id)}>重建</Button>
              <Button size="small" icon={<FileTextOutlined />} style={{ fontSize: 10, height: 18, padding: '0 4px' }}
                onClick={() => { setEmbeddingLogLines([`📊 状态: done=${emb.done}, failed=${emb.failed}, total=${emb.total}`, '点击「继续」或「重建」查看实时日志']); setEmbeddingLogDone(true); setEmbeddingLogVisible(true); }}>日志</Button>
            </div>
          );
        }
        return (
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ fontSize: 11, color: '#8c8c8c' }}>未建立</span>
            <Button size="small" type="primary" style={{ fontSize: 10, height: 20, padding: '0 6px' }}
              loading={!!rebuildingEmbedding[record.id]}
              onClick={() => handleRebuildEmbedding(record.id)}>建立向量</Button>
          </div>
        );
      },
    },
    {
      title: '操作',
      key: 'actions',
      width: 360,
      fixed: 'right',
      render: (_, record) => {
        const busy = record.status === 'ANALYZING' || record.status === 'BUILDING' || record.status === 'QUEUED';
        return (
        <Space size={4} wrap>
          <Button size="small" icon={<SyncOutlined />} loading={!!actionLoading[`pull-${record.id}`]} onClick={() => handlePull(record.id)} disabled={busy}>拉取</Button>
          <Dropdown.Button
            size="small"
            type="primary"
            loading={!!actionLoading[`analyze-${record.id}`]}
            disabled={busy}
            onClick={() => handleAnalyze(record.id)}
            menu={{
              items: [
                { key: 'rebuild', label: '重新编译+分析', icon: <SyncOutlined /> },
              ],
              onClick: ({ key }) => {
                if (key === 'rebuild') handleAnalyze(record.id, true);
              },
            }}
            icon={<DownOutlined />}
          >
            <ThunderboltOutlined /> 分析
          </Dropdown.Button>
          <Button size="small" icon={<FileTextOutlined />} onClick={() => showLogs(record.id)}>日志</Button>
          <Button size="small" icon={<ContainerOutlined />} onClick={() => showJars(record.id, record.name)}>jar列表</Button>
          <Button size="small" icon={<UploadOutlined />} onClick={() => { setUploadRepoId(record.id); uploadFilesRef.current = []; setUploadModalOpen(true); }}>上传jar</Button>
          <Button size="small" icon={<SettingOutlined />} onClick={() => openConfigModal(record.id, record.name)}>配置</Button>
          <Button size="small" icon={<EditOutlined />} onClick={async () => { 
            setEditingRepo(record); 
            
            // 加载包前缀配置
            let packagePrefixValue = '';
            try {
              const configs = await fetchRepoConfigs(record.id);
              const packagePrefixConfig = configs.find(c => c.configKey === 'analyze.package.prefix');
              packagePrefixValue = packagePrefixConfig?.configValue || '';
            } catch (err) {
              console.error('加载包前缀配置失败:', err);
            }
            
            editForm.setFieldsValue({ 
              name: record.name || '',
              gitUrl: record.gitUrl || '',
              branch: record.branch || '',
              packagePrefix: packagePrefixValue,
              urlPathIdentifier: record.urlPathIdentifier || '' 
            }); 
            setEditModalOpen(true); 
          }}>编辑</Button>
          {(record.status === 'ERROR' || busy) && (
            <Button size="small" icon={<UndoOutlined />} onClick={async () => { await resetRepo(record.id); message.success('已重置'); load(); }}>重置</Button>
          )}
          <Button size="small" danger icon={<DeleteOutlined />} onClick={() => handleDelete(record.id, record.name)} disabled={busy}>删除</Button>
        </Space>
        );
      },
    },
  ];

  return (
    <div className="repo-manager">
      <Card
        title="仓库管理"
        extra={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
              刷新
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setShowForm(true)}>
              添加仓库
            </Button>
            <Button icon={<UploadOutlined />} onClick={() => { uploadFilesRef.current = []; setUploadNewName(''); setUploadNewModalOpen(true); }}>
              上传 jar
            </Button>
          </Space>
        }
      >
        <Table
          rowKey="id"
          columns={columns}
          dataSource={repos}
          loading={loading}
          pagination={false}
          size="middle"
          scroll={{ x: 720 }}
        />
      </Card>

      <Modal
        title="添加仓库"
        open={showForm}
        onOk={handleClone}
        onCancel={() => { setShowForm(false); form.resetFields(); setBranches([]); }}
        confirmLoading={submitting}
        okText="克隆"
        cancelText="取消"
        width={560}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item
            name="gitUrl"
            label="Git 仓库地址"
            rules={[{ required: true, message: '请输入 Git 仓库地址' }]}
          >
            <Input placeholder="https://github.com/user/repo.git" />
          </Form.Item>
          <Form.Item name="repoType" label="仓库类型" initialValue="GITLAB" rules={[{ required: true }]}>
            <Select>
              <Select.Option value="GITLAB">GitLab</Select.Option>
              <Select.Option value="GITHUB">GitHub</Select.Option>
            </Select>
          </Form.Item>
          <Form.Item name="token" label="访问令牌（私有仓库）">
            <Input.Password placeholder="GitLab Personal Access Token / GitHub Token" />
          </Form.Item>
          <Form.Item label="分支">
            <Space.Compact style={{ width: '100%' }}>
              <Form.Item name="branch" noStyle rules={[{ required: true, message: '请选择分支' }]}>
                <Select
                  placeholder={loadingBranches ? '加载中...' : '请先获取分支列表'}
                  loading={loadingBranches}
                  showSearch
                  style={{ flex: 1 }}
                  notFoundContent={loadingBranches ? <Spin size="small" /> : '请点击右侧按钮获取分支'}
                >
                  {branches.map((b) => (
                    <Select.Option key={b} value={b}>{b}</Select.Option>
                  ))}
                </Select>
              </Form.Item>
              <Button
                icon={<BranchesOutlined />}
                onClick={fetchBranchList}
                loading={loadingBranches}
              >
                获取分支
              </Button>
            </Space.Compact>
          </Form.Item>
          <Form.Item
            name="packagePrefix"
            label="分析包前缀（必填）"
            rules={[{ required: true, message: '请输入包前缀' }]}
            tooltip="只分析这些包下的代码，跳过第三方依赖。多个用逗号分隔"
          >
            <Input placeholder="例如: com.example 或 com.example.order, com.example.payment" />
          </Form.Item>
          <Form.Item
            name="urlPathIdentifier"
            label="URL 路径标识符（可选）"
            tooltip="智能推荐时，只有包含这些标识符的 URL 才会匹配此仓库。支持多个标识符，用逗号分隔。例如：/api/order、/user、/payment 等"
          >
            <Input placeholder="例如: /api/order, /api/payment, /user" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="编译 & 分析日志"
        open={logModalOpen}
        onCancel={() => setLogModalOpen(false)}
        footer={
          <Button onClick={() => setLogModalOpen(false)}>
            {logFinished ? '关闭' : '后台继续运行'}
          </Button>
        }
        width={800}
      >
        <div style={{ position: 'relative' }}>
          {!logFinished && (
            <Tag color="processing" style={{ position: 'absolute', top: 8, right: 8, zIndex: 1 }}>
              <SyncOutlined spin /> 运行中...
            </Tag>
          )}
          <pre
            ref={logRef}
            style={{
              background: '#1e1e1e',
              color: '#d4d4d4',
              padding: 16,
              borderRadius: 6,
              height: 450,
              overflow: 'auto',
              fontSize: 12,
              lineHeight: 1.6,
              fontFamily: "'Fira Code', 'JetBrains Mono', Consolas, monospace",
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-all',
            }}
          >
            {logLines.length === 0 ? (logFinished ? '暂无日志' : '等待日志输出...') : logLines.join('\n')}
          </pre>
        </div>
      </Modal>

      {/* jar 列表 */}
      <Modal
        title={`jar 列表 — ${jarModalRepoName}`}
        open={jarModalOpen}
        onCancel={() => setJarModalOpen(false)}
        footer={<Button onClick={() => setJarModalOpen(false)}>关闭</Button>}
        width={560}
      >
        <Spin spinning={loadingJars}>
          {!loadingJars && jarList.length === 0 ? (
            <div style={{ textAlign: 'center', color: '#8c8c8c', padding: 24 }}>暂无 jar 文件（分析完成后会出现）</div>
          ) : (
            <List
              size="small"
              dataSource={jarList}
              renderItem={(item) => (
                <List.Item>
                  <ContainerOutlined style={{ marginRight: 8, color: item.source === '上传' ? '#52c41a' : '#1677ff' }} />
                  <span style={{ flex: 1 }}>{item.name}</span>
                  <Tag color={item.source === '上传' ? 'green' : item.source.includes('待合并') ? 'orange' : 'blue'} style={{ marginLeft: 8 }}>
                    {item.source}
                  </Tag>
                  <span style={{ color: '#999', fontSize: 11, marginLeft: 8 }}>{item.size} KB</span>
                </List.Item>
              )}
            />
          )}
        </Spin>
      </Modal>

      {/* 上传 jar 到已有仓库 */}
      <Modal
        title="上传 jar 文件"
        open={uploadModalOpen}
        onOk={handleUploadToRepo}
        onCancel={() => setUploadModalOpen(false)}
        confirmLoading={uploading}
        okText="上传"
        cancelText="取消"
      >
        <Upload.Dragger
          multiple
          accept=".jar,.war"
          beforeUpload={(file) => {
            uploadFilesRef.current = [...uploadFilesRef.current, file];
            return false; // 阻止自动上传
          }}
          onRemove={(file) => {
            uploadFilesRef.current = uploadFilesRef.current.filter(f => f.name !== file.name);
          }}
        >
          <p style={{ fontSize: 32, color: '#8c8c8c' }}><UploadOutlined /></p>
          <p>点击或拖拽 jar/war 文件到此处</p>
          <p style={{ fontSize: 12, color: '#8c8c8c' }}>上传后可跳过编译，直接用于 javacg2 分析</p>
        </Upload.Dragger>
      </Modal>

      {/* 上传 jar 创建新仓库 */}
      <Modal
        title="上传 jar 创建仓库"
        open={uploadNewModalOpen}
        onOk={handleUploadNewRepo}
        onCancel={() => setUploadNewModalOpen(false)}
        confirmLoading={uploading}
        okText="创建"
        cancelText="取消"
      >
        <Form layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item label="仓库名称">
            <Input
              value={uploadNewName}
              onChange={e => setUploadNewName(e.target.value)}
              placeholder="给这个仓库起个名字"
            />
          </Form.Item>
        </Form>
        <Upload.Dragger
          multiple
          accept=".jar,.war"
          beforeUpload={(file) => {
            uploadFilesRef.current = [...uploadFilesRef.current, file];
            return false;
          }}
          onRemove={(file) => {
            uploadFilesRef.current = uploadFilesRef.current.filter(f => f.name !== file.name);
          }}
        >
          <p style={{ fontSize: 32, color: '#8c8c8c' }}><UploadOutlined /></p>
          <p>点击或拖拽 jar/war 文件到此处</p>
          <p style={{ fontSize: 12, color: '#8c8c8c' }}>不需要 Git 仓库，直接上传编译好的 jar 进行分析</p>
        </Upload.Dragger>
      </Modal>

      {/* 仓库配置管理 — IDEA 风格树形展示 */}
      <Modal
        title={`仓库配置 — ${configRepoName}`}
        open={configModalOpen}
        onCancel={() => setConfigModalOpen(false)}
        footer={null}
        width={900}
      >
        {/* 提示信息 */}
        <div style={{ padding: '10px 12px', background: '#e6f7ff', border: '1px solid #91d5ff', borderRadius: 6, marginBottom: 12 }}>
          <div style={{ fontSize: 12, color: '#0050b3' }}>
            💡 <strong>提示：</strong>仓库名称、Git URL、分支、包前缀、URL 路径标识符等基础配置，请使用"<strong>编辑</strong>"按钮修改。<br/>
            本页面用于管理 JDK 编译路径、javacg2 高级配置等其他配置项。
          </div>
        </div>

        {/* 编译 JDK 配置（醒目区域） */}
        <div style={{ padding: '10px 12px', background: '#fff7e6', border: '1px solid #ffd591', borderRadius: 6, marginBottom: 12 }}>
          <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 6 }}>☕ 编译 JDK（选填，本仓库专用）</div>
          <div style={{ display: 'flex', gap: 8 }}>
            <Input
              placeholder="例如: /Library/Java/JavaVirtualMachines/jdk-8.jdk/Contents/Home"
              value={configItems.find(c => c.configKey === 'build.java.home')?.configValue ?? ''}
              onChange={e => {
                setConfigItems(prev => {
                  const idx = prev.findIndex(c => c.configKey === 'build.java.home');
                  if (idx >= 0) {
                    const updated = [...prev];
                    updated[idx] = { ...updated[idx], configValue: e.target.value };
                    return updated;
                  }
                  return [...prev, { id: -1, repoId: configRepoId!, configKey: 'build.java.home', configValue: e.target.value, source: 'USER', defaultValue: null }];
                });
              }}
              style={{ fontFamily: 'monospace', fontSize: 12 }}
            />
            <Button type="primary" onClick={() => {
              const item = configItems.find(c => c.configKey === 'build.java.home');
              if (configRepoId) saveConfigValue('build.java.home', item?.configValue ?? '');
            }}>保存</Button>
          </div>
          <div style={{ fontSize: 11, color: '#8c8c8c', marginTop: 4 }}>
            指定本仓库编译用的 JDK 根目录（JAVA_HOME）。留空则按系统配置 → 自动探测（读项目声明的 Java 版本）→ 系统默认 java 依次回退。
          </div>
        </div>

        {/* 工具栏 */}
        <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
          <Input.Search
            placeholder="搜索配置项"
            value={configSearch}
            onChange={e => setConfigSearch(e.target.value)}
            onSearch={v => searchConfigs(v)}
            allowClear
            style={{ flex: 1 }}
          />
          <Upload
            accept=".yml,.yaml,.properties"
            showUploadList={false}
            beforeUpload={(file) => {
              if (!configRepoId) return false;
              Modal.confirm({
                title: '上传配置文件',
                content: (
                  <div>
                    <p>文件: <b>{file.name}</b></p>
                    <p>请选择导入模式：</p>
                    <p>• <b>增量合并</b>：只更新文件中有的配置项，保留其他已有配置</p>
                    <p>• <b>全量覆盖</b>：清除所有用户自定义配置，重新导入</p>
                  </div>
                ),
                okText: '增量合并',
                cancelText: '全量覆盖',
                onOk: async () => {
                  try {
                    const count = await uploadRepoConfigFile(configRepoId!, file as unknown as File, 'merge');
                    message.success(`增量合并成功，更新 ${count} 项配置`);
                    searchConfigs(configSearch);
                  } catch (err: unknown) { if (err instanceof Error) message.error(err.message); }
                },
                onCancel: async () => {
                  try {
                    const count = await uploadRepoConfigFile(configRepoId!, file as unknown as File, 'replace');
                    message.success(`全量覆盖成功，导入 ${count} 项配置`);
                    searchConfigs(configSearch);
                  } catch (err: unknown) { if (err instanceof Error) message.error(err.message); }
                },
              });
              return false;
            }}
          >
            <Button icon={<UploadOutlined />}>上传配置文件</Button>
          </Upload>
        </div>

        {/* 图例 */}
        <div style={{ fontSize: 11, color: '#999', marginBottom: 8 }}>
          <Tag color="green" style={{ fontSize: 10 }}>FILE</Tag> 从仓库配置文件读取
          <Tag color="blue" style={{ fontSize: 10, marginLeft: 4 }}>USER</Tag> 用户自定义/上传
          {configItems.length > 0 && <span style={{ marginLeft: 8 }}>共 {configItems.length} 项</span>}
        </div>

        {/* 树形配置列表 */}
        <div style={{ maxHeight: 550, overflow: 'auto', border: '1px solid #f0f0f0', borderRadius: 6 }}>
          {configLoading ? (
            <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>
          ) : configItems.length === 0 ? (
            <div style={{ textAlign: 'center', padding: 40, color: '#999' }}>
              暂无配置项。分析仓库后自动提取，或上传配置文件。
            </div>
          ) : (
            (() => {
              // 构建树形结构
              type TreeNode = { key: string; value?: string; source?: string; children: Map<string, TreeNode>; item?: RepoConfigItem };
              const root: TreeNode = { key: '', children: new Map() };

              for (const item of configItems) {
                const parts = item.configKey.split('.');
                let current = root;
                for (let i = 0; i < parts.length; i++) {
                  const part = parts[i];
                  if (!current.children.has(part)) {
                    current.children.set(part, { key: parts.slice(0, i + 1).join('.'), children: new Map() });
                  }
                  current = current.children.get(part)!;
                }
                current.value = item.configValue ?? undefined;
                current.source = item.source;
                current.item = item;
              }

              // 渲染树
              const renderTree = (node: TreeNode, depth: number): React.ReactNode[] => {
                const result: React.ReactNode[] = [];
                const sortedEntries = [...node.children.entries()].sort((a, b) => a[0].localeCompare(b[0]));

                for (const [name, child] of sortedEntries) {
                  const hasValue = child.value !== undefined;
                  const hasChildren = child.children.size > 0;
                  const isLeaf = hasValue && !hasChildren;

                  result.push(
                    <div key={child.key} style={{
                      paddingLeft: depth * 20 + 12,
                      paddingRight: 12,
                      paddingTop: 4,
                      paddingBottom: 4,
                      borderBottom: '1px solid #fafafa',
                      background: child.source === 'USER' ? '#f0f5ff' : 'transparent',
                      display: 'flex',
                      alignItems: 'center',
                      gap: 6,
                      minHeight: 32,
                    }}>
                      {/* 展开图标占位 */}
                      <span style={{ width: 14, fontSize: 10, color: '#bfbfbf', flexShrink: 0 }}>
                        {hasChildren && !isLeaf ? '▸' : isLeaf ? '•' : '▸'}
                      </span>

                      {/* key 名 */}
                      <span style={{
                        fontSize: 12,
                        fontFamily: 'monospace',
                        color: hasValue ? '#333' : '#8c8c8c',
                        fontWeight: hasValue ? 500 : 400,
                        flexShrink: 0,
                      }}>
                        {name}
                      </span>

                      {/* 值 */}
                      {hasValue && (
                        <>
                          <span style={{ color: '#d9d9d9', margin: '0 4px' }}>=</span>
                          {editingKey === child.key ? (
                            <div style={{ display: 'flex', gap: 4, flex: 1 }}>
                              <Input
                                size="small"
                                value={editingValue}
                                onChange={e => setEditingValue(e.target.value)}
                                onPressEnter={() => saveConfigValue(child.key, editingValue)}
                                style={{ fontFamily: 'monospace', fontSize: 12 }}
                                autoFocus
                              />
                              <Button size="small" type="primary" onClick={() => saveConfigValue(child.key, editingValue)}>保存</Button>
                              <Button size="small" onClick={() => setEditingKey(null)}>取消</Button>
                            </div>
                          ) : (
                            <span
                              style={{
                                fontSize: 12, fontFamily: 'monospace', flex: 1,
                                color: child.value ? '#1890ff' : '#999',
                                cursor: 'pointer', wordBreak: 'break-all',
                              }}
                              onClick={() => { setEditingKey(child.key); setEditingValue(child.value ?? ''); }}
                            >
                              {child.value || '(空)'}
                              <EditOutlined style={{ marginLeft: 4, fontSize: 10, color: '#d9d9d9' }} />
                            </span>
                          )}
                          {child.source && (
                            <Tag color={child.source === 'USER' ? 'blue' : 'green'}
                                 style={{ fontSize: 9, lineHeight: '14px', padding: '0 3px', flexShrink: 0 }}>
                              {child.source}
                            </Tag>
                          )}
                        </>
                      )}
                    </div>
                  );

                  // 递归渲染子节点
                  if (hasChildren) {
                    result.push(...renderTree(child, depth + 1));
                  }
                }
                return result;
              };

              return renderTree(root, 0);
            })()
          )}
        </div>
      </Modal>

      {/* 向量建设日志 Modal */}
      <Modal
        title="向量建设日志"
        open={embeddingLogVisible}
        onCancel={() => {
          setEmbeddingLogVisible(false);
          if (!embeddingLogDone && embeddingAbortRef.current) {
            embeddingAbortRef.current.abort();
          }
        }}
        footer={embeddingLogDone ? [
          <Button key="close" type="primary" onClick={() => setEmbeddingLogVisible(false)}>关闭</Button>
        ] : [
          <Button key="stop" danger onClick={() => {
            if (embeddingAbortRef.current) embeddingAbortRef.current.abort();
            setEmbeddingLogDone(true);
            setEmbeddingLogLines(prev => [...prev, '⛔ 已手动中断']);
          }}>中断</Button>
        ]}
        width={700}
      >
        <div style={{
          background: '#1e1e1e', color: '#d4d4d4', padding: 16, borderRadius: 8,
          maxHeight: 400, overflowY: 'auto', fontFamily: 'monospace', fontSize: 12, lineHeight: 1.8
        }}>
          {embeddingLogLines.length === 0 && !embeddingLogDone && (
            <span style={{ color: '#888' }}>等待日志...</span>
          )}
          {embeddingLogLines.map((line, idx) => (
            <div key={idx}>{line}</div>
          ))}
          <div ref={embeddingLogEndRef} />
        </div>
      </Modal>

      {/* 编辑仓库 Modal */}
      <Modal
        title={`编辑仓库 — ${editingRepo?.name}`}
        open={editModalOpen}
        onOk={async () => {
          if (!editingRepo) return;
          try {
            const values = await editForm.validateFields();
            await updateRepo(editingRepo.id, {
              name: values.name || '',
              gitUrl: values.gitUrl || '',
              branch: values.branch || '',
              packagePrefix: values.packagePrefix || '',
              urlPathIdentifier: values.urlPathIdentifier || ''
            });
            message.success('更新成功');
            setEditModalOpen(false);
            load();
          } catch (err: unknown) {
            if (err instanceof Error) message.error(err.message);
          }
        }}
        onCancel={() => setEditModalOpen(false)}
        okText="保存"
        cancelText="取消"
        width={600}
      >
        <Form form={editForm} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item
            name="name"
            label="仓库名称"
            rules={[{ required: true, message: '请输入仓库名称' }]}
          >
            <Input placeholder="例如: my-project" />
          </Form.Item>
          
          <Form.Item
            name="gitUrl"
            label="Git 仓库地址"
            rules={[{ required: true, message: '请输入 Git 仓库地址' }]}
          >
            <Input placeholder="https://github.com/user/repo.git" />
          </Form.Item>
          
          <Form.Item
            name="branch"
            label="分支"
            rules={[{ required: true, message: '请选择分支' }]}
          >
            <Input.Group compact>
              <Form.Item name="branch" noStyle rules={[{ required: true, message: '请选择分支' }]}>
                <Select
                  style={{ width: 'calc(100% - 90px)' }}
                  placeholder="请先获取分支列表"
                  showSearch
                  filterOption={(input, option) => (option?.label ?? '').toLowerCase().includes(input.toLowerCase())}
                  options={editBranches.map(b => ({ label: b, value: b }))}
                  notFoundContent={editBranchLoading ? <Spin size="small" /> : '请点击右侧按钮获取'}
                />
              </Form.Item>
              <Button style={{ width: 90 }} loading={editBranchLoading} onClick={async () => {
                const gitUrl = editForm.getFieldValue('gitUrl');
                if (!gitUrl) { message.warning('请先填写 Git 地址'); return; }
                setEditBranchLoading(true);
                try {
                  const list = await fetchBranches(gitUrl, undefined, editingRepo?.repoType);
                  setEditBranches(list);
                  message.success(`获取到 ${list.length} 个分支`);
                } catch (e: unknown) {
                  if (e instanceof Error) message.error(e.message);
                } finally { setEditBranchLoading(false); }
              }}>获取分支</Button>
            </Input.Group>
          </Form.Item>
          
          <Form.Item
            name="packagePrefix"
            label="分析包前缀（必填）"
            rules={[{ required: true, message: '请输入包前缀' }]}
            tooltip="只分析这些包下的代码，跳过第三方依赖。多个用逗号分隔"
          >
            <Input placeholder="例如: com.example.order, com.mycompany" />
          </Form.Item>
          
          <Form.Item
            name="urlPathIdentifier"
            label="URL 路径标识符（可选）"
            tooltip="智能推荐时，只有包含这些标识符的 URL 才会匹配此仓库。多个标识符用逗号分隔。"
          >
            <Input placeholder="例如: /api/order, /api/payment, /user" />
          </Form.Item>
          
          <div style={{ fontSize: 12, color: '#8c8c8c', marginTop: -8, padding: '8px 12px', background: '#f5f5f5', borderRadius: 4 }}>
            💡 <strong>配置说明：</strong><br/>
            • <strong>分析包前缀：</strong>只分析指定包下的代码，跳过第三方依赖。修改后需要重新分析仓库。<br/>
            • <strong>URL 路径标识符：</strong>用于智能仓库推荐和前缀剥离匹配。修改后立即生效，无需重新分析。
          </div>
        </Form>
      </Modal>
    </div>
  );
}
