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
    // L'auteur du programme n'accepte pas les messages de ses participants.
    // Nommé plutôt que rendu par un FORBIDDEN générique : le client en tire un
    // texte propre et masque le composeur, là où un message serveur brut
    // laisserait l'utilisateur croire à une panne.
    PROGRAM_MESSAGES_DISABLED,
    // Écriture tentée par un non-auteur dans un fil de diffusion. Distinct de
    // PROGRAM_MESSAGES_DISABLED : celui-ci dit « l'auteur a fermé sa
    // messagerie », celui-là « ce fil n'est en écriture que pour l'auteur ».
    // Deux refus, deux textes chez le client.
    PROGRAM_BROADCAST_READ_ONLY,

    // — historique de recherche (SearchHistoryService) —
    SEARCH_HISTORY_ENTRY_NOT_FOUND,

    // — médias (StorageService) —
    MEDIA_FILE_NOT_FOUND,

    // — cartes-souvenirs de créneau (SlotRecapService) —
    // Publication refusée : personne d'autre que l'hôte n'a confirmé sa
    // présence. Le client en fait une phrase d'attente, pas un bandeau
    // d'erreur — c'est un « pas encore », pas un « non ».
    RECAP_NEEDS_ATTENDEE,
    // La fenêtre de sept jours après le créneau est refermée : la carte est figée.
    RECAP_WINDOW_CLOSED,
    // Contribuer suppose d'y avoir été (was_present = true).
    RECAP_NOT_ATTENDEE,
    // Le mot d'hôte et la visibilité n'appartiennent qu'à l'hôte.
    RECAP_NOT_HOST,
    // Plus de deux ambiances, ou une valeur hors du vocabulaire fermé SlotVibe.
    RECAP_INVALID_VIBES,

    // — abonnements (SubscriptionService) —
    // Un abonnement existe déjà sur cette cible. Le client le traite comme un
    // succès : l'état voulu est en base, l'affichage se stabilise sur « Abonné »
    // sans message d'erreur. Nommé plutôt que rendu par le CONFLICT générique,
    // qu'il partageait avec tous les autres 409 de l'API et sur lequel aucune
    // logique ne pouvait donc brancher.
    ALREADY_SUBSCRIBED,
    // Le profil visé refuse les nouveaux abonnements (PrivacySettings
    // allowSubscriptions = NOBODY). Ne concerne que le type AUTHOR.
    SUBSCRIPTIONS_NOT_ALLOWED,

    // — bornage de la carte (MapService) —
    MAP_RADIUS_REQUIRES_USER_LOCATION,
    MAP_RADIUS_OUT_OF_RANGE,
    MAP_BOUNDS_INCOMPLETE,
    MAP_BOUNDS_INVALID,
    MAP_LIMIT_OUT_OF_RANGE,
    MAP_ZOOM_OUT_OF_RANGE
}
