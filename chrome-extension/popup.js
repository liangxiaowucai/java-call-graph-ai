// Popup Script
let requests = [];
let isRecording = true; // 默认录制中
let errorCount = 0;

// ============ Toast 提示功能 ============

/**
 * 显示 Toast 提示
 * @param {string} message - 提示消息
 * @param {string} type - 类型：success, error, warning, info
 * @param {number} duration - 持续时间（毫秒），默认 2000ms
 */
function showToast(message, type = 'success', duration = 2000) {
  const container = document.getElementById('toastContainer');
  if (!container) return;
  
  const toast = document.createElement('div');
  toast.className = `toast ${type}`;
  toast.textContent = message;
  
  container.appendChild(toast);
  
  // 自动移除
  setTimeout(() => {
    toast.classList.add('removing');
    setTimeout(() => {
      if (toast.parentNode) {
        toast.parentNode.removeChild(toast);
      }
    }, 300); // 等待动画完成
  }, duration);
}

// 默认配置
const DEFAULT_BACKEND_URL = 'http://localhost:8080';
const DEFAULT_URL_PREFIXES = '/api/'; // 默认只捕获 /api/ 开头的请求

// DOM元素
const requestList = document.getElementById('requestList');
const requestCountEl = document.getElementById('requestCount');
const errorCountEl = document.getElementById('errorCount');
const recordingStatusEl = document.getElementById('recordingStatus');
const recordingIconEl = document.getElementById('recordingIcon');
const userActionInput = document.getElementById('userActionInput');
const exportBtn = document.getElementById('exportBtn');
const analyzeBtn = document.getElementById('analyzeBtn');
const clearBtn = document.getElementById('clearBtn');
const toggleRecordingBtn = document.getElementById('toggleRecording');
const analysisResult = document.getElementById('analysisResult');
const closeAnalysisBtn = document.getElementById('closeAnalysis');
const loadingOverlay = document.getElementById('loadingOverlay');
const settingsBtn = document.getElementById('settingsBtn');
const settingsPanel = document.getElementById('settingsPanel');
const closeSettingsBtn = document.getElementById('closeSettings');
const saveSettingsBtn = document.getElementById('saveSettings');
const cancelSettingsBtn = document.getElementById('cancelSettings');
const urlPrefixInput = document.getElementById('urlPrefixInput');
const backendUrlInput = document.getElementById('backendUrlInput');
const prependUrlInput = document.getElementById('prependUrlInput');
const prependEnabledCheckbox = document.getElementById('prependEnabledCheckbox');
const includeHeadersCheckbox = document.getElementById('includeHeadersCheckbox');
const minDurationInput = document.getElementById('minDurationInput');
const requestDetailPanel = document.getElementById('requestDetailPanel');
const closeDetailBtn = document.getElementById('closeDetail');
const requestDetailContent = document.getElementById('requestDetailContent');
const exportSelectionPanel = document.getElementById('exportSelectionPanel');
const closeExportSelection = document.getElementById('closeExportSelection');
const exportRequestList = document.getElementById('exportRequestList');
const selectAllBtn = document.getElementById('selectAllBtn');
const deselectAllBtn = document.getElementById('deselectAllBtn');
const invertSelectionBtn = document.getElementById('invertSelectionBtn');
const confirmExportBtn = document.getElementById('confirmExportBtn');
const cancelExportBtn = document.getElementById('cancelExportBtn');

// 初始化
chrome.tabs.query({ active: true, currentWindow: true }, async (tabs) => {
  if (tabs[0]) {
    // 从 storage 加载录制状态（如果之前保存过的话）
    const result = await chrome.storage.local.get(['isRecording']);
    if (result.isRecording !== undefined) {
      isRecording = result.isRecording;
    }
    updateRecordingStatus();
    
    loadChain();
  }
});

// 检查关键 DOM 元素是否加载成功
console.log('[DEBUG] closeDetailBtn:', closeDetailBtn);
console.log('[DEBUG] requestDetailPanel:', requestDetailPanel);

// 加载请求链
function loadChain() {
  console.log('[POPUP] 开始加载请求链...');
  chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
    if (tabs[0]) {
      // 检查是否是特殊页面（chrome://, chrome-extension://, about: 等）
      const url = tabs[0].url || '';
      if (url.startsWith('chrome://') || url.startsWith('chrome-extension://') || 
          url.startsWith('about:') || url.startsWith('edge://') || 
          url === '' || !url) {
        console.log('[POPUP] 当前页面不支持注入脚本:', url);
        showEmptyState('当前页面不支持请求捕获');
        return;
      }

      console.log('[POPUP] 向 content script 发送 GET_CHAIN 消息...');
      chrome.tabs.sendMessage(tabs[0].id, { type: 'GET_CHAIN' }, (response) => {
        if (chrome.runtime.lastError) {
          // 静默处理错误，不显示警告
          console.log('[POPUP] Content script 未就绪:', chrome.runtime.lastError.message);
          showEmptyState('请刷新页面以启用请求捕获');
          return;
        }

        console.log('[POPUP] 收到响应:', response);
        if (response && response.requestChain) {
          requests = response.requestChain;
          console.log('[POPUP] 已加载 ' + requests.length + ' 个请求');
          renderRequests();
          updateStats();
        } else {
          console.log('[POPUP] 响应中没有请求链数据');
          showEmptyState();
        }
      });
    } else {
      console.log('[POPUP] 未找到活动标签页');
      showEmptyState('未找到活动标签页');
    }
  });
}

