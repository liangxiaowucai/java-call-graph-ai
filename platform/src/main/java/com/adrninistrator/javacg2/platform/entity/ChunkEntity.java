package com.adrninistrator.javacg2.platform.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "chunks", indexes = {
        @Index(name = "idx_chunk_repo_method", columnList = "repo_id, full_method"),
        @Index(name = "idx_chunk_repo_class", columnList = "repo_id, class_name")
})
public class ChunkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_id", nullable = false)
    private Long repoId;

    @Column(name = "full_method", nullable = false, length = 1000)
    private String fullMethod;

    @Column(name = "class_name", length = 500)
    private String className;

    @Column(name = "package_name", length = 500)
    private String packageName;

    @Column(name = "method_name", length = 200)
    private String methodName;

    @Column(name = "file_path", length = 500)
    private String filePath;

    @Column(name = "start_line")
    private Integer startLine;

    @Column(name = "end_line")
    private Integer endLine;

    @Column(name = "return_type", length = 200)
    private String returnType;

    @Column(name = "access_flags", length = 50)
    private String accessFlags;

    @Column(columnDefinition = "TEXT")
    private String annotations;

    @Column(columnDefinition = "TEXT")
    private String parameters;

    @Column(name = "call_summary", columnDefinition = "TEXT")
    private String callSummary;

    @Column(name = "jar_num")
    private Integer jarNum;

    @Column(name = "method_hash", length = 64)
    private String methodHash;

    @Column(name = "embedding_status", length = 10)
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
    public Integer getJarNum() { return jarNum; }
    public void setJarNum(Integer jarNum) { this.jarNum = jarNum; }
    public String getMethodHash() { return methodHash; }
    public void setMethodHash(String methodHash) { this.methodHash = methodHash; }
    public String getEmbeddingStatus() { return embeddingStatus; }
    public void setEmbeddingStatus(String embeddingStatus) { this.embeddingStatus = embeddingStatus; }
}
