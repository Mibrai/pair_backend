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

    /**
     * La demande de suppression elle-même, au moment où elle est reçue.
     *
     * <p>Distincte de {@link #GDPR_ANONYMIZE}, qui vient trente jours plus tard
     * et n'est pas une demande mais son exécution. Tant que la colonne
     * {@code users.deactivated_at} n'existe pas (elle arrive avec la purge),
     * <b>cette ligne est la seule date de la demande</b> : c'est elle qui fait
     * courir le délai, et sans elle rien en base ne dit qu'on a demandé quoi que
     * ce soit — c'était exactement l'état de la route jusqu'ici.
     */
    GDPR_DELETE_REQUEST,

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