// 监听来自content script的消息
chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  console.log('[POPUP] 收到消息:', message.type);
  if (message.type === 'REQUEST_CAPTURED') {
    console.log('[POPUP] 捕获到新请求:', message.data.url);
    requests.push(message.data);
    renderRequests();
    updateStats();
  }
});

// 渲染请求列表
async function renderRequests() {
  if (requests.length === 0) {
    showEmptyState();
    exportBtn.disabled = true;
    analyzeBtn.disabled = true;
    return;
  }

  exportBtn.disabled = false;
  analyzeBtn.disabled = false;

  // 获取过滤配置
  const config = await getConfig();
  const minDurationMs = (config.minDuration || 0) * 1000; // 转换为毫秒

  // 过滤请求
  const filteredRequests = requests.filter(req => {
    // 如果设置了最小时间，只显示超过此时间的请求
    if (minDurationMs > 0 && req.duration < minDurationMs) {
      return false;
    }
    return true;
  });

  // 如果过滤后没有请求
  if (filteredRequests.length === 0) {
    showEmptyState(`没有响应时间超过 ${config.minDuration} 秒的请求`);
    return;
  }

  const html = filteredRequests.map((req, displayIndex) => {
    const statusClass = req.success ? 'success' : 'error';
    const statusCode = req.responseStatus;
    const statusCodeClass = req.success ? 'success' : 'error';
    const method = req.method;
    const url = req.url;
    const duration = req.duration;
    const originalIndex = requests.indexOf(req); // 使用原始索引

    // 高亮慢请求
    const isSlowRequest = minDurationMs > 0 && duration >= minDurationMs;
    const slowIndicator = isSlowRequest ? ' 🐢' : '';

    return `
      <div class="request-item ${statusClass}" data-index="${originalIndex}">
        <div class="request-seq">${req.seq}</div>
        <div class="request-info">
          <div class="request-header">
            <span class="request-method method-${method}">${method}</span>
            <span class="request-url" title="${url}">${url}</span>
          </div>
          <div class="request-meta">
            <div class="request-status">
              <span class="status-code ${statusCodeClass}">${statusCode}</span>
              ${!req.success ? '⚠️' : ''}
            </div>
            <span style="${isSlowRequest ? 'color: #fa8c16; font-weight: 600;' : ''}">${duration}ms${slowIndicator}</span>
          </div>
        </div>
      </div>
    `;
  }).join('');

  requestList.innerHTML = html;
}

// 显示空状态
function showEmptyState(message) {
  requestList.innerHTML = `
    <div class="empty-state">
      <p>📡 ${message || '等待捕获请求...'}</p>
      <p class="tip">在网页中进行操作，插件会自动捕获API请求</p>
    </div>
  `;
}

// 更新统计信息
async function updateStats() {
  const config = await getConfig();
  const minDurationMs = (config.minDuration || 0) * 1000;
  
  // 过滤请求
  const filteredRequests = requests.filter(req => {
    if (minDurationMs > 0 && req.duration < minDurationMs) {
      return false;
    }
    return true;
  });
  
  // 显示过滤后的数量
  if (minDurationMs > 0) {
    requestCountEl.textContent = `${filteredRequests.length}/${requests.length}`;
    requestCountEl.title = `显示 ${filteredRequests.length} 个（总共 ${requests.length} 个）`;
  } else {
    requestCountEl.textContent = requests.length;
    requestCountEl.title = '';
  }
  
  errorCount = filteredRequests.filter(r => !r.success).length;
  errorCountEl.textContent = errorCount;
}

