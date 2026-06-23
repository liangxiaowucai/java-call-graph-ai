# JavaCG2 请求链追踪插件

Chrome浏览器插件，用于捕获前端请求链并智能分析后端问题。

## 🎯 核心功能

### 1. 自动捕获请求
- 拦截所有 XHR 和 Fetch 请求
- 实时显示请求状态（成功/失败）
- 记录请求时间和响应时间

### 2. 数据流分析
- 自动检测请求间的参数传递关系
- 发现数据不一致问题
- 可视化请求依赖链

### 3. 智能诊断
- 结合后端调用图谱分析问题
- AI 诊断失败原因
- 给出具体的代码修复建议

### 4. 导出分享
- 一键导出 JSON 格式的请求链
- 复制到剪贴板或下载文件
- 方便团队协作和问题追踪

## 📦 安装步骤

### 方法一：开发者模式安装（推荐）

1. **打开Chrome扩展管理页面**
   ```
   在浏览器地址栏输入：chrome://extensions/
   ```

2. **开启开发者模式**
   - 在页面右上角打开"开发者模式"开关

3. **加载插件**
   - 点击"加载已解压的扩展程序"
   - 选择 `java-call-graph/chrome-extension` 目录
   - 确认加载

4. **验证安装**
   - 插件图标应出现在浏览器工具栏
   - 图标显示为蓝色背景的放大镜 🔍

### 方法二：打包安装

```bash
# 打包插件
cd java-call-graph
zip -r javacg2-chrome-extension.zip chrome-extension/

# 在 Chrome 中:
# 1. 访问 chrome://extensions/
# 2. 点击"打包扩展程序"
# 3. 选择 chrome-extension 目录
# 4. 生成 .crx 文件供团队使用
```

## 🚀 快速开始

### Step 1: 启动后端服务

```bash
cd platform
./gradlew bootRun

# 等待服务启动...
# 默认地址: http://localhost:8080
```

### Step 2: 使用插件

1. **打开需要调试的网页**

2. **打开插件面板**
   - 点击浏览器工具栏的插件图标

3. **输入操作描述**
   - 在"操作描述"输入框填写，例如："点击提交订单按钮"

4. **执行操作**
   - 在网页上执行操作
   - 插件会自动捕获所有 API 请求
   - 实时显示在请求列表中

5. **查看结果**
   - 🟢 绿色 = 成功请求
   - 🔴 红色 = 失败请求

6. **智能分析**
   - 点击"🔬 智能分析"按钮
   - 等待后端 AI 分析完成
   - 查看诊断报告

7. **导出数据**
   - 点击"📋 导出JSON"
   - 数据自动复制到剪贴板并下载

## 💡 使用场景

### 场景1：调试接口调用问题

```
问题：点击"提交订单"按钮后，订单创建失败

解决步骤：
1. 打开插件，输入："提交订单"
2. 清空历史记录
3. 点击页面上的"提交订单"按钮
4. 查看请求链（获取地址 → 验证商品 → 创建订单）
5. 点击"智能分析"
6. 查看 AI 诊断：哪个环节出错、为什么、如何修复
```

### 场景2：排查数据传递问题

```
问题：第一个接口返回的ID，传给第二个接口时数据不对

解决步骤：
1. 捕获请求链
2. 点击"智能分析"
3. 查看"数据流问题"部分
4. 插件自动发现字段值不一致
5. 显示期望值和实际值对比
```

### 场景3：团队协作

```
场景：后端需要前端提供完整的请求数据

解决步骤：
1. 前端同学捕获请求链
2. 点击"导出JSON"
3. 将 JSON 文件发给后端同学
4. 后端同学导入分析或直接查看
```

## 🔧 配置说明

### 修改后端地址

插件默认连接：`http://localhost:8080`

修改方法：
1. 打开 `popup.js`
2. 修改 `DEFAULT_BACKEND_URL` 常量
3. 重新加载插件

### 修改请求过滤规则

默认只捕获包含 `/api/` 的请求。

修改方法：
1. 编辑 `content.js`
2. 找到 `if (requestData.url.includes('/api/'))`
3. 修改过滤逻辑
4. 重新加载插件

### 录制控制

- 点击"🟢 录制中"可暂停录制
- 暂停后不会捕获新请求
- 再次点击恢复录制

## 📊 数据格式

### 导出的 JSON 结构

```json
{
  "sessionId": 1718611234567,
  "userAction": "提交订单",
  "timestamp": 1718611234567,
  "url": "https://example.com/order",
  "requestChain": [
    {
      "seq": 1,
      "method": "GET",
      "url": "/api/address/list",
      "responseStatus": 200,
      "responseBody": "{\"data\":[...]}",
      "duration": 123,
      "success": true
    }
  ],
  "dataFlow": [
    {
      "from": "/api/address/list",
      "to": "/api/order/create",
      "fields": [
        {"field": "addressId", "value": "addr_123"}
      ]
    }
  ]
}
```

## 🐛 常见问题

### Q: 插件图标不显示？

**A:** 
- 检查 chrome://extensions/ 是否成功加载
- 确保图标文件存在（icons/icon16.png等）
- 尝试重新加载插件

### Q: 无法捕获请求？

**A:**
- 刷新页面后再试
- 检查请求URL是否包含 `/api/`
- 查看浏览器 Console 是否有报错
- 确保录制状态是"🟢 录制中"

### Q: 智能分析失败？

**A:**
- 确保后端服务已启动（http://localhost:8080）
- 检查浏览器 Console 的网络错误
- 查看后端日志排查问题
- 确认项目已导入并分析

### Q: 找不到对应的后端方法？

**A:**
- 确保项目已通过后端服务分析
- 检查 `api_endpoints` 表是否有数据
- 确认 URL 路径映射正确
- 查看后端日志的匹配信息

## 🛠️ 开发调试

### 项目结构

```
chrome-extension/
├── manifest.json        # 插件配置
├── content.js          # 页面注入脚本（请求拦截）
├── background.js       # 后台服务
├── popup.html          # 插件弹窗界面
├── popup.css           # 弹窗样式
├── popup.js            # 弹窗逻辑
├── icons/              # 插件图标
│   ├── icon16.png
│   ├── icon48.png
│   └── icon128.png
├── create_icons.py     # 图标生成脚本
└── README.md           # 本文档
```

### 调试方法

**调试 Content Script:**
- 打开网页的开发者工具 Console
- 筛选 `[JavaCG2]` 关键字查看日志

**调试 Popup:**
- 右键点击插件图标
- 选择"检查弹出内容"
- 查看 Console 和 Elements

**调试 Background:**
- 访问 chrome://extensions/
- 找到本插件，点击"背景页"
- 查看 Service Worker 的 Console

### 重新加载插件

修改代码后：
1. 访问 chrome://extensions/
2. 找到本插件
3. 点击"重新加载"按钮

### 重新生成图标

```bash
cd chrome-extension
python3 create_icons.py
```

## 🔒 隐私和安全

- ✅ 所有数据仅在本地处理
- ✅ 不会上传到任何第三方服务器
- ✅ 仅捕获 `/api/` 路径的请求
- ✅ 可随时暂停或清空记录
- ⚠️ 敏感数据请注意脱敏后再导出

## 📄 许可证

与主项目相同

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！
