package com.adrninistrator.javacg2.platform.dto;

import java.util.List;

/**
 * 增强版调试分析结果DTO
 * 整合 Chrome 插件的请求链时序 + 后端调用树（懒加载）
 */
public class EnhancedDebugAnalysisResult extends DebugAnalysisResult {
    
    private List<RequestSequenceItem> requestSequence;  // 时序图（来自Chrome插件）
    private List<CrossRequestCallChain> crossRequestChains;  // 跨请求调用链
    
    /**
     * 请求时序项（来自Chrome插件）
     */
    public static class RequestSequenceItem {
        private Integer seq;
        private Long timestamp;
        private String method;  // HTTP方法 (GET/POST)
        private String url;
        private String fullUrl;
        private Integer responseStatus;
        private Long duration;
        private Boolean success;
        
        // 关联的后端调用树（懒加载，初始可为null）
        private String backendMethod;  // 匹配到的后端方法
        private Long repoId;  // 所属仓库
        private Boolean callTreeLoaded;  // 调用树是否已加载
        
        public Integer getSeq() { return seq; }
        public void setSeq(Integer seq) { this.seq = seq; }
        public Long getTimestamp() { return timestamp; }
        public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getFullUrl() { return fullUrl; }
        public void setFullUrl(String fullUrl) { this.fullUrl = fullUrl; }
        public Integer getResponseStatus() { return responseStatus; }
        public void setResponseStatus(Integer responseStatus) { this.responseStatus = responseStatus; }
        public Long getDuration() { return duration; }
        public void setDuration(Long duration) { this.duration = duration; }
        public Boolean getSuccess() { return success; }
        public void setSuccess(Boolean success) { this.success = success; }
        public String getBackendMethod() { return backendMethod; }
        public void setBackendMethod(String backendMethod) { this.backendMethod = backendMethod; }
        public Long getRepoId() { return repoId; }
        public void setRepoId(Long repoId) { this.repoId = repoId; }
        public Boolean getCallTreeLoaded() { return callTreeLoaded; }
        public void setCallTreeLoaded(Boolean callTreeLoaded) { this.callTreeLoaded = callTreeLoaded; }
    }
    
    /**
     * 跨请求调用链
     * 描述两个请求之间的完整调用路径
     */
    public static class CrossRequestCallChain {
        private String fromUrl;
        private String toUrl;
        private Integer fromSeq;
        private Integer toSeq;
        private String relationshipType;  // SAME_REPO_DIRECT, CROSS_REPO_HTTP, NONE, UNKNOWN
        private String description;
        private List<String> callPath;  // 完整调用路径
        private String fromRepo;
        private String toRepo;
        private Boolean crossRepo;
        
        public String getFromUrl() { return fromUrl; }
        public void setFromUrl(String fromUrl) { this.fromUrl = fromUrl; }
        public String getToUrl() { return toUrl; }
        public void setToUrl(String toUrl) { this.toUrl = toUrl; }
        public Integer getFromSeq() { return fromSeq; }
        public void setFromSeq(Integer fromSeq) { this.fromSeq = fromSeq; }
        public Integer getToSeq() { return toSeq; }
        public void setToSeq(Integer toSeq) { this.toSeq = toSeq; }
        public String getRelationshipType() { return relationshipType; }
        public void setRelationshipType(String relationshipType) { this.relationshipType = relationshipType; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public List<String> getCallPath() { return callPath; }
        public void setCallPath(List<String> callPath) { this.callPath = callPath; }
        public String getFromRepo() { return fromRepo; }
        public void setFromRepo(String fromRepo) { this.fromRepo = fromRepo; }
        public String getToRepo() { return toRepo; }
        public void setToRepo(String toRepo) { this.toRepo = toRepo; }
        public Boolean getCrossRepo() { return crossRepo; }
        public void setCrossRepo(Boolean crossRepo) { this.crossRepo = crossRepo; }
    }
    
    public List<RequestSequenceItem> getRequestSequence() { return requestSequence; }
    public void setRequestSequence(List<RequestSequenceItem> requestSequence) { this.requestSequence = requestSequence; }
    public List<CrossRequestCallChain> getCrossRequestChains() { return crossRequestChains; }
    public void setCrossRequestChains(List<CrossRequestCallChain> crossRequestChains) { this.crossRequestChains = crossRequestChains; }
}
