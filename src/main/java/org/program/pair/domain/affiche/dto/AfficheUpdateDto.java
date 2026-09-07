package org.program.pair.domain.affiche.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Une personne qui a publié, et quand — rien d'autre.
 *
 * <p>Ni motif, ni texte, ni image, ni même le créneau : l'anneau sur l'avatar
 * n'a besoin que de savoir <i>chez qui</i> il doit s'allumer. Tout champ de plus
 * ferait de cette route un second chemin de lecture des affiches, avec ses
 * propres règles d'audience à maintenir en parallèle — c'est-à-dire à laisser
 * diverger.
 */
public record AfficheUpdateDto(

    UUID userId,

    @Schema(description = "La plus récente des publications de cette personne que "
        + "j'ai le droit de voir. Strictement postérieure au since demandé.")
    Instant latestPublishedAt
) {}
