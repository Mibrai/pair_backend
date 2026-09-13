package org.program.pair.shared.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Le corps de tout refus de l'API (P-BA-11, étape 8).
 */
@Schema(description = "Corps de tout refus de l'API. `code` est stable et fait foi : c'est lui "
    + "qu'un client lit pour décider. `message` est destiné à l'utilisateur et suit "
    + "l'en-tête Accept-Language (fr par défaut, en, de) ; il peut changer de formulation "
    + "sans préavis. La liste des codes est celle de l'énumération ErrorCode du serveur, "
    + "volontairement non publiée comme enum : un code ajouté ne doit pas casser un client "
    + "généré — tolérer un code inconnu.")
public record ErrorResponse(
    @Schema(description = "Code stable du refus, par ex. VALIDATION_ERROR, TOKEN_EXPIRED, "
        + "SLOT_FULL. Tolérer une valeur inconnue.")
    String code,

    @Schema(description = "Message lisible, dans la langue demandée par Accept-Language.")
    String message,

    Instant timestamp
) {}
