package org.program.pair.domain.activity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Une activité proposée à quelqu'un qui n'en a encore déclaré aucune.")
public record SuggestedActivityDto(

    UUID id,
    String name,
    String slug,
    String icon,
    String imageUrl,
    UUID categoryId,
    String categoryName,

    @Schema(description = "Nombre de personnes qui déclarent cette activité dans le rayon, "
        + "et acceptent d'être vues sur la carte. Sert à dire « c'est vivant ici », pas à "
        + "classer qui que ce soit : il compte des activités, jamais des personnes "
        + "les unes par rapport aux autres. Vaut 0 sur les propositions de repli, qui ne "
        + "viennent pas du voisinage.")
    long practitionersNearby,

    @Schema(description = "Vrai si la proposition vient du repli national et non du "
        + "voisinage. Le client peut vouloir le dire autrement — « populaire sur meetDo » "
        + "plutôt que « près de chez vous » — sans avoir à le deviner.")
    boolean fallback
) {}
