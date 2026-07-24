package org.program.pair.domain.program.dto;

import org.program.pair.domain.user.dto.UserPublicDto;

import java.time.Instant;
import java.util.UUID;

public record SlotParticipantDto(
    UUID participationId,
    UserPublicDto user,
    String status,
    String joinMessage,
    Instant createdAt
) {}
