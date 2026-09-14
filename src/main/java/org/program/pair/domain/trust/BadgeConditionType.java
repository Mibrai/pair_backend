package org.program.pair.domain.trust;

/**
 * Nature de la condition d'un badge.
 *
 * <p><b>Aucune série, aucune note</b> (V122, demande mobile du 14/09/2026) :
 * {@code PROGRESSION_STREAK}, {@code STREAK_DAYS}, {@code WEEKLY_STREAK},
 * {@code AVERAGE_REVIEW_SCORE} et {@code PERFECT_REVIEWS} ont quitté cette
 * énumération, et la contrainte {@code chk_badges_ni_serie_ni_note} les refuse en
 * base. Une série mesure un effort dans la durée, une note classe les gens :
 * la doctrine refuse les deux. Les rajouter ici ne suffirait pas à les
 * rétablir, et c'est voulu.
 */
public enum BadgeConditionType {
    VERIFICATION,
    RECOMMENDATION_COUNT,
    PROGRAM_COUNT,
    ACTIVITY_DIVERSITY,
    MANUAL,
    // Valeurs déjà présentes dans les données de seed V12/V27, absentes de l'enum d'origine
    PROGRAMS_CREATED,
    CONVERSATIONS_STARTED,
    ACTIVITIES_REGISTERED,
    RECOMMENDATIONS_RECEIVED,
    ACTIVITIES_COMPLETED,
    MORNING_SESSIONS,
    GROUP_ENROLLMENTS,
    UNIQUE_ACTIVITIES,
    // meetDo — présence et diversité des partenaires (jamais un classement)
    ATTENDANCE_COUNT,
    DISTINCT_PARTNERS,
    SLOT_HOSTED_COUNT,
    // Invitations qui ont abouti sur un créneau. Le seuil est à 1 : la
    // récompense marque un geste, elle ne mesure pas une performance.
    INVITATION_CONVERTED
}