// 显示请求详情
function showRequestDetail(request, requestIndex) {
  let html = '<div style="padding: 16px;">';
  
  // 添加导出按钮
  html += `
    <div style="margin-bottom: 16px; display: flex; gap: 8px;">
      <button id="exportSingleBtn" style="flex: 1; padding: 8px 16px; background: #1890ff; color: white; border: none; border-radius: 4px; cursor: pointer; font-size: 14px;">
        📥 导出此请求
      </button>
      <button id="copyJsonBtn" style="flex: 1; padding: 8px 16px; background: #52c41a; color: white; border: none; border-radius: 4px; cursor: pointer; font-size: 14px;">
        📋 复制 JSON
      </button>
    </div>
  `;
  
  // 基本信息
  html += `
    <div style="margin-bottom: 20px;">
      <h4 style="margin-bottom: 10px; color: #1890ff;">📋 基本信息</h4>
      <div style="background: #f5f5f5; padding: 12px; border-radius: 6px; font-size: 13px;">
        <div style="margin-bottom: 8px;"><strong>请求方法:</strong> <span class="request-method method-${request.method}">${request.method}</span></div>
        <div style="margin-bottom: 8px;"><strong>请求URL:</strong> <code>${escapeHtml(request.url)}</code></div>
        <div style="margin-bottom: 8px;"><strong>完整URL:</strong> <code class="detail-value url-value" title="点击可选中复制">${escapeHtml(request.fullUrl)}</code></div>
        <div style="margin-bottom: 8px;"><strong>响应状态:</strong> <span class="status-code ${request.success ? 'success' : 'error'}">${request.responseStatus}</span></div>
        <div style="margin-bottom: 8px;"><strong>耗时:</strong> ${request.duration}ms</div>
        <div style="margin-bottom: 8px;"><strong>时间戳:</strong> ${new Date(request.timestamp).toLocaleString()}</div>
        <div><strong>序列号:</strong> ${request.seq}</div>
      </div>
    </div>
  `;
  
  // 请求头
  if (request.requestHeaders && Object.keys(request.requestHeaders).length > 0) {
    html += `
      <div style="margin-bottom: 20px;">
        <h4 style="margin-bottom: 10px; color: #13c2c2;">📤 请求头 (Request Headers)</h4>
        <div class="json-container"><pre style="margin:0;">${formatAndHighlight(request.requestHeaders)}</pre></div>
      </div>
    `;
  }
  
  // 请求体
  html += `
    <div style="margin-bottom: 20px;">
      <h4 style="margin-bottom: 10px; color: #52c41a;">📤 请求体 (Request Body)</h4>
  `;
  
  if (request.requestBody) {
    html += `<div class="json-container"><pre style="margin:0;">${formatAndHighlight(request.requestBody)}</pre></div>`;
  } else {
    html += `<div style="color: #999; font-style: italic; padding: 12px;">无请求体</div>`;
  }
  
  html += `</div>`;
  
  // 响应头
  if (request.responseHeaders && Object.keys(request.responseHeaders).length > 0) {
    html += `
      <div style="margin-bottom: 20px;">
        <h4 style="margin-bottom: 10px; color: #722ed1;">📥 响应头 (Response Headers)</h4>
        <div class="json-container"><pre style="margin:0;">${formatAndHighlight(request.responseHeaders)}</pre></div>
      </div>
    `;
  }
  
  // 响应体
  html += `
    <div style="margin-bottom: 20px;">
      <h4 style="margin-bottom: 10px; color: #fa8c16;">📥 响应体 (Response Body)</h4>
  `;
  
  if (request.responseBody) {
    html += `<div class="json-container"><pre style="margin:0;">${formatAndHighlight(request.responseBody)}</pre></div>`;
  } else {
    html += `<div style="color: #999; font-style: italic; padding: 12px;">无响应体</div>`;
  }
  
  html += `</div>`;
  
  html += '</div>';
  
  requestDetailContent.innerHTML = html;
  requestDetailPanel.style.display = 'block';
  
  // 为导出按钮添加事件监听
  const exportSingleBtn = document.getElementById('exportSingleBtn');
  const copyJsonBtn = document.getElementById('copyJsonBtn');
  
  if (exportSingleBtn) {
    exportSingleBtn.addEventListener('click', async () => {
      chrome.tabs.query({ active: true, currentWindow: true }, async (tabs) => {
        if (tabs[0]) {
          const userAction = userActionInput.value.trim() || '未描述操作';
          const config = await getConfig();
          
          // 只导出当前请求
          let requestData = { ...request };
          
          // 如果不包含 headers，则移除 headers 字段
          if (!config.includeHeaders) {
            const { requestHeaders, responseHeaders, ...rest } = requestData;
            requestData = rest;
          }
          
          const exportData = {
            sessionId: Date.now(),
            userAction: userAction,
            timestamp: Date.now(),
            url: request.fullUrl,
            requestChain: [requestData],
            dataFlow: []
          };

          const json = JSON.stringify(exportData, null, 2);
          
          // 下载
          const blob = new Blob([json], { type: 'application/json' });
          const url = URL.createObjectURL(blob);
          const a = document.createElement('a');
          a.href = url;
          a.download = `request-${request.seq}-${Date.now()}.json`;
          a.click();
          
          showToast('已导出单个请求');
        }
      });
    });
  }
  
  if (copyJsonBtn) {
    copyJsonBtn.addEventListener('click', async () => {
      const config = await getConfig();
      
      // 复制请求的 JSON
      let requestData = { ...request };
      
      // 如果不包含 headers，则移除 headers 字段
      if (!config.includeHeaders) {
        const { requestHeaders, responseHeaders, ...rest } = requestData;
        requestData = rest;
      }
      
      const json = JSON.stringify(requestData, null, 2);
      
      navigator.clipboard.writeText(json).then(() => {
        showToast('JSON 已复制到剪贴板');
      });
    });
  }
}

// 格式化 JSON，智能截断超长字段
function formatJsonWithTruncation(obj, maxStringLength = 200) {
  const longTextFields = ['profile', 'overview', 'description', 'content', 'detail', 'summary', 'text'];
  
  return JSON.stringify(obj, (key, value) => {
    if (typeof value === 'string' && value.length > maxStringLength) {
      const truncateLength = longTextFields.includes(key.toLowerCase()) ? 100 : maxStringLength;
      if (value.length > truncateLength) {
        return value.substring(0, truncateLength) + `... [已截断，总长度: ${value.length} 字符]`;
      }
    }
    return value;
  }, 2);
}

