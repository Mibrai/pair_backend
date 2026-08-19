package org.program.pair.domain.guidelines.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AcceptGuidelinesRequest(

    @Schema(description = "La version que le client vient d'afficher. Exigée, et refusée "
        + "si elle ne correspond pas à celle en vigueur : sans elle, une application "
        + "restée sur un texte ancien ferait enregistrer l'acceptation d'un texte que "
        + "personne n'a lu.")
    @NotBlank @Size(max = 10) String version
) {}
