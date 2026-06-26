package org.program.pair.domain.search.dto;

public record SearchIntent(
    String activityKeyword,      // "yoga", "escalade", "photographie"...
    String categoryHint,         // "Sport", "Arts"...
    String level,                // BEGINNER | INTERMEDIATE | ADVANCED | null
    String format,               // SOLO | DUO | GROUP | null
    Integer suggestedRadius,     // en mètres, détecté dans la phrase
    String timeHint,             // "week-end", "matin", "soir"...
    boolean needsClarification,
    String clarificationQuestion // si trop vague
) {
    public SearchIntent() {
        this(null, null, null, null, 5000, null, false, null);
    }
}
