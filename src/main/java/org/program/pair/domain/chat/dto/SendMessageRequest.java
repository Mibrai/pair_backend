package org.program.pair.domain.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record SendMessageRequest(
    @NotNull UUID conversationId,
    @NotBlank @Size(max = 4000) String content
) {}
