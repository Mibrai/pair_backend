package org.program.pair.shared.exception;

/**
 * Portée par les exceptions capables de nommer précisément le refus métier
 * qu'elles représentent, au-delà de leur catégorie technique.
 *
 * <p>{@link GlobalExceptionHandler} lit ce code quand il est présent, et retombe
 * sinon sur le code générique historique du type d'exception. Les exceptions
 * existantes qui ne le renseignent pas gardent donc exactement le comportement
 * qu'elles avaient.
 */
public interface HasErrorCode {

    /** Code stable du refus, ou {@code null} pour laisser le code générique. */
    ErrorCode getErrorCode();
}
