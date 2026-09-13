package org.program.pair.shared.exception;

/**
 * Refus dû à l'<b>état</b> de la ressource, et non à la requête ni à
 * l'appelant : un {@code 409}.
 *
 * <p>Comblait un trou du jeu d'exceptions. Jusqu'ici, un refus nommé ne
 * pouvait sortir qu'en {@code 400} ({@link ValidationException}), {@code 403}
 * ({@link ForbiddenException}) ou {@code 422} ({@link BusinessException}) ; les
 * seuls {@code 409} venaient d'{@code IllegalStateException}, qui ne sait pas
 * porter de code, et de {@link ScheduleConflictException}, qui a son propre
 * corps de réponse.
 *
 * <p>Le distinguo avec {@link BusinessException} : celle-ci dit « ce que vous
 * demandez n'est pas permis », celle-là « pas dans l'état où sont les choses ».
 * La fenêtre de contribution refermée ou la carte-souvenir sans second
 * participant confirmé relèvent de la seconde — réessayer plus tard, ou après
 * que quelqu'un d'autre a agi, peut changer la réponse.
 */
public class ConflictException extends RuntimeException implements HasErrorCode {

    private final ErrorCode errorCode;
    private String messageKey;
    private Object[] messageArgs = new Object[0];

    public ConflictException(String message) {
        super(message);
        this.errorCode = null;
    }

    public ConflictException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * Un refus dont le code ne change pas — l'app le lit — mais dont le message se
     * traduit par sa propre clé {@code error.<messageKey>} (P-BA-11).
     */
    public ConflictException(ErrorCode errorCode, String messageKey, String message) {
        this(errorCode, message);
        this.messageKey = messageKey;
    }

    /** Comme le précédent, pour une clé qui porte des valeurs : {@code error.<messageKey>} avec {0}, {1}… */
    public ConflictException(ErrorCode errorCode, String messageKey, String message, Object... messageArgs) {
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
