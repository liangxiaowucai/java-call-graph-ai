// Background Service Worker
chrome.runtime.onInstalled.addListener(() => {
  console.log('JavaCG2 请求链追踪器已安装');
});

// 保持service worker活跃
chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  // 转发消息
  return true;
});
