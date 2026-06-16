import { createContext, useContext, useState } from 'react';
import { BrowserRouter, Routes, Route, useNavigate, useLocation } from 'react-router-dom';
import { Layout, Menu } from 'antd';
import {
  DatabaseOutlined,
  FileTextOutlined,
  ApartmentOutlined,
  RobotOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import RepoManager from './pages/RepoManager';
import CallGraph from './pages/CallGraph';
import QAChat from './pages/QAChat';
import ProjectOverview from './pages/ProjectOverview';
import Settings from './pages/Settings';
import OperationLogPanel from './components/OperationLogPanel';
import { useOperationLog, type LogEntry } from './hooks/useOperationLog';

const { Sider, Content } = Layout;

const menuItems = [
  { key: '/repos', icon: <DatabaseOutlined />, label: '仓库管理' },
  { key: '/overview', icon: <FileTextOutlined />, label: '项目概览' },
  { key: '/callgraph', icon: <ApartmentOutlined />, label: '调用链分析' },
  { key: '/qa', icon: <RobotOutlined />, label: 'AI 问答' },
  { key: '/settings', icon: <SettingOutlined />, label: '系统配置' },
];

// 全局日志 Context
type AddLogFn = (action: string, detail?: string, level?: LogEntry['level']) => void;
export const LogContext = createContext<AddLogFn>(() => {});
export const useLog = () => useContext(LogContext);

function AppLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const { logs, addLog, clearLogs } = useOperationLog();
  const [logPanelOpen, setLogPanelOpen] = useState(false);

  const selectedKey = menuItems.find((m) => location.pathname.startsWith(m.key))?.key ?? '/repos';

  return (
    <LogContext.Provider value={addLog}>
      <Layout className="app-layout">
        <Sider breakpoint="lg" collapsedWidth={60} theme="dark">
          <div className="logo">
            <span className="logo-full">JavaCG2</span>
            <span className="logo-short">CG</span>
          </div>
          <Menu
            theme="dark"
            mode="inline"
            selectedKeys={[selectedKey]}
            items={menuItems}
            onClick={({ key }) => { navigate(key); addLog('导航', key); }}
          />
        </Sider>
        <Layout>
          <Content className="site-layout-content">
            <Routes>
              <Route path="/repos" element={<RepoManager />} />
              <Route path="/overview" element={<ProjectOverview />} />
              <Route path="/callgraph" element={<CallGraph />} />
              <Route path="/qa" element={<QAChat />} />
              <Route path="/settings" element={<Settings />} />
              <Route path="*" element={<RepoManager />} />
            </Routes>
          </Content>
        </Layout>
      </Layout>
      <OperationLogPanel
        logs={logs}
        open={logPanelOpen}
        onToggle={() => setLogPanelOpen(!logPanelOpen)}
        onClear={clearLogs}
      />
    </LogContext.Provider>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <AppLayout />
    </BrowserRouter>
  );
}
