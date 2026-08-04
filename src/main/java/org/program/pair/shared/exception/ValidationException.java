package org.program.pair.shared.exception;

public class ValidationException extends RuntimeException implements HasErrorCode {

    private final ErrorCode errorCode;

    public ValidationException(String message) {
        super(message);
        this.errorCode = null;
    }

    public ValidationException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    @Override
    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
