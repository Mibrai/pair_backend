package org.program.pair.shared.exception;

public class ValidationException extends RuntimeException implements HasErrorCode {

    private final ErrorCode errorCode;
    private String messageKey;

    public ValidationException(String message) {
        super(message);
        this.errorCode = null;
    }

    public ValidationException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * Un refus dont le code ne change pas — l'app le lit — mais dont le message se
     * traduit par sa propre clé {@code error.<messageKey>} (P-BA-11).
     */
    public ValidationException(ErrorCode errorCode, String messageKey, String message) {
        this(errorCode, message);
        this.messageKey = messageKey;
    }

    @Override
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    @Override
    public String getMessageKey() {
        return messageKey;
    }
}
