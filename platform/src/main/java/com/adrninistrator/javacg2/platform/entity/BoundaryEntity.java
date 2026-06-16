package com.adrninistrator.javacg2.platform.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "boundaries", indexes = {
        @Index(name = "idx_boundary_repo_method", columnList = "repo_id, full_method")
})
public class BoundaryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_id", nullable = false)
    private Long repoId;

    @Column(name = "full_method", nullable = false, length = 1000)
    private String fullMethod;

    @Column(name = "boundary_type", nullable = false, length = 30)
    private String boundaryType;

    @Column(name = "line_number")
    private Integer lineNumber;

    @Column(columnDefinition = "TEXT")
    private String context;

    @Column(name = "callee_method", length = 1000)
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
