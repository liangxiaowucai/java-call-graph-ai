package com.adrninistrator.javacg2.platform.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Comment;

/**
 * 方法返回的常量值，来自 javacg2 中间文件 method_return_const_value.txt。
 * 固化到 DB，供源码面板/文档生成按方法查询返回常量，替代查询时直接读文件。
 */
@Entity
@Table(name = "method_return_const", indexes = {
        @Index(name = "idx_mrc_repo", columnList = "repo_id"),
        @Index(name = "idx_mrc_repo_method", columnList = "repo_id, full_method")
})
@Comment("方法返回常量表：方法直接 return 的常量值，来自 method_return_const_value.txt")
public class MethodReturnConstEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_id", nullable = false)
    @Comment("所属仓库 ID")
    private Long repoId;

    @Column(name = "full_method", nullable = false, length = 1000)
    @Comment("方法全限定签名（类名:方法名(参数)）")
    private String fullMethod;

    @Column(name = "value", length = 2000)
    @Comment("方法返回的常量值")
    private String value;

    public MethodReturnConstEntity() {}

    public MethodReturnConstEntity(Long repoId, String fullMethod, String value) {
        this.repoId = repoId;
        this.fullMethod = fullMethod;
        this.value = value;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRepoId() { return repoId; }
    public void setRepoId(Long repoId) { this.repoId = repoId; }
    public String getFullMethod() { return fullMethod; }
    public void setFullMethod(String fullMethod) { this.fullMethod = fullMethod; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
