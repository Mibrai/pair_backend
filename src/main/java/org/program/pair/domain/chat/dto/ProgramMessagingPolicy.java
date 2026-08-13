package org.program.pair.domain.chat.dto;

import java.util.UUID;

/**
 * Ce que la messagerie a besoin de savoir d'un programme : qui en est l'auteur,
 * et s'il accepte les messages de ses participants.
 *
 * <p>Projection plutôt que l'entité {@code Program} : le paquet {@code program}
 * dépend déjà de {@code chat} ({@code SlotService} ouvre une conversation en
 * rejoignant un créneau), et importer l'entité en retour fermerait le cycle.
 * Même choix que {@link ConversationContextDto}. Elle évite aussi de charger un
 * programme entier, puis son {@code UserActivity}, puis son utilisateur, pour
 * lire un identifiant et un booléen.
 */
public record ProgramMessagingPolicy(
    UUID programId,
    UUID authorId,
    Boolean allowParticipantMessages
) {

    /** L'appelant est-il refusé par ce réglage en écrivant à {@code targetId} ? */
    public boolean refuses(UUID senderId, UUID targetId) {
        return !Boolean.TRUE.equals(allowParticipantMessages)
            && targetId.equals(authorId)
            && !senderId.equals(authorId);
    }
}
