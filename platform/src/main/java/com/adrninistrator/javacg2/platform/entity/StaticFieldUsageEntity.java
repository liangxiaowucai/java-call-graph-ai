package com.adrninistrator.javacg2.platform.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Comment;

/**
 * 方法用到的静态字段/枚举常量引用，来自 javacg2 中间文件 method_call_static_field.txt。
 * 固化到 DB，供源码面板/文档生成按方法查询常量与枚举，替代查询时直接读文件。
 */
@Entity
@Table(name = "static_field_usage", indexes = {
        @Index(name = "idx_sfu_repo", columnList = "repo_id"),
        @Index(name = "idx_sfu_repo_caller", columnList = "repo_id, caller_method")
})
@Comment("静态字段/枚举引用表：某方法用到的静态字段（含枚举常量），来自 method_call_static_field.txt")
public class StaticFieldUsageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_id", nullable = false)
    @Comment("所属仓库 ID")
    private Long repoId;

    @Column(name = "caller_method", nullable = false, length = 1000)
    @Comment("引用该字段的方法全限定签名")
    private String callerMethod;

    @Column(name = "field_class", nullable = false, length = 500)
    @Comment("被引用静态字段所属的类全限定名")
    private String fieldClass;

    @Column(name = "field_name", nullable = false, length = 255)
    @Comment("被引用的静态字段/枚举常量名")
    private String fieldName;

    @Column(name = "line_num")
    @Comment("引用发生的源码行号")
    private Integer lineNum;

    public StaticFieldUsageEntity() {}

    public StaticFieldUsageEntity(Long repoId, String callerMethod, String fieldClass,
                                  String fieldName, Integer lineNum) {
        this.repoId = repoId;
        this.callerMethod = callerMethod;
        this.fieldClass = fieldClass;
        this.fieldName = fieldName;
        this.lineNum = lineNum;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRepoId() { return repoId; }
    public void setRepoId(Long repoId) { this.repoId = repoId; }
    public String getCallerMethod() { return callerMethod; }
    public void setCallerMethod(String callerMethod) { this.callerMethod = callerMethod; }
    public String getFieldClass() { return fieldClass; }
    public void setFieldClass(String fieldClass) { this.fieldClass = fieldClass; }
    public String getFieldName() { return fieldName; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }
    public Integer getLineNum() { return lineNum; }
    public void setLineNum(Integer lineNum) { this.lineNum = lineNum; }
}
