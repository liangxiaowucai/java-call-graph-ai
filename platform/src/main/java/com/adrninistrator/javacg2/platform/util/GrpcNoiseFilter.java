package com.adrninistrator.javacg2.platform.util;

import java.util.Set;

/**
 * gRPC 生成代码噪点过滤工具。
 *
 * gRPC/protobuf 会生成大量与业务无关的基础设施类和方法：
 *  - XxxGrpc 工厂类（newBlockingStub/bindService/getServiceDescriptor 等）
 *  - $MethodHandlers 内部类（仅路由分发，无业务语义）
 *  - $*DescriptorSupplier 内部类（描述符注册，无业务语义）
 *  - $*ImplBase 内部类（服务端抽象基类，无业务语义）
 *  - $AsyncService 内部类（gRPC v1.39+ 异步服务接口）
 *  - $*Stub AbstractStub 基础设施方法（getChannel/getCallOptions/with*）
 *  - Proto model $Builder / OrBuilder（序列化/反序列化样板）
 *  - Proto 序列化方法（parseFrom/writeTo/mergeFrom 等，仅对 gRPC/proto 类生效）
 *  - io.grpc.* 框架运行时包
 *  - com.google.protobuf.* 框架运行时包
 *
 * 保留内容：$BlockingStub/$FutureStub/$Stub 上的真实业务 RPC 调用方法。
 *
 * 用法：
 *   GrpcNoiseFilter.isGrpcNoise(fullMethod)     // 传入完整方法签名
 *   GrpcNoiseFilter.isGrpcNoiseClass(className) // 仅检查类名
 */
public final class GrpcNoiseFilter {

    private GrpcNoiseFilter() {}

    /**
     * 严格 gRPC/proto 专属方法名——这些名称在非 gRPC 业务代码里极少出现，可对所有类安全兜底。
     * 注意：build/newBuilder/toBuilder/buildPartial 故意不在此集合，因为普通 Builder 模式也用这些名称。
     */
    private static final Set<String> STRICT_GRPC_METHOD_NAMES = Set.of(
        // Stub 工厂（只在 XxxGrpc 工厂类上出现）
        "newBlockingStub", "newFutureStub", "newStub",
        // 服务注册（只在 XxxGrpc 工厂类上出现）
        "bindService", "getServiceDescriptor",
        // Proto 描述符（只在 proto 生成类上出现）
        "getDescriptor", "getFileDescriptor",
        // Proto 序列化（只在 proto message 上出现）
        "parseFrom", "parseDelimitedFrom",
        "writeTo", "mergeFrom",
        "isInitialized", "getParserForType",
        "getDefaultInstance", "getDefaultInstanceForType"
    );

    /**
     * Proto Builder 类方法——build/newBuilder 等也会出现在业务 Builder 模式中，
     * 只对已识别为 gRPC/proto 噪点类时才过滤，不做全局兜底。
     */
    private static final Set<String> PROTO_BUILDER_METHOD_NAMES = Set.of(
        "build", "buildPartial", "newBuilder", "toBuilder"
    );

    /**
     * 判断 fullMethod（格式：com.example.XxxGrpc$BlockingStub:methodName(params)）是否是 gRPC 噪点。
     * 噪点返回 true，业务方法返回 false。
     */
    public static boolean isGrpcNoise(String fullMethod) {
        if (fullMethod == null) return false;

        // 构造方法：只过滤 gRPC 生成类的构造，不过滤普通类
        if (fullMethod.contains(":<init>(") || fullMethod.contains(":<clinit>(")) {
            return isGrpcGeneratedClass(fullMethod);
        }

        int colon = fullMethod.lastIndexOf(':');
        String className = colon > 0 ? fullMethod.substring(0, colon) : fullMethod;
        String methodName = colon > 0 ? extractMethodName(fullMethod.substring(colon + 1)) : "";

        // 整类过滤（类名本身就是噪点类）
        if (isGrpcNoiseClass(className)) return true;

        // $*Stub 上的 AbstractStub 基础设施方法（getChannel/getCallOptions/with*）
        // 业务 RPC 方法不在此列，仅过滤继承自 AbstractStub 的框架方法
        String simple = simpleClass(className);
        if (simple.contains("$")) {
            String inner = simple.substring(simple.lastIndexOf('$') + 1);
            if (inner.endsWith("Stub")
                    && ("getChannel".equals(methodName) || "getCallOptions".equals(methodName)
                        || methodName.startsWith("with"))) {
                return true;
            }
        }

        // 严格 gRPC/proto 专属方法名兜底（对所有类安全，不含 build/newBuilder 等通用 Builder 方法名）
        return STRICT_GRPC_METHOD_NAMES.contains(methodName);
    }

