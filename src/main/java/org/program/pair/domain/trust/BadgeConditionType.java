package org.program.pair.domain.trust;

public enum BadgeConditionType {
    VERIFICATION,
    RECOMMENDATION_COUNT,
    PROGRAM_COUNT,
    PROGRESSION_STREAK,
    ACTIVITY_DIVERSITY,
    MANUAL,
    // Valeurs déjà présentes dans les données de seed V12/V27, absentes de l'enum d'origine
    PROGRAMS_CREATED,
    CONVERSATIONS_STARTED,
    AVERAGE_REVIEW_SCORE,
    ACTIVITIES_REGISTERED,
    RECOMMENDATIONS_RECEIVED,
    ACTIVITIES_COMPLETED,
    MORNING_SESSIONS,
    GROUP_ENROLLMENTS,
    PERFECT_REVIEWS,
    STREAK_DAYS,
    UNIQUE_ACTIVITIES,
    // meetDo — régularité et diversité des partenaires (jamais un classement)
    ATTENDANCE_COUNT,
    DISTINCT_PARTNERS,
    WEEKLY_STREAK,
    SLOT_HOSTED_COUNT,
    // Invitations qui ont abouti sur un créneau. Le seuil est à 1 : la
    // récompense marque un geste, elle ne mesure pas une performance.
    INVITATION_CONVERTED
}
