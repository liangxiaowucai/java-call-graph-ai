import { useState, useCallback, useRef } from 'react';

export interface LogEntry {
  time: string;
  action: string;
  detail?: string;
  level: 'info' | 'success' | 'error' | 'warn';
}

export function useOperationLog() {
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const logRef = useRef<LogEntry[]>([]);

  const addLog = useCallback((action: string, detail?: string, level: LogEntry['level'] = 'info') => {
    const entry: LogEntry = {
      time: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
      action,
      detail,
      level,
    };
    logRef.current = [...logRef.current, entry];
    // 最多保留 200 条
    if (logRef.current.length > 200) {
      logRef.current = logRef.current.slice(-200);
    }
    setLogs([...logRef.current]);
  }, []);

  const clearLogs = useCallback(() => {
    logRef.current = [];
    setLogs([]);
  }, []);

  return { logs, addLog, clearLogs };
}