// JSON 语法高亮
function highlightJson(jsonStr) {
  return jsonStr
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"([^"]+)"(\s*:)/g, '<span class="json-key">"$1"</span>$2')
    .replace(/:\s*"([^"]*)"/g, ': <span class="json-string">"$1"</span>')
    .replace(/:\s*(\d+\.?\d*)/g, ': <span class="json-number">$1</span>')
    .replace(/:\s*(true|false)/g, ': <span class="json-boolean">$1</span>')
    .replace(/:\s*(null)/g, ': <span class="json-null">$1</span>')
    .replace(/([{}\[\]])/g, '<span class="json-bracket">$1</span>');
}

// 格式化 JSON 并高亮
function formatAndHighlight(data) {
  try {
    const obj = typeof data === 'string' ? JSON.parse(data) : data;
    const jsonStr = formatJsonWithTruncation(obj);
    return highlightJson(jsonStr);
  } catch (e) {
    return escapeHtml(String(data));
  }
}

// ── 请求列表筛选 ──
const filterInput = document.getElementById('filterInput');
if (filterInput) {
  filterInput.addEventListener('input', () => {
    const keyword = filterInput.value.trim().toLowerCase();
    const items = document.querySelectorAll('.request-item');
    items.forEach(item => {
      if (!keyword) {
        item.classList.remove('filtered-out');
        return;
      }
      const url = (item.querySelector('.request-url')?.textContent || '').toLowerCase();
      const method = (item.querySelector('.request-method')?.textContent || '').toLowerCase();
      const status = (item.querySelector('.status-code')?.textContent || '').toLowerCase();
      if (url.includes(keyword) || method.includes(keyword) || status.includes(keyword)) {
        item.classList.remove('filtered-out');
      } else {
        item.classList.add('filtered-out');
      }
    });
  });
}

// 关闭请求详情面板
if (closeDetailBtn) {
  closeDetailBtn.addEventListener('click', (e) => {
    console.log('[DEBUG] 关闭按钮被点击', e);
    requestDetailPanel.style.display = 'none';
  });
  console.log('[DEBUG] 关闭按钮事件监听器已绑定');
} else {
  console.error('[ERROR] closeDetailBtn 元素未找到！');
}

// 支持 ESC 键关闭详情面板
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') {
    if (requestDetailPanel.style.display === 'block' || requestDetailPanel.style.display === 'flex') {
      requestDetailPanel.style.display = 'none';
      console.log('[DEBUG] ESC 键关闭详情面板');
    } else if (exportSelectionPanel.style.display === 'flex') {
      exportSelectionPanel.style.display = 'none';
      console.log('[DEBUG] ESC 键关闭导出选择面板');
    } else if (settingsPanel.style.display === 'block') {
      settingsPanel.style.display = 'none';
      console.log('[DEBUG] ESC 键关闭设置面板');
    } else if (analysisResult.style.display === 'block') {
      analysisResult.style.display = 'none';
      console.log('[DEBUG] ESC 键关闭分析结果');
    }
  }
});

// 导出JSON
exportBtn.addEventListener('click', () => {
  // 显示导出选择面板
  showExportSelection();
});

// 智能分析
analyzeBtn.addEventListener('click', async () => {
  chrome.tabs.query({ active: true, currentWindow: true }, async (tabs) => {
    if (!tabs[0]) return;

    chrome.tabs.sendMessage(tabs[0].id, { type: 'GET_CHAIN' }, async (response) => {
      if (!response) return;

      const userAction = userActionInput.value.trim() || '未描述操作';
      
      const requestData = {
        sessionId: response.sessionId,
        userAction: userAction,
        timestamp: response.timestamp,
        url: response.url,
        requestChain: response.requestChain,
        dataFlow: response.dataFlow
      };

      // 显示加载中
      loadingOverlay.style.display = 'flex';

      try {
        // 调用后端API - 使用增强版接口
        const backendUrl = await getBackendUrl();
        const apiResponse = await fetch(`${backendUrl}/api/debug/analyze-with-callgraph`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json'
          },
          body: JSON.stringify(requestData)
        });

        if (!apiResponse.ok) {
          throw new Error(`HTTP ${apiResponse.status}: ${apiResponse.statusText}`);
        }

        const result = await apiResponse.json();
        
        // 显示分析结果（增强版）
        showEnhancedAnalysisResult(result);

      } catch (error) {
        alert('❌ 分析失败: ' + error.message + '\n\n请确保后端服务已启动（默认: http://localhost:8080）');
        console.error('Analysis error:', error);
      } finally {
        loadingOverlay.style.display = 'none';
      }
    });
  });
});

// 获取后端URL（从storage读取，如果没有则使用默认值）
async function getBackendUrl() {
  return new Promise((resolve) => {
    chrome.storage.local.get(['backendUrl'], (result) => {
      resolve(result.backendUrl || DEFAULT_BACKEND_URL);
    });
  });
}

