package com.adrninistrator.javacg2.platform.exception;

public class ClaudeApiException extends PlatformException {

    public ClaudeApiException(String errorType, String message, String suggestion) {
        super(errorType, message, suggestion, 502);
    }

    public ClaudeApiException(String errorType, String message, String suggestion, int httpStatus) {
        super(errorType, message, suggestion, httpStatus);
    }
}
