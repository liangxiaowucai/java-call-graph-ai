/**
 * 图布局预加载服务
 * 在用户打开仓库拓扑之前，后台提前计算 d3-force 布局坐标并缓存到内存。
 * 用户打开拓扑时直接读内存坐标，G6 跳过 layout 直接渲染，无需等待。
 */
import { fetchFileTree } from '../api';

/** 内存缓存：repoKey → 节点坐标 map */
const positionCache = new Map<string, Record<string, { x: number; y: number }>>();
/** 正在计算的 repoKey，防重复 */
const computing = new Set<string>();

/** repo 的缓存 key（带 lastSyncTime，重新分析后自动失效） */
export function layoutCacheKey(repoId: number, lastSyncTime: string | undefined): string {
  return `graph_layout_v1_${repoId}_${lastSyncTime ?? ''}`;
}

/** 从内存获取坐标（已经算好就立刻返回） */
export function getPositions(key: string): Record<string, { x: number; y: number }> | null {
  return positionCache.get(key) ?? null;
}

/** 主入口：预加载指定仓库的布局坐标（幂等，重复调用只算一次） */
export async function preloadLayout(
  repoId: number,
  lastSyncTime: string | undefined,
  containerWidth = 1200,
  containerHeight = 700,
): Promise<void> {
  const key = layoutCacheKey(repoId, lastSyncTime);

  // 内存里已有 → 无需重算
  if (positionCache.has(key)) return;
  // localStorage 里有 → 读进内存
  try {
    const raw = localStorage.getItem(key);
    if (raw) {
      const cached = JSON.parse(raw);
      if (cached && Object.keys(cached).length > 10) {
        positionCache.set(key, cached);
        return;
      }
    }
  } catch { /* */ }

  // 已在计算中 → 跳过
  if (computing.has(key)) return;
  computing.add(key);

  try {
    // 后台拉节点数据（class-edges 比 file-tree 小，只用它推算节点列表）
    const [treeItems] = await Promise.all([
      fetchFileTree(repoId),
    ]);
    if (!treeItems.length) { computing.delete(key); return; }

    // 启动 Worker 算布局
    const worker = new Worker(new URL('../workers/layoutWorker.ts', import.meta.url), { type: 'module' });
    const nodes = treeItems.map(item => ({ id: item.className }));

    await new Promise<void>((resolve) => {
      worker.onmessage = (e) => {
        const { type, positions } = e.data;
        if (type === 'done' && Array.isArray(positions)) {
          const map: Record<string, { x: number; y: number }> = {};
          positions.forEach((p: { id: string; x: number; y: number }) => { map[p.id] = { x: p.x, y: p.y }; });
          positionCache.set(key, map);
          // 同时写 localStorage，持久化
          try { localStorage.setItem(key, JSON.stringify(map)); } catch { /* */ }
        }
        worker.terminate();
        resolve();
      };
      worker.onerror = () => { worker.terminate(); resolve(); };
      worker.postMessage({ nodes, width: containerWidth, height: containerHeight });
    });
  } catch { /* 预加载失败不影响主流程 */ } finally {
    computing.delete(key);
  }
}
