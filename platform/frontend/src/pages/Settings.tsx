import { useEffect, useState, useCallback } from 'react';
import { Card, Form, Input, Button, message, Typography, Divider, Modal, List, Breadcrumb, Tag, Select, Collapse, Tabs, Badge, Space, Alert, Table, Switch, Tooltip } from 'antd';
import { SaveOutlined, FolderOutlined, FileOutlined, FolderOpenOutlined, ApiOutlined, CheckCircleOutlined, CloseCircleOutlined, CopyOutlined, ReloadOutlined, PlusOutlined, DeleteOutlined, ThunderboltOutlined } from '@ant-design/icons';
import axios from 'axios';
import {
  fetchMavenConfig, saveMavenConfig, fetchClaudeConfig, saveClaudeConfig,
  fetchEmbeddingConfig, saveEmbeddingConfig,
  fetchGitConfig, saveGitConfig, fetchMcpStatus, fetchMcpServers, createMcpServer, updateMcpServer, deleteMcpServer, testMcpServer,
  fetchPrompts, savePrompt,
  type MavenConfig, type ClaudeConfig, type EmbeddingConfig, type GitConfig, type McpStatus, type McpServerItem, type PromptDef,
} from '../api';

const { Title, Text } = Typography;

interface FileItem {
  name: string;
  path: string;
  isDir: boolean;
  size: number;
}

// ─── File Browser Modal ──────────────────────────────────────────────────────

function FileBrowser({ open, onClose, onSelect, filter }: {
  open: boolean;
  onClose: () => void;
  onSelect: (path: string) => void;
  filter: 'all' | 'dir' | 'xml';
}) {
  const [files, setFiles] = useState<FileItem[]>([]);
  const [currentPath, setCurrentPath] = useState('');
  const [loading, setLoading] = useState(false);

  const browse = useCallback(async (path: string) => {
    setLoading(true);
    try {
      const res = await axios.get('/api/files/browse', { params: { path, filter } });
      if (res.data.success) {
        setFiles(res.data.data);
        setCurrentPath(path);
      }
    } catch {
      message.error('无法浏览目录');
    } finally {
      setLoading(false);
    }
  }, [filter]);

  useEffect(() => {
    if (open) browse('');
  }, [open, browse]);

  const pathParts = currentPath.split('/').filter(Boolean);

  return (
    <Modal
      title="选择文件/目录"
      open={open}
      onCancel={onClose}
      width={640}
      footer={null}
      bodyStyle={{ padding: 0 }}
    >
      <div style={{ padding: '12px 16px', borderBottom: '1px solid #f0f0f0', background: '#fafafa' }}>
        <Breadcrumb>
          <Breadcrumb.Item>
            <a onClick={() => browse('')}>~</a>
          </Breadcrumb.Item>
          {pathParts.map((part, i) => (
            <Breadcrumb.Item key={i}>
              <a onClick={() => browse('/' + pathParts.slice(0, i + 1).join('/'))}>{part}</a>
            </Breadcrumb.Item>
          ))}
        </Breadcrumb>
      </div>
      <div style={{ height: 400, overflow: 'auto' }}>
        <List
          loading={loading}
          dataSource={files}
          size="small"
          renderItem={(item) => (
            <List.Item
              style={{ padding: '8px 16px', cursor: 'pointer' }}
              onClick={() => {
                if (item.isDir) {
                  browse(item.path);
                } else {
                  onSelect(item.path);
                  onClose();
                }
              }}
              actions={
                item.name !== '..' ? [
                  item.isDir ? (
                    <Button
                      size="small"
                      type="link"
                      onClick={(e) => { e.stopPropagation(); onSelect(item.path); onClose(); }}
                    >
                      选择此目录
                    </Button>
                  ) : null,
                ].filter(Boolean) as React.ReactNode[] : undefined
              }
            >
              <List.Item.Meta
                avatar={item.isDir
                  ? <FolderOutlined style={{ fontSize: 20, color: '#faad14' }} />
                  : <FileOutlined style={{ fontSize: 20, color: '#8c8c8c' }} />
                }
                title={<span style={{ fontSize: 13 }}>{item.name}</span>}
                description={item.name !== '..' && !item.isDir
                  ? <Tag style={{ fontSize: 11 }}>{(item.size / 1024).toFixed(1)} KB</Tag>
                  : null
                }
              />
            </List.Item>
          )}
        />
      </div>
    </Modal>
  );
}

