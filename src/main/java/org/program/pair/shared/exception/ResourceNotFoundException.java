package org.program.pair.shared.exception;

public class ResourceNotFoundException extends RuntimeException implements HasErrorCode {

    private final ErrorCode errorCode;
    private String messageKey;

    public ResourceNotFoundException(String message) {
        super(message);
        this.errorCode = null;
    }

    public ResourceNotFoundException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * Un refus dont le code ne change pas — l'app le lit — mais dont le message se
     * traduit par sa propre clé {@code error.<messageKey>} (P-BA-11).
     */
    public ResourceNotFoundException(ErrorCode errorCode, String messageKey, String message) {
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
