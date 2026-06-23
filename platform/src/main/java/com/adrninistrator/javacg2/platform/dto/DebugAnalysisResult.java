package com.adrninistrator.javacg2.platform.dto;

import java.util.List;

/**
 * 调试分析结果DTO
 */
public class DebugAnalysisResult {
    
    private String summary;
    private List<ApiCall> apiCalls;
    private List<DataFlowIssue> dataFlowIssues;
    private String report;
    private List<RepoRecommendation> recommendedRepos;

    public static class ApiCall {
        private String url;
        private String method;  // 后端方法全名
        private String requestMethod;  // HTTP 方法 (GET/POST/PUT/DELETE)
        private String requestBody;
        private String responseBody;
        private Integer status;
        private Object requestHeaders;  // 请求头（用于入参值关联）
        private Object callTree;  // CallTreeDTO
        private String diagnosis;  // AI诊断结果
        private RepoRecommendation recommendedRepo;  // 该接口推荐的仓库
        
        // 跨请求调用关系分析（新增）
        private List<String> downstreamMethods;  // 该请求调用的下游方法列表
        private List<String> httpCalls;  // 该请求发起的HTTP调用
        private CallRelationship relationshipToNext;  // 与下一个请求的关系

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public String getRequestMethod() { return requestMethod; }
        public void setRequestMethod(String requestMethod) { this.requestMethod = requestMethod; }
        public String getRequestBody() { return requestBody; }
        public void setRequestBody(String requestBody) { this.requestBody = requestBody; }
        public String getResponseBody() { return responseBody; }
        public void setResponseBody(String responseBody) { this.responseBody = responseBody; }
        public Integer getStatus() { return status; }
        public void setStatus(Integer status) { this.status = status; }
        public Object getRequestHeaders() { return requestHeaders; }
        public void setRequestHeaders(Object requestHeaders) { this.requestHeaders = requestHeaders; }
        public Object getCallTree() { return callTree; }
        public void setCallTree(Object callTree) { this.callTree = callTree; }
        public String getDiagnosis() { return diagnosis; }
        public void setDiagnosis(String diagnosis) { this.diagnosis = diagnosis; }
        public RepoRecommendation getRecommendedRepo() { return recommendedRepo; }
        public void setRecommendedRepo(RepoRecommendation recommendedRepo) { this.recommendedRepo = recommendedRepo; }
        public List<String> getDownstreamMethods() { return downstreamMethods; }
        public void setDownstreamMethods(List<String> downstreamMethods) { this.downstreamMethods = downstreamMethods; }
        public List<String> getHttpCalls() { return httpCalls; }
        public void setHttpCalls(List<String> httpCalls) { this.httpCalls = httpCalls; }
        public CallRelationship getRelationshipToNext() { return relationshipToNext; }
        public void setRelationshipToNext(CallRelationship relationshipToNext) { this.relationshipToNext = relationshipToNext; }
    }
    
    /**
     * 请求间调用关系
     */
    public static class CallRelationship {
        private String type;  // SAME_REPO_DIRECT, SAME_REPO_INDIRECT, CROSS_REPO_HTTP, UNKNOWN, NONE
        private String description;  // 人类可读的描述
        private List<String> evidenceMethods;  // 证据方法（调用链中的关键方法）
        private String targetRepo;  // 目标仓库（跨仓库时）
        private Boolean targetRepoConfigured;  // 目标仓库是否已配置
        
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public List<String> getEvidenceMethods() { return evidenceMethods; }
        public void setEvidenceMethods(List<String> evidenceMethods) { this.evidenceMethods = evidenceMethods; }
        public String getTargetRepo() { return targetRepo; }
        public void setTargetRepo(String targetRepo) { this.targetRepo = targetRepo; }
        public Boolean getTargetRepoConfigured() { return targetRepoConfigured; }
        public void setTargetRepoConfigured(Boolean targetRepoConfigured) { this.targetRepoConfigured = targetRepoConfigured; }
    }

    public static class DataFlowIssue {
        private String fromApi;
        private String toApi;
        private String field;
        private Object expectedValue;
        private Object actualValue;
        private String severity;
        private String description;
        private String fromRepo;  // 数据来源仓库
        private String toRepo;    // 数据接收仓库
        private String suggestedAction;  // 建议排查哪个仓库

        public String getFromApi() { return fromApi; }
        public void setFromApi(String fromApi) { this.fromApi = fromApi; }
        public String getToApi() { return toApi; }
        public void setToApi(String toApi) { this.toApi = toApi; }
        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
        public Object getExpectedValue() { return expectedValue; }
        public void setExpectedValue(Object expectedValue) { this.expectedValue = expectedValue; }
        public Object getActualValue() { return actualValue; }
        public void setActualValue(Object actualValue) { this.actualValue = actualValue; }
        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getFromRepo() { return fromRepo; }
        public void setFromRepo(String fromRepo) { this.fromRepo = fromRepo; }
        public String getToRepo() { return toRepo; }
        public void setToRepo(String toRepo) { this.toRepo = toRepo; }
        public String getSuggestedAction() { return suggestedAction; }
        public void setSuggestedAction(String suggestedAction) { this.suggestedAction = suggestedAction; }
    }

    public static class RepoRecommendation {
        private Long repoId;
        private String repoName;
        private Double confidence;  // 0.0 - 1.0
        private String reason;
        private List<String> matchedUrls;
        private List<String> matchedFields;

        public Long getRepoId() { return repoId; }
        public void setRepoId(Long repoId) { this.repoId = repoId; }
        public String getRepoName() { return repoName; }
        public void setRepoName(String repoName) { this.repoName = repoName; }
        public Double getConfidence() { return confidence; }
        public void setConfidence(Double confidence) { this.confidence = confidence; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        public List<String> getMatchedUrls() { return matchedUrls; }
        public void setMatchedUrls(List<String> matchedUrls) { this.matchedUrls = matchedUrls; }
        public List<String> getMatchedFields() { return matchedFields; }
        public void setMatchedFields(List<String> matchedFields) { this.matchedFields = matchedFields; }
    }

    // Main Getters and Setters
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public List<ApiCall> getApiCalls() { return apiCalls; }
    public void setApiCalls(List<ApiCall> apiCalls) { this.apiCalls = apiCalls; }
    public List<DataFlowIssue> getDataFlowIssues() { return dataFlowIssues; }
    public void setDataFlowIssues(List<DataFlowIssue> dataFlowIssues) { this.dataFlowIssues = dataFlowIssues; }
    public String getReport() { return report; }
    public void setReport(String report) { this.report = report; }
    public List<RepoRecommendation> getRecommendedRepos() { return recommendedRepos; }
    public void setRecommendedRepos(List<RepoRecommendation> recommendedRepos) { this.recommendedRepos = recommendedRepos; }
}
