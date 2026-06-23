package com.adrninistrator.javacg2.platform.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 请求链DTO - 从Chrome插件传入
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RequestChainDTO {
    
    private Long sessionId;
    private String userAction;
    private Long timestamp;
    private String url;
    private List<RequestInfo> requestChain;
    private List<DataFlow> dataFlow;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RequestInfo {
        private Long timestamp;
        private Integer seq;
        private String method;
        private String url;
        private String fullUrl;
        private String requestBody;
        private Integer responseStatus;
        private String responseBody;
        private Long duration;
        private Boolean success;
        private java.util.Map<String, Object> requestHeaders;

        // Getters and Setters
        public Long getTimestamp() { return timestamp; }
        public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }
        public Integer getSeq() { return seq; }
        public void setSeq(Integer seq) { this.seq = seq; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getFullUrl() { return fullUrl; }
        public void setFullUrl(String fullUrl) { this.fullUrl = fullUrl; }
        public String getRequestBody() { return requestBody; }
        public void setRequestBody(String requestBody) { this.requestBody = requestBody; }
        public Integer getResponseStatus() { return responseStatus; }
        public void setResponseStatus(Integer responseStatus) { this.responseStatus = responseStatus; }
        public String getResponseBody() { return responseBody; }
        public void setResponseBody(String responseBody) { this.responseBody = responseBody; }
        public Long getDuration() { return duration; }
        public void setDuration(Long duration) { this.duration = duration; }
        public Boolean getSuccess() { return success; }
        public void setSuccess(Boolean success) { this.success = success; }
        public java.util.Map<String, Object> getRequestHeaders() { return requestHeaders; }
        public void setRequestHeaders(java.util.Map<String, Object> requestHeaders) { this.requestHeaders = requestHeaders; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DataFlow {
        private String from;
        private String to;
        private List<FieldMapping> fields;

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class FieldMapping {
            private String field;
            private Object value;

            public String getField() { return field; }
            public void setField(String field) { this.field = field; }
            public Object getValue() { return value; }
            public void setValue(Object value) { this.value = value; }
        }

        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }
        public String getTo() { return to; }
        public void setTo(String to) { this.to = to; }
        public List<FieldMapping> getFields() { return fields; }
        public void setFields(List<FieldMapping> fields) { this.fields = fields; }
    }

    // Main Getters and Setters
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public String getUserAction() { return userAction; }
    public void setUserAction(String userAction) { this.userAction = userAction; }
    public Long getTimestamp() { return timestamp; }
    public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public List<RequestInfo> getRequestChain() { return requestChain; }
    public void setRequestChain(List<RequestInfo> requestChain) { this.requestChain = requestChain; }
    public List<DataFlow> getDataFlow() { return dataFlow; }
    public void setDataFlow(List<DataFlow> dataFlow) { this.dataFlow = dataFlow; }
}
