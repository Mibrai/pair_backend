package org.program.pair.domain.notification;

public enum NotificationType {
    NEW_MESSAGE,
    NEW_MATCH,
    NEARBY_PROGRAM,
    NEW_FOLLOWER,
    PEER_RECOMMENDATION,
    PROGRAM_REVIEW,
    BADGE_EARNED,
    PROGRAM_REMINDER,
    PROGRESSION_REMINDER,
    ACCOUNT_VERIFICATION,
    PASSWORD_RESET,
    MODERATION_ACTION,
    AUTHOR_NEW_ACTIVITY,
    AUTHOR_NEW_PROGRAM,
    ACTIVITY_UPDATED,
    ACTIVITY_NEW_PROGRAM,
    CATEGORY_NEW_ACTIVITY,
    // Valeurs déjà présentes dans les données de seed V12/V13/V27, absentes de l'enum d'origine
    NEW_REVIEW,
    NEW_BADGE,
    NEW_PEER_REC,
    MATCH_FOUND,
    PROGRAM_CANCELLED,
    SCHEDULE_CHANGED,
    SYSTEM,
    // meetDo — créneaux, présence et alertes
    SLOT_JOINED,             // quelqu'un a rejoint mon créneau
    SLOT_CANCELLED,          // un créneau que j'ai rejoint est annulé
    ATTENDANCE_PROMPT,       // "tu y étais ?" après un créneau
    ACTIVITY_ALERT_MATCH,    // quelqu'un pratique enfin cette activité près de moi
    STREAK_MILESTONE,        // série de N semaines atteinte
    PARTNER_MILESTONE,       // Nème partenaire différent
    // Diffusion de l'auteur d'un programme à ses participants. Distinct de
    // NEW_MESSAGE : le tap doit ouvrir le fil du programme, pas une
    // conversation à deux, et le client range les deux dans des rubriques
    // différentes de son catalogue de préférences.
    PROGRAM_BROADCAST
}
