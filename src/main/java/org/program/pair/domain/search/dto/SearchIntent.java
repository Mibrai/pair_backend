package org.program.pair.domain.search.dto;

public record SearchIntent(
    String activityKeyword,      // "yoga", "escalade", "photographie"...
    String categoryHint,         // "Sport", "Arts"...
    String level,                // BEGINNER | INTERMEDIATE | ADVANCED | null
    String format,               // SOLO | DUO | GROUP | null
    Integer suggestedRadius,     // en mètres, détecté dans la phrase
    String timeHint,             // "week-end", "matin", "soir"...
    boolean needsClarification,
    String clarificationQuestion, // si trop vague
    String canonicalActivitySlug  // slug de la taxonomie EN/DE/FR (ex: "running"), ou null
) {
    public SearchIntent() {
        this(null, null, null, null, 5000, null, false, null, null);
    }

    /** Compatibilité : ancien schéma sans canonicalActivitySlug (ex: réponses LLM). */
    public SearchIntent(
            String activityKeyword, String categoryHint, String level, String format,
            Integer suggestedRadius, String timeHint, boolean needsClarification,
            String clarificationQuestion) {
        this(activityKeyword, categoryHint, level, format, suggestedRadius, timeHint,
            needsClarification, clarificationQuestion, null);
    }
}