// 显示增强版分析结果（包含时序图 + 调用树）
function showEnhancedAnalysisResult(result) {
  let html = '<div style="padding: 16px;">';
  
  // 概览
  if (result.summary) {
    html += `
      <div style="margin-bottom: 20px;">
        <h4 style="margin-bottom: 10px;">📊 概览</h4>
        <div style="background: #f5f5f5; padding: 12px; border-radius: 6px;">
          ${result.summary}
        </div>
      </div>
    `;
  }
  
  // 请求时序图（新增）
  if (result.requestSequence && result.requestSequence.length > 0) {
    html += `
      <div style="margin-bottom: 20px;">
        <h4 style="margin-bottom: 10px;">⏱️ 请求时序</h4>
        <div style="background: #f9f9f9; padding: 12px; border-radius: 6px;">
    `;
    
    result.requestSequence.forEach((item, index) => {
      const statusColor = item.success ? '#52c41a' : '#ff4d4f';
      const methodColor = item.method === 'GET' ? '#1890ff' : 
                          item.method === 'POST' ? '#52c41a' : 
                          item.method === 'PUT' ? '#faad14' : '#f5222d';
      
      html += `
        <div style="display: flex; align-items: center; margin-bottom: 8px; padding: 8px; background: white; border-radius: 4px; border-left: 3px solid ${statusColor};">
          <span style="font-weight: 600; color: #666; min-width: 30px;">${item.seq}.</span>
          <span style="background: ${methodColor}; color: white; padding: 2px 8px; border-radius: 3px; font-size: 11px; margin-right: 8px;">${item.method}</span>
          <span style="flex: 1; font-size: 13px; color: #333;">${item.url}</span>
          <span style="color: ${statusColor}; font-weight: 600; margin-right: 8px;">${item.responseStatus}</span>
          <span style="color: #999; font-size: 12px;">${item.duration}ms</span>
        </div>
      `;
      
      // 如果有后端方法匹配，显示
      if (result.apiCalls && result.apiCalls[index] && result.apiCalls[index].method) {
        const apiCall = result.apiCalls[index];
        html += `
          <div style="margin-left: 45px; margin-bottom: 12px; padding: 8px; background: #e6f7ff; border-radius: 4px; font-size: 12px;">
            <div style="color: #1890ff; font-weight: 500;">→ ${apiCall.method}</div>
            ${apiCall.recommendedRepo ? `<div style="color: #666; margin-top: 4px;">📦 ${apiCall.recommendedRepo.repoName}</div>` : ''}
            ${apiCall.callTree ? `<div style="color: #52c41a; margin-top: 4px;">✓ 调用树已加载（${apiCall.callTree.totalNodes || 0} 个节点）</div>` : ''}
          </div>
        `;
      }
    });
    
    html += '</div></div>';
  }
  
  // 跨请求调用链（新增）
  if (result.crossRequestChains && result.crossRequestChains.length > 0) {
    html += `
      <div style="margin-bottom: 20px;">
        <h4 style="margin-bottom: 10px;">🔗 跨请求调用链</h4>
        <div style="background: #f9f9f9; padding: 12px; border-radius: 6px;">
    `;
    
    result.crossRequestChains.forEach(chain => {
      const typeColor = chain.relationshipType === 'CROSS_REPO_HTTP' ? '#fa8c16' :
                        chain.relationshipType === 'SAME_REPO_DIRECT' ? '#52c41a' :
                        chain.relationshipType === 'SAME_REPO_INDIRECT' ? '#1890ff' : '#999';
      
      html += `
        <div style="margin-bottom: 12px; padding: 10px; background: white; border-radius: 4px; border-left: 3px solid ${typeColor};">
          <div style="font-size: 12px; color: #666; margin-bottom: 4px;">请求 ${chain.fromSeq} → 请求 ${chain.toSeq}</div>
          <div style="font-weight: 500; color: #333; margin-bottom: 6px;">${chain.description}</div>
          ${chain.crossRepo ? `<div style="color: #fa8c16; font-size: 11px; margin-bottom: 4px;">⚠️ 跨仓库调用: ${chain.fromRepo} → ${chain.toRepo}</div>` : ''}
          ${chain.callPath && chain.callPath.length > 0 ? `
            <div style="font-size: 11px; color: #666; margin-top: 6px; padding: 6px; background: #f5f5f5; border-radius: 3px; font-family: monospace;">
              ${chain.callPath.join(' → ')}
            </div>
          ` : ''}
        </div>
      `;
    });
    
    html += '</div></div>';
  }

  // 原有的数据流问题

  // 数据流问题
  if (result.dataFlowIssues && result.dataFlowIssues.length > 0) {
    html += `
      <div style="margin-bottom: 20px;">
        <h4 style="margin-bottom: 10px;">⚠️ 数据流问题</h4>
    `;
    
    result.dataFlowIssues.forEach(issue => {
      html += `
        <div style="background: #fff7e6; border-left: 3px solid #faad14; padding: 12px; margin-bottom: 10px; border-radius: 4px;">
          <div style="font-weight: 600; margin-bottom: 6px;">${issue.description}</div>
          <div style="font-size: 12px; color: #666;">
            <div>字段: <code>${issue.field}</code></div>
            <div>期望值: <code>${JSON.stringify(issue.expectedValue)}</code></div>
            <div>实际值: <code>${JSON.stringify(issue.actualValue)}</code></div>
          </div>
        </div>
      `;
    });
    
    html += '</div>';
  }

  // 失败分析
  if (result.apiCalls) {
    const failedCalls = result.apiCalls.filter(call => call.status >= 400);
    
    if (failedCalls.length > 0) {
      html += `
        <div style="margin-bottom: 20px;">
          <h4 style="margin-bottom: 10px;">❌ 失败分析</h4>
      `;
      
      failedCalls.forEach(call => {
        html += `
          <div style="background: #fff2f0; border-left: 3px solid #ff4d4f; padding: 12px; margin-bottom: 10px; border-radius: 4px;">
            <div style="font-weight: 600; margin-bottom: 6px;">${call.url}</div>
            <div style="font-size: 12px; color: #666; margin-bottom: 8px;">
              状态码: ${call.status}
            </div>
            ${call.diagnosis ? `
              <div style="background: white; padding: 10px; border-radius: 4px; white-space: pre-wrap; font-size: 12px;">
                ${escapeHtml(call.diagnosis)}
              </div>
            ` : ''}
          </div>
        `;
      });
      
      html += '</div>';
    }
  }

  // 完整报告
  if (result.report) {
    html += `
      <div style="margin-bottom: 20px;">
        <h4 style="margin-bottom: 10px;">📋 完整报告</h4>
        <div style="background: #f5f5f5; padding: 12px; border-radius: 6px; white-space: pre-wrap; font-size: 12px; max-height: 400px; overflow-y: auto;">
          ${escapeHtml(result.report)}
        </div>
      </div>
    `;
  }

  html += '</div>';

  document.getElementById('analysisContent').innerHTML = html;
  analysisResult.style.display = 'block';
}

