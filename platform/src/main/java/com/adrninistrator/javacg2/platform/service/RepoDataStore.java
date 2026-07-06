package com.adrninistrator.javacg2.platform.service;

import com.adrninistrator.javacg2.platform.entity.BoundaryEntity;
import com.adrninistrator.javacg2.platform.entity.CallGraphEntity;
import com.adrninistrator.javacg2.platform.entity.ChunkEntity;
import com.adrninistrator.javacg2.platform.entity.ApiEndpointEntity;
import com.adrninistrator.javacg2.platform.repository.BoundaryRepo;
import com.adrninistrator.javacg2.platform.repository.CallGraphRepo;
import com.adrninistrator.javacg2.platform.repository.ChunkRepo;
import com.adrninistrator.javacg2.platform.repository.ApiEndpointRepo;
import com.adrninistrator.javacg2.platform.repository.ClassReferenceRepo;
import com.adrninistrator.javacg2.platform.repository.RepoConfigRepo;
import com.adrninistrator.javacg2.platform.entity.ClassReferenceEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局仓库数据内存缓存（分级/分片懒加载）。
 *
 * <p>不再一次性把整仓库全部数据载入内存，而是把数据切成 5 个独立分片，
 * 各自按需懒加载、各自缓存：
 * <ul>
 *   <li>callGraph（含正向 callGraphMap + 反向 calleeIndex，同一次查询构建）</li>
 *   <li>boundary（边界点）</li>
 *   <li>chunk（方法元数据）</li>
 *   <li>endpoint（入口点）</li>
 *   <li>classRef（类引用/import 关系）</li>
 * </ul>
 * 例如「仓库拓扑」只用到 callGraph + chunk + classRef，就不会加载 boundary / endpoint。
 *
 * <p>生命周期：
 * <ul>
 *   <li>分析开始：{@link #invalidate(Long)} 清除该仓库所有分片缓存</li>
 *   <li>分析成功 / 启动预热：{@link #warmup(Long)} 预载常用分片（拓扑相关）</li>
 *   <li>读取：{@link #get(Long)} 返回轻量视图，分片在首次访问时才加载</li>
 * </ul>
 */
@Component
public class RepoDataStore {

    private static final Logger log = LoggerFactory.getLogger(RepoDataStore.class);

    private final CallGraphRepo callGraphRepo;
    private final BoundaryRepo boundaryRepo;
    private final ChunkRepo chunkRepo;
    private final ApiEndpointRepo apiEndpointRepo;
    private final RepoConfigRepo repoConfigRepo;
    private final ClassReferenceRepo classReferenceRepo;

    // ── 分片缓存（各自独立懒加载）─────────────────────────────────────────────
    private final ConcurrentHashMap<Long, CallGraphSlice> callGraphCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Map<String, List<BoundaryEntity>>> boundaryCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Map<String, ChunkEntity>> chunkCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Map<String, ApiEndpointEntity>> endpointCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Map<String, List<String>>> classRefCache = new ConcurrentHashMap<>();

    /** 包前缀缓存：repoId → 解析好的前缀列表，避免每次请求都打 DB。 */
    private final ConcurrentHashMap<Long, List<String>> packagePrefixCache = new ConcurrentHashMap<>();

    public RepoDataStore(CallGraphRepo callGraphRepo, BoundaryRepo boundaryRepo,
                         ChunkRepo chunkRepo, ApiEndpointRepo apiEndpointRepo,
                         RepoConfigRepo repoConfigRepo, ClassReferenceRepo classReferenceRepo) {
        this.callGraphRepo = callGraphRepo;
        this.boundaryRepo = boundaryRepo;
        this.chunkRepo = chunkRepo;
        this.apiEndpointRepo = apiEndpointRepo;
        this.repoConfigRepo = repoConfigRepo;
        this.classReferenceRepo = classReferenceRepo;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** 分析开始时清除该仓库所有分片缓存，确保下次加载得到最新数据。 */
    public void invalidate(Long repoId) {
        callGraphCache.remove(repoId);
        boundaryCache.remove(repoId);
        chunkCache.remove(repoId);
        endpointCache.remove(repoId);
        classRefCache.remove(repoId);
        packagePrefixCache.remove(repoId);
    }

    /**
     * 预热常用分片（拓扑/目录用到的 callGraph + chunk + classRef）到内存。
     * 在后台线程调用，避免首次请求在请求线程内同步加载造成卡顿。
     * boundary / endpoint 仍保持懒加载（调用链/搜索首次使用时再载）。
     */
    public void warmup(Long repoId) {
        getCallGraphMap(repoId);
        getChunkMap(repoId);
        getClassRefMap(repoId);
    }

    /** 返回轻量视图；各分片在首次访问对应方法时才加载。 */
    public RepoData get(Long repoId) {
        return new RepoData(this, repoId);
    }

    // ── 分片访问器（懒加载 + 独立缓存）──────────────────────────────────────────

    public Map<String, List<CallGraphEntity>> getCallGraphMap(Long repoId) {
        return callGraphSlice(repoId).callGraphMap;
    }

    public Map<String, List<CallGraphEntity>> getCalleeIndex(Long repoId) {
        return callGraphSlice(repoId).calleeIndex;
    }

    private CallGraphSlice callGraphSlice(Long repoId) {
        return callGraphCache.computeIfAbsent(repoId, this::loadCallGraph);
    }

    public Map<String, List<BoundaryEntity>> getBoundaryMap(Long repoId) {
        return boundaryCache.computeIfAbsent(repoId, this::loadBoundary);
    }

    public Map<String, ChunkEntity> getChunkMap(Long repoId) {
        return chunkCache.computeIfAbsent(repoId, this::loadChunk);
    }

    public Map<String, ApiEndpointEntity> getEndpointMap(Long repoId) {
        return endpointCache.computeIfAbsent(repoId, this::loadEndpoint);
    }

    public Map<String, List<String>> getClassRefMap(Long repoId) {
        return classRefCache.computeIfAbsent(repoId, this::loadClassRef);
    }

    /** 仓库的包前缀配置（带缓存）。 */
    public List<String> getPackagePrefixes(Long repoId) {
        return packagePrefixCache.computeIfAbsent(repoId, this::loadPackagePrefixes);
    }

    public void invalidatePackagePrefix(Long repoId) {
        packagePrefixCache.remove(repoId);
    }

    /** 清除所有仓库缓存。 */
    public void invalidateAll() {
        callGraphCache.clear();
        boundaryCache.clear();
        chunkCache.clear();
        endpointCache.clear();
        classRefCache.clear();
        packagePrefixCache.clear();
        log.info("[RepoDataStore] 已清除所有仓库内存缓存");
    }

    // ── 分片加载（各自计时日志）─────────────────────────────────────────────────

    private CallGraphSlice loadCallGraph(Long repoId) {
        long t = System.currentTimeMillis();
        List<CallGraphEntity> allEdges = callGraphRepo.findByRepoId(repoId);
        Map<String, List<CallGraphEntity>> callGraphMap = new HashMap<>(allEdges.size() * 2);
        Map<String, List<CallGraphEntity>> calleeIndex  = new HashMap<>(allEdges.size() * 2);
        for (CallGraphEntity edge : allEdges) {
            callGraphMap.computeIfAbsent(edge.getCallerMethod(), k -> new ArrayList<>()).add(edge);
            calleeIndex .computeIfAbsent(edge.getCalleeMethod(), k -> new ArrayList<>()).add(edge);
        }
        log.info("[RepoDataStore] 加载仓库 {} 调用图分片: {} 条边, 耗时 {}ms",
                repoId, allEdges.size(), System.currentTimeMillis() - t);
        return new CallGraphSlice(
                Collections.unmodifiableMap(callGraphMap),
                Collections.unmodifiableMap(calleeIndex));
    }

    private Map<String, List<BoundaryEntity>> loadBoundary(Long repoId) {
        long t = System.currentTimeMillis();
        List<BoundaryEntity> all = boundaryRepo.findByRepoId(repoId);
        Map<String, List<BoundaryEntity>> map = new HashMap<>(all.size() * 2);
        for (BoundaryEntity b : all) {
            map.computeIfAbsent(b.getFullMethod(), k -> new ArrayList<>()).add(b);
        }
        log.info("[RepoDataStore] 加载仓库 {} 边界分片: {} 个, 耗时 {}ms",
                repoId, all.size(), System.currentTimeMillis() - t);
        return Collections.unmodifiableMap(map);
    }

    private Map<String, ChunkEntity> loadChunk(Long repoId) {
        long t = System.currentTimeMillis();
        List<ChunkEntity> all = chunkRepo.findByRepoId(repoId);
        Map<String, ChunkEntity> map = new HashMap<>(all.size() * 2);
        for (ChunkEntity c : all) {
            map.putIfAbsent(c.getFullMethod(), c);
        }
        log.info("[RepoDataStore] 加载仓库 {} Chunk分片: {} 个, 耗时 {}ms",
                repoId, all.size(), System.currentTimeMillis() - t);
        return Collections.unmodifiableMap(map);
    }

    private Map<String, ApiEndpointEntity> loadEndpoint(Long repoId) {
        long t = System.currentTimeMillis();
        List<ApiEndpointEntity> all = apiEndpointRepo.findByRepoId(repoId);
        Map<String, ApiEndpointEntity> map = new HashMap<>(all.size() * 2);
        for (ApiEndpointEntity ep : all) {
            map.put(ep.getFullMethod(), ep);
        }
        log.info("[RepoDataStore] 加载仓库 {} 入口分片: {} 个, 耗时 {}ms",
                repoId, all.size(), System.currentTimeMillis() - t);
        return Collections.unmodifiableMap(map);
    }

    private Map<String, List<String>> loadClassRef(Long repoId) {
        long t = System.currentTimeMillis();
        List<ClassReferenceEntity> all = classReferenceRepo.findByRepoId(repoId);
        Map<String, List<String>> map = new HashMap<>(all.size() * 2);
        for (ClassReferenceEntity ref : all) {
            map.computeIfAbsent(ref.getSourceClass(), k -> new ArrayList<>()).add(ref.getTargetClass());
        }
        log.info("[RepoDataStore] 加载仓库 {} 类引用分片: {} 条, 耗时 {}ms",
                repoId, all.size(), System.currentTimeMillis() - t);
        return Collections.unmodifiableMap(map);
    }

    private List<String> loadPackagePrefixes(Long repoId) {
        String raw = repoConfigRepo.findByRepoIdAndConfigKey(repoId, "analyze.package.prefix")
                .map(c -> c.getConfigValue())
                .filter(s -> s != null && !s.isBlank())
                .orElse(null);
        List<String> prefixes = new ArrayList<>();
        if (raw != null) {
            for (String p : raw.split("[,;\\s]+")) {
                String trimmed = p.trim();
                if (!trimmed.isEmpty()) prefixes.add(trimmed);
            }
        }
        return Collections.unmodifiableList(prefixes);
    }

    // ── 内部分片数据 ────────────────────────────────────────────────────────────

    /** 调用图分片：正向 + 反向索引一次查询构建。 */
    private record CallGraphSlice(Map<String, List<CallGraphEntity>> callGraphMap,
                                  Map<String, List<CallGraphEntity>> calleeIndex) {}

    // ── 轻量视图 ───────────────────────────────────────────────────────────────

    /**
     * 仓库数据的轻量视图。各分片在首次访问对应方法时才从 {@link RepoDataStore} 懒加载并缓存。
     * 线程安全、只读。
     */
    public static final class RepoData {
        private final RepoDataStore store;
        private final Long repoId;

        RepoData(RepoDataStore store, Long repoId) {
            this.store = store;
            this.repoId = repoId;
        }

        /** callerMethod → 直接调用边列表（正向索引） */
        public Map<String, List<CallGraphEntity>> callGraphMap() { return store.getCallGraphMap(repoId); }
        /** calleeMethod → 被调用边列表（反向索引） */
        public Map<String, List<CallGraphEntity>> calleeIndex() { return store.getCalleeIndex(repoId); }
        /** fullMethod → 外部 I/O 边界列表 */
        public Map<String, List<BoundaryEntity>> boundaryMap() { return store.getBoundaryMap(repoId); }
        /** fullMethod → 方法元数据 */
        public Map<String, ChunkEntity> chunkMap() { return store.getChunkMap(repoId); }
        /** fullMethod → 入口点元数据 */
        public Map<String, ApiEndpointEntity> endpointMap() { return store.getEndpointMap(repoId); }
        /** sourceClass → 引用的目标类列表 */
        public Map<String, List<String>> classRefMap() { return store.getClassRefMap(repoId); }

        public List<CallGraphEntity> getCallees(String callerMethod) {
            return callGraphMap().getOrDefault(callerMethod, List.of());
        }

        /** 反向查询：谁调用了 calleeMethod */
        public List<CallGraphEntity> getCallers(String calleeMethod) {
            return calleeIndex().getOrDefault(calleeMethod, List.of());
        }

        public List<BoundaryEntity> getBoundaries(String fullMethod) {
            return boundaryMap().getOrDefault(fullMethod, List.of());
        }

        public ChunkEntity getChunk(String fullMethod) {
            return chunkMap().get(fullMethod);
        }

        public ApiEndpointEntity getEndpoint(String fullMethod) {
            return endpointMap().get(fullMethod);
        }
    }
}
