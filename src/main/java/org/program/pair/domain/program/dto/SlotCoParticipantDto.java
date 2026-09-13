package org.program.pair.domain.program.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Un autre inscrit, tel qu'un inscrit le voit (demande mobile du 13/09, P-MU-28).
 *
 * <p><b>Un DTO distinct, et volontairement pauvre.</b> L'hôte reçoit le profil
 * public, le message d'inscription et l'état d'arrivée ; un inscrit ne reçoit que
 * de quoi reconnaître quelqu'un en arrivant : un prénom et un avatar. Ni
 * biographie, ni activités, ni présence en ligne, ni signal de fiabilité, ni
 * arrivée — rien de ce qui ferait d'une liste d'inscrits un annuaire.
 */
@Schema(description = "Un autre inscrit confirmé du créneau, vu par un inscrit : prénom et avatar seulement.")
public record SlotCoParticipantDto(
    UUID userId,

    @Schema(description = "Prénom, déduit du nom affiché ; le nom affiché entier s'il n'a qu'un mot.")
    String firstName,

    String avatarUrl
) {}
