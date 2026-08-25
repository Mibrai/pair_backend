package org.program.pair.domain.auth;

/**
 * Usage d'un {@link AuthToken}.
 *
 * <p>Les deux valeurs partagent une table (V79) parce qu'elles partagent leur
 * nature : un secret à usage unique, porteur d'un utilisateur et d'une
 * échéance. Elles ne partagent en revanche pas leur durée de vie, décidée à
 * l'émission par {@code EmailVerificationService}.
 */
public enum AuthTokenType {
    EMAIL_VERIFICATION,
    PASSWORD_RESET
}
