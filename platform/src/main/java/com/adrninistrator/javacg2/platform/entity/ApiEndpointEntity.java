package com.adrninistrator.javacg2.platform.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "api_endpoints", indexes = {
        @Index(name = "idx_ep_repo", columnList = "repo_id")
})
public class ApiEndpointEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_id", nullable = false)
    private Long repoId;

    @Column(name = "endpoint_type", nullable = false, length = 30)
    private String endpointType;

    @Column(name = "http_method", length = 10)
    private String httpMethod;

    @Column(name = "url_path", length = 500)
    private String urlPath;

    @Column(name = "full_method", nullable = false, length = 1000)
    private String fullMethod;

    @Column(name = "class_name", length = 500)
    private String className;

    @Column(name = "annotation_class", length = 200)
    private String annotationClass;

    @Column(name = "sql_statement", columnDefinition = "TEXT")
    private String sqlStatement;

    @Column(name = "table_names", length = 500)
    private String tableNames;

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
}
