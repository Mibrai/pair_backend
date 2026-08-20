package org.program.pair.domain.publicslot.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "L'adresse publique d'un créneau, à coller dans une conversation.")
public record PublicShareLinkDto(

    String token,

    @Schema(description = "L'adresse à partager, et la seule. C'est elle que les messageries "
        + "afficheront et que les liens universels intercepteront : /s/{jeton} pour un "
        + "créneau, /p/{jeton} pour un programme.")
    String shortUrl,

    @Schema(description = "La même page, à son adresse longue. Toutes deux rendent du HTML — "
        + "ce champ n'a jamais désigné une route de données, et pointer un programme sur son "
        + "JSON était un défaut, corrigé le 2026-08-20 : collée dans un message, la valeur "
        + "ouvrait un navigateur sur du texte brut.\n\n"
        + "En pratique, partagez shortUrl. Celle-ci n'existe que pour les contextes où une "
        + "adresse courte serait mal supportée.")
    String pageUrl,

    @Schema(description = "Faux si l'organisateur a retiré ce créneau du web ouvert : le "
        + "lien existe alors mais ne mène nulle part.")
    boolean shareable
) {}
