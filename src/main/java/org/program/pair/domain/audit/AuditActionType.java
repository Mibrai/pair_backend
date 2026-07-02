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
    PREFERENCE_UPDATE
}
