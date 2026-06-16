package com.adrninistrator.javacg2.platform.exception;

public class AnalysisException extends PlatformException {

    public AnalysisException(String message) {
        super("ANALYSIS_ERROR", message, "检查编译产物是否完整", 500);
    }

    public AnalysisException(String errorType, String message, String suggestion) {
        super(errorType, message, suggestion, 500);
    }
}