// ─── AI Prompt 配置面板 ───────────────────────────────────────────────────────

function PromptConfigPanel() {
  const [prompts, setPrompts] = useState<PromptDef[]>([]);
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    fetchPrompts()
      .then((list) => {
        setPrompts(list);
        const d: Record<string, string> = {};
        list.forEach((p) => { d[p.key] = p.value ?? ''; });
        setDrafts(d);
      })
      .catch(() => message.error('加载 Prompt 失败'))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { load(); }, [load]);

  const doSave = async (key: string, value: string, resetHint: boolean) => {
    setSaving(key);
    try {
      await savePrompt(key, value);
      message.success(resetHint ? '已恢复默认' : '已保存');
      load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败');
    } finally {
      setSaving(null);
    }
  };

  return (
    <Card>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <Title level={4} style={{ margin: 0 }}>AI Prompt 配置</Title>
        <Button icon={<ReloadOutlined />} size="small" onClick={load} loading={loading}>刷新</Button>
      </div>
      <Text type="secondary" style={{ display: 'block', marginBottom: 16 }}>
        所有 AI 使用的系统 prompt 集中在此管理。留空则使用内置默认值，填写后覆盖默认；保存后即时生效。
      </Text>
      <Collapse items={prompts.map((p) => ({
        key: p.key,
        label: (
          <span>
            {p.label}
            {p.customized && <Tag color="blue" style={{ marginLeft: 8 }}>已自定义</Tag>}
          </span>
        ),
        children: (
          <div>
            <Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>{p.description}</Text>
            <Input.TextArea
              value={drafts[p.key] ?? ''}
              onChange={(e) => setDrafts({ ...drafts, [p.key]: e.target.value })}
              rows={10}
              placeholder="留空使用内置默认值"
              style={{ fontFamily: 'monospace', fontSize: 12 }}
            />
            <Space style={{ marginTop: 8 }}>
              <Button type="primary" icon={<SaveOutlined />} size="small"
                loading={saving === p.key} onClick={() => doSave(p.key, drafts[p.key] ?? '', false)}>
                保存
              </Button>
              <Button size="small" loading={saving === p.key}
                onClick={() => { setDrafts({ ...drafts, [p.key]: '' }); doSave(p.key, '', true); }}>
                恢复默认
              </Button>
            </Space>
            <Collapse size="small" style={{ marginTop: 12 }} items={[{
              key: 'def',
              label: '📋 查看内置默认值',
              children: (
                <pre style={{ fontSize: 12, fontFamily: 'monospace', whiteSpace: 'pre-wrap', wordBreak: 'break-word', margin: 0, maxHeight: 360, overflow: 'auto', background: '#fafafa', padding: 12, borderRadius: 4 }}>
                  {p.defaultText}
                </pre>
              ),
            }]} />
          </div>
        ),
      }))} />
    </Card>
  );
}

// ─── Settings Page ───────────────────────────────────────────────────────────

