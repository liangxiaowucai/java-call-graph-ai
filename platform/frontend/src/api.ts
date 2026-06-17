import axios from 'axios';

const api = axios.create({
  baseURL: '/api',
  timeout: 600_000, // 10 分钟，编译+分析大项目需要时间
});

// ─── Types ───────────────────────────────────────────────────────────────────

export interface ApiResponse<T> {
  success: boolean;
  data: T;
  error: { errorType: string; message: string; suggestion: string } | null;
}

export interface RepoEntity {
  id: number;
  name: string;
  gitUrl: string;
  repoType: string;
  branch: string;
  localPath: string;
  lastCommitHash: string | null;
  lastSyncTime: string | null;
  status: string;
  createdAt: string;
}

export interface CloneRequest {
  gitUrl: string;
  token?: string;
  repoType: string;
  branch: string;
  packagePrefix?: string;
}

export interface EntryPoint {
  id: number;
  endpointType: string;   // CONTROLLER | LISTENER | SCHEDULED
  httpMethod: string;
  urlPath: string;
  fullMethod: string;
  className: string;
}

export interface BoundaryInfo {
  boundaryType: string;    // DB | HTTP | EXCEPTION | TRANSACTION | SERIALIZATION
  lineNumber: number;
  context: string;
}

export interface CallTreeNode {
  fullMethod: string;
  className: string;
  methodName: string;
  callType: string;
  lineNumber: number | null;
  boundaries: BoundaryInfo[];
  children: CallTreeNode[];
  isRecursive: boolean;
  isLazyLoad: boolean;
  ambiguous: boolean;
}

export interface AmbiguityWarning {
  fullMethod: string;
  locations: AmbiguityLocation[];
}

export interface AmbiguityLocation {
  repoId: number;
  repoName: string;
  filePath: string;
}

export interface CallTree {
  root: CallTreeNode;
  totalNodes: number;
  maxDepth: number;
  hasCycle: boolean;
  warnings?: AmbiguityWarning[];
}

export interface CallerInfo {
  fullMethod: string;
  className: string;
  callType: string;
  lineNumber: number | null;
}

// ─── API Functions ───────────────────────────────────────────────────────────

export async function fetchRepos(): Promise<RepoEntity[]> {
  const res = await api.get<ApiResponse<RepoEntity[]>>('/repos');
  return res.data.data;
}

export async function cloneRepo(req: CloneRequest): Promise<unknown> {
  const res = await api.post<ApiResponse<unknown>>('/repos', req);
  if (!res.data.success) throw new Error(res.data.error?.message ?? '克隆失败');
  return res.data.data;
}

export async function fetchBranches(gitUrl: string, token?: string, repoType?: string): Promise<string[]> {
  const res = await api.post<ApiResponse<string[]>>('/repos/branches', { gitUrl, token, repoType: repoType ?? 'GITLAB', branch: '' });
  if (!res.data.success) throw new Error(res.data.error?.message ?? '获取分支失败');
  return res.data.data;
}

export async function pullRepo(id: number): Promise<unknown> {
  const res = await api.post<ApiResponse<unknown>>(`/repos/${id}/pull`);
  if (!res.data.success) throw new Error(res.data.error?.message ?? '拉取失败');
  return res.data.data;
}

export async function analyzeRepo(id: number, forceRebuild = false): Promise<unknown> {
  const res = await api.post<ApiResponse<unknown>>(`/repos/${id}/analyze?forceRebuild=${forceRebuild}`);
  if (!res.data.success) throw new Error(res.data.error?.message ?? '分析失败');
  return res.data.data;
}

export async function deleteRepo(id: number): Promise<void> {
  const res = await api.delete<ApiResponse<string>>(`/repos/${id}`);
  if (!res.data.success) throw new Error(res.data.error?.message ?? '删除失败');
}

export async function resetRepo(id: number): Promise<void> {
  const res = await api.post<ApiResponse<string>>(`/repos/${id}/reset`);
  if (!res.data.success) throw new Error(res.data.error?.message ?? '重置失败');
}

