package com.adrninistrator.javacg2.platform.service;

import com.adrninistrator.javacg2.platform.entity.BoundaryEntity;
import com.adrninistrator.javacg2.platform.entity.CallGraphEntity;
import com.adrninistrator.javacg2.platform.entity.ChunkEntity;
import com.adrninistrator.javacg2.platform.entity.ApiEndpointEntity;
import com.adrninistrator.javacg2.platform.repository.BoundaryRepo;
import com.adrninistrator.javacg2.platform.repository.CallGraphRepo;
import com.adrninistrator.javacg2.platform.repository.ChunkRepo;
import com.adrninistrator.javacg2.platform.repository.ApiEndpointRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局仓库数据内存缓存。
 *
 * <p>分析完成后把每个仓库的调用图、边界、Chunk 和入口点全量加载到内存，
 * 供 CallGraphEngine / BoundaryDetector / TopologyEngine 等直接读取，
 * 避免每次接口请求都重新全表扫描数据库。
 *
 * <p>生命周期：
 * <ul>
 *   <li>分析开始：{@link #invalidate(Long)} — 清除旧缓存</li>
 *   <li>分析成功：{@link #warmup(Long)} — 重新加载</li>
 *   <li>读取：{@link #get(Long)} — 立刻返回内存数据；未命中则按需加载一次</li>
 * </ul>
 */
@Component
public class RepoDataStore {

    private static final Logger log = LoggerFactory.getLogger(RepoDataStore.class);

    private final CallGraphRepo callGraphRepo;
    private final BoundaryRepo boundaryRepo;
    private final ChunkRepo chunkRepo;
    private final ApiEndpointRepo apiEndpointRepo;

    /** repoId → 已加载的仓库数据（线程安全写，并发读） */
    private final ConcurrentHashMap<Long, RepoData> store = new ConcurrentHashMap<>();

    public RepoDataStore(CallGraphRepo callGraphRepo, BoundaryRepo boundaryRepo,
                         ChunkRepo chunkRepo, ApiEndpointRepo apiEndpointRepo) {
        this.callGraphRepo = callGraphRepo;
        this.boundaryRepo = boundaryRepo;
        this.chunkRepo = chunkRepo;
        this.apiEndpointRepo = apiEndpointRepo;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * 分析开始时清除旧缓存，确保下次 warmup 或按需加载得到最新数据。
     */
    public void invalidate(Long repoId) {
        store.remove(repoId);
        log.info("[RepoDataStore] 已清除仓库 {} 的内存缓存", repoId);
    }

    /**
     * 分析成功后主动 warmup，把数据加载进内存。
     * 同步加载（调用方在后台线程中调用即可，不阻塞 HTTP 响应）。
     */
    public void warmup(Long repoId) {
        log.info("[RepoDataStore] 开始 warmup 仓库 {} ...", repoId);
        long t = System.currentTimeMillis();
        RepoData data = load(repoId);
        store.put(repoId, data);
        log.info("[RepoDataStore] warmup 仓库 {} 完成: {}条调用边 / {}个边界 / {}个Chunk / {}个入口点, 耗时 {}ms",
                repoId,
                data.callGraphMap.values().stream().mapToInt(List::size).sum(),
                data.boundaryMap.values().stream().mapToInt(List::size).sum(),
                data.chunkMap.size(),
                data.endpointMap.size(),
                System.currentTimeMillis() - t);
    }

    /**
     * 获取仓库数据；若缓存不存在则按需加载（首次或未 warmup 时的安全兜底）。
     */
    public RepoData get(Long repoId) {
        return store.computeIfAbsent(repoId, this::load);
    }

    /**
     * 清除所有仓库缓存（如系统重置时使用）。
     */
    public void invalidateAll() {
        store.clear();
        log.info("[RepoDataStore] 已清除所有仓库内存缓存");
    }

    // ── Internal load ─────────────────────────────────────────────────────────

    private RepoData load(Long repoId) {
        // 1. 调用边：按 callerMethod 分组
        List<CallGraphEntity> allEdges = callGraphRepo.findByRepoId(repoId);
        Map<String, List<CallGraphEntity>> callGraphMap = new HashMap<>(allEdges.size() * 2);
        for (CallGraphEntity edge : allEdges) {
            callGraphMap.computeIfAbsent(edge.getCallerMethod(), k -> new ArrayList<>()).add(edge);
        }

        // 2. 边界信息：按 fullMethod 分组
        List<BoundaryEntity> allBoundaries = boundaryRepo.findByRepoId(repoId);
        Map<String, List<BoundaryEntity>> boundaryMap = new HashMap<>(allBoundaries.size() * 2);
        for (BoundaryEntity b : allBoundaries) {
            boundaryMap.computeIfAbsent(b.getFullMethod(), k -> new ArrayList<>()).add(b);
        }

        // 3. Chunk 元数据：按 fullMethod 索引（同方法取第一个）
        List<ChunkEntity> allChunks = chunkRepo.findByRepoId(repoId);
        Map<String, ChunkEntity> chunkMap = new HashMap<>(allChunks.size() * 2);
        for (ChunkEntity c : allChunks) {
            chunkMap.putIfAbsent(c.getFullMethod(), c);
        }

        // 4. 入口点：按 fullMethod 索引
        List<ApiEndpointEntity> allEndpoints = apiEndpointRepo.findByRepoId(repoId);
        Map<String, ApiEndpointEntity> endpointMap = new HashMap<>(allEndpoints.size() * 2);
        for (ApiEndpointEntity ep : allEndpoints) {
            endpointMap.put(ep.getFullMethod(), ep);
        }

        return new RepoData(callGraphMap, boundaryMap, chunkMap, endpointMap);
    }

    // ── Data holder ───────────────────────────────────────────────────────────

    /**
     * 一个仓库的全量内存数据。所有字段只读，线程安全。
     */
    public static final class RepoData {
        /** callerMethod → 直接调用边列表 */
        public final Map<String, List<CallGraphEntity>> callGraphMap;
        /** fullMethod → 外部 I/O 边界列表 */
        public final Map<String, List<BoundaryEntity>> boundaryMap;
        /** fullMethod → 方法元数据（constants/exceptions/resolvedUrls 等） */
        public final Map<String, ChunkEntity> chunkMap;
        /** fullMethod → 入口点元数据 */
        public final Map<String, ApiEndpointEntity> endpointMap;

        RepoData(Map<String, List<CallGraphEntity>> callGraphMap,
                 Map<String, List<BoundaryEntity>> boundaryMap,
                 Map<String, ChunkEntity> chunkMap,
                 Map<String, ApiEndpointEntity> endpointMap) {
            this.callGraphMap = Collections.unmodifiableMap(callGraphMap);
            this.boundaryMap  = Collections.unmodifiableMap(boundaryMap);
            this.chunkMap     = Collections.unmodifiableMap(chunkMap);
            this.endpointMap  = Collections.unmodifiableMap(endpointMap);
        }

        public List<CallGraphEntity> getCallees(String callerMethod) {
            return callGraphMap.getOrDefault(callerMethod, List.of());
        }

        public List<BoundaryEntity> getBoundaries(String fullMethod) {
            return boundaryMap.getOrDefault(fullMethod, List.of());
        }

        public ChunkEntity getChunk(String fullMethod) {
            return chunkMap.get(fullMethod);
        }

        public ApiEndpointEntity getEndpoint(String fullMethod) {
            return endpointMap.get(fullMethod);
        }
    }
}
