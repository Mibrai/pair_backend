package org.program.pair.domain.chat.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Contexte d'une conversation, lu en une jointure plutôt qu'en trois allers.
 *
 * <p>Alimente les champs de contexte de {@link ConversationSummaryDto} et de
 * {@link ConversationDetailDto}. Tous les champs sauf {@code conversationId}
 * sont nuls pour une conversation née hors de tout programme — depuis un profil,
 * par exemple : le client affiche alors l'en-tête sans contexte et ne grise rien.
 */
public record ConversationContextDto(
    UUID conversationId,
    UUID programId,
    String programTitle,
    String activityName,
    UUID scheduleId,
    Instant scheduleStartsAt,
    Instant scheduleEndsAt
) {

    /** Contexte vide, pour une conversation qui n'en a pas. */
    public static ConversationContextDto empty(UUID conversationId) {
        return new ConversationContextDto(conversationId, null, null, null, null, null, null);
    }
}
