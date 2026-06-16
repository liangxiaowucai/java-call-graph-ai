package com.adrninistrator.javacg2.platform.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "call_graph", indexes = {
        @Index(name = "idx_cg_repo_caller", columnList = "repo_id, caller_method"),
        @Index(name = "idx_cg_repo_callee", columnList = "repo_id, callee_method"),
        @Index(name = "idx_cg_caller", columnList = "caller_method"),
        @Index(name = "idx_cg_callee", columnList = "callee_method")
})
public class CallGraphEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_id", nullable = false)
    private Long repoId;

    @Column(name = "call_id")
    private Integer callId;

    @Column(name = "caller_method", nullable = false, length = 1000)
    private String callerMethod;

    @Column(name = "callee_method", nullable = false, length = 1000)
    private String calleeMethod;

    @Column(name = "call_type", length = 20)
    private String callType;

    @Column(name = "line_number")
    private Integer lineNumber;

    @Column(name = "caller_return_type", length = 200)
    private String callerReturnType;

    @Column(name = "callee_obj_type", length = 10)
    private String calleeObjType;

    @Column(name = "callee_raw_return_type", length = 200)
    private String calleeRawReturnType;

    @Column(name = "callee_actual_return_type", length = 200)
    private String calleeActualReturnType;

    @Column(name = "caller_jar_num")
    private Integer callerJarNum;

    @Column(name = "callee_jar_num")
    private Integer calleeJarNum;

    @Column
    private Boolean enabled = true;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRepoId() { return repoId; }
    public void setRepoId(Long repoId) { this.repoId = repoId; }
    public Integer getCallId() { return callId; }
    public void setCallId(Integer callId) { this.callId = callId; }
    public String getCallerMethod() { return callerMethod; }
    public void setCallerMethod(String callerMethod) { this.callerMethod = callerMethod; }
    public String getCalleeMethod() { return calleeMethod; }
    public void setCalleeMethod(String calleeMethod) { this.calleeMethod = calleeMethod; }
    public String getCallType() { return callType; }
    public void setCallType(String callType) { this.callType = callType; }
    public Integer getLineNumber() { return lineNumber; }
    public void setLineNumber(Integer lineNumber) { this.lineNumber = lineNumber; }
    public String getCallerReturnType() { return callerReturnType; }
    public void setCallerReturnType(String callerReturnType) { this.callerReturnType = callerReturnType; }
    public String getCalleeObjType() { return calleeObjType; }
    public void setCalleeObjType(String calleeObjType) { this.calleeObjType = calleeObjType; }
    public String getCalleeRawReturnType() { return calleeRawReturnType; }
    public void setCalleeRawReturnType(String calleeRawReturnType) { this.calleeRawReturnType = calleeRawReturnType; }
    public String getCalleeActualReturnType() { return calleeActualReturnType; }
    public void setCalleeActualReturnType(String calleeActualReturnType) { this.calleeActualReturnType = calleeActualReturnType; }
    public Integer getCallerJarNum() { return callerJarNum; }
    public void setCallerJarNum(Integer callerJarNum) { this.callerJarNum = callerJarNum; }
    public Integer getCalleeJarNum() { return calleeJarNum; }
    public void setCalleeJarNum(Integer calleeJarNum) { this.calleeJarNum = calleeJarNum; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
