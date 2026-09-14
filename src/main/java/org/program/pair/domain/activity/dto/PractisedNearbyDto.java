package org.program.pair.domain.activity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Une activité, et le fait qu'elle se pratique autour d'une position. Un booléen,
 * jamais un compte : voir {@code PractisedNearbyService}.
 */
@Schema(description = "Une activité demandée, et le fait qu'elle se pratique déjà autour de la "
    + "position donnée. Aucun décompte n'est servi, ni ici ni dans un en-tête.")
public record PractisedNearbyDto(

    UUID activityId,

    @Schema(description = "Vrai quand au moins 3 personnes distinctes, autres que l'appelant, "
        + "déclarent cette activité en la montrant sur la carte, avec un compte actif et une "
        + "position rendue publique, à moins de 25 km de la position reçue arrondie au "
        + "centième de degré (environ 1 km). Une personne bloquée, dans un sens ou dans "
        + "l'autre, ne compte pas. Faux sinon — ce qui ne veut pas dire que personne ne la "
        + "pratique, seulement que personne de visible n'en dit assez.")
    boolean practisedNearby
) {}
