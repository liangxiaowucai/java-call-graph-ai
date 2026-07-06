package com.adrninistrator.javacg2.platform.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Comment;

/**
 * 类级引用关系（来自源文件 import 解析）。
 * 补充 call_graph 无法覆盖的类型引用：方法参数类型、返回类型、字段类型等。
 * 专用于仓库拓扑图的类间连线，与方法级调用图隔离。
 */
@Entity
@Table(name = "class_reference", indexes = {
        @Index(name = "idx_classref_repo_src", columnList = "repo_id, source_class")
})
@Comment("类引用关系表：源文件 import 解析出的类间引用（参数/返回/字段类型），供仓库拓扑图连线")
public class ClassReferenceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_id", nullable = false)
    @Comment("所属仓库 ID")
    private Long repoId;

    @Column(name = "source_class", nullable = false, length = 500)
    @Comment("引用发起方类全限定名")
    private String sourceClass;

    @Column(name = "target_class", nullable = false, length = 500)
    @Comment("被引用的目标类全限定名")
    private String targetClass;

    public ClassReferenceEntity() {}

    public ClassReferenceEntity(Long repoId, String sourceClass, String targetClass) {
        this.repoId = repoId;
        this.sourceClass = sourceClass;
        this.targetClass = targetClass;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRepoId() { return repoId; }
    public void setRepoId(Long repoId) { this.repoId = repoId; }
    public String getSourceClass() { return sourceClass; }
    public void setSourceClass(String sourceClass) { this.sourceClass = sourceClass; }
    public String getTargetClass() { return targetClass; }
    public void setTargetClass(String targetClass) { this.targetClass = targetClass; }
}
