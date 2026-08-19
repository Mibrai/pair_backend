package org.program.pair.domain.publicslot.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "L'adresse publique d'un créneau, à coller dans une conversation.")
public record PublicShareLinkDto(

    String token,

    @Schema(description = "L'adresse courte, celle qu'on partage. C'est elle que les "
        + "messageries afficheront et que les liens universels intercepteront.")
    String shortUrl,

    @Schema(description = "L'adresse longue de la page. Même contenu ; fournie pour les "
        + "contextes où une redirection serait mal supportée.")
    String pageUrl,

    @Schema(description = "Faux si l'organisateur a retiré ce créneau du web ouvert : le "
        + "lien existe alors mais ne mène nulle part.")
    boolean shareable
) {}
