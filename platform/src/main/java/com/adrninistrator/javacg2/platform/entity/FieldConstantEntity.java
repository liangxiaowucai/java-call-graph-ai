package com.adrninistrator.javacg2.platform.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Comment;

/**
 * 类的 static final 常量字段，来自 javacg2 中间文件 field_info.txt。
 * 固化到 DB，供文档生成的业务数据字典使用，替代查询时直接读文件。
 */
@Entity
@Table(name = "field_constant", indexes = {
        @Index(name = "idx_fieldconst_repo", columnList = "repo_id")
})
@Comment("常量字段表：类的 static final 常量字段，来自 field_info.txt")
public class FieldConstantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_id", nullable = false)
    @Comment("所属仓库 ID")
    private Long repoId;

    @Column(name = "class_name", nullable = false, length = 500)
    @Comment("常量所属类的全限定名")
    private String className;

    @Column(name = "field_name", nullable = false, length = 255)
    @Comment("常量字段名")
    private String fieldName;

    @Column(name = "field_type", length = 500)
    @Comment("常量字段类型")
    private String fieldType;

    @Column(name = "value", length = 2000)
    @Comment("常量值（当前占位，javacg2 未提供具体值时为空）")
    private String value;

    public FieldConstantEntity() {}

    public FieldConstantEntity(Long repoId, String className, String fieldName,
                               String fieldType, String value) {
        this.repoId = repoId;
        this.className = className;
        this.fieldName = fieldName;
        this.fieldType = fieldType;
        this.value = value;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRepoId() { return repoId; }
    public void setRepoId(Long repoId) { this.repoId = repoId; }
    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }
    public String getFieldName() { return fieldName; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }
    public String getFieldType() { return fieldType; }
    public void setFieldType(String fieldType) { this.fieldType = fieldType; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
