package com.adrninistrator.javacg2.platform.exception;

public class GitOperationException extends PlatformException {

    public GitOperationException(String errorType, String message, String suggestion) {
        super(errorType, message, suggestion, 400);
    }

    public GitOperationException(String errorType, String message, String suggestion, int httpStatus) {
        super(errorType, message, suggestion, httpStatus);
    }
}
