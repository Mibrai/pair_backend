package org.program.pair.domain.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Un partage de position ponctuel : un point, capturé maintenant, "
    + "qui cesse d'être servi à son échéance. Il n'existe pas de partage continu — "
    + "renouveler suppose de renvoyer un message, donc de le laisser visible dans le fil.")
public record ShareLocationRequest(

    @NotNull
    @DecimalMin(value = "-90.0", message = "La latitude doit être comprise entre -90 et 90.")
    @DecimalMax(value = "90.0", message = "La latitude doit être comprise entre -90 et 90.")
    Double lat,

    @NotNull
    @DecimalMin(value = "-180.0", message = "La longitude doit être comprise entre -180 et 180.")
    @DecimalMax(value = "180.0", message = "La longitude doit être comprise entre -180 et 180.")
    Double lng,

    @Schema(description = "Durée du partage, en minutes. Absente : 30, le maximum. "
        + "Au-delà de 30, la requête est refusée plutôt que rabotée en silence — "
        + "l'appelant doit savoir que sa durée n'a pas été retenue.",
        defaultValue = "30", minimum = "1", maximum = "30")
    @Min(value = 1, message = "La durée du partage doit valoir au moins une minute.")
    @Max(value = 30, message = "Un partage de position ne peut pas dépasser 30 minutes.")
    Integer expiresInMinutes,

    @Schema(description = "Mot joint au partage, facultatif. À défaut, le message porte "
        + "un texte neutre : la colonne de contenu n'accepte pas le vide, et un fil qui "
        + "afficherait une bulle sans rien dire se lirait comme un message perdu.")
    @Size(max = 200, message = "Le mot joint ne peut pas dépasser 200 caractères.")
    String note
) {}
