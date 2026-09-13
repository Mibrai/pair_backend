package org.program.pair.shared.exception;

public class ForbiddenException extends RuntimeException implements HasErrorCode {

    private final ErrorCode errorCode;
    private String messageKey;
    private Object[] messageArgs = new Object[0];

    public ForbiddenException(String message) {
        super(message);
        this.errorCode = null;
    }

    public ForbiddenException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * Un refus dont le code ne change pas — l'app le lit — mais dont le message se
     * traduit par sa propre clé {@code error.<messageKey>} (P-BA-11).
     */
    public ForbiddenException(ErrorCode errorCode, String messageKey, String message) {
        this(errorCode, message);
        this.messageKey = messageKey;
    }

    /** Comme le précédent, pour une clé qui porte des valeurs : {@code error.<messageKey>} avec {0}, {1}… */
    public ForbiddenException(ErrorCode errorCode, String messageKey, String message, Object... messageArgs) {
        this(errorCode, messageKey, message);
        this.messageArgs = messageArgs;
    }

    @Override
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    @Override
    public String getMessageKey() {
        return messageKey;
    }

    @Override
    public Object[] getMessageArgs() {
        return messageArgs;
    }
}