export async function uploadJarToRepo(id: number, files: File[]): Promise<string[]> {
  const formData = new FormData();
  files.forEach(f => formData.append('files', f));
  const res = await api.post<ApiResponse<string[]>>(`/repos/${id}/upload-jar`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  if (!res.data.success) throw new Error(res.data.error?.message ?? '上传失败');
  return res.data.data;
}

export async function uploadNewRepo(files: File[], name: string): Promise<RepoEntity> {
  const formData = new FormData();
  files.forEach(f => formData.append('files', f));
  formData.append('name', name);
  const res = await api.post<ApiResponse<RepoEntity>>('/repos/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  if (!res.data.success) throw new Error(res.data.error?.message ?? '上传失败');
  return res.data.data;
}

export async function fetchEntryPoints(repoId: number): Promise<EntryPoint[]> {
  const res = await api.get<ApiResponse<EntryPoint[]>>(`/repos/${repoId}/entry-points`);
  return res.data.data;
}

export async function fetchCallTree(repoId: number, method: string, maxDepth = 20): Promise<CallTree> {
  const res = await api.get<ApiResponse<CallTree>>(`/repos/${repoId}/call-tree`, {
    params: { method, maxDepth },
  });
  if (!res.data.success) throw new Error(res.data.error?.message ?? '获取调用树失败');
  return res.data.data;
}

export async function fetchCallers(repoId: number, method: string, depth = 5): Promise<CallerInfo[]> {
  const res = await api.get<ApiResponse<CallerInfo[]>>(`/repos/${repoId}/callers`, {
    params: { method, depth },
  });
  return res.data.data;
}

export async function fetchCallees(repoId: number, method: string, depth = 5): Promise<CallerInfo[]> {
  const res = await api.get<ApiResponse<CallerInfo[]>>(`/repos/${repoId}/callees`, {
    params: { method, depth },
  });
  return res.data.data;
}

export async function fetchMethodSource(repoId: number, method: string): Promise<string | null> {
  const res = await api.get<ApiResponse<string>>(`/repos/${repoId}/source`, {
    params: { method },
  });
  if (!res.data.success) return null;
  return res.data.data;
}

export interface MethodSourceDetail {
  sourceCode: string;
  methodSignature: string | null;
  enumValues: string[];
  chainContext: string[];
  paramClasses: { className: string; shortName: string; fields: string[] }[];
  startLine: number;
}

export async function fetchMethodSourceDetail(repoId: number, method: string, entryMethod?: string): Promise<MethodSourceDetail | null> {
  const res = await api.get<ApiResponse<MethodSourceDetail>>(`/repos/${repoId}/source-detail`, {
    params: { method, entryMethod },
  });
  if (!res.data.success) return null;
  return res.data.data;
}

// ─── Config API ──────────────────────────────────────────────────────────────

export interface MavenConfig {
  settingsPath?: string;
  localRepo?: string;
  mavenHome?: string;
  javaHome?: string;
}

export interface ClaudeConfig {
  apiUrl?: string;
  apiKey?: string;
  apiKeyConfigured?: string;
  systemPrompt?: string;
  defaultPrompt?: string;
}

export async function fetchMavenConfig(): Promise<MavenConfig> {
  const res = await api.get<ApiResponse<MavenConfig>>('/config/maven');
  return res.data.data;
}

export async function saveMavenConfig(config: MavenConfig): Promise<void> {
  const res = await api.put<ApiResponse<string>>('/config/maven', config);
  if (!res.data.success) throw new Error(res.data.error?.message ?? '保存失败');
}

export async function fetchClaudeConfig(): Promise<ClaudeConfig> {
  const res = await api.get<ApiResponse<ClaudeConfig>>('/config/claude');
  return res.data.data;
}

export async function saveClaudeConfig(config: ClaudeConfig): Promise<void> {
  const res = await api.put<ApiResponse<string>>('/config/claude', config);
  if (!res.data.success) throw new Error(res.data.error?.message ?? '保存失败');
}

export interface EmbeddingConfig {
  apiUrl?: string;
  apiKey?: string;
  apiKeyConfigured?: string;
  model?: string;
  qdrantUrl?: string;
}

export async function fetchEmbeddingConfig(): Promise<EmbeddingConfig> {
  const res = await api.get<ApiResponse<EmbeddingConfig>>('/config/embedding');
  return res.data.data;
}

export async function saveEmbeddingConfig(config: EmbeddingConfig): Promise<void> {
  const res = await api.put<ApiResponse<string>>('/config/embedding', config);
  if (!res.data.success) throw new Error(res.data.error?.message ?? '保存失败');
}

export interface AnalyzeConfig {
  packagePrefix?: string;
}

export async function fetchAnalyzeConfig(): Promise<AnalyzeConfig> {
  const res = await api.get<ApiResponse<AnalyzeConfig>>('/config/analyze');
  return res.data.data ?? {};
}

export async function saveAnalyzeConfig(config: AnalyzeConfig): Promise<void> {
  const res = await api.put<ApiResponse<string>>('/config/analyze', config);
  if (!res.data.success) throw new Error(res.data.error?.message ?? '保存失败');
}

export async function fetchRepoJars(id: number): Promise<string[]> {
  const res = await api.get<ApiResponse<string[]>>(`/repos/${id}/jars`);
  return res.data.data ?? [];
}

export async function fetchRepoOverview(id: number): Promise<string | null> {
  const res = await api.get<ApiResponse<string>>(`/repos/${id}/overview`);
  return res.data.data;
}

export async function regenerateOverview(id: number): Promise<void> {
  const res = await api.post<ApiResponse<string>>(`/repos/${id}/regenerate-overview`);
  if (!res.data.success) throw new Error(res.data.error?.message ?? '生成失败');
}

// ─── Build Log API ───────────────────────────────────────────────────────────

export interface BuildLogResponse {
  lines: string[];
  total: number;
  finished: boolean;
}

export async function fetchBuildLogs(repoId: number, fromIndex: number): Promise<BuildLogResponse> {
  const res = await api.get<ApiResponse<BuildLogResponse>>(`/repos/${repoId}/logs`, {
    params: { fromIndex },
  });
  return res.data.data;
}

// ─── Log Analysis API ────────────────────────────────────────────────────────

export interface NodeStatus {
  fullMethod: string;
  status: 'OK' | 'ERROR' | 'UNKNOWN';
  errorMessage: string | null;
  logLineNumber: number;
  logSnippet: string | null;
}

export interface LogAnalysisResult {
  nodeStatuses: NodeStatus[];
  summary: string;
  extractedUrl: string | null;
  extractedException: string | null;
}

export async function analyzeLog(repoId: number, entryMethod: string, logText: string): Promise<LogAnalysisResult> {
  const res = await api.post<ApiResponse<LogAnalysisResult>>(`/repos/${repoId}/analyze-log`, { entryMethod, logText });
  if (!res.data.success) throw new Error(res.data.error?.message ?? '日志分析失败');
  return res.data.data;
}

export interface MockConfig {
  mockRequest: string;
  mockResponse: string;
}

export async function getMock(repoId: number, method: string): Promise<MockConfig | null> {
  const res = await api.get<ApiResponse<MockConfig | null>>(`/repos/${repoId}/mock`, { params: { method } });
  return res.data.data;
}

export async function saveMock(repoId: number, method: string, mockRequest: string, mockResponse: string): Promise<void> {
  await api.put(`/repos/${repoId}/mock`, { method, mockRequest, mockResponse });
}

export async function deleteMock(repoId: number, method: string): Promise<void> {
  await api.delete(`/repos/${repoId}/mock`, { params: { method } });
}

// ─── Code Generation API ─────────────────────────────────────────────────────

export async function generateCallChainCode(repoId: number, method: string, statuses?: Record<string, string>): Promise<string> {
  if (statuses && Object.keys(statuses).length > 0) {
    const res = await api.post<ApiResponse<string>>(`/repos/${repoId}/generate-code`, { method, statuses });
    if (!res.data.success) throw new Error(res.data.error?.message ?? '生成失败');
    return res.data.data;
  }
  const res = await api.get<ApiResponse<string>>(`/repos/${repoId}/generate-code`, { params: { method } });
  if (!res.data.success) throw new Error(res.data.error?.message ?? '生成失败');
  return res.data.data;
}

// ─── Repo Config API ─────────────────────────────────────────────────────────

export interface RepoConfigItem {
  id: number;
  repoId: number;
  configKey: string;
  configValue: string | null;
  source: string; // FILE | USER | DEFAULT
  defaultValue: string | null;
}

export async function fetchRepoConfigs(repoId: number, search?: string): Promise<RepoConfigItem[]> {
  const res = await api.get<ApiResponse<RepoConfigItem[]>>(`/repos/${repoId}/config`, { params: { search } });
  return res.data.data;
}

export async function updateRepoConfig(repoId: number, key: string, value: string): Promise<void> {
  await api.put(`/repos/${repoId}/config`, { key, value });
}


export async function uploadRepoConfigFile(repoId: number, file: File, mode: 'merge' | 'replace'): Promise<number> {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('mode', mode);
  const res = await api.post<ApiResponse<number>>(`/repos/${repoId}/config/upload`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  if (!res.data.success) throw new Error(res.data.error?.message ?? '上传失败');
  return res.data.data;
}


// ─── Doc Generation API ──────────────────────────────────────────────────────

export async function generateProductDoc(repoId: number, method: string): Promise<string> {
  const res = await api.get<ApiResponse<string>>(`/repos/${repoId}/doc/product`, { params: { method } });
  if (!res.data.success) throw new Error(res.data.error?.message ?? '生成失败');
  return res.data.data;
}

export async function generateDevDoc(repoId: number, method: string): Promise<string> {
  const res = await api.get<ApiResponse<string>>(`/repos/${repoId}/doc/dev`, { params: { method } });
  if (!res.data.success) throw new Error(res.data.error?.message ?? '生成失败');
  return res.data.data;
}

export async function generateProductDocDiagrams(repoId: number, method: string): Promise<Record<string, string>> {
  const res = await api.get<ApiResponse<Record<string, string>>>(`/repos/${repoId}/doc/diagrams`, { params: { method } });
  if (!res.data.success) throw new Error(res.data.error?.message ?? '生成失败');
  return res.data.data;
}


// ─── Git Config API ──────────────────────────────────────────────────────────

export interface GitConfig {
  token?: string;
  repoType?: string;
  tokenConfigured?: string;
}

export async function fetchGitConfig(): Promise<GitConfig> {
  const res = await api.get<ApiResponse<GitConfig>>('/config/git');
  return res.data.data;
}

export async function saveGitConfig(config: GitConfig): Promise<void> {
  const res = await api.put<ApiResponse<string>>('/config/git', config);
  if (!res.data.success) throw new Error(res.data.error?.message ?? '保存失败');
}

// ─── MCP API ─────────────────────────────────────────────────────────────────

export interface McpTool {
  name: string;
  description: string;
}

export interface McpStatus {
  enabled: boolean;
  sseEndpoint: string;
  protocolVersion: string;
  tools: McpTool[];
}

export async function fetchMcpStatus(): Promise<McpStatus> {
  const res = await api.get<ApiResponse<McpStatus>>('/mcp/status');
  return res.data.data;
}

export interface EmbeddingStatus {
  repoId: number;
  total: number;
  done: number;
  failed: number;
  pending: number;
  status: string;  // DONE | IN_PROGRESS | PARTIAL | PENDING
  qdrantAvailable: boolean;
}

export async function fetchEmbeddingStatus(repoId: number): Promise<EmbeddingStatus> {
  const res = await api.get<ApiResponse<EmbeddingStatus>>('/embedding/status', { params: { repoId } });
  return res.data.data;
}

export async function rebuildEmbedding(repoId: number): Promise<void> {
  const res = await api.post<ApiResponse<unknown>>(`/embedding/rebuild?repoId=${repoId}`);
  if (!res.data.success) throw new Error(res.data.error?.message ?? '触发失败');
}

/**
 * SSE 方式触发向量重建，实时接收日志。
 */
export function rebuildEmbeddingSSE(
  repoId: number,
  callbacks: {
    onLog: (line: string) => void;
    onDone: () => void;
    onError: (msg: string) => void;
  }
): AbortController {
  return _embeddingSSE(`http://localhost:8080/api/embedding/rebuild?repoId=${repoId}`, callbacks);
}

/**
 * SSE 方式继续未完成的向量建设。
 */
export function continueEmbeddingSSE(
  repoId: number,
  callbacks: {
    onLog: (line: string) => void;
    onDone: () => void;
    onError: (msg: string) => void;
  }
): AbortController {
  return _embeddingSSE(`http://localhost:8080/api/embedding/continue?repoId=${repoId}`, callbacks);
}

function _embeddingSSE(
  url: string,
  callbacks: {
    onLog: (line: string) => void;
    onDone: () => void;
    onError: (msg: string) => void;
  }
): AbortController {
  const controller = new AbortController();
  fetch(url, {
    method: 'POST',
    headers: { 'Accept': 'text/event-stream' },
    signal: controller.signal,
  }).then(async (response) => {
    if (!response.ok || !response.body) {
      callbacks.onError('请求失败: ' + response.status);
      return;
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });

      const lines = buffer.split('\n');
      buffer = lines.pop() || '';

      let eventName = '';
      for (const line of lines) {
        if (line.startsWith('event:')) {
          eventName = line.slice(6).trim();
        } else if (line.startsWith('data:')) {
          const data = line.slice(5).trim();
          if (eventName === 'log') {
            callbacks.onLog(data);
          } else if (eventName === 'done') {
            callbacks.onDone();
          } else if (eventName === 'error') {
            callbacks.onError(data);
          }
          eventName = '';
        }
      }
    }
    callbacks.onDone();
  }).catch((err) => {
    if (err.name !== 'AbortError') {
      callbacks.onError(err.message || '连接中断');
    }
  });
  return controller;
}

// ─── 外部 MCP Server API ──────────────────────────────────────────────────────

export interface McpServerItem {
  id: number;
  name: string;
  url: string;
  transport: string;    // SSE | STDIO
  enabled: boolean;
  description: string | null;
  lastTestStatus: string;  // OK | FAIL | UNTESTED
  lastTestMessage: string | null;
  createdAt: string;
}

export async function fetchMcpServers(): Promise<McpServerItem[]> {
  const res = await api.get<ApiResponse<McpServerItem[]>>('/mcp-servers');
  return res.data.data ?? [];
}

export async function createMcpServer(item: Omit<McpServerItem, 'id' | 'lastTestStatus' | 'lastTestMessage' | 'createdAt'>): Promise<McpServerItem> {
  const res = await api.post<ApiResponse<McpServerItem>>('/mcp-servers', item);
  if (!res.data.success) throw new Error(res.data.error?.message ?? '创建失败');
  return res.data.data;
}

export async function updateMcpServer(id: number, item: Partial<McpServerItem>): Promise<McpServerItem> {
  const res = await api.put<ApiResponse<McpServerItem>>(`/mcp-servers/${id}`, item);
  if (!res.data.success) throw new Error(res.data.error?.message ?? '更新失败');
  return res.data.data;
}

export async function deleteMcpServer(id: number): Promise<void> {
  const res = await api.delete<ApiResponse<string>>(`/mcp-servers/${id}`);
  if (!res.data.success) throw new Error(res.data.error?.message ?? '删除失败');
}

export async function testMcpServer(id: number): Promise<{ status: string; message: string }> {
  const res = await api.post<ApiResponse<{ status: string; message: string }>>(`/mcp-servers/${id}/test`);
  if (!res.data.success) throw new Error(res.data.error?.message ?? '测试失败');
  return res.data.data;
}

// ─── QA API ──────────────────────────────────────────────────────────────────

export interface EndpointSearchResult {
  id: number;
  endpointType: string;
  httpMethod: string | null;
  urlPath: string;
  fullMethod: string;
  className: string;
  methodName: string;
  comment: string;
  score: number;
}

export interface QAResponseData {
  answer: string;
  references: string[];
  cached: boolean;
}

export interface MatchedEndpoint {
  fullMethod: string;
  className: string;
  methodName: string;
  endpointType: string;
  httpMethod: string | null;
  urlPath: string | null;
  repoName: string;
  score: number;
}

export interface IntentResult {
  intentType: string;      // DEBUG | INTEGRATION | DATA_FLOW | UNDERSTAND | CONFIG
  intentLabel: string;     // 排错 | 对接指南 | 数据流向 | 功能理解 | 配置查询
  summary: string;         // 一句话描述
  entity: string;          // 核心业务实体
  focusOn: string[];       // 搜索重点
  keywords: string[];      // 搜索关键词
  confidence: number;      // 0-100
}

export interface IntentConfirmation {
  intentType: string;
  intentLabel: string;
  clarification?: string;  // 用户补充说明
}

export interface SmartQAResponseData {
  answer: string;
  references: string[];
  cached: boolean;
  keywords: string[];
  matchedEndpoints: MatchedEndpoint[];
  needsConfirmation: boolean;
  intentResult: IntentResult | null;
  needsIntentConfirmation: boolean;
}

/** 工具调用步骤（SSE thinking 事件） */
export interface ToolCallStep {
  content: string;   // 展示文本，如 "🔍 正在分析 QAController.smartAsk()..."
  round: number;     // 第几轮
  toolName: string;  // getMethodSource | getCallees | getCallers | getBoundaries
}

/** SSE 回调集合 */
export interface QASseCallbacks {
  onThinking?: (step: ToolCallStep) => void;
  onMatched?: (data: { keywords: string[]; matchedEndpoints: MatchedEndpoint[] }) => void;
  onConfirmation?: (data: SmartQAResponseData) => void;
  onAnswer?: (content: string) => void;
  onDone?: (data: { references: string[]; cached?: boolean; matchedEndpoints?: MatchedEndpoint[] }) => void;
  onError?: (message: string) => void;
}

export async function fetchQAStatus(): Promise<{ configured: boolean }> {
  const res = await api.get<ApiResponse<{ configured: boolean }>>('/qa/status');
  return res.data.data;
}

export async function searchQAEndpoints(repoId: number, keyword: string): Promise<EndpointSearchResult[]> {
  const res = await api.get<ApiResponse<EndpointSearchResult[]>>('/qa/search', { params: { repoId, keyword } });
  return res.data.data;
}

export async function fetchPresetAnswer(repoId: number, method: string, type: string): Promise<string> {
  const res = await api.get<ApiResponse<string>>('/qa/preset', { params: { repoId, method, type } });
  return res.data.data;
}

/** 已选接口的 SSE 问答 */
export function askSSE(
  repoId: number,
  methods: string[],
  question: string,
  history: { role: string; content: string }[],
  callbacks: QASseCallbacks
): () => void {
  const controller = new AbortController();

  fetch('/api/qa/ask', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ repoId, methods, question, history }),
    signal: controller.signal,
  }).then(response => {
    const reader = response.body!.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    const processStream = async () => {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() ?? '';
        for (const line of lines) {
          if (line.startsWith('event:')) continue;
          if (line.startsWith('data:')) {
            const raw = line.slice(5).trim();
            try {
              const parsed = JSON.parse(raw);
              // 找到事件类型（前一行 event:xxx）
              const eventLine = lines[lines.indexOf(line) - 1] ?? '';
              const eventType = eventLine.startsWith('event:') ? eventLine.slice(6).trim() : '';
              dispatchSseEvent(eventType || 'data', parsed, callbacks);
            } catch { /* ignore malformed */ }
          }
        }
      }
    };
    processStream().catch(e => {
      if (e.name !== 'AbortError') callbacks.onError?.('连接中断: ' + e.message);
    });
  }).catch(e => {
    if (e.name !== 'AbortError') callbacks.onError?.('请求失败: ' + e.message);
  });

  return () => controller.abort();
}