// 保留原有的显示方法作为兼容（如果后端返回的是旧格式）
function showAnalysisResult(result) {
  // 检查是否是增强版结果
  if (result.requestSequence || result.crossRequestChains) {
    showEnhancedAnalysisResult(result);
    return;
  }
  
  // 原有逻辑保持不变
  let html = '<div style="padding: 16px;">';
  
  // 概览
  if (result.summary) {
    html += `
      <div style="margin-bottom: 20px;">
        <h4 style="margin-bottom: 10px;">📊 概览</h4>
        <div style="background: #f5f5f5; padding: 12px; border-radius: 6px;">
          ${result.summary}
        </div>
      </div>
    `;
  }
  
  // 数据流问题
  if (result.dataFlowIssues && result.dataFlowIssues.length > 0) {
    html += `
      <div style="margin-bottom: 20px;">
        <h4 style="margin-bottom: 10px;">⚠️ 数据流问题</h4>
    `;
    
    result.dataFlowIssues.forEach(issue => {
      html += `
        <div style="background: #fff7e6; border-left: 3px solid #faad14; padding: 12px; margin-bottom: 10px; border-radius: 4px;">
          <div style="font-weight: 600; margin-bottom: 6px;">${issue.description}</div>
          <div style="font-size: 12px; color: #666;">
            <div>字段: <code>${issue.field}</code></div>
            <div>期望值: <code>${JSON.stringify(issue.expectedValue)}</code></div>
            <div>实际值: <code>${JSON.stringify(issue.actualValue)}</code></div>
          </div>
        </div>
      `;
    });
    
    html += '</div>';
  }

  // 失败分析
  if (result.apiCalls) {
    const failedCalls = result.apiCalls.filter(call => call.status >= 400);
    
    if (failedCalls.length > 0) {
      html += `
        <div style="margin-bottom: 20px;">
          <h4 style="margin-bottom: 10px;">❌ 失败分析</h4>
      `;
      
      failedCalls.forEach(call => {
        html += `
          <div style="background: #fff2f0; border-left: 3px solid #ff4d4f; padding: 12px; margin-bottom: 10px; border-radius: 4px;">
            <div style="font-weight: 600; margin-bottom: 6px;">${call.url}</div>
            <div style="font-size: 12px; color: #666; margin-bottom: 8px;">
              状态码: ${call.status}
            </div>
            ${call.diagnosis ? `
              <div style="background: white; padding: 10px; border-radius: 4px; white-space: pre-wrap; font-size: 12px;">
                ${escapeHtml(call.diagnosis)}
              </div>
            ` : ''}
          </div>
        `;
      });
      
      html += '</div>';
    }
  }

  // 完整报告
  if (result.report) {
    html += `
      <div style="margin-bottom: 20px;">
        <h4 style="margin-bottom: 10px;">📋 完整报告</h4>
        <div style="background: #f5f5f5; padding: 12px; border-radius: 6px; white-space: pre-wrap; font-size: 12px; max-height: 400px; overflow-y: auto;">
          ${escapeHtml(result.report)}
        </div>
      </div>
    `;
  }
  
  html += '</div>';
  
  document.getElementById('analysisContent').innerHTML = html;
  analysisResult.style.display = 'block';
}

// 转义HTML
function escapeHtml(text) {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

// 关闭分析结果
closeAnalysisBtn.addEventListener('click', () => {
  analysisResult.style.display = 'none';
});

// 清空
clearBtn.addEventListener('click', () => {
  if (confirm('确定要清空所有请求记录吗？')) {
    requests = [];
    errorCount = 0;
    
    chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
      if (tabs[0]) {
        chrome.tabs.sendMessage(tabs[0].id, { type: 'CLEAR_CHAIN' }, (response) => {
          // 忽略错误，即使通信失败也清空本地数据
          if (chrome.runtime.lastError) {
            console.log('[INFO] 无法通知页面清空数据，仅清空本地数据');
          }
        });
      }
    });
    
    renderRequests();
    updateStats();
    userActionInput.value = '';
  }
});

