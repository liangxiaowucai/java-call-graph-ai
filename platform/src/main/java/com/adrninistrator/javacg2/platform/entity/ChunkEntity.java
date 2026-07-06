package com.adrninistrator.javacg2.platform.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Comment;

/** 方法块表：每个方法的结构化信息与搜索索引（源码位置/签名/注解/常量/异常/URL），是搜索与调用链展示的主数据。 */
@Entity
@Table(name = "chunks", indexes = {
        @Index(name = "idx_chunk_repo_method", columnList = "repo_id, full_method"),
        @Index(name = "idx_chunk_repo_class", columnList = "repo_id, class_name")
})
@Comment("方法块表：每个方法的结构化信息与搜索索引（位置/签名/注解/常量/异常/URL）")
public class ChunkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_id", nullable = false)
    @Comment("所属仓库 ID")
    private Long repoId;

    @Column(name = "full_method", nullable = false, length = 1000)
    @Comment("方法全限定签名（类名:方法名(参数)）")
    private String fullMethod;

    @Column(name = "class_name", length = 500)
    @Comment("所属类全限定名")
    private String className;

    @Column(name = "package_name", length = 500)
    @Comment("所属包名")
    private String packageName;

    @Column(name = "method_name", length = 200)
    @Comment("方法名")
    private String methodName;

    @Column(name = "file_path", length = 500)
    @Comment("源文件路径")
    private String filePath;

    @Column(name = "start_line")
    @Comment("方法起始行号")
    private Integer startLine;

    @Column(name = "end_line")
    @Comment("方法结束行号")
    private Integer endLine;

    @Column(name = "return_type", length = 200)
    @Comment("方法返回类型")
    private String returnType;

    @Column(name = "access_flags", length = 50)
    @Comment("方法访问修饰符（public/private/static 等）")
    private String accessFlags;

    @Column(columnDefinition = "TEXT")
    @Comment("方法注解列表")
    private String annotations;

    @Column(columnDefinition = "TEXT")
    @Comment("方法参数列表")
    private String parameters;

    @Column(name = "call_summary", columnDefinition = "TEXT")
    @Comment("方法调用摘要（充实后的搜索文本：常量/注释/异常/URL 等）")
    private String callSummary;

    // 干净的字符串常量（method_call_info 中 type=v 的 String 常量，换行分隔），用于调用链展示
    @Column(name = "constants", columnDefinition = "TEXT")
    @Comment("方法内字符串常量（换行分隔），用于调用链展示")
    private String constants;

    // 方法抛出/捕获的异常类型（短类名，换行分隔），用于调用链展示
    @Column(name = "exceptions", columnDefinition = "TEXT")
    @Comment("方法抛出/捕获的异常类型（短类名，换行分隔）")
    private String exceptions;

    // 解析后的外部调用 URL（field→@Value→config 数据流解析，换行分隔）
    @Column(name = "resolved_urls", columnDefinition = "TEXT")
    @Comment("解析后的外部调用 URL（@Value/config 数据流解析，换行分隔）")
    private String resolvedUrls;

    // 业务错误码+消息（Result.buildResult/throw 语句解析，JSON 数组），用于调用链展示
    @Column(name = "error_codes", columnDefinition = "TEXT")
    @Comment("业务错误码+消息（JSON 数组，来自 Result/throw 解析）")
    private String errorCodes;

    @Column(name = "jar_num")
    @Comment("所属 jar 序号（对应 jar_info.jar_num）")
    private Integer jarNum;

    @Column(name = "method_hash", length = 64)
    @Comment("方法内容哈希（用于增量/去重）")
    private String methodHash;

    @Column(name = "embedding_status", length = 10)
    @Comment("向量索引状态：null=待处理, DONE=已完成, FAILED=失败")
    private String embeddingStatus;  // null=待处理, DONE=已完成, FAILED=失败

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRepoId() { return repoId; }
    public void setRepoId(Long repoId) { this.repoId = repoId; }
    public String getFullMethod() { return fullMethod; }
    public void setFullMethod(String fullMethod) { this.fullMethod = fullMethod; }
    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }
    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }
    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public Integer getStartLine() { return startLine; }
    public void setStartLine(Integer startLine) { this.startLine = startLine; }
    public Integer getEndLine() { return endLine; }
    public void setEndLine(Integer endLine) { this.endLine = endLine; }
    public String getReturnType() { return returnType; }
    public void setReturnType(String returnType) { this.returnType = returnType; }
    public String getAccessFlags() { return accessFlags; }
    public void setAccessFlags(String accessFlags) { this.accessFlags = accessFlags; }
    public String getAnnotations() { return annotations; }
    public void setAnnotations(String annotations) { this.annotations = annotations; }
    public String getParameters() { return parameters; }
    public void setParameters(String parameters) { this.parameters = parameters; }
    public String getCallSummary() { return callSummary; }
    public void setCallSummary(String callSummary) { this.callSummary = callSummary; }
    public String getConstants() { return constants; }
    public void setConstants(String constants) { this.constants = constants; }
    public String getExceptions() { return exceptions; }
    public void setExceptions(String exceptions) { this.exceptions = exceptions; }
    public String getResolvedUrls() { return resolvedUrls; }
    public void setResolvedUrls(String resolvedUrls) { this.resolvedUrls = resolvedUrls; }
    public String getErrorCodes() { return errorCodes; }
    public void setErrorCodes(String errorCodes) { this.errorCodes = errorCodes; }
    public Integer getJarNum() { return jarNum; }
    public void setJarNum(Integer jarNum) { this.jarNum = jarNum; }
    public String getMethodHash() { return methodHash; }
    public void setMethodHash(String methodHash) { this.methodHash = methodHash; }
    public String getEmbeddingStatus() { return embeddingStatus; }
    public void setEmbeddingStatus(String embeddingStatus) { this.embeddingStatus = embeddingStatus; }
}
