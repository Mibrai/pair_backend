package org.program.pair.domain.recap.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Une ambiance et le nombre de personnes qui l'ont choisie.
 *
 * <p>Le serveur ne renvoie que la valeur d'enum, jamais de libellé : le client
 * l'affiche dans la langue de son utilisateur, et il n'y a rien à traduire ici.
 */
public record VibeCountDto(

    @Schema(description = "Valeur de SlotVibe. Le client tient les libellés dans ses trois "
        + "catalogues ; une valeur qu'il ne connaît pas est ignorée à la lecture.")
    String vibe,

    int count
) {}
