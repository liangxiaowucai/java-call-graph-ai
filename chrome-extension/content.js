// Content Script - 桥接注入脚本和插件
(function() {
  'use strict';

  console.log('[JavaCG2-CONTENT] Content script 已加载');

  // 默认URL前缀配置
  let urlPrefixes = ['/api/'];

  // 从 storage 加载配置
  chrome.storage.local.get(['urlPrefixes'], (result) => {
    if (result.urlPrefixes) {
      urlPrefixes = result.urlPrefixes.split(',').map(p => p.trim()).filter(p => p);
      console.log('[JavaCG2-CONTENT] 加载URL前缀配置:', urlPrefixes);
    }
  });

  // 检查URL是否匹配前缀
  function matchesUrlPrefix(url) {
    if (urlPrefixes.length === 0 || (urlPrefixes.length === 1 && !urlPrefixes[0])) {
      // 没有配置或配置为空，捕获所有请求
      return true;
    }
    return urlPrefixes.some(prefix => url.includes(prefix));
  }

  // 注入脚本到页面主环境
  const script = document.createElement('script');
  script.src = chrome.runtime.getURL('injected.js');
  script.onload = function() {
    this.remove();
    console.log('[JavaCG2-CONTENT] Injected script 已加载');
  };
  (document.head || document.documentElement).appendChild(script);

  let requestChain = [];

  // 监听来自注入脚本的消息
  window.addEventListener('message', function(event) {
    if (event.source !== window) return;
    
    if (event.data.type === 'JAVACG2_REQUEST_CAPTURED') {
      const requestData = event.data.data;
      
      // 使用配置的前缀进行过滤
      if (matchesUrlPrefix(requestData.url) || matchesUrlPrefix(requestData.fullUrl)) {
        requestChain.push(requestData);
        
        console.log('[JavaCG2-CONTENT] ✅ 捕获请求:', requestData.method, requestData.url, requestData.responseStatus);
        
        // 通知 popup
        chrome.runtime.sendMessage({
          type: 'REQUEST_CAPTURED',
          data: requestData,
          total: requestChain.length
        }).catch(() => {
          // Popup 可能未打开
        });
      } else {
        console.log('[JavaCG2-CONTENT] ⏭️  跳过请求（不匹配前缀）:', requestData.method, requestData.url);
      }
    }
    
    if (event.data.type === 'JAVACG2_CHAIN_DATA') {
      // 处理链数据 - 也要按前缀过滤
      requestChain = event.data.requestChain.filter(req => 
        matchesUrlPrefix(req.url) || matchesUrlPrefix(req.fullUrl)
      );
    }
  });

  // 监听来自 popup 的消息
  chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
    console.log('[JavaCG2-CONTENT] 收到消息:', message.type);
    
    if (message.type === 'UPDATE_CONFIG') {
      // 更新URL前缀配置
      if (message.urlPrefixes !== undefined) {
        urlPrefixes = message.urlPrefixes.split(',').map(p => p.trim()).filter(p => p);
        console.log('[JavaCG2-CONTENT] URL前缀配置已更新:', urlPrefixes);
      }
      sendResponse({ success: true });
      return true;
    }
    
    if (message.type === 'GET_CHAIN') {
      console.log('[JavaCG2-CONTENT] 收到 GET_CHAIN 请求，当前有 ' + requestChain.length + ' 个请求');
      
      // 先发送当前已有的数据
      if (requestChain.length > 0) {
        const dataFlow = analyzeDataFlow(requestChain);
        const response = {
          sessionId: Date.now(),
          timestamp: Date.now(),
          url: window.location.href,
          requestChain: requestChain,
          dataFlow: dataFlow
        };
        console.log('[JavaCG2-CONTENT] 直接返回已有数据:', response);
        sendResponse(response);
        return true;
      }
      
      console.log('[JavaCG2-CONTENT] 本地没有数据，请求注入脚本的数据');
      // 如果没有数据，请求注入脚本的数据
      window.postMessage({ type: 'JAVACG2_GET_CHAIN' }, '*');
      
      // 等待响应
      const listener = function(event) {
        if (event.source !== window) return;
        if (event.data.type === 'JAVACG2_CHAIN_DATA') {
          window.removeEventListener('message', listener);
          
          console.log('[JavaCG2-CONTENT] 收到注入脚本的数据:', event.data.requestChain.length + ' 个请求');
          
          // 过滤只包含 /api/ 的请求
          const filteredChain = event.data.requestChain.filter(req =>
            matchesUrlPrefix(req.url) || matchesUrlPrefix(req.fullUrl)
          );
          
          console.log('[JavaCG2-CONTENT] 过滤后剩余 ' + filteredChain.length + ' 个请求');
          
          // 分析数据流
          const dataFlow = analyzeDataFlow(filteredChain);
          
          sendResponse({
            sessionId: event.data.sessionId,
            timestamp: event.data.timestamp,
            url: event.data.url,
            requestChain: filteredChain,
            dataFlow: dataFlow
          });
        }
      };
      
      window.addEventListener('message', listener);
      
      // 超时处理 - 返回空数据而不是失败
      setTimeout(() => {
        window.removeEventListener('message', listener);
        console.log('[JavaCG2-CONTENT] 超时，返回空数据');
        sendResponse({
          sessionId: Date.now(),
          timestamp: Date.now(),
          url: window.location.href,
          requestChain: [],
          dataFlow: []
        });
      }, 1000);
      
      return true;
    }
    
    if (message.type === 'CLEAR_CHAIN') {
      console.log('[JavaCG2-CONTENT] 清空请求链');
      requestChain = [];
      window.postMessage({ type: 'JAVACG2_CLEAR_CHAIN' }, '*');
      sendResponse({ success: true });
      return true;
    }

    if (message.type === 'TOGGLE_RECORDING') {
      console.log('[JavaCG2-CONTENT] 切换录制状态:', message.enabled);
      window.postMessage({ 
        type: 'JAVACG2_TOGGLE_RECORDING',
        enabled: message.enabled
      }, '*');
      sendResponse({ success: true, isRecording: message.enabled });
      return true;
    }
  });

  // 分析数据流
  function analyzeDataFlow(chain) {
    const flows = [];

    for (let i = 0; i < chain.length - 1; i++) {
      const current = chain[i];
      const next = chain[i + 1];

      if (!current.success) continue;

      try {
        const currentResp = JSON.parse(current.responseBody);
        const nextReq = next.requestBody ? JSON.parse(next.requestBody) : {};

        const sharedFields = findSharedFields(currentResp, nextReq);

        if (sharedFields.length > 0) {
          flows.push({
            from: current.url,
            to: next.url,
            fields: sharedFields
          });
        }
      } catch (e) {
        // JSON解析失败，跳过
      }
    }

    return flows;
  }

  // 查找共享字段
  function findSharedFields(obj1, obj2, prefix = '') {
    const fields = [];

    function traverse(o1, o2, path) {
      if (!o1 || !o2 || typeof o1 !== 'object' || typeof o2 !== 'object') {
        return;
      }

      for (const key in o1) {
        if (o1.hasOwnProperty(key) && o2.hasOwnProperty(key)) {
          const val1 = o1[key];
          const val2 = o2[key];
          const fullPath = path ? `${path}.${key}` : key;

          if (val1 === val2 && val1 !== null && val1 !== undefined) {
            fields.push({
              field: fullPath,
              value: val1
            });
          } else if (typeof val1 === 'object' && typeof val2 === 'object') {
            traverse(val1, val2, fullPath);
          }
        }
      }
    }

    traverse(obj1, obj2, prefix);
    return fields;
  }

  console.log('[JavaCG2-CONTENT] Content script 初始化完成');
})();
