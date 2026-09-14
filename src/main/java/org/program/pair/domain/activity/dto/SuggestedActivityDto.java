package org.program.pair.domain.activity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Une activité proposée à quelqu'un qui n'en a encore déclaré aucune, "
    + "les plus pratiquées autour de lui d'abord. Aucun décompte n'est servi : pour savoir si "
    + "une activité se pratique près d'une position, GET /api/activities/practised-nearby.")
public record SuggestedActivityDto(

    UUID id,
    String name,
    String slug,
    String icon,
    String imageUrl,
    UUID categoryId,
    String categoryName,

    // practitionersNearby a quitté ce DTO le 14/09 (demande mobile badges BIS) :
    // jamais un compte là où un booléen suffit. Le décompte trie encore les
    // propositions dans la requête, et n'en sort plus. « Se pratique ici » :
    // GET /api/activities/practised-nearby.

    @Schema(description = "Vrai si la proposition vient du repli national et non du "
        + "voisinage. Le client peut vouloir le dire autrement — « populaire sur meetDo » "
        + "plutôt que « près de chez vous » — sans avoir à le deviner.")
    boolean fallback
) {}
