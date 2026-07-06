package com.adrninistrator.javacg2.platform.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Comment;

/** 边界点表：方法触及的外部系统交互（DB/HTTP/gRPC/MQ/缓存/序列化），供调用链边界标注。 */
@Entity
@Table(name = "boundaries", indexes = {
        @Index(name = "idx_boundary_repo_method", columnList = "repo_id, full_method")
})
@Comment("边界点表：方法触及的外部系统交互（DB/HTTP/gRPC/MQ/缓存/序列化）")
public class BoundaryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_id", nullable = false)
    @Comment("所属仓库 ID")
    private Long repoId;

    @Column(name = "full_method", nullable = false, length = 1000)
    @Comment("触发该边界的方法全限定签名")
    private String fullMethod;

    @Column(name = "boundary_type", nullable = false, length = 30)
    @Comment("边界类型：DB/HTTP/GRPC/MQ/CACHE/REDIS 等")
    private String boundaryType;

    @Column(name = "line_number")
    @Comment("边界调用所在源码行号")
    private Integer lineNumber;

    @Column(columnDefinition = "TEXT")
    @Comment("边界上下文（如 SQL 语句、URL、topic 名等）")
    private String context;

    @Column(name = "callee_method", length = 1000)
    @Comment("触发边界的被调用方法全限定签名")
    private String calleeMethod;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRepoId() { return repoId; }
    public void setRepoId(Long repoId) { this.repoId = repoId; }
    public String getFullMethod() { return fullMethod; }
    public void setFullMethod(String fullMethod) { this.fullMethod = fullMethod; }
    public String getBoundaryType() { return boundaryType; }
    public void setBoundaryType(String boundaryType) { this.boundaryType = boundaryType; }
    public Integer getLineNumber() { return lineNumber; }
    public void setLineNumber(Integer lineNumber) { this.lineNumber = lineNumber; }
    public String getContext() { return context; }
    public void setContext(String context) { this.context = context; }
    public String getCalleeMethod() { return calleeMethod; }
    public void setCalleeMethod(String calleeMethod) { this.calleeMethod = calleeMethod; }
}
