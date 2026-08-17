package org.program.pair.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record PrivacySettingsDto(
    String profileVisibility,
    Boolean showAge,
    Boolean showLastActive,
    Boolean showLocation,
    String allowMessages,
    Boolean showOnMap,

    @Schema(description = "OPEN | NOBODY. Qui peut me suivre — par mon profil comme par "
        + "l'une de mes activités : suivre ce que quelqu'un propose, c'est le suivre. "
        + "S'abonner à une catégorie n'est pas concerné, une catégorie n'appartenant à "
        + "personne. Ne vaut que pour les abonnements À VENIR : passer à NOBODY refuse "
        + "les nouveaux, il ne supprime pas les existants et ne les fait pas taire. Le "
        + "libellé du réglage doit donc dire « empêcher de nouveaux abonnements », et non "
        + "« personne ne peut me suivre » qui promettrait un effet rétroactif.",
        example = "OPEN")
    String allowSubscriptions
) {}
