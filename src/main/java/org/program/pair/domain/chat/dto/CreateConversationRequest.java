package org.program.pair.domain.chat.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateConversationRequest(
    @NotNull UUID targetUserId,
    UUID activityContextId,
    /**
     * Programme au titre duquel la conversation est ouverte, s'il y en a un.
     *
     * <p>Facultatif, et c'est ce qui rend applicable le refus de l'auteur : une
     * activité — « Yoga » — porte autant de programmes que d'auteurs, donc
     * {@code activityContextId} ne permet pas de savoir quel réglage consulter.
     * Sans {@code programId}, aucun programme n'est en jeu et rien n'est refusé.
     *
     * <p>Sert aussi de contexte à la conversation quand elle ne naît pas d'un
     * créneau (voir {@code ChatService}).
     */
    UUID programId
) {}
