package com.adrninistrator.javacg2.platform.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Comment;

/** 接口/入口点表：Controller HTTP 接口及 MQ/定时/gRPC 等入口，供调用链入口列表展示。 */
@Entity
@Table(name = "api_endpoints", indexes = {
        @Index(name = "idx_ep_repo", columnList = "repo_id")
}, uniqueConstraints = {
        // jar_num 纳入唯一键，允许同一方法在不同 jar（模块）中各存一条
        @UniqueConstraint(name = "uk_ep_repo_method_jar", columnNames = {"repo_id", "full_method", "jar_num"})
})
@Comment("接口/入口点表：Controller HTTP 接口及 MQ/定时/gRPC 等入口")
public class ApiEndpointEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_id", nullable = false)
    @Comment("所属仓库 ID")
    private Long repoId;

    @Column(name = "endpoint_type", nullable = false, length = 30)
    @Comment("入口类型：CONTROLLER/KAFKA/ROCKETMQ/SCHEDULED/GRPC 等")
    private String endpointType;

    @Column(name = "http_method", length = 10)
    @Comment("HTTP 方法：GET/POST/PUT/DELETE 等（仅 HTTP 接口有值）")
    private String httpMethod;

    @Column(name = "url_path", length = 500)
    @Comment("接口 URL 路径（仅 HTTP 接口有值）")
    private String urlPath;

    @Column(name = "full_method", nullable = false, length = 1000)
    @Comment("入口方法全限定签名（类名:方法名(参数)）")
    private String fullMethod;

    @Column(name = "class_name", length = 500)
    @Comment("入口所在类全限定名")
    private String className;

    @Column(name = "annotation_class", length = 200)
    @Comment("识别该入口的注解类全限定名")
    private String annotationClass;

    @Column(name = "sql_statement", columnDefinition = "TEXT")
    @Comment("关联的 SQL 语句（若解析到）")
    private String sqlStatement;

    @Column(name = "table_names", length = 500)
    @Comment("关联的数据库表名（逗号分隔）")
    private String tableNames;

    @Column(name = "jar_num")
    @Comment("所属 jar 序号，与 jar_info.jar_num 对应；纳入唯一键以支持同方法在不同模块中各存一条")
    private Integer jarNum;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRepoId() { return repoId; }
    public void setRepoId(Long repoId) { this.repoId = repoId; }
    public String getEndpointType() { return endpointType; }
    public void setEndpointType(String endpointType) { this.endpointType = endpointType; }
    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }
    public String getUrlPath() { return urlPath; }
    public void setUrlPath(String urlPath) { this.urlPath = urlPath; }
    public String getFullMethod() { return fullMethod; }
    public void setFullMethod(String fullMethod) { this.fullMethod = fullMethod; }
    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }
    public String getAnnotationClass() { return annotationClass; }
    public void setAnnotationClass(String annotationClass) { this.annotationClass = annotationClass; }
    public String getSqlStatement() { return sqlStatement; }
    public void setSqlStatement(String sqlStatement) { this.sqlStatement = sqlStatement; }
    public String getTableNames() { return tableNames; }
    public void setTableNames(String tableNames) { this.tableNames = tableNames; }
    public Integer getJarNum() { return jarNum; }
    public void setJarNum(Integer jarNum) { this.jarNum = jarNum; }
}
