package org.program.pair.domain.chat.dto;

import org.program.pair.domain.user.dto.UserPublicDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ConversationDetailDto(
    UUID id,
    String type,
    List<UserPublicDto> members,
    String activityContextName,
    Instant createdAt
) {}
