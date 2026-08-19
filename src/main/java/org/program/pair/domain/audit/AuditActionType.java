package org.program.pair.domain.audit;

/**
 * Types of actions tracked for GDPR compliance
 */
public enum AuditActionType {
    // User actions
    USER_REGISTER,
    USER_LOGIN,
    USER_LOGOUT,
    USER_UPDATE,
    USER_DELETE,
    USER_DEACTIVATE,
    USER_REACTIVATE,

    // GDPR specific
    GDPR_EXPORT,
    GDPR_PURGE,
    GDPR_ANONYMIZE,

    // Data operations
    CREATE,
    UPDATE,
    DELETE,

    // Security
    PASSWORD_CHANGE,
    PASSWORD_RESET,
    EMAIL_VERIFY,
    TOKEN_REFRESH,

    // Privacy
    LOCATION_UPDATE,
    VISIBILITY_CHANGE,
    PREFERENCE_UPDATE,

    // Parcours d'accueil. Passer l'accueil est permis — la spec le dit
    // explicitement — mais tracé : c'est la seule façon de savoir plus tard si
    // les gens le sautent, et donc si le parcours vaut ce qu'il coûte.
    ONBOARDING_SKIP
}
