package org.program.pair.domain.preference.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** La valeur d'un réglage privé. Opaque : le serveur ne l'interprète jamais. */
@Schema(description = "La valeur d'un réglage privé, opaque pour le serveur.")
public record PreferenceValue(

    @Schema(description = "Contenu libre, au plus 8192 caractères. Le serveur ne le lit pas, "
        + "ne l'indexe pas et ne le sert à personne d'autre que son propriétaire.")
    @NotNull(message = "La valeur est obligatoire.")
    @Size(max = 8192, message = "Une préférence ne peut pas dépasser 8192 caractères.")
    String value
) {}
