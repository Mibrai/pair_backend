package org.program.pair.domain.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EditMessageRequest(
    @NotBlank(message = "Le contenu ne peut pas être vide")
    @Size(max = 4000, message = "Le message ne peut pas dépasser 4000 caractères")
    String content
) {}
