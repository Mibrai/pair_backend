package org.program.pair.domain.program.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import org.program.pair.domain.program.PlaceType;

import java.time.Instant;

public record UpdateScheduleRequest(
    @Size(max = 200) String placeName,
    PlaceType placeType,
    @DecimalMin("-90") @DecimalMax("90") Double lat,
    @DecimalMin("-180") @DecimalMax("180") Double lng,
    String addressPublic,
    Boolean showExactAddress,

    // Voir CreateScheduleRequest.city : nulle laisse la valeur en place.
    @Schema(description = "Absente ou null : inchangée. Chaîne vide : retirée.")
    @Size(max = 120) String city,

    Instant startsAt,
    // Absente, la fin reste celle en place : une mise à jour ne peut pas la
    // retirer (P-BL-15).
    @Schema(description = "Absente ou null : inchangée. Ne se retire pas : une séance a toujours une fin.")
    Instant endsAt,

    @Schema(description = "Règle RFC 5545 sans le préfixe RRULE:. Absente ou null : inchangée. "
        + "Chaîne vide : retirée — le créneau devient une séance unique, à la date de sa prochaine "
        + "séance ; ses inscrits y restent, aucune séance ne suit. Aucune notification ne part.")
    String recurrenceRule,

    @Schema(description = "Absent ou null : inchangé. 0 : sans limite. Sinon au moins 1 ; une "
        + "capacité relevée ou retirée fait entrer la liste d'attente.")
    @Min(0) Integer maxParticipants,
    Boolean isOpenToPartners,

    @Schema(description = "Absent ou null : inchangé. Chaîne vide : retiré.")
    @Size(max = 300) String welcomeNote,

    // Chaîne vide pour retirer la langue déclarée : null veut dire « ne touche
    // pas », comme partout ailleurs dans cette requête de mise à jour partielle.
    @Size(max = 5) String primaryLanguage,

    // Liste vide pour tout retirer ; null veut dire « ne touche pas », comme
    // partout ailleurs dans cette mise à jour partielle.
    java.util.Set<org.program.pair.domain.program.AccessibilityTag> accessibilityTags,

    // Clé absente ou null : le niveau déclaré reste en place — c'est ce qui
    // permet à un client qui ne connaît pas encore ce champ de modifier un
    // créneau sans l'effacer. Chaîne vide pour revenir à « non précisé ».
    @Schema(description = "Niveau attendu pour la séance. Absent ou null : inchangé. "
        + "Chaîne vide : retiré, le créneau redevient « non précisé ». Sinon un nom de "
        + "ActivityLevel.",
        allowableValues = {"BEGINNER", "INTERMEDIATE", "ADVANCED", "EXPERT", "ANY", ""})
    String level
) {}
