package org.program.pair.domain.chat.dto;

import org.program.pair.domain.user.dto.UserPublicDto;
import java.time.Instant;
import java.util.UUID;

public record ConversationSummaryDto(
    UUID id,
    String type,
    UserPublicDto otherUser,
    // Contexte : le programme, l'activité et la séance qui lient les membres.
    // Tous nullables — une conversation peut naître hors de tout programme.
    // activityName double activityContextName : même valeur, nom attendu par le
    // client aux côtés de programTitle.
    String activityContextName,
    UUID programId,
    String programTitle,
    String activityName,
    UUID scheduleId,
    Instant scheduleStartsAt,
    Instant scheduleEndsAt,
    String lastMessageContent,
    Instant lastMessageAt,
    int unreadCount
) {}
