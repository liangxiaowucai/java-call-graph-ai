package com.adrninistrator.javacg2.platform.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Comment;

/**
 * jar 模块信息（jarNum → 显示名称），来自 javacg2 中间文件 jar_info.txt。
 * 固化到 DB，供目录树展示模块名，替代查询时直接读文件。
 */
@Entity
@Table(name = "jar_info", indexes = {
        @Index(name = "idx_jarinfo_repo", columnList = "repo_id")
})
@Comment("jar 模块信息表：jarNum→模块显示名，来自 javacg2 中间文件 jar_info.txt，供目录树展示模块名")
public class JarInfoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_id", nullable = false)
    @Comment("所属仓库 ID")
    private Long repoId;

    @Column(name = "jar_num", nullable = false)
    @Comment("jar 序号（javacg2 分配，与方法/类记录的 jarNum 对应）")
    private Integer jarNum;

    @Column(name = "jar_name", nullable = false, length = 500)
    @Comment("模块显示名（去掉 .jar/.war 扩展名的文件名）")
    private String jarName;

    public JarInfoEntity() {}

    public JarInfoEntity(Long repoId, Integer jarNum, String jarName) {
        this.repoId = repoId;
        this.jarNum = jarNum;
        this.jarName = jarName;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRepoId() { return repoId; }
    public void setRepoId(Long repoId) { this.repoId = repoId; }
    public Integer getJarNum() { return jarNum; }
    public void setJarNum(Integer jarNum) { this.jarNum = jarNum; }
    public String getJarName() { return jarName; }
    public void setJarName(String jarName) { this.jarName = jarName; }
}
