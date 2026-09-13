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

    /**
     * Clé de traduction propre au refus, distincte de son code (P-BA-11) — ou
     * {@code null}.
     *
     * <p>Elle existe pour les refus dont le <b>code</b> est lu par l'app publiée et
     * ne peut pas changer : {@code GlobalExceptionHandler} cherche
     * {@code error.<messageKey>} avant {@code error.<CODE>}, si bien qu'un refus
     * garde son code générique et reçoit quand même un message traduit.
     */
    default String getMessageKey() {
        return null;
    }
}
