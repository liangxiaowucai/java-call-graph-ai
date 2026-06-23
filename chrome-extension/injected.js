// 注入到页面主环境的脚本
(function() {
  'use strict';

  let requestChain = [];
  let sessionId = Date.now();
  let isRecording = true;

  console.log('[JavaCG2-INJECTED] 请求拦截器已注入到主环境');

  // 静默处理未捕获的 Promise rejections（仅限插件相关的）
  const originalUnhandledRejection = window.onunhandledrejection;
  window.addEventListener('unhandledrejection', function(event) {
    // 如果错误消息包含我们的标记，静默处理
    if (event.reason && event.reason.message && 
        (event.reason.message.includes('JavaCG2') || 
         event.reason.message.includes('postMessage') ||
         event.reason.message.includes('clone'))) {
      event.preventDefault();
      return;
    }
  });

  // 拦截 XMLHttpRequest
  const originalXHR = window.XMLHttpRequest;
  window.XMLHttpRequest = function() {
    const xhr = new originalXHR();
    const originalOpen = xhr.open;
    const originalSend = xhr.send;
    const originalSetRequestHeader = xhr.setRequestHeader;

    let requestData = {
      timestamp: Date.now(),
      seq: requestChain.length + 1,
      requestHeaders: {}
    };

    xhr.open = function(method, url, ...args) {
      requestData.method = method;
      requestData.url = url;
      requestData.fullUrl = new URL(url, window.location.href).href;
      return originalOpen.apply(this, [method, url, ...args]);
    };

    // 捕获请求头
    xhr.setRequestHeader = function(header, value) {
      requestData.requestHeaders[header] = value;
      return originalSetRequestHeader.apply(this, arguments);
    };

    xhr.send = function(data) {
      // 安全地处理请求体
      if (data) {
        try {
          if (typeof data === 'string') {
            requestData.requestBody = data;
          } else if (data instanceof URLSearchParams) {
            requestData.requestBody = data.toString();
          } else if (data instanceof FormData) {
            // FormData 转换为对象
            const formObj = {};
            data.forEach((value, key) => {
              formObj[key] = value;
            });
            requestData.requestBody = JSON.stringify(formObj);
          } else if (data instanceof Blob) {
            requestData.requestBody = '[Blob数据]';
          } else {
            requestData.requestBody = String(data);
          }
        } catch (e) {
          requestData.requestBody = '[无法序列化的请求体]';
        }
      } else {
        requestData.requestBody = null;
      }

      xhr.addEventListener('load', function() {
        if (!isRecording) return;

        requestData.responseStatus = xhr.status;
        requestData.responseBody = xhr.responseText;
        requestData.duration = Date.now() - requestData.timestamp;
        requestData.success = xhr.status >= 200 && xhr.status < 300;

        // 捕获响应头
        try {
          const responseHeaders = {};
          const headerStr = xhr.getAllResponseHeaders();
          const headers = headerStr.trim().split(/[\r\n]+/);
          headers.forEach(line => {
            const parts = line.split(': ');
            const header = parts.shift();
            const value = parts.join(': ');
            if (header) {
              responseHeaders[header] = value;
            }
          });
          requestData.responseHeaders = responseHeaders;
        } catch (e) {
          requestData.responseHeaders = {};
        }

        console.log('[JavaCG2-INJECTED] XHR请求:', requestData.method, requestData.url, requestData.responseStatus);

        // 所有请求都记录 - 确保数据可序列化
        const safeData = JSON.parse(JSON.stringify(requestData));
        requestChain.push(safeData);
        
        // 发送到页面环境 - 使用 try-catch 避免错误
        try {
          window.postMessage({
            type: 'JAVACG2_REQUEST_CAPTURED',
            data: safeData
          }, '*');
        } catch (e) {
          // 静默失败
        }
      });

      xhr.addEventListener('error', function() {
        if (!isRecording) return;

        requestData.responseStatus = 0;
        requestData.responseBody = 'Network Error';
        requestData.duration = Date.now() - requestData.timestamp;
        requestData.success = false;
        requestData.responseHeaders = {};

        console.log('[JavaCG2-INJECTED] XHR请求失败:', requestData.method, requestData.url);
        
        const safeData = JSON.parse(JSON.stringify(requestData));
        requestChain.push(safeData);
        
        try {
          window.postMessage({
            type: 'JAVACG2_REQUEST_CAPTURED',
            data: safeData
          }, '*');
        } catch (e) {
          // 静默失败
        }
      });

      return originalSend.apply(this, arguments);
    };

    return xhr;
  };

  // 拦截 Fetch
  const originalFetch = window.fetch;
  window.fetch = function(url, options = {}) {
    if (!isRecording) {
      return originalFetch.apply(this, arguments);
    }

    const startTime = Date.now();
    let requestData = {
      timestamp: startTime,
      seq: requestChain.length + 1,
      method: (options.method || 'GET').toUpperCase(),
      url: typeof url === 'string' ? url : url.url,
      fullUrl: typeof url === 'string' ? new URL(url, window.location.href).href : url.url,
      requestBody: null,
      requestHeaders: {}
    };

    // 安全地处理请求体
    if (options.body) {
      try {
        if (typeof options.body === 'string') {
          requestData.requestBody = options.body;
        } else if (options.body instanceof URLSearchParams) {
          requestData.requestBody = options.body.toString();
        } else if (options.body instanceof FormData) {
          // FormData 转换为对象
          const formObj = {};
          options.body.forEach((value, key) => {
            formObj[key] = value;
          });
          requestData.requestBody = JSON.stringify(formObj);
        } else if (options.body instanceof Blob) {
          requestData.requestBody = '[Blob数据]';
        } else {
          requestData.requestBody = String(options.body);
        }
      } catch (e) {
        requestData.requestBody = '[无法序列化的请求体]';
      }
    }

    // 捕获请求头
    if (options.headers) {
      if (options.headers instanceof Headers) {
        options.headers.forEach((value, key) => {
          requestData.requestHeaders[key] = value;
        });
      } else if (typeof options.headers === 'object') {
        requestData.requestHeaders = { ...options.headers };
      }
    }

    return originalFetch.apply(this, arguments)
      .then(response => {
        // 不要克隆响应，直接记录基本信息
        requestData.responseStatus = response.status;
        requestData.duration = Date.now() - startTime;
        requestData.success = response.ok;

        // 捕获响应头
        try {
          const responseHeaders = {};
          response.headers.forEach((value, key) => {
            responseHeaders[key] = value;
          });
          requestData.responseHeaders = responseHeaders;
        } catch (e) {
          requestData.responseHeaders = {};
        }

        console.log('[JavaCG2-INJECTED] Fetch请求:', requestData.method, requestData.url, requestData.responseStatus);
        
        // 尝试读取响应体（但不影响原始响应）
        // 使用更安全的方式，避免错误出现在控制台
        Promise.resolve().then(() => {
          try {
            const clonedResponse = response.clone();
            return clonedResponse.text();
          } catch (e) {
            return Promise.reject(e);
          }
        }).then(text => {
          requestData.responseBody = text;
          
          // 确保数据可序列化
          const safeData = JSON.parse(JSON.stringify(requestData));
          requestChain.push(safeData);
          
          try {
            window.postMessage({
              type: 'JAVACG2_REQUEST_CAPTURED',
              data: safeData
            }, '*');
          } catch (e) {
            // 静默失败
          }
        }).catch(() => {
          // 静默处理失败，不输出任何日志
          requestData.responseBody = '';
          
          const safeData = JSON.parse(JSON.stringify(requestData));
          requestChain.push(safeData);
          
          try {
            window.postMessage({
              type: 'JAVACG2_REQUEST_CAPTURED',
              data: safeData
            }, '*');
          } catch (e) {
            // 静默失败
          }
        });

        return response;
      })
      .catch(error => {
        requestData.responseStatus = 0;
        requestData.responseBody = error.message;
        requestData.duration = Date.now() - startTime;
        requestData.success = false;
        requestData.responseHeaders = {};

        console.log('[JavaCG2-INJECTED] Fetch请求失败:', requestData.method, requestData.url);
        
        const safeData = JSON.parse(JSON.stringify(requestData));
        requestChain.push(safeData);
        
        try {
          window.postMessage({
            type: 'JAVACG2_REQUEST_CAPTURED',
            data: safeData
          }, '*');
        } catch (e) {
          // 静默失败
        }

        throw error;
      });
  };

  // 监听来自 content script 的消息
  window.addEventListener('message', function(event) {
    if (event.source !== window) return;
    
    if (event.data.type === 'JAVACG2_GET_CHAIN') {
      window.postMessage({
        type: 'JAVACG2_CHAIN_DATA',
        sessionId: sessionId,
        timestamp: Date.now(),
        url: window.location.href,
        requestChain: requestChain
      }, '*');
    }
    
    if (event.data.type === 'JAVACG2_CLEAR_CHAIN') {
      requestChain = [];
      sessionId = Date.now();
    }
    
    if (event.data.type === 'JAVACG2_TOGGLE_RECORDING') {
      isRecording = event.data.enabled;
    }
  });

  console.log('[JavaCG2-INJECTED] 请求拦截器已激活');
})();
