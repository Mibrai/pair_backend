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
    Instant createdAt
) {}