export default function Settings() {
  const [gitForm] = Form.useForm<GitConfig>();
  const [mavenForm] = Form.useForm<MavenConfig>();
  const [claudeForm] = Form.useForm<ClaudeConfig>();
  const [embeddingForm] = Form.useForm<EmbeddingConfig>();
  const [savingGit, setSavingGit] = useState(false);
  const [savingMaven, setSavingMaven] = useState(false);
  const [savingClaude, setSavingClaude] = useState(false);
  const [savingEmbedding, setSavingEmbedding] = useState(false);
  const [browserOpen, setBrowserOpen] = useState(false);
  const [browserFilter, setBrowserFilter] = useState<'all' | 'dir' | 'xml'>('all');
  const [browserTarget, setBrowserTarget] = useState<string>('');
  const [mcpStatus, setMcpStatus] = useState<McpStatus | null>(null);
  const [mcpLoading, setMcpLoading] = useState(false);
  const [mcpError, setMcpError] = useState(false);
  const [mcpIde, setMcpIde] = useState<'kiro' | 'cursor' | 'claude'>('kiro');
  // 外部 MCP Server 管理
  const [mcpServers, setMcpServers] = useState<McpServerItem[]>([]);
  const [mcpServersLoading, setMcpServersLoading] = useState(false);
  const [addModalOpen, setAddModalOpen] = useState(false);
  const [editingServer, setEditingServer] = useState<McpServerItem | null>(null);
  const [testingId, setTestingId] = useState<number | null>(null);
  const [serverForm] = Form.useForm();

  useEffect(() => {
    fetchGitConfig().then(c => gitForm.setFieldsValue(c)).catch(() => {});
    fetchMavenConfig().then(c => mavenForm.setFieldsValue(c)).catch(() => {});
    fetchClaudeConfig().then(c => { claudeForm.setFieldsValue(c); }).catch(() => {});
    fetchEmbeddingConfig().then(c => embeddingForm.setFieldsValue(c)).catch(() => {});
    loadMcpStatus();
    loadMcpServers();
  }, [gitForm, mavenForm, claudeForm, embeddingForm]);

  const loadMcpServers = () => {
    setMcpServersLoading(true);
    fetchMcpServers()
      .then(list => setMcpServers(list))
      .catch(() => {})
      .finally(() => setMcpServersLoading(false));
  };

  const handleSaveServer = async () => {
    try {
      const values = await serverForm.validateFields();
      if (editingServer) {
        await updateMcpServer(editingServer.id, values);
        message.success('已更新');
      } else {
        await createMcpServer(values);
        message.success('已添加');
      }
      setAddModalOpen(false);
      serverForm.resetFields();
      setEditingServer(null);
      loadMcpServers();
    } catch (err: unknown) {
      if (err instanceof Error) message.error(err.message);
    }
  };

  const handleDeleteServer = (id: number, name: string) => {
    Modal.confirm({
      title: `确认删除 "${name}"？`,
      okText: '删除', okType: 'danger', cancelText: '取消',
      onOk: async () => {
        await deleteMcpServer(id);
        message.success('已删除');
        loadMcpServers();
      },
    });
  };

  const handleTestServer = async (id: number) => {
    setTestingId(id);
    try {
      const result = await testMcpServer(id);
      if (result.status === 'OK') {
        message.success(result.message);
      } else {
        message.error(result.message);
      }
      loadMcpServers();
    } catch (err: unknown) {
      if (err instanceof Error) message.error(err.message);
    } finally {
      setTestingId(null);
    }
  };

  const loadMcpStatus = () => {
    setMcpLoading(true);
    setMcpError(false);
    fetchMcpStatus()
      .then(s => setMcpStatus(s))
      .catch(() => setMcpError(true))
      .finally(() => setMcpLoading(false));
  };

  const getMcpConfig = (ide: 'kiro' | 'cursor' | 'claude') => {
    const endpoint = mcpStatus?.sseEndpoint ?? 'http://localhost:8080/mcp/sse';
    const config = {
      mcpServers: {
        'java-callgraph': {
          url: endpoint,
          transport: 'sse',
        },
      },
    };
    if (ide === 'kiro') return JSON.stringify(config, null, 2);
    if (ide === 'cursor') return JSON.stringify(config, null, 2);
    // Claude Code (claude_desktop_config.json)
    return JSON.stringify({
      mcpServers: {
        'java-callgraph': {
          command: 'npx',
          args: ['-y', 'mcp-client-sse', endpoint],
        },
      },
    }, null, 2);
  };

  const copyConfig = () => {
    navigator.clipboard.writeText(getMcpConfig(mcpIde))
      .then(() => message.success('配置已复制到剪贴板'))
      .catch(() => message.error('复制失败，请手动复制'));
  };

  const openBrowser = (target: string, filter: 'all' | 'dir' | 'xml') => {
    setBrowserTarget(target);
    setBrowserFilter(filter);
    setBrowserOpen(true);
  };

  const handleFileSelect = (path: string) => {
    mavenForm.setFieldValue(browserTarget as keyof MavenConfig, path);
  };

  const handleSaveGit = async () => {
    setSavingGit(true);
    try {
      const values = await gitForm.validateFields();
      await saveGitConfig(values);
      message.success('Git 配置已保存');
    } catch (err: unknown) {
      if (err instanceof Error) message.error(err.message);
    } finally {
      setSavingGit(false);
    }
  };

  const handleSaveMaven = async () => {
    setSavingMaven(true);
    try {
      const values = await mavenForm.validateFields();
      await saveMavenConfig(values);
      message.success('Maven 配置已保存');
    } catch (err: unknown) {
      if (err instanceof Error) message.error(err.message);
    } finally {
      setSavingMaven(false);
    }
  };

  const handleSaveClaude = async () => {
    setSavingClaude(true);
    try {
      const values = await claudeForm.validateFields();
      await saveClaudeConfig(values);
      message.success('Claude API 配置已保存');
    } catch (err: unknown) {
      if (err instanceof Error) message.error(err.message);
    } finally {
      setSavingClaude(false);
    }
  };

  const handleSaveEmbedding = async () => {
    setSavingEmbedding(true);
    try {
      const values = await embeddingForm.validateFields();
      await saveEmbeddingConfig(values);
      message.success('Embedding 配置已保存');
    } catch (err: unknown) {
      if (err instanceof Error) message.error(err.message);
    } finally {
      setSavingEmbedding(false);
    }
  };

  const browseButton = (target: string, filter: 'all' | 'dir' | 'xml') => (
    <Button
      icon={<FolderOpenOutlined />}
      onClick={() => openBrowser(target, filter)}
    >
      浏览
    </Button>
  );

  return (
    <div style={{ maxWidth: 760, margin: '0 auto' }}>
      <Tabs defaultActiveKey="general" items={[
        {
          key: 'general',
          label: '系统配置',
          children: (
            <Card>
              <Title level={4}>Git 全局配置</Title>
              <Text type="secondary" style={{ display: 'block', marginBottom: 16 }}>
                配置默认的 Git Token，添加仓库时如果不单独填写 Token，将使用此全局配置
              </Text>
              <Form form={gitForm} layout="vertical">
                <Form.Item name="repoType" label="默认仓库类型" initialValue="GITLAB">
                  <Select>
                    <Select.Option value="GITLAB">GitLab</Select.Option>
                    <Select.Option value="GITHUB">GitHub</Select.Option>
                  </Select>
                </Form.Item>
                <Form.Item name="token" label="默认 Git Token">
                  <Input.Password placeholder="GitLab Personal Access Token / GitHub Token" />
                </Form.Item>
                <Button type="primary" icon={<SaveOutlined />} onClick={handleSaveGit} loading={savingGit}>
                  保存 Git 配置
                </Button>
              </Form>

              <Divider />

              <Title level={4}>Maven 构建配置</Title>
              <Text type="secondary" style={{ display: 'block', marginBottom: 16 }}>
                配置私有 Maven 仓库信息，编译 Git 项目时将使用这些配置
              </Text>
              <Form form={mavenForm} layout="vertical">
                <Form.Item name="settingsPath" label="settings.xml 路径"
                  tooltip="Maven settings.xml 的绝对路径，包含私有仓库认证信息">
                  <Input placeholder="/Users/liang/.m2/settings.xml" addonAfter={browseButton('settingsPath', 'xml')} />
                </Form.Item>
                <Form.Item name="localRepo" label="本地仓库路径"
                  tooltip="Maven 本地仓库目录的绝对路径">
                  <Input placeholder="/Users/liang/.m2/repository" addonAfter={browseButton('localRepo', 'dir')} />
                </Form.Item>
                <Form.Item name="mavenHome" label="Maven 安装目录（可选）"
                  tooltip="Maven 安装根目录（包含 bin/mvn），留空则使用系统 PATH 中的 mvn">
                  <Input placeholder="/usr/local/apache-maven-3.9.6" addonAfter={browseButton('mavenHome', 'dir')} />
                </Form.Item>
                <Form.Item name="javaHome" label="编译用 JDK 目录（可选）"
                  tooltip="编译被分析仓库时使用的 JDK 根目录（包含 bin/java）。老的 gRPC/protobuf 项目依赖 javax.annotation.Generated，需指定 JDK 8；留空则用平台自身的 JDK。仓库级可在「仓库配置」中用 build.java.home 覆盖此项。">
                  <Input placeholder="/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home" addonAfter={browseButton('javaHome', 'dir')} />
                </Form.Item>
                <Button type="primary" icon={<SaveOutlined />} onClick={handleSaveMaven} loading={savingMaven}>
                  保存 Maven 配置
                </Button>
              </Form>

              <Divider />

              <Title level={4}>Claude API 配置</Title>
              <Text type="secondary" style={{ display: 'block', marginBottom: 16 }}>
                配置 Claude API 用于智能问答功能，所有回答严格基于真实代码
              </Text>
              <Form form={claudeForm} layout="vertical">
                <Form.Item name="apiUrl" label="API 地址">
                  <Input placeholder="https://api.anthropic.com" />
                </Form.Item>
                <Form.Item name="apiKey" label="API Key">
                  <Input.Password placeholder="输入新的 API Key（留空则保持原有配置）" />
                </Form.Item>
                <Alert type="info" showIcon style={{ marginBottom: 16 }}
                  message="System Prompt 已迁移" 
                  description="智能问答及各类 AI 分析的 prompt 现统一在「AI Prompt」标签页管理。" />
                <Button type="primary" icon={<SaveOutlined />} onClick={handleSaveClaude} loading={savingClaude}>
                  保存 Claude 配置
                </Button>
              </Form>
            </Card>
          ),
        },
        {
          key: 'prompts',
          label: 'AI Prompt',
          children: <PromptConfigPanel />,
        },
        {
          key: 'embedding',
          label: 'Embedding 配置',
          children: (
            <Card>
              <Title level={4}>Embedding 模型配置</Title>
              <Text type="secondary" style={{ display: 'block', marginBottom: 16 }}>
                配置向量化模型，用于语义搜索和 AI 问答的代码检索。支持 OpenAI 及兼容接口（如 Azure OpenAI、OneAPI 等）。
                不配置时语义搜索降级为关键词匹配。
              </Text>
              <Form form={embeddingForm} layout="vertical">
                <Form.Item name="apiUrl" label="API 地址"
                  tooltip="OpenAI 兼容的 Embedding 接口地址，接口路径为 /v1/embeddings">
                  <Input placeholder="https://api.openai.com" />
                </Form.Item>
                <Form.Item name="apiKey" label="API Key">
                  <Input.Password placeholder="输入新的 API Key（留空则保持原有配置）" />
                </Form.Item>
                <Form.Item name="model" label="模型名称"
                  tooltip="Embedding 模型名，默认 text-embedding-3-large（3072 维）。使用其他模型时需确认维度与 Qdrant collection 一致">
                  <Input placeholder="text-embedding-3-large" />
                </Form.Item>
                <Form.Item name="qdrantUrl" label="Qdrant 地址"
                  tooltip="Qdrant 向量数据库地址，Docker 模式下默认 http://qdrant:6333，本地开发默认 http://localhost:6333">
                  <Input placeholder="http://localhost:6333" />
                </Form.Item>
                <Button type="primary" icon={<SaveOutlined />} onClick={handleSaveEmbedding} loading={savingEmbedding}>
                  保存 Embedding 配置
                </Button>
              </Form>
            </Card>
          ),
        },
        {
          key: 'mcp',
          label: (
            <span>
              <ApiOutlined />
              MCP 配置
              {mcpStatus && !mcpError && (
                <Badge status="success" style={{ marginLeft: 6 }} />
              )}
              {mcpError && (
                <Badge status="error" style={{ marginLeft: 6 }} />
              )}
            </span>
          ),
          children: (
            <Card>
              {/* 状态卡片 */}
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
                <Title level={4} style={{ margin: 0 }}>MCP Server 状态</Title>
                <Button icon={<ReloadOutlined />} size="small" loading={mcpLoading} onClick={loadMcpStatus}>
                  刷新
                </Button>
              </div>

              {mcpError ? (
                <Alert
                  type="error"
                  icon={<CloseCircleOutlined />}
                  showIcon
                  message="MCP Server 未响应"
                  description="请确认后端服务已启动（./start.sh），然后点击刷新"
                  style={{ marginBottom: 16 }}
                />
              ) : mcpStatus ? (
                <Alert
                  type="success"
                  icon={<CheckCircleOutlined />}
                  showIcon
                  message={`MCP Server 运行中 · ${mcpStatus.tools.length} 个工具可用`}
                  description={
                    <span>
                      SSE 端点：<code style={{ background: '#f5f5f5', padding: '1px 6px', borderRadius: 3 }}>{mcpStatus.sseEndpoint}</code>
                      &nbsp;·&nbsp;协议版本：{mcpStatus.protocolVersion}
                    </span>
                  }
                  style={{ marginBottom: 16 }}
                />
              ) : (
                <Alert type="info" message="正在检查 MCP Server 状态..." showIcon style={{ marginBottom: 16 }} />
              )}

              {/* 工具列表 */}
              {mcpStatus && (
                <>
                  <Title level={5} style={{ marginBottom: 12 }}>可用工具</Title>
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8, marginBottom: 24 }}>
                    {mcpStatus.tools.map(tool => (
                      <div key={tool.name} style={{ padding: '10px 12px', background: '#f6f8fa', borderRadius: 6, border: '1px solid #e8e8e8' }}>
                        <div style={{ fontWeight: 600, fontSize: 13, color: '#1890ff', fontFamily: 'monospace', marginBottom: 4 }}>
                          {tool.name}
                        </div>
                        <div style={{ fontSize: 12, color: '#666', lineHeight: 1.5 }}>{tool.description}</div>
                      </div>
                    ))}
                  </div>
                </>
              )}

              {/* 接入配置 */}
              <Title level={5} style={{ marginBottom: 12 }}>AI 编程助手接入配置</Title>
              <Space style={{ marginBottom: 12 }}>
                {(['kiro', 'cursor', 'claude'] as const).map(ide => (
                  <Button
                    key={ide}
                    size="small"
                    type={mcpIde === ide ? 'primary' : 'default'}
                    onClick={() => setMcpIde(ide)}
                  >
                    {ide === 'kiro' ? 'Kiro' : ide === 'cursor' ? 'Cursor' : 'Claude Code'}
                  </Button>
                ))}
              </Space>

              <div style={{ position: 'relative' }}>
                <Button
                  icon={<CopyOutlined />}
                  size="small"
                  onClick={copyConfig}
                  style={{ position: 'absolute', top: 8, right: 8, zIndex: 1 }}
                >
                  复制
                </Button>
                <pre style={{
                  background: '#282c34', color: '#abb2bf', padding: '16px 48px 16px 16px',
                  borderRadius: 8, fontSize: 13, fontFamily: 'monospace',
                  lineHeight: 1.6, overflow: 'auto', margin: 0,
                }}>
                  {getMcpConfig(mcpIde)}
                </pre>
              </div>

              <div style={{ marginTop: 12, fontSize: 12, color: '#999' }}>
                {mcpIde === 'kiro' && '📁 保存到 ~/.kiro/settings/mcp.json 或 .kiro/settings/mcp.json（工作区级）'}
                {mcpIde === 'cursor' && '📁 保存到 .cursor/mcp.json（项目根目录）或 ~/.cursor/mcp.json（全局）'}
                {mcpIde === 'claude' && '📁 保存到 ~/Library/Application Support/Claude/claude_desktop_config.json'}
              </div>

              {/* 外部 MCP Server 管理 */}
              <Divider style={{ margin: '20px 0 12px' }} />
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
                <Title level={5} style={{ margin: 0 }}>外部 MCP Server（平台作为 Client 调用）</Title>
                <Button
                  type="primary" size="small" icon={<PlusOutlined />}
                  onClick={() => { setEditingServer(null); serverForm.resetFields(); serverForm.setFieldsValue({ transport: 'SSE', enabled: true }); setAddModalOpen(true); }}
                >
                  添加
                </Button>
              </div>
              <Table
                size="small"
                loading={mcpServersLoading}
                dataSource={mcpServers}
                rowKey="id"
                pagination={false}
                columns={[
                  {
                    title: '名称', dataIndex: 'name', key: 'name', width: 120,
                    render: (name: string, r: McpServerItem) => (
                      <div>
                        <div style={{ fontWeight: 600, fontSize: 12 }}>{name}</div>
                        {r.description && <div style={{ fontSize: 11, color: '#999' }}>{r.description}</div>}
                      </div>
                    ),
                  },
                  {
                    title: 'URL', dataIndex: 'url', key: 'url',
                    render: (url: string) => <span style={{ fontSize: 11, fontFamily: 'monospace' }}>{url}</span>,
                  },
                  { title: '传输', dataIndex: 'transport', key: 'transport', width: 60, render: (t: string) => <Tag>{t}</Tag> },
                  {
                    title: '状态', key: 'status', width: 100,
                    render: (_: unknown, r: McpServerItem) => {
                      if (r.lastTestStatus === 'OK') return <Tooltip title={r.lastTestMessage}><Tag color="success">✅ 正常</Tag></Tooltip>;
                      if (r.lastTestStatus === 'FAIL') return <Tooltip title={r.lastTestMessage}><Tag color="error">❌ 失败</Tag></Tooltip>;
                      return <Tag>未测试</Tag>;
                    },
                  },
                  {
                    title: '启用', dataIndex: 'enabled', key: 'enabled', width: 60,
                    render: (enabled: boolean, r: McpServerItem) => (
                      <Switch
                        size="small" checked={enabled}
                        onChange={async (checked) => {
                          await updateMcpServer(r.id, { enabled: checked });
                          loadMcpServers();
                        }}
                      />
                    ),
                  },
                  {
                    title: '操作', key: 'actions', width: 140,
                    render: (_: unknown, r: McpServerItem) => (
                      <Space size={4}>
                        <Button
                          size="small" icon={<ThunderboltOutlined />}
                          loading={testingId === r.id}
                          onClick={() => handleTestServer(r.id)}
                        >测试</Button>
                        <Button
                          size="small"
                          onClick={() => { setEditingServer(r); serverForm.setFieldsValue(r); setAddModalOpen(true); }}
                        >编辑</Button>
                        <Button size="small" danger icon={<DeleteOutlined />} onClick={() => handleDeleteServer(r.id, r.name)} />
                      </Space>
                    ),
                  },
                ]}
                locale={{ emptyText: '暂无外部 MCP Server，点击「添加」配置' }}
              />
            </Card>
          ),
        },
      ]} />

      <FileBrowser
        open={browserOpen}
        onClose={() => setBrowserOpen(false)}
        onSelect={handleFileSelect}
        filter={browserFilter}
      />

      {/* 添加/编辑外部 MCP Server */}
      <Modal
        title={editingServer ? '编辑 MCP Server' : '添加外部 MCP Server'}
        open={addModalOpen}
        onOk={handleSaveServer}
        onCancel={() => { setAddModalOpen(false); serverForm.resetFields(); setEditingServer(null); }}
        okText={editingServer ? '保存' : '添加'}
        cancelText="取消"
        width={480}
      >
        <Form form={serverForm} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="如：我的工具服务" />
          </Form.Item>
          <Form.Item name="url" label="URL" rules={[{ required: true, message: '请输入 URL' }]}
            tooltip="SSE 模式填写 MCP Server 的 SSE 端点，如 http://localhost:9000/mcp/sse">
            <Input placeholder="http://localhost:9000/mcp/sse" />
          </Form.Item>
          <Form.Item name="transport" label="传输方式" initialValue="SSE" rules={[{ required: true }]}>
            <Select>
              <Select.Option value="SSE">SSE（HTTP Server-Sent Events）</Select.Option>
              <Select.Option value="STDIO">STDIO（标准输入输出）</Select.Option>
            </Select>
          </Form.Item>
          <Form.Item name="description" label="描述（可选）">
            <Input placeholder="这个服务提供什么工具" />
          </Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked" initialValue={true}>
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
