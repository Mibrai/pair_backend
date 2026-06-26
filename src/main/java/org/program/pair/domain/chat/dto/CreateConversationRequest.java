package org.program.pair.domain.chat.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateConversationRequest(
    @NotNull UUID targetUserId,
    UUID activityContextId
) {}