/** 智能问答 SSE */
export function smartAskSSE(
  repoIds: number[],
  question: string,
  callbacks: QASseCallbacks,
  confirmedMethods?: string[],
  confirmedIntent?: IntentConfirmation
): () => void {
  const controller = new AbortController();

  fetch('/api/qa/smart-ask', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({
      repoIds, question,
      confirmedMethods: confirmedMethods ?? [],
      confirmedIntent: confirmedIntent ?? null,
    }),
    signal: controller.signal,
  }).then(response => {
    const reader = response.body!.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let lastEventType = '';

    const processStream = async () => {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() ?? '';

        for (const line of lines) {
          if (line.startsWith('event:')) {
            lastEventType = line.slice(6).trim();
          } else if (line.startsWith('data:')) {
            const raw = line.slice(5).trim();
            try {
              const parsed = JSON.parse(raw);
              dispatchSseEvent(lastEventType || 'data', parsed, callbacks);
              lastEventType = '';
            } catch { /* ignore */ }
          }
        }
      }
    };
    processStream().catch(e => {
      if (e.name !== 'AbortError') callbacks.onError?.('连接中断: ' + e.message);
    });
  }).catch(e => {
    if (e.name !== 'AbortError') callbacks.onError?.('请求失败: ' + e.message);
  });

  return () => controller.abort();
}

