import { useEffect, useRef } from 'react';
import { Badge, Button, Drawer, Tag } from 'antd';
import { UnorderedListOutlined, ClearOutlined } from '@ant-design/icons';
import type { LogEntry } from '../hooks/useOperationLog';

const LEVEL_COLORS: Record<string, string> = {
  info: 'blue',
  success: 'green',
  error: 'red',
  warn: 'orange',
};

export default function OperationLogPanel({ logs, open, onToggle, onClear }: {
  logs: LogEntry[];
  open: boolean;
  onToggle: () => void;
  onClear: () => void;
}) {
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (open && bottomRef.current) {
      bottomRef.current.scrollIntoView({ behavior: 'smooth' });
    }
  }, [logs, open]);

  return (
    <>
      <Badge count={logs.filter(l => l.level === 'error').length} size="small" offset={[-4, 4]}>
        <Button
          size="small"
          icon={<UnorderedListOutlined />}
          onClick={onToggle}
          style={{ position: 'fixed', bottom: 16, right: 16, zIndex: 100, boxShadow: '0 2px 8px rgba(0,0,0,0.15)' }}
        >
          操作日志 ({logs.length})
        </Button>
      </Badge>

      <Drawer
        title="操作日志"
        placement="bottom"
        height="35%"
        open={open}
        onClose={onToggle}
        extra={
          <Button size="small" icon={<ClearOutlined />} onClick={onClear}>清空</Button>
        }
      >
        <div style={{ fontFamily: 'monospace', fontSize: 12, lineHeight: 1.8 }}>
          {logs.length === 0 ? (
            <div style={{ color: '#999', textAlign: 'center', padding: 20 }}>暂无操作日志</div>
          ) : (
            logs.map((log, i) => (
              <div key={i} style={{
                padding: '2px 0',
                borderBottom: '1px solid #fafafa',
                color: log.level === 'error' ? '#cf1322' : log.level === 'warn' ? '#d48806' : '#333',
              }}>
                <span style={{ color: '#999', marginRight: 8 }}>{log.time}</span>
                <Tag color={LEVEL_COLORS[log.level]} style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px' }}>
                  {log.level.toUpperCase()}
                </Tag>
                <span style={{ fontWeight: 500 }}>{log.action}</span>
                {log.detail && <span style={{ color: '#666', marginLeft: 8 }}>{log.detail}</span>}
              </div>
            ))
          )}
          <div ref={bottomRef} />
        </div>
      </Drawer>
    </>
  );
}
