package com.adrninistrator.javacg2.platform.exception;

public class PlatformException extends RuntimeException {

    private final String errorType;
    private final String suggestion;
    private final int httpStatus;

    public PlatformException(String errorType, String message, String suggestion, int httpStatus) {
        super(message);
        this.errorType = errorType;
        this.suggestion = suggestion;
        this.httpStatus = httpStatus;
    }

    public String getErrorType() { return errorType; }
    public String getSuggestion() { return suggestion; }
    public int getHttpStatus() { return httpStatus; }
}
