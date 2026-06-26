package org.program.pair.domain.chat.dto;

import java.time.Instant;
import java.util.UUID;

public record MessageDto(
    UUID id,
    UUID conversationId,
    UUID senderId,
    String senderName,
    String senderAvatarUrl,
    String content,
    String status,
    Instant sentAt
) {}
