package org.program.pair.domain.safety.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Le lien à envoyer à un proche.")
public record SafetyShareLinkDto(

    @Schema(description = "Jeton opaque. Fourni pour que le client puisse composer "
        + "d'autres formes de partage ; l'URL ci-dessous suffit dans la plupart des cas.")
    String token,

    @Schema(description = "L'adresse complète à partager, absolue et en https — un "
        + "chemin relatif ne survivrait pas à un copier-coller dans une messagerie.")
    String url,

    @Schema(description = "Quand le lien cesse de fonctionner : six heures après la fin "
        + "prévue de la séance. Figé à la création, y compris sur un créneau récurrent.")
    Instant expiresAt
) {}
