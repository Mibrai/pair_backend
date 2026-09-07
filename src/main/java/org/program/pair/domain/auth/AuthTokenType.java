package org.program.pair.domain.auth;

/**
 * Usage d'un {@link AuthToken}.
 *
 * <p>Les trois valeurs partagent une table (V79) parce qu'elles partagent leur
 * nature : un secret à usage unique, porteur d'un utilisateur et d'une
 * échéance. Elles ne partagent en revanche pas leur durée de vie, décidée à
 * l'émission par {@code EmailVerificationService}.
 */
public enum AuthTokenType {
    EMAIL_VERIFICATION,
    PASSWORD_RESET,

    /**
     * Confirmation d'une <b>nouvelle</b> adresse (V105). Distinct de
     * {@link #EMAIL_VERIFICATION} et pas seulement par propreté : un renvoi de
     * vérification consomme les jetons ouverts de son propre type, et confondre
     * les deux ferait qu'un renvoi annule silencieusement un changement
     * d'adresse en cours — ou l'inverse.
     */
    EMAIL_CHANGE
}