function dispatchSseEvent(eventType: string, data: unknown, callbacks: QASseCallbacks) {
  const d = data as Record<string, unknown>;
  switch (eventType) {
    case 'thinking':
      callbacks.onThinking?.(d as unknown as ToolCallStep);
      break;
    case 'matched':
      callbacks.onMatched?.(d as { keywords: string[]; matchedEndpoints: MatchedEndpoint[] });
      break;
    case 'confirmation':
      callbacks.onConfirmation?.(d as unknown as SmartQAResponseData);
      break;
    case 'answer':
      callbacks.onAnswer?.(String(d.content ?? ''));
      break;
    case 'done':
      callbacks.onDone?.(d as { references: string[]; cached?: boolean; matchedEndpoints?: MatchedEndpoint[] });
      break;
    case 'error':
      callbacks.onError?.(String(d.content ?? '未知错误'));
      break;
  }
}

// ── 兼容旧接口（保留，供降级使用）──────────────────────────────────────────────

export async function askQuestion(repoId: number, methods: string[], question: string, history: { role: string; content: string }[]): Promise<QAResponseData> {
  const res = await api.post<ApiResponse<QAResponseData>>('/qa/ask-sync', { repoId, methods, question, history });
  if (!res.data.success) throw new Error(res.data.error?.message ?? '问答失败');
  return res.data.data;
}

export async function smartAsk(repoIds: number[], question: string, confirmedMethods?: string[], confirmedIntent?: IntentConfirmation): Promise<SmartQAResponseData> {
  const res = await api.post<ApiResponse<SmartQAResponseData>>('/qa/smart-ask-sync', {
    repoIds, question,
    confirmedMethods: confirmedMethods ?? [],
    confirmedIntent: confirmedIntent ?? null,
  });
  if (!res.data.success) throw new Error(res.data.error?.message ?? '问答失败');
  return res.data.data;
}
