package com.adrninistrator.javacg2.platform.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Comment;

/**
 * 枚举常量定义（已聚合 code/description），来自 javacg2 中间文件 enum_init_assign_info.txt。
 * 固化到 DB，供源码面板与文档生成展示枚举，替代查询时直接读文件。
 */
@Entity
@Table(name = "enum_constant", indexes = {
        @Index(name = "idx_enumconst_repo", columnList = "repo_id"),
        @Index(name = "idx_enumconst_repo_class", columnList = "repo_id, enum_class")
})
@Comment("枚举常量定义表：聚合后的枚举 code/描述，来自 enum_init_assign_info.txt")
public class EnumConstantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_id", nullable = false)
    @Comment("所属仓库 ID")
    private Long repoId;

    @Column(name = "enum_class", nullable = false, length = 500)
    @Comment("枚举类全限定名")
    private String enumClass;

    @Column(name = "const_name", nullable = false, length = 255)
    @Comment("枚举常量名（如 PAID、REFUNDED）")
    private String constName;

    @Column(name = "ordinal", length = 50)
    @Comment("枚举序号（当无业务 code 时回退使用）")
    private String ordinal;

    @Column(name = "code", length = 255)
    @Comment("业务 code 值（取首个整型构造参数）")
    private String code;

    @Column(name = "description", length = 1000)
    @Comment("中文描述（取首个长度>=2 的字符串构造参数）")
    private String description;

    public EnumConstantEntity() {}

    public EnumConstantEntity(Long repoId, String enumClass, String constName,
                              String ordinal, String code, String description) {
        this.repoId = repoId;
        this.enumClass = enumClass;
        this.constName = constName;
        this.ordinal = ordinal;
        this.code = code;
        this.description = description;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRepoId() { return repoId; }
    public void setRepoId(Long repoId) { this.repoId = repoId; }
    public String getEnumClass() { return enumClass; }
    public void setEnumClass(String enumClass) { this.enumClass = enumClass; }
    public String getConstName() { return constName; }
    public void setConstName(String constName) { this.constName = constName; }
    public String getOrdinal() { return ordinal; }
    public void setOrdinal(String ordinal) { this.ordinal = ordinal; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
