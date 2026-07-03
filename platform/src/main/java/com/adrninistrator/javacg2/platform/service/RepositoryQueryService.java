package com.adrninistrator.javacg2.platform.service;

import com.adrninistrator.javacg2.platform.entity.CallGraphEntity;
import com.adrninistrator.javacg2.platform.entity.ChunkEntity;
import com.adrninistrator.javacg2.platform.entity.JarInfoEntity;
import com.adrninistrator.javacg2.platform.entity.RepositoryEntity;
import com.adrninistrator.javacg2.platform.repository.JarInfoRepo;
import com.adrninistrator.javacg2.platform.repository.RepositoryRepo;
import com.adrninistrator.javacg2.platform.util.GrpcNoiseFilter;
import com.adrninistrator.javacg2.platform.util.CallFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 仓库数据查询 Service。
 * 承接原本散落在 RepositoryController 里的 class-edges / file-tree 业务逻辑，
 * 使 Controller 只做参数校验与响应封装。
 */
@Service
public class RepositoryQueryService {

    private static final Logger log = LoggerFactory.getLogger(RepositoryQueryService.class);

    private final RepoDataStore repoDataStore;
    private final RepositoryRepo repositoryRepo;
    private final JarInfoRepo jarInfoRepo;

    public RepositoryQueryService(RepoDataStore repoDataStore, RepositoryRepo repositoryRepo,
                                  JarInfoRepo jarInfoRepo) {
        this.repoDataStore = repoDataStore;
        this.repositoryRepo = repositoryRepo;
        this.jarInfoRepo = jarInfoRepo;
    }

    /**
     * 获取仓库的类间调用关系（用于文件关系图的边）。
     */
    public List<Map<String, String>> getClassEdges(Long repoId) {
        RepoDataStore.RepoData cache = repoDataStore.get(repoId);
        List<String> prefixes = repoDataStore.getPackagePrefixes(repoId);
        Set<String> orgRoots = CallFilter.deriveOrgRoots(prefixes);

        Set<String> seen = new HashSet<>();
        List<Map<String, String>> edges = new ArrayList<>();

        for (List<CallGraphEntity> edgeList : cache.callGraphMap().values()) {
            for (CallGraphEntity cg : edgeList) {
                if (cg.getEnabled() == null || !cg.getEnabled()) continue;
                if (CallFilter.isInheritanceEdge(cg.getCallType())) continue;

                String callerFull = cg.getCallerMethod();
                String calleeFull = cg.getCalleeMethod();
                int c1 = callerFull.lastIndexOf(':');
                int c2 = calleeFull.lastIndexOf(':');
                if (c1 <= 0 || c2 <= 0) continue;

                String callerClass = callerFull.substring(0, c1);
                String calleeClass = calleeFull.substring(0, c2);

                if (callerClass.equals(calleeClass)) continue;
                if (callerClass.matches(".*\\$\\d+$") || calleeClass.matches(".*\\$\\d+$")) continue;

                // 调用方必须在业务包前缀内
                if (!prefixes.isEmpty() && prefixes.stream().noneMatch(callerClass::startsWith)) continue;
                // 被调用方：只保留业务组织根下的类（com.example.*），过滤所有第三方
                if (!CallFilter.isBusinessClass(calleeClass, orgRoots)) continue;

                if (GrpcNoiseFilter.isGrpcNoiseClass(callerClass)) continue;
                if (GrpcNoiseFilter.isGrpcNoiseClass(calleeClass)) continue;

                String key = callerClass + ">" + calleeClass;
                if (seen.add(key)) {
                    edges.add(Map.of("source", callerClass, "target", calleeClass, "type", "call"));
                }
            }
        }
        // 补充 import 类型引用边（从 RepoDataStore 缓存的 classRefMap 读取，分析时已入库）
        // 只保留业务组织根包下的类（如 com.example.*），过滤所有第三方/框架依赖
        cache.classRefMap().forEach((src, targets) -> {
            if (!prefixes.isEmpty() && prefixes.stream().noneMatch(src::startsWith)) return;
            // 跳过以数据载体为「源」的引用（DTO→其他，属噪点）；但保留 X→DTO，以便展示某类依赖的实体
            if (CallFilter.isDataCarrierClass(src)) return;
            for (String tgt : targets) {
                if (src.equals(tgt)) continue;
                if (!CallFilter.isBusinessClass(tgt, orgRoots)) continue;
                if (GrpcNoiseFilter.isGrpcNoiseClass(tgt)) continue;
                String key = src + ">" + tgt;
                if (seen.add(key)) {
                    edges.add(Map.of("source", src, "target", tgt, "type", "import"));
                }
            }
        });

        return edges;
    }

    /**
     * 获取仓库的类/文件树结构（从 RepoDataStore chunkMap 聚合，只返回业务代码）。
     */
    public List<Map<String, Object>> getFileTree(Long repoId) {
        RepoDataStore.RepoData cache = repoDataStore.get(repoId);
        List<String> packagePrefixes = repoDataStore.getPackagePrefixes(repoId);

        // 从 jar_info 表读取真实 jar 名称（分析阶段已固化到 DB）
        Map<Integer, String> jarNameMap = loadJarNameMap(repoId);

        Map<String, Map<String, Object>> classMap = new LinkedHashMap<>();
        for (ChunkEntity chunk : cache.chunkMap().values()) {
            String cn = chunk.getClassName();
            if (cn == null || cn.isBlank()) continue;

            if (!packagePrefixes.isEmpty() && packagePrefixes.stream().noneMatch(cn::startsWith)) continue;
            if (cn.matches(".*\\$\\d+$")) continue;
            if (cn.contains("$")) continue;
            if (GrpcNoiseFilter.isGrpcNoiseClass(cn)) continue;

            String pkg = chunk.getPackageName() != null ? chunk.getPackageName() : "";
            if (pkg.contains(".api.client.model") || pkg.contains(".api.client.service")
                    || pkg.contains(".api.grpc.model") || pkg.contains(".proto.")) continue;

            String uniqueKey = cn + "#" + (chunk.getJarNum() != null ? chunk.getJarNum() : 0);
            final String finalPkg = pkg;
            final Integer jarNum = chunk.getJarNum();
            final String jarName = jarNameMap.getOrDefault(jarNum != null ? jarNum : 0,
                    "module-" + (jarNum != null ? jarNum : 0));

            classMap.compute(uniqueKey, (k, v) -> {
                if (v == null) {
                    v = new LinkedHashMap<>();
                    v.put("className", cn);
                    v.put("packageName", finalPkg);
                    v.put("filePath", chunk.getFilePath());
                    v.put("jarNum", jarNum != null ? jarNum : 0);
                    v.put("jarName", jarName);
                    v.put("methodCount", 0);
                }
                v.put("methodCount", (int) v.get("methodCount") + 1);
                return v;
            });
        }
        return new ArrayList<>(classMap.values());
    }

    // ── 内部工具 ──────────────────────────────────────────────────────────────

    /**
     * 从 jar_info 表构建 jarNum → 显示名称 的映射（分析阶段已固化，不再读中间文件）。
     */
    private Map<Integer, String> loadJarNameMap(Long repoId) {
        Map<Integer, String> result = new HashMap<>();
        for (JarInfoEntity e : jarInfoRepo.findByRepoId(repoId)) {
            if (e.getJarNum() != null && e.getJarName() != null) {
                result.put(e.getJarNum(), e.getJarName());
            }
        }
        return result;
    }
}
