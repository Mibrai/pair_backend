package org.program.pair.shared.exception;

/**
 * Énumération stable des codes d'erreur exposés dans {@code ErrorResponse.code}.
 *
 * <p>Contrat vis-à-vis des clients : un code n'est <b>jamais</b> traduit et ne
 * change <b>jamais</b> de nom une fois publié — c'est la seule partie du corps
 * d'erreur sur laquelle un client peut brancher une logique. Le {@code message}
 * qui l'accompagne est, lui, destiné à l'utilisateur final et peut changer de
 * formulation (et, à terme, de langue — cf.
 * {@code docs/specs/REPONSE_BACKEND_EVOLUTIONS_2026-08.md} demande 3).
 *
 * <p>Deux familles cohabitent :
 * <ul>
 *   <li>les codes <b>génériques</b>, dérivés du type d'exception, historiques et
 *       conservés tels quels pour ne rien casser ;</li>
 *   <li>les codes <b>métier</b>, portés explicitement par l'exception levée, qui
 *       nomment le refus plutôt que sa catégorie technique.</li>
 * </ul>
 *
 * <p>Ajouter un code est additif. En renommer un est une rupture de contrat.
 */
public enum ErrorCode {

    // — génériques (dérivés du type d'exception) —
    VALIDATION_ERROR,
    NOT_FOUND,
    FORBIDDEN,
    CONFLICT,
    BUSINESS_RULE_VIOLATION,
    INVALID_CREDENTIALS,
    INVALID_TOKEN,
    EMAIL_EXISTS,
    RATE_LIMITED,
    INVALID_PARAMETER,
    INVALID_JSON,
    METHOD_NOT_ALLOWED,
    INTERNAL_ERROR,

    // — créneaux (SlotService) —
    SLOT_OWN_SLOT,
    SLOT_NOT_OPEN_TO_PARTNERS,
    SLOT_NOT_ACCEPTING_PARTICIPANTS,
    SLOT_ALREADY_STARTED,
    SLOT_ALREADY_JOINED,
    SLOT_FULL,
    SLOT_PARTICIPANTS_HOST_ONLY,

    // — programmes et inscriptions (ProgramEnrollmentService) —
    PROGRAM_NOT_ACTIVE,
    PROGRAM_OWN_PROGRAM,
    PROGRAM_ALREADY_ENROLLED,
    PROGRAM_SCHEDULE_MISMATCH,
    PROGRAM_SCHEDULE_FULL,
    // Seul refus dont le corps porte plus que code + message : voir
    // ScheduleConflictResponse.
    SCHEDULE_CONFLICT,
    ENROLLMENT_NOT_OWNED,
    ENROLLMENT_ALREADY_LEFT,
    ENROLLMENT_NOT_ACTIVE,
    ENROLLMENT_PROGRESS_OUT_OF_RANGE,
    ACTIVITY_ALREADY_COMPLETED,
    ACTIVITY_ALREADY_SKIPPED,

    // — historique de recherche (SearchHistoryService) —
    SEARCH_HISTORY_ENTRY_NOT_FOUND,

    // — bornage de la carte (MapService) —
    MAP_RADIUS_REQUIRES_USER_LOCATION,
    MAP_RADIUS_OUT_OF_RANGE,
    MAP_BOUNDS_INCOMPLETE,
    MAP_BOUNDS_INVALID,
    MAP_LIMIT_OUT_OF_RANGE,
    MAP_ZOOM_OUT_OF_RANGE
}
