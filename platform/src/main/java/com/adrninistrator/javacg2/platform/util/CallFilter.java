package com.adrninistrator.javacg2.platform.util;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 调用图/类引用的公共过滤规则。
 *
 * 集中管理散落在各 Service 里的过滤逻辑：
 *  - 继承/实现关系判断（EXTENDS/IMPLEMENTS）
 *  - 样板方法判断（构造器、getter/setter、equals/hashCode/toString、gRPC 噪点）
 *  - 业务组织根推导（从包前缀推出 com.example 这类根包）
 *  - 是否属于业务代码（在组织根内，非第三方框架）
 *
 * gRPC/proto 专属噪点仍由 {@link GrpcNoiseFilter} 负责，此类聚焦通用调用过滤。
 */
public final class CallFilter {

    private CallFilter() {}

    // ── 调用类型 ──────────────────────────────────────────────────────────────

    /** 是否是继承/实现关系边（这些不是真实方法调用，遍历调用链时应跳过） */
    public static boolean isInheritanceEdge(String callType) {
        return "EXTENDS".equals(callType) || "IMPLEMENTS".equals(callType);
    }

    // ── 样板方法 ──────────────────────────────────────────────────────────────

    /**
     * 判断是否是样板方法（无业务价值）：构造器、类初始化、getter/setter、
     * equals/hashCode/toString、gRPC 噪点。
     *
     * 仅按签名特征识别真正的访问器，避免误杀 getDetail(Long)/getById(Long) 这类业务方法：
     * 真正的 getter/is 是无参 getXxx()/isXxx()，真正的 setter 是单参 setXxx(one)。
     */
    public static boolean isBoilerplateMethod(String fullMethod) {
        if (fullMethod == null) return false;
        if (fullMethod.contains(":<init>(") || fullMethod.contains(":<clinit>(")) return true;

        String methodName = extractMethodName(fullMethod);
        if ("equals".equals(methodName) || "hashCode".equals(methodName) || "toString".equals(methodName)) return true;

        if (GrpcNoiseFilter.isGrpcNoise(fullMethod)) return true;

        String params = extractParams(fullMethod);
        if (params == null) return false;
        boolean noArg = params.isEmpty();
        boolean singleArg = !noArg && !params.contains(",");
        if (noArg && (methodName.startsWith("get") || methodName.startsWith("is"))) return true;
        if (singleArg && methodName.startsWith("set")) return true;
        return false;
    }

    // ── 业务代码范围 ──────────────────────────────────────────────────────────

    /**
     * 从包前缀推导业务组织根包（取前两段）。
     * 如 [com.example.goods, com.example.order] → [com.example]
     * 用于只保留自家业务代码的跨模块依赖，排除所有第三方库。
     */
    public static Set<String> deriveOrgRoots(List<String> prefixes) {
        Set<String> roots = new HashSet<>();
        if (prefixes == null) return roots;
        for (String p : prefixes) {
            String[] parts = p.split("\\.");
            if (parts.length >= 2) roots.add(parts[0] + "." + parts[1]);
            else if (parts.length == 1) roots.add(parts[0]);
        }
        return roots;
    }

    /**
     * 判断类是否属于业务代码：在组织根内（如 com.example.*）。
     * orgRoots 为空时不做限制（返回 true）。
     */
    public static boolean isBusinessClass(String className, Set<String> orgRoots) {
        if (className == null) return false;
        if (orgRoots == null || orgRoots.isEmpty()) return true;
        return orgRoots.stream().anyMatch(className::startsWith);
    }

    /**
     * 判断是否是纯数据载体类（DTO/Request/Response/VO/PO/Bean/Entity/Param/Enum 等）。
     * 拓扑图关注组件依赖结构（Service/Controller/Mapper 等），数据载体是噪点应过滤。
     */
    public static boolean isDataCarrierClass(String className) {
        if (className == null) return true;
        // 内部类（$）一律视为数据载体（嵌套的 Response/Request 结构）
        if (className.contains("$")) return true;

        String simple = className.contains(".") ? className.substring(className.lastIndexOf('.') + 1) : className;
        // 按类名后缀识别数据载体
        if (simple.endsWith("Request") || simple.endsWith("Response") || simple.endsWith("DTO")
            || simple.endsWith("Dto") || simple.endsWith("VO") || simple.endsWith("Vo")
            || simple.endsWith("PO") || simple.endsWith("Po") || simple.endsWith("BO") || simple.endsWith("Bo")
            || simple.endsWith("Bean") || simple.endsWith("Entity") || simple.endsWith("Param")
            || simple.endsWith("Info") || simple.endsWith("Detail") || simple.endsWith("Result")
            || simple.endsWith("Enum") || simple.endsWith("Constant") || simple.endsWith("Constants")
            || simple.endsWith("Model") || simple.endsWith("Data") || simple.endsWith("Context")) {
            return true;
        }
        // 按包名识别数据载体
        String lower = className.toLowerCase();
        return lower.contains(".dto.") || lower.contains(".bean.") || lower.contains(".entity.")
            || lower.contains(".vo.") || lower.contains(".po.") || lower.contains(".model.")
            || lower.contains(".request.") || lower.contains(".response.") || lower.contains(".em.")
            || lower.contains(".enums.") || lower.contains(".constant.");
    }

    /**
     * 判断是否是结构性组件类（Service/Controller/Mapper/Repository/Remote/Dao/Component/Manager）。
     * 这些是拓扑图应该展示的架构节点。
     */
    public static boolean isStructuralClass(String className) {
        if (className == null) return false;
        String simple = className.contains(".") ? className.substring(className.lastIndexOf('.') + 1) : className;
        return simple.endsWith("Controller") || simple.endsWith("Service") || simple.endsWith("ServiceImpl")
            || simple.endsWith("Mapper") || simple.endsWith("Repository") || simple.endsWith("Dao")
            || simple.endsWith("Component") || simple.endsWith("Manager") || simple.endsWith("Handler")
            || simple.endsWith("Client") || simple.endsWith("Remote") || simple.endsWith("Filter")
            || simple.endsWith("Interceptor") || simple.endsWith("Aspect") || simple.endsWith("Config")
            || simple.endsWith("Listener") || simple.endsWith("Consumer") || simple.endsWith("Producer")
            || simple.endsWith("Util") || simple.endsWith("Utils") || simple.endsWith("Helper");
    }

    // ── 内部工具 ──────────────────────────────────────────────────────────────

    /** 从完整方法签名提取方法名 */
    public static String extractMethodName(String fullMethod) {
        if (fullMethod == null) return "";
        int colon = fullMethod.lastIndexOf(':');
        String methodPart = colon >= 0 ? fullMethod.substring(colon + 1) : fullMethod;
        int paren = methodPart.indexOf('(');
        return paren > 0 ? methodPart.substring(0, paren) : methodPart;
    }

    /** 从完整方法签名提取类名 */
    public static String extractClassName(String fullMethod) {
        if (fullMethod == null) return "";
        int colon = fullMethod.lastIndexOf(':');
        return colon > 0 ? fullMethod.substring(0, colon) : fullMethod;
    }

    /** 提取方法签名括号内的入参字符串；无括号返回 null，无参返回 "" */
    private static String extractParams(String fullMethod) {
        int open = fullMethod.indexOf('(');
        int close = fullMethod.lastIndexOf(')');
        if (open < 0 || close < open) return null;
        return fullMethod.substring(open + 1, close).trim();
    }
}
