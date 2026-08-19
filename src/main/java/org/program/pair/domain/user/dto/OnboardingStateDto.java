package org.program.pair.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Où en est le parcours d'accueil de l'appelant.")
public record OnboardingStateDto(

    @Schema(description = "Dernière étape franchie. Nulle pour un compte qui n'a rien "
        + "commencé. Un client qui reçoit une valeur inconnue doit la traiter comme "
        + "« en cours », jamais échouer : le parcours évoluera avant lui.")
    String step,

    @Schema(description = "Date de sortie du parcours, nulle tant qu'il est en cours. "
        + "Sortir en franchissant la dernière étape ou en passant donne le même "
        + "résultat ici — c'est le champ `skipped` qui les distingue.")
    Instant completedAt,

    @Schema(description = "Vrai si l'accueil est derrière la personne, quelle qu'en soit "
        + "la façon. C'est le seul champ dont le client a besoin pour décider où "
        + "atterrir au démarrage.")
    boolean completed
) {}
