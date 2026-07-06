package com.adrninistrator.javacg2.platform.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Comment;

/** 方法调用关系表：方法级调用图（谁调用谁），调用链分析的核心数据。 */
@Entity
@Table(name = "call_graph", indexes = {
        @Index(name = "idx_cg_repo_caller", columnList = "repo_id, caller_method"),
        @Index(name = "idx_cg_repo_callee", columnList = "repo_id, callee_method"),
        @Index(name = "idx_cg_caller", columnList = "caller_method"),
        @Index(name = "idx_cg_callee", columnList = "callee_method")
})
@Comment("方法调用关系表：方法级调用图（谁调用谁），调用链分析核心数据")
public class CallGraphEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_id", nullable = false)
    @Comment("所属仓库 ID")
    private Long repoId;

    @Column(name = "call_id")
    @Comment("javacg2 分配的调用序号")
    private Integer callId;

    @Column(name = "caller_method", nullable = false, length = 1000)
    @Comment("调用方方法全限定签名")
    private String callerMethod;

    @Column(name = "callee_method", nullable = false, length = 1000)
    @Comment("被调用方方法全限定签名")
    private String calleeMethod;

    @Column(name = "call_type", length = 20)
    @Comment("调用类型：如 ITF(接口)/IMPL(实现)/STA(静态)/EXTENDS/IMPLEMENTS 等")
    private String callType;

    @Column(name = "line_number")
    @Comment("调用发生的源码行号")
    private Integer lineNumber;

    @Column(name = "caller_return_type", length = 200)
    @Comment("调用方方法返回类型")
    private String callerReturnType;

    @Column(name = "callee_obj_type", length = 10)
    @Comment("被调用对象类型标识")
    private String calleeObjType;

    @Column(name = "callee_raw_return_type", length = 200)
    @Comment("被调用方原始返回类型")
    private String calleeRawReturnType;

    @Column(name = "callee_actual_return_type", length = 200)
    @Comment("被调用方实际返回类型（泛型推断后）")
    private String calleeActualReturnType;

    @Column(name = "caller_jar_num")
    @Comment("调用方所属 jar 序号")
    private Integer callerJarNum;

    @Column(name = "callee_jar_num")
    @Comment("被调用方所属 jar 序号")
    private Integer calleeJarNum;

    @Column
    @Comment("该调用边是否启用")
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