// 切换录制状态
toggleRecordingBtn.addEventListener('click', async () => {
  isRecording = !isRecording;
  
  // 保存录制状态到 storage
  await chrome.storage.local.set({ isRecording: isRecording });
  
  chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
    if (tabs[0]) {
      chrome.tabs.sendMessage(tabs[0].id, { 
        type: 'TOGGLE_RECORDING', 
        enabled: isRecording 
      }, (response) => {
        // 忽略错误
        if (chrome.runtime.lastError) {
          console.log('[INFO] 无法通知页面切换录制状态');
        }
      });
    }
  });
  
  updateRecordingStatus();
});

// 更新录制状态显示
function updateRecordingStatus() {
  if (isRecording) {
    recordingStatusEl.textContent = '🟢 录制中';
    recordingStatusEl.className = 'badge badge-success';
    recordingIconEl.textContent = '⏸️';
  } else {
    recordingStatusEl.textContent = '⏸️ 已暂停';
    recordingStatusEl.className = 'badge badge-warning';
    recordingIconEl.textContent = '▶️';
  }
}


// ============ 设置功能 ============

// 打开设置面板
settingsBtn.addEventListener('click', async () => {
  // 加载当前配置
  const config = await getConfig();
  urlPrefixInput.value = config.urlPrefixes || DEFAULT_URL_PREFIXES;
  backendUrlInput.value = config.backendUrl || DEFAULT_BACKEND_URL;
  prependUrlInput.value = config.prependUrl || '';
  prependEnabledCheckbox.checked = config.prependEnabled || false;
  includeHeadersCheckbox.checked = config.includeHeaders !== false; // 默认为 true
  minDurationInput.value = config.minDuration || '';
  
  settingsPanel.style.display = 'block';
});

// 关闭设置面板
closeSettingsBtn.addEventListener('click', () => {
  settingsPanel.style.display = 'none';
});

cancelSettingsBtn.addEventListener('click', () => {
  settingsPanel.style.display = 'none';
});

// 保存设置
saveSettingsBtn.addEventListener('click', async () => {
  const urlPrefixes = urlPrefixInput.value.trim();
  const backendUrl = backendUrlInput.value.trim() || DEFAULT_BACKEND_URL;
  const prependUrl = prependUrlInput.value.trim();
  const prependEnabled = prependEnabledCheckbox.checked;
  const includeHeaders = includeHeadersCheckbox.checked;
  const minDuration = parseFloat(minDurationInput.value.trim()) || 0;
  
  // 保存到 storage
  await chrome.storage.local.set({
    urlPrefixes: urlPrefixes,
    backendUrl: backendUrl,
    prependUrl: prependUrl,
    prependEnabled: prependEnabled,
    includeHeaders: includeHeaders,
    minDuration: minDuration
  });
  
  // 通知 content script 更新配置
  chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
    if (tabs[0]) {
      chrome.tabs.sendMessage(tabs[0].id, {
        type: 'UPDATE_CONFIG',
        urlPrefixes: urlPrefixes
      }, (response) => {
        // 忽略错误
        if (chrome.runtime.lastError) {
          console.log('[INFO] 无法通知页面更新配置');
        }
      });
    }
  });
  
  // 重新渲染请求列表以应用过滤
  renderRequests();
  
  settingsPanel.style.display = 'none';
  showToast('✅ 设置已保存！', 'success');
});

// 获取配置
async function getConfig() {
  return new Promise((resolve) => {
    chrome.storage.local.get(['urlPrefixes', 'backendUrl', 'prependUrl', 'prependEnabled', 'includeHeaders', 'minDuration'], (result) => {
      resolve({
        urlPrefixes: result.urlPrefixes || DEFAULT_URL_PREFIXES,
        backendUrl: result.backendUrl || DEFAULT_BACKEND_URL,
        prependUrl: result.prependUrl || '',
        prependEnabled: result.prependEnabled || false,
        includeHeaders: result.includeHeaders !== false, // 默认为 true
        minDuration: result.minDuration || 0 // 默认为 0（不过滤）
      });
    });
  });
}

// 获取后端URL（修改原有函数以使用新的配置）
async function getBackendUrl() {
  const config = await getConfig();
  return config.backendUrl;
}


// 使用事件委托处理请求项点击
requestList.addEventListener('click', (event) => {
  const requestItem = event.target.closest('.request-item');
  if (requestItem) {
    const index = parseInt(requestItem.dataset.index);
    if (!isNaN(index) && requests[index]) {
      showRequestDetail(requests[index], index);
    }
  }
});

// ============ 导出选择功能 ============

let selectedRequestIndices = new Set();

// 显示导出选择面板
function showExportSelection() {
  if (requests.length === 0) {
    alert('没有可导出的请求！');
    return;
  }

  // 默认全不选
  selectedRequestIndices = new Set();
  
  renderExportSelection();
  exportSelectionPanel.style.display = 'flex';
}

