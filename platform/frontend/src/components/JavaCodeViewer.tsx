import { useRef } from 'react';
import Prism from 'prismjs';
import 'prismjs/components/prism-java';
import 'prismjs/themes/prism-coy.css';

// IDEA-like 风格覆盖
const STYLE_OVERRIDE = `
.code-viewer {
  position: relative;
  font-family: 'JetBrains Mono', 'Fira Code', 'Cascadia Code', Consolas, monospace;
  font-size: 13px;
  line-height: 1.7;
  background: #fff;
  border-radius: 0;
  overflow: auto;
}
.code-viewer pre {
  margin: 0;
  padding: 0;
  background: transparent !important;
}
.code-viewer code {
  background: transparent !important;
  text-shadow: none !important;
}
.code-viewer .line-numbers {
  display: flex;
}
.code-viewer .line-num {
  color: #999;
  text-align: right;
  padding-right: 16px;
  padding-left: 12px;
  user-select: none;
  min-width: 40px;
  border-right: 1px solid #eee;
}
.code-viewer .line-code {
  padding-left: 12px;
  flex: 1;
  min-width: 0;
  white-space: pre;
  tab-size: 4;
}
.code-viewer .line-code pre {
  margin: 0;
  padding: 0;
  background: transparent !important;
  font-family: inherit;
  font-size: inherit;
  white-space: pre;
  tab-size: 4;
}
.code-viewer .line-code code {
  background: transparent !important;
  white-space: pre;
  tab-size: 4;
}
.code-viewer .line-row {
  display: flex;
  min-height: 22px;
}
.code-viewer .line-row:hover {
  background: rgba(255,255,255,0.04);
}
.code-viewer .line-row.highlight {
  background: rgba(255,200,0,0.12);
}
/* IDEA-like token colors */
.code-viewer .token.keyword { color: #cc7832; font-weight: bold; }
.code-viewer .token.string { color: #6a8759; }
.code-viewer .token.number { color: #6897bb; }
.code-viewer .token.comment { color: #808080; font-style: italic; }
.code-viewer .token.annotation { color: #bbb529; }
.code-viewer .token.class-name { color: #a9b7c6; }
.code-viewer .token.function { color: #ffc66d; }
.code-viewer .token.boolean { color: #cc7832; font-weight: bold; }
.code-viewer .token.operator { color: #a9b7c6; }
.code-viewer .token.punctuation { color: #a9b7c6; }
.code-viewer .token.generics .class-name { color: #a9b7c6; }

/* 方法签名区域 */
.code-viewer .method-header {
  background: rgba(61, 117, 181, 0.15);
  border-left: 3px solid #3d75b5;
}
/* 参数高亮 */
.code-viewer .param-marker {
  background: rgba(152, 118, 170, 0.2);
  border-bottom: 1px dashed #9876aa;
}
`;

interface Props {
  code: string;
  startLine?: number;
  highlightLines?: number[];
  methodSignature?: string;
  maxHeight?: string;
}

export default function JavaCodeViewer({ code, startLine = 1, highlightLines = [], maxHeight = '100%' }: Props) {
  const codeRef = useRef<HTMLDivElement>(null);

  if (!code) return <div style={{ color: '#999', padding: 20, textAlign: 'center' }}>暂无源码</div>;

  const lines = code.split('\n');
  const highlightSet = new Set(highlightLines);

  // 检测方法签名行（用于特殊高亮）
  const isMethodSignature = (line: string) => {
    const trimmed = line.trim();
    return trimmed.includes('(') && (
      trimmed.startsWith('public ') || trimmed.startsWith('private ') ||
      trimmed.startsWith('protected ') || trimmed.startsWith('static ') ||
      trimmed.startsWith('@Override')
    );
  };

  return (
    <>
      <style>{STYLE_OVERRIDE}</style>
      <div className="code-viewer" ref={codeRef} style={{ maxHeight, overflow: 'auto' }}>
        {lines.map((line, i) => {
          const lineNum = startLine + i;
          const isHighlight = highlightSet.has(lineNum);
          const isSignature = isMethodSignature(line);

          return (
            <div
              key={i}
              className={`line-row${isHighlight ? ' highlight' : ''}${isSignature ? ' method-header' : ''}`}
            >
              <span className="line-num">{lineNum}</span>
              <span className="line-code">
                <pre style={{ margin: 0, padding: 0, background: 'transparent', display: 'inline' }}><code className="language-java" dangerouslySetInnerHTML={{
                  __html: Prism.highlight(line || ' ', Prism.languages.java, 'java')
                }} /></pre>
              </span>
            </div>
          );
        })}
      </div>
    </>
  );
}
