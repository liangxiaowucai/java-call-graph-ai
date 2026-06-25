import { useState, useEffect, useRef } from 'react';

interface ThinkingPanelProps {
  steps: Array<{ label: string; content: string; status: 'pending' | 'done'; toolName?: string; round?: number; detail?: string }>;
  isDone: boolean;
  intent?: { intentLabel: string; summary: string; focus: string[] } | null;
}

/** 从 label 猜测文件路径（展示用）*/
function guessFilePath(label: string): string | null {
  const match = label.match(/([A-Z]\w+)\.\w+/);
  if (!match) return null;
  return `src/main/java/…/${match[1]}.java`;
}

/** 单行思考步骤，带打字机效果 */
function ThinkingStep({ label, isNew, round, detail }: { label: string; isNew: boolean; round?: number; detail?: string }) {
  const [displayed, setDisplayed] = useState(isNew ? '' : label);
  const indexRef = useRef(isNew ? 0 : label.length);

  useEffect(() => {
    if (!isNew) { setDisplayed(label); return; }
    const interval = setInterval(() => {
      indexRef.current += 2;
      if (indexRef.current >= label.length) {
        setDisplayed(label);
        clearInterval(interval);
      } else {
        setDisplayed(label.substring(0, indexRef.current));
      }
    }, 18);
    return () => clearInterval(interval);
  }, [label, isNew]);

  const filePath = guessFilePath(label);

  return (
    <div style={{ marginBottom: 6 }}>
      <div style={{ fontSize: 12, color: '#d4d4d4', lineHeight: 1.5, fontFamily: 'monospace' }}>
        {round != null && (
          <span style={{
            display: 'inline-block', fontSize: 10, color: '#f9e2af', background: '#33333e',
            borderRadius: 4, padding: '0 5px', marginRight: 6, verticalAlign: 'middle',
          }}>第{round}轮</span>
        )}
        {displayed}
        {isNew && displayed !== label && (
          <span style={{
            display: 'inline-block', width: 2, height: 12, background: '#52c41a',
            marginLeft: 2, verticalAlign: 'middle',
            animation: 'thinking-blink 0.8s step-start infinite',
          }} />
        )}
      </div>
      {detail ? (
        <div style={{ fontSize: 11, color: '#8a8a96', marginLeft: 20, fontFamily: 'monospace', marginTop: 1, lineHeight: 1.5, wordBreak: 'break-word' }}>
          {detail}
        </div>
      ) : filePath && (
        <div style={{ fontSize: 11, color: '#6a9955', marginLeft: 20, fontFamily: 'monospace', marginTop: 1 }}>
          {filePath}
        </div>
      )}
    </div>
  );
}

export default function ThinkingPanel({ steps, isDone, intent }: ThinkingPanelProps) {
  const [collapsed, setCollapsed] = useState(false);
  const prevCountRef = useRef(0);

  // 回答完成后延迟收缩
  useEffect(() => {
    if (isDone) {
      const t = setTimeout(() => setCollapsed(true), 1000);
      return () => clearTimeout(t);
    } else {
      setCollapsed(false);
    }
  }, [isDone]);

  const stepCount = steps.length;
  const currentRound = steps.reduce((max, s) => (s.round != null && s.round > max ? s.round : max), 0);

  return (
    <div style={{
      background: '#1e1e2e',
      border: '1px solid #313244',
      borderRadius: 8,
      marginBottom: 12,
      overflow: 'hidden',
    }}>
      {/* 标题栏 */}
      <div
        onClick={() => setCollapsed(c => !c)}
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '8px 14px',
          cursor: 'pointer',
          background: '#24243e',
          userSelect: 'none',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ fontSize: 14 }}>🧠</span>
          <span style={{ fontSize: 12, color: '#cba6f7', fontWeight: 600 }}>
            {isDone ? `已分析 ${stepCount} 步` : (currentRound > 0 ? `深度分析中 · 第 ${currentRound} 轮` : '深度分析中...')}
          </span>
          {!isDone && (
            <span style={{
              display: 'inline-block', width: 6, height: 6, borderRadius: '50%',
              background: '#52c41a',
              animation: 'thinking-pulse 1s ease-in-out infinite',
            }} />
          )}
        </div>
        <span style={{ fontSize: 11, color: '#6c7086' }}>
          {collapsed ? '查看详情 ▾' : '收起 ▴'}
        </span>
      </div>

      {/* 步骤列表 */}
      {!collapsed && (
        <div style={{ padding: '10px 14px', maxHeight: 300, overflowY: 'auto' }}>
          {intent && (intent.summary || intent.intentLabel) && (
            <div style={{ marginBottom: 10, padding: '8px 10px', background: '#181825', border: '1px solid #313244', borderRadius: 6 }}>
              <div style={{ fontSize: 12, color: '#89b4fa', fontWeight: 600, marginBottom: 4 }}>
                🧭 我对你问题的理解
              </div>
              {intent.intentLabel && (
                <div style={{ fontSize: 12, color: '#cdd6f4', lineHeight: 1.5 }}>
                  <span style={{ color: '#a6adc8' }}>意图：</span>{intent.intentLabel}
                </div>
              )}
              {intent.summary && (
                <div style={{ fontSize: 12, color: '#cdd6f4', lineHeight: 1.5 }}>
                  <span style={{ color: '#a6adc8' }}>你想知道：</span>{intent.summary}
                </div>
              )}
              {intent.focus && intent.focus.length > 0 && (
                <div style={{ fontSize: 12, color: '#cdd6f4', lineHeight: 1.5 }}>
                  <span style={{ color: '#a6adc8' }}>回答角度：</span>{intent.focus.join(' · ')}
                </div>
              )}
            </div>
          )}
          {steps.length === 0 ? (
            <div style={{ fontSize: 12, color: '#6c7086', fontFamily: 'monospace' }}>
              正在准备分析...
            </div>
          ) : (
            steps.map((step, i) => {
              const isNew = i === steps.length - 1 && !isDone;
              return <ThinkingStep key={i} label={step.label} isNew={isNew} round={step.round} detail={step.detail} />;
            })
          )}
          {(() => { prevCountRef.current = steps.length; return null; })()}
        </div>
      )}

      <style>{`
        @keyframes thinking-blink { 0%,100%{opacity:1} 50%{opacity:0} }
        @keyframes thinking-pulse { 0%,100%{opacity:1;transform:scale(1)} 50%{opacity:0.5;transform:scale(0.8)} }
      `}</style>
    </div>
  );
}
