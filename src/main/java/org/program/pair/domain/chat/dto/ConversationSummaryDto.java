package org.program.pair.domain.chat.dto;

import org.program.pair.domain.user.dto.UserPublicDto;
import java.time.Instant;
import java.util.UUID;

public record ConversationSummaryDto(
    UUID id,
    String type,
    UserPublicDto otherUser,
    String activityContextName,
    String lastMessageContent,
    Instant lastMessageAt,
    int unreadCount
) {}
