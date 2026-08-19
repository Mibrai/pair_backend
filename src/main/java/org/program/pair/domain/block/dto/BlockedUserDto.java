package org.program.pair.domain.block.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Quelqu'un que l'appelant a bloqué. Vu du seul bloqueur : cette "
    + "liste n'existe pour personne d'autre.")
public record BlockedUserDto(

    UUID userId,
    String displayName,
    String avatarUrl,

    @Schema(description = "Quand le blocage a été posé. Sert à ordonner la liste, et à "
        + "situer une décision qu'on a parfois oubliée.")
    Instant blockedAt
) {}
