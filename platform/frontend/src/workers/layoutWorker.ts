/**
 * Graph layout Web Worker
 * 在独立线程里跑 d3-force，不阻塞主线程。
 * 主线程发送 { nodes, width, height }，Worker 返回 { positions: [{id, x, y}] }
 */
import { D3ForceLayout } from '@antv/layout';

self.onmessage = async (e: MessageEvent) => {
  const { nodes, width, height } = e.data as {
    nodes: { id: string }[];
    width: number;
    height: number;
  };

  const cx = width / 2;
  const cy = height / 2;

  // 给节点随机初始位置（Worker 里没有 Math.random 限制）
  const layoutNodes = nodes.map(n => ({
    id: n.id,
    data: {},
    style: {
      x: cx + (Math.random() - 0.5) * width * 0.8,
      y: cy + (Math.random() - 0.5) * height * 0.8,
    },
  }));

  const layout = new D3ForceLayout({
    width,
    height,
    center: { x: cx, y: cy, strength: 0.15 },
    charge: { strength: -60 },
    collide: { radius: 12 },
    x: { strength: 0.08 },
    y: { strength: 0.08 },
  });

  try {
    const result = await layout.execute({ nodes: layoutNodes, edges: [] });
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const resultNodes = (result as any)?.nodes ?? [];
    const positions = resultNodes.map((n: any) => ({
      id: n.id,
      x: n.style?.x ?? cx,
      y: n.style?.y ?? cy,
    }));
    self.postMessage({ type: 'done', positions });
  } catch (err: any) {
    self.postMessage({ type: 'error', message: err?.message ?? 'layout failed' });
  }
};
