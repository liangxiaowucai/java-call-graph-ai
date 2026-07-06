import { createContext, useContext } from 'react';
import { BrowserRouter, Routes, Route, useNavigate, useLocation } from 'react-router-dom';
import { Layout, Menu } from 'antd';
import {
  DatabaseOutlined,
  ApartmentOutlined,
  RobotOutlined,
  SettingOutlined,
  BugOutlined,
  RocketOutlined,
} from '@ant-design/icons';
import RepoManager from './pages/RepoManager';
import CallGraph from './pages/CallGraph';
import QAChat from './pages/QAChat';
import Settings from './pages/Settings';
import RequestChainAnalyzer from './pages/RequestChainAnalyzer';
import ImpactAnalysis from './pages/ImpactAnalysis';
import ReleaseDoc from './pages/ReleaseDoc';
// import OperationLogPanel from './components/OperationLogPanel';

const { Sider, Content } = Layout;

const menuItems = [
  { key: '/repos', icon: <DatabaseOutlined />, label: '仓库管理' },
  { key: '/callgraph', icon: <ApartmentOutlined />, label: '仓库拓扑' },
  { key: '/release-doc', icon: <RocketOutlined />, label: '上线文档' },
  { key: '/request-analyzer', icon: <BugOutlined />, label: '调用链追踪' },
  { key: '/qa', icon: <RobotOutlined />, label: 'AI 问答' },
  { key: '/settings', icon: <SettingOutlined />, label: '系统配置' },
];

// 全局日志 Context
type AddLogFn = (action: string, detail?: string, level?: string) => void;
export const LogContext = createContext<AddLogFn>(() => {});
export const useLog = () => useContext(LogContext);

function AppLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const addLog: AddLogFn = (_action: string, _detail?: string) => {};

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
              <Route path="/callgraph" element={<CallGraph />} />
              <Route path="/release-doc" element={<ReleaseDoc />} />
              <Route path="/request-analyzer" element={<RequestChainAnalyzer />} />
              <Route path="/qa" element={<QAChat />} />
              <Route path="/settings" element={<Settings />} />
              <Route path="*" element={<RepoManager />} />
            </Routes>
          </Content>
        </Layout>
      </Layout>
      {/* OperationLogPanel removed */}
    </LogContext.Provider>
  );
}

export default function App() {
  return (
    <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Routes>
        {/* 独立全屏页（无侧边栏） */}
        <Route path="/impact" element={<ImpactAnalysis />} />
        {/* 主应用（带侧边栏） */}
        <Route path="*" element={<AppLayout />} />
      </Routes>
    </BrowserRouter>
  );
}
