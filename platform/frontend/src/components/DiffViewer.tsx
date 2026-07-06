import Prism from 'prismjs';
import 'prismjs/components/prism-java';

// 一个单元格：行号 + 代码 + 类型
interface Cell {
  num: number;
  text: string;
  type: 'ctx' | 'del' | 'add';
}
interface Row {
  hunk?: string;      // @@ 行，整行跨列展示
  left?: Cell;
  right?: Cell;
}

function detectLang(path: string): string {
  if (path.endsWith('.java')) return 'java';
  return 'java'; // 目前仅高亮 Java，其它按纯文本走 java 语法也无碍
}

/** 解析 git unified diff 为左右对齐的行 */
function parseUnifiedDiff(diff: string): Row[] {
  const rows: Row[] = [];
  const lines = diff.split('\n');
  let oldNo = 0;
  let newNo = 0;
  let delBuf: Cell[] = [];
  let addBuf: Cell[] = [];

  const flush = () => {
    const n = Math.max(delBuf.length, addBuf.length);
    for (let i = 0; i < n; i++) {
      rows.push({ left: delBuf[i], right: addBuf[i] });
    }
    delBuf = [];
    addBuf = [];
  };

  for (const line of lines) {
    if (line.startsWith('@@')) {
      flush();
      const m = /@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(line);
      if (m) { oldNo = parseInt(m[1], 10); newNo = parseInt(m[2], 10); }
      rows.push({ hunk: line });
      continue;
    }
    // 跳过 diff 头
    if (line.startsWith('diff ') || line.startsWith('index ')
      || line.startsWith('--- ') || line.startsWith('+++ ')
      || line.startsWith('new file') || line.startsWith('deleted file')
      || line.startsWith('similarity ') || line.startsWith('rename ')) continue;

    if (line.startsWith('-')) {
      delBuf.push({ num: oldNo++, text: line.slice(1), type: 'del' });
    } else if (line.startsWith('+')) {
      addBuf.push({ num: newNo++, text: line.slice(1), type: 'add' });
    } else {
      flush();
      const t = line.startsWith(' ') ? line.slice(1) : line;
      rows.push({
        left: { num: oldNo++, text: t, type: 'ctx' },
        right: { num: newNo++, text: t, type: 'ctx' },
      });
    }
  }
  flush();
  return rows;
}

const bgOf = (type?: 'ctx' | 'del' | 'add', side?: 'l' | 'r') => {
  if (type === 'del') return '#ffeef0';
  if (type === 'add') return '#e6ffed';
  return side ? '#fff' : '#fafafa';
};

function CodeCell({ cell, lang, side }: { cell?: Cell; lang: string; side: 'l' | 'r' }) {
  if (!cell) {
    return <div style={{ background: '#f6f8fa', minHeight: 20, borderRight: side === 'l' ? '1px solid #eee' : undefined }} />;
  }
  const html = Prism.highlight(cell.text || ' ', Prism.languages[lang] ?? Prism.languages.java, lang);
  return (
    <div style={{ display: 'flex', background: bgOf(cell.type, side), borderRight: side === 'l' ? '1px solid #eee' : undefined }}>
      <span style={{ color: '#bbb', textAlign: 'right', padding: '0 8px', userSelect: 'none', minWidth: 44, flexShrink: 0, fontVariantNumeric: 'tabular-nums' }}>
        {cell.num}
      </span>
      <span style={{ width: 12, color: cell.type === 'del' ? '#cb2431' : cell.type === 'add' ? '#22863a' : '#ccc', flexShrink: 0, userSelect: 'none' }}>
        {cell.type === 'del' ? '-' : cell.type === 'add' ? '+' : ''}
      </span>
      <pre style={{ margin: 0, padding: 0, background: 'transparent', flex: 1, minWidth: 0, whiteSpace: 'pre', overflow: 'visible' }}>
        <code dangerouslySetInnerHTML={{ __html: html }} />
      </pre>
    </div>
  );
}

interface Props {
  diff: string;
  path?: string;
  maxHeight?: string;
}

export default function DiffViewer({ diff, path = '', maxHeight = '100%' }: Props) {
  if (!diff || !diff.trim()) {
    return <div style={{ color: '#999', padding: 24, textAlign: 'center' }}>无差异内容（可能为二进制文件或纯重命名）</div>;
  }
  const rows = parseUnifiedDiff(diff);
  const lang = detectLang(path);

  return (
    <div style={{
      fontFamily: "'JetBrains Mono', 'Fira Code', Consolas, monospace",
      fontSize: 12, lineHeight: 1.6, overflow: 'auto', maxHeight, border: '1px solid #eee', borderRadius: 4,
    }}>
      {rows.map((row, i) => {
        if (row.hunk !== undefined) {
          return (
            <div key={i} style={{ background: '#f1f8ff', color: '#586069', padding: '2px 12px', borderTop: '1px solid #e1e4e8', borderBottom: '1px solid #e1e4e8', whiteSpace: 'pre' }}>
              {row.hunk}
            </div>
          );
        }
        return (
          <div key={i} style={{ display: 'grid', gridTemplateColumns: '1fr 1fr' }}>
            <CodeCell cell={row.left} lang={lang} side="l" />
            <CodeCell cell={row.right} lang={lang} side="r" />
          </div>
        );
      })}
    </div>
  );
}
