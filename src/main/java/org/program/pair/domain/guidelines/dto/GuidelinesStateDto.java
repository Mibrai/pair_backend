package org.program.pair.domain.guidelines.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Où en est cette personne vis-à-vis des règles de communauté.")
public record GuidelinesStateDto(

    @Schema(description = "La version en vigueur, celle que le client doit afficher et "
        + "renvoyer à l'acceptation. C'est le serveur qui la porte : un client ne peut "
        + "pas savoir seul que le texte a changé.")
    String currentVersion,

    @Schema(description = "La version acceptée, nulle si la personne n'a jamais accepté.")
    String acceptedVersion,

    @Schema(description = "Quand elle a accepté cette version-là.")
    Instant acceptedAt,

    @Schema(description = "Vrai s'il faut lui présenter les règles. Calculé par le "
        + "serveur pour que le client n'ait pas à comparer des versions — une "
        + "comparaison écrite des deux côtés finit par diverger.")
    boolean acceptanceRequired
) {}
