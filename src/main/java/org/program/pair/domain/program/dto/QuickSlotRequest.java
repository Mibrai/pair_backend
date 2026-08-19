package org.program.pair.domain.program.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.program.pair.domain.activity.ActivityFormat;
import org.program.pair.domain.activity.ActivityLevel;
import org.program.pair.domain.program.PlaceType;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Tout ce qu'il faut pour publier un créneau en une fois : une "
    + "activité, une date, un lieu. Le programme et son titre sont fabriqués par le "
    + "serveur.")
public record QuickSlotRequest(

    @Schema(description = "Activité pratiquée. Elle est ajoutée au profil de l'appelant "
        + "si elle n'y est pas encore — chercher quelqu'un pour une activité, c'est la "
        + "pratiquer.")
    @NotNull UUID activityId,

    @NotNull Instant startsAt,

    @Schema(description = "Fin de la séance. Absente, elle vaut le début plus deux "
        + "heures — la même convention que partout ailleurs.")
    Instant endsAt,

    @NotBlank @Size(max = 200) String placeName,

    @NotNull PlaceType placeType,

    @Schema(description = "Obligatoires pour un lieu physique, ignorées pour un lieu en "
        + "ligne.")
    @DecimalMin("-90") @DecimalMax("90") Double lat,
    @DecimalMin("-180") @DecimalMax("180") Double lng,

    @Schema(description = "Adresse affichée. Obligatoire quand le lieu est PUBLIC. Pour "
        + "un lieu PRIVATE elle n'est retenue que si showExactAddress vaut true — "
        + "sinon elle est acceptée puis ignorée, ce qui vaut mieux que de publier "
        + "l'adresse de chez quelqu'un.")
    @Size(max = 300) String addressPublic,

    Boolean showExactAddress,

    @Schema(description = "Ville, facultative et jamais devinée.")
    @Size(max = 120) String city,

    @Schema(description = "Nombre maximum de participants. Absent, le créneau est sans "
        + "limite.")
    @Min(1) Integer maxParticipants,

    @Size(max = 300) String welcomeNote,

    @Schema(description = "Niveau attendu. Absent, vaut ANY.")
    ActivityLevel level,

    @Schema(description = "Format. Absent, vaut ANY.")
    ActivityFormat format
) {}
