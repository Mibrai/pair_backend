package org.program.pair.domain.activity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@Schema(description = "Ce que chaque filtre de l'Explorer rendrait s'il était coché. "
    + "Les compteurs portent sur la zone, les catégories et l'expiration demandées, "
    + "et IGNORENT les filtres de même nature — sans quoi toutes les cases non cochées "
    + "afficheraient zéro et passeraient pour des impasses.")
public record ActivityFacetsDto(

    @Schema(description = "Total des entrées de la zone, tous filtres personnels et de "
        + "niveau confondus.")
    long total,

    @Schema(description = "Par niveau déclaré. La clé « UNSPECIFIED » regroupe les entrées "
        + "dont l'organisateur n'a déclaré aucun niveau : elles comptent dans le total, et "
        + "les ranger sous « ANY » inventerait une déclaration que personne n'a faite.")
    Map<String, Long> byLevel,

    @Schema(description = "Combien portent une activité que l'appelant pratique. Zéro pour "
        + "un appelant sans activité déclarée — et zéro aussi, faute d'identité, sur un "
        + "appel non authentifié.")
    long myActivities,

    @Schema(description = "Combien l'appelant suit.")
    long subscribed
) {}