// 渲染导出选择列表
function renderExportSelection() {
  const html = requests.map((req, index) => {
    const checked = selectedRequestIndices.has(index) ? 'checked' : '';
    const selectedClass = selectedRequestIndices.has(index) ? 'selected' : '';
    const statusClass = req.success ? 'success' : 'error';
    
    return `
      <div class="export-request-item ${selectedClass}" data-index="${index}">
        <input type="checkbox" ${checked} data-index="${index}" />
        <div class="request-seq">${index + 1}</div>
        <div class="export-request-info">
          <div class="request-header">
            <span class="request-method method-${req.method}">${req.method}</span>
            <span class="request-url" title="${req.url}">${req.url}</span>
          </div>
          <div class="request-meta">
            <span class="status-code ${statusClass}">${req.responseStatus}</span>
            <span>${req.duration}ms</span>
          </div>
        </div>
      </div>
    `;
  }).join('');
  
  exportRequestList.innerHTML = html;
  
  // 为复选框添加事件监听
  exportRequestList.querySelectorAll('input[type="checkbox"]').forEach(checkbox => {
    checkbox.addEventListener('change', (e) => {
      const index = parseInt(e.target.dataset.index);
      if (e.target.checked) {
        selectedRequestIndices.add(index);
      } else {
        selectedRequestIndices.delete(index);
      }
      // 更新UI
      const item = e.target.closest('.export-request-item');
      if (e.target.checked) {
        item.classList.add('selected');
      } else {
        item.classList.remove('selected');
      }
    });
  });
  
  // 点击项目本身也可以切换选择
  exportRequestList.querySelectorAll('.export-request-item').forEach(item => {
    item.addEventListener('click', (e) => {
      if (e.target.type !== 'checkbox') {
        const checkbox = item.querySelector('input[type="checkbox"]');
        checkbox.checked = !checkbox.checked;
        checkbox.dispatchEvent(new Event('change'));
      }
    });
  });
}

// 全选
selectAllBtn.addEventListener('click', () => {
  selectedRequestIndices = new Set(requests.map((_, index) => index));
  renderExportSelection();
});

// 全不选
deselectAllBtn.addEventListener('click', () => {
  selectedRequestIndices.clear();
  renderExportSelection();
});

// 反选
invertSelectionBtn.addEventListener('click', () => {
  const newSelection = new Set();
  requests.forEach((_, index) => {
    if (!selectedRequestIndices.has(index)) {
      newSelection.add(index);
    }
  });
  selectedRequestIndices = newSelection;
  renderExportSelection();
});

// 关闭导出选择面板
closeExportSelection.addEventListener('click', () => {
  exportSelectionPanel.style.display = 'none';
});

cancelExportBtn.addEventListener('click', () => {
  exportSelectionPanel.style.display = 'none';
});

// 确认导出
confirmExportBtn.addEventListener('click', async () => {
  if (selectedRequestIndices.size === 0) {
    alert('请至少选择一个请求！');
    return;
  }
  
  chrome.tabs.query({ active: true, currentWindow: true }, async (tabs) => {
    if (tabs[0]) {
      chrome.tabs.sendMessage(tabs[0].id, { type: 'GET_CHAIN' }, async (response) => {
        if (!response) return;

        const userAction = userActionInput.value.trim() || '未描述操作';
        
        // 获取配置
        const config = await getConfig();
        
        // 只导出选中的请求
        let requestChain = response.requestChain.filter((_, index) => 
          selectedRequestIndices.has(index)
        );
        
        // 如果不包含 headers，则移除 headers 字段
        if (!config.includeHeaders) {
          requestChain = requestChain.map(req => {
            const { requestHeaders, responseHeaders, ...rest } = req;
            return rest;
          });
        }
        
        const exportData = {
          sessionId: response.sessionId,
          userAction: userAction,
          timestamp: response.timestamp,
          url: response.url,
          requestChain: requestChain,
          dataFlow: response.dataFlow
        };

        // 检查是否需要包含前置URL数据
        if (config.prependEnabled && config.prependUrl) {
          // 查找前置URL的请求
          const prependRequest = response.requestChain.find(req => 
            req.url === config.prependUrl || req.url.endsWith(config.prependUrl)
          );
          
          if (prependRequest) {
            // 根据 includeHeaders 配置决定是否包含 headers
            let prependData = { ...prependRequest };
            if (!config.includeHeaders) {
              const { requestHeaders, responseHeaders, ...rest } = prependData;
              prependData = rest;
            }
            exportData.prependRequest = prependData;
          } else {
            console.warn('未找到前置URL的请求数据:', config.prependUrl);
          }
        }

        const json = JSON.stringify(exportData, null, 2);
        
        // 复制到剪贴板
        navigator.clipboard.writeText(json).then(() => {
          // 也提供下载
          const blob = new Blob([json], { type: 'application/json' });
          const url = URL.createObjectURL(blob);
          const a = document.createElement('a');
          a.href = url;
          a.download = `request-chain-${response.sessionId}.json`;
          a.click();
          
          let message = `已导出 ${selectedRequestIndices.size} 个请求`;
          if (config.prependEnabled && config.prependUrl) {
            const found = exportData.prependRequest ? '✓' : '✗';
            message += `\n前置URL: ${found}`;
          }
          if (!config.includeHeaders) {
            message += '\n已移除 Headers';
          }
          showToast(message, 'success', 3000);
          
          // 关闭导出选择面板
          exportSelectionPanel.style.display = 'none';
        });
      });
    }
  });
});
