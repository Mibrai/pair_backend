package org.program.pair.domain.program.dto;

import jakarta.validation.constraints.*;
import org.program.pair.domain.program.PlaceType;

import java.time.Instant;

public record CreateScheduleRequest(
    @NotBlank @Size(max = 200) String placeName,
    @NotNull PlaceType placeType,
    // Obligatoires pour un lieu physique, interdites de sens pour un lieu en
    // ligne. La contrainte ne peut donc pas être portée par une annotation :
    // elle dépend de placeType, et c'est le service qui la vérifie — comme il
    // le fait déjà pour addressPublic. Les avoir déclarées @NotNull rendait tout
    // créneau ONLINE impossible à créer par l'API.
    @DecimalMin("-90") @DecimalMax("90") Double lat,
    @DecimalMin("-180") @DecimalMax("180") Double lng,
    String addressPublic,
    Boolean showExactAddress,

    // Ville du créneau, facultative. Contrairement à addressPublic, elle est
    // diffusable sans condition — c'est le grain de lieu que porte la
    // carte-souvenir. Absente, elle reste nulle : rien ne la devine.
    @Size(max = 120) String city,

    @NotNull Instant startsAt,
    Instant endsAt,
    String recurrenceRule,
    @Min(1) Integer maxParticipants,
    Boolean isOpenToPartners,
    @Size(max = 300) String welcomeNote,

    // Langue principale de la séance. Facultative, et jamais devinée : un
    // créneau qui n'en déclare pas reste visible de tous.
    @Size(max = 5) String primaryLanguage,

    // Étiquettes d'accueil. Déclaratives, jamais vérifiées : le contrat le dit,
    // l'interface doit le dire aussi.
    java.util.Set<org.program.pair.domain.program.AccessibilityTag> accessibilityTags
) {}
