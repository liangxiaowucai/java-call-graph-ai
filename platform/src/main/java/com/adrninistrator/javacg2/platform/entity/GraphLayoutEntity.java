package com.adrninistrator.javacg2.platform.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Comment;
import java.time.LocalDateTime;

/**
 * 仓库拓扑图布局缓存。
 * 分析完成后由后端自动计算（径向分包布局），存入 DB，供所有用户共享。
 * 重新分析时先清除旧数据再重算；前端通过 computed_at（版本号）比对是否需要刷新。
 */
@Entity
@Table(name = "graph_layout", indexes = {
        @Index(name = "idx_gl_repo", columnList = "repo_id"),
        @Index(name = "idx_gl_repo_class", columnList = "repo_id, class_name")
})
@Comment("仓库拓扑图布局缓存：后端分析完自动计算节点坐标，所有用户共享，重新分析时自动清除重算")
public class GraphLayoutEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_id", nullable = false)
    @Comment("所属仓库 ID")
    private Long repoId;

    @Column(name = "class_name", nullable = false, length = 500)
    @Comment("节点对应的类全限定名")
    private String className;

    @Column(name = "x", nullable = false)
    @Comment("节点 x 坐标（像素，相对 1200×800 画布）")
    private Double x;

    @Column(name = "y", nullable = false)
    @Comment("节点 y 坐标（像素，相对 1200×800 画布）")
    private Double y;

    /** 版本号：等于本次分析的完成时间，前端比对后决定是否重新拉取 */
    @Column(name = "computed_at", nullable = false)
    @Comment("布局计算时间（版本号），与分析完成时间对齐，前端用于判断是否需刷新")
    private LocalDateTime computedAt;

    public GraphLayoutEntity() {}

    public GraphLayoutEntity(Long repoId, String className, Double x, Double y, LocalDateTime computedAt) {
        this.repoId = repoId;
        this.className = className;
        this.x = x;
        this.y = y;
        this.computedAt = computedAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRepoId() { return repoId; }
    public void setRepoId(Long repoId) { this.repoId = repoId; }
    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }
    public Double getX() { return x; }
    public void setX(Double x) { this.x = x; }
    public Double getY() { return y; }
    public void setY(Double y) { this.y = y; }
    public LocalDateTime getComputedAt() { return computedAt; }
    public void setComputedAt(LocalDateTime computedAt) { this.computedAt = computedAt; }
}