    /**
     * 判断类名本身是否是 gRPC 噪点类（整类过滤，无论调用什么方法）。
     * 噪点类返回 true，业务类（如 $BlockingStub）返回 false。
     */
    public static boolean isGrpcNoiseClass(String className) {
        if (className == null) return false;

        // gRPC 框架运行时包
        if (className.startsWith("io.grpc.")) return true;
        // protobuf 运行时包
        if (className.startsWith("com.google.protobuf.")) return true;

        // model/proto 包（项目约定）
        if (className.contains(".api.client.model") || className.contains(".api.grpc.model")
                || className.contains(".proto.")) {
            return true;
        }
        // Builder / OrBuilder（proto message 内部类）
        if (className.endsWith("$Builder") || className.endsWith("OrBuilder")) return true;

        String simple = simpleClass(className);

        // XxxGrpc 工厂类（含命名冲突时的 XxxGrpc1/XxxGrpc2 数字后缀变体）
        if (!simple.contains("$") && isGrpcFactoryClass(simple)) return true;

        // gRPC 生成的内部类：$MethodHandlers / $*DescriptorSupplier / $*ImplBase / $AsyncService
        if (simple.contains("$")) {
            String inner = simple.substring(simple.lastIndexOf('$') + 1);
            if ("MethodHandlers".equals(inner)
                    || inner.endsWith("DescriptorSupplier")
                    || inner.endsWith("ImplBase")
                    || "AsyncService".equals(inner)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 判断类名是否是任意 gRPC 生成类（包括 $BlockingStub 等 Stub 子类）。
     * 仅用于「构造方法 :<init> 应该过滤哪些」的判断，比 isGrpcNoiseClass 更宽松。
     */
    private static boolean isGrpcGeneratedClass(String fullMethod) {
        int colon = fullMethod.lastIndexOf(':');
        String className = colon > 0 ? fullMethod.substring(0, colon) : fullMethod;
        if (className.startsWith("io.grpc.") || className.startsWith("com.google.protobuf.")) return true;
        String simple = simpleClass(className);
        if (!simple.contains("$") && isGrpcFactoryClass(simple)) return true;
        if (simple.contains("$")) {
            String inner = simple.substring(simple.lastIndexOf('$') + 1);
            if (inner.endsWith("Stub") || "MethodHandlers".equals(inner)
                    || inner.endsWith("DescriptorSupplier") || inner.endsWith("ImplBase")
                    || "AsyncService".equals(inner)) {
                return true;
            }
        }
        return className.contains(".api.client.model") || className.contains(".proto.")
                || className.endsWith("$Builder") || className.endsWith("OrBuilder");
    }

    /**
     * 判断简单类名是否是 gRPC 工厂类（XxxGrpc 或带纯数字后缀的 XxxGrpc1、XxxGrpc2 等）。
     */
    private static boolean isGrpcFactoryClass(String simpleName) {
        if (simpleName.endsWith("Grpc")) return true;
        if (simpleName.contains("Grpc")) {
            int idx = simpleName.lastIndexOf("Grpc");
            String suffix = simpleName.substring(idx + 4);
            return !suffix.isEmpty() && suffix.chars().allMatch(Character::isDigit);
        }
        return false;
    }

    /**
     * 判断方法名本身是否是 gRPC/proto 专属基础设施方法名（不区分类，适用于宽松过滤场景）。
     * 注意：build/newBuilder 等通用 Builder 方法名不在此集合，以避免误杀业务 Builder 模式。
     */
    public static boolean isGrpcInfraMethodName(String methodName) {
        return STRICT_GRPC_METHOD_NAMES.contains(methodName)
                || PROTO_BUILDER_METHOD_NAMES.contains(methodName);
    }

    // ── 内部工具 ──────────────────────────────────────────────────────────────

    private static String simpleClass(String className) {
        if (className == null) return "";
        int dot = className.lastIndexOf('.');
        return dot >= 0 ? className.substring(dot + 1) : className;
    }

    private static String extractMethodName(String methodPart) {
        int paren = methodPart.indexOf('(');
        return paren > 0 ? methodPart.substring(0, paren) : methodPart;
    }
}

