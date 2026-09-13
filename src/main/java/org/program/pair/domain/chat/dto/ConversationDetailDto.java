package org.program.pair.domain.chat.dto;

import org.program.pair.domain.user.dto.UserPublicDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ConversationDetailDto(
    UUID id,
    String type,
    List<UserPublicDto> members,
    // Même contexte que ConversationSummaryDto, et pour la même raison : l'écran
    // de conversation affiche l'en-tête sans repasser par la liste.
    String activityContextName,
    UUID programId,
    String programTitle,
    String activityName,
    UUID scheduleId,
    Instant scheduleStartsAt,
    Instant scheduleEndsAt,
    // Voir ConversationSummaryDto : nommer un fil de groupe demande un titre,
    // pas un interlocuteur.
    String title,
    Integer memberCount,
    Instant createdAt,

    // Pourquoi ce fil ne s'écrit plus, ou null s'il s'écrit. C'est l'écran de
    // conversation qui en a le plus besoin : c'est lui qui porte le composeur, et
    // c'est par lui qu'on ouvre un fil dont la liste ne parle plus (une
    // conversation avec quelqu'un de bloqué quitte la liste des deux côtés, sans
    // que son historique cesse d'être lisible — D6, preuve pour un signalement).
    ConversationReadOnlyReason readOnlyReason
) {}
