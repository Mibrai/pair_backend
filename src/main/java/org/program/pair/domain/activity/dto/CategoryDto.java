package org.program.pair.domain.activity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public record CategoryDto(
    UUID id,
    String name,
    String icon,
    @Schema(
        description = "Nom de rampe (ex. \"orange-red\") — jamais un code hexadécimal, "
            + "jamais null. Chaque client résout ce nom dans sa propre palette visuelle.",
        example = "orange-red"
    )
    String colorRamp,

    @Schema(description = "Nombre d'abonnés de type CATEGORY. **Nul hors de "
        + "GET /api/categories** : partout ailleurs, CategoryDto est imbriqué (dans "
        + "ActivityDto notamment) et le compter y coûterait une requête par activité. "
        + "Null dit « non calculé ici » ; un 0 dirait « personne », ce qui serait faux.")
    Long subscriberCount,

    @Schema(description = "L'appelant suit-il cette catégorie ? **Nul hors de "
        + "GET /api/categories**, pour la même raison. Faux — et non nul — quand "
        + "l'appelant est anonyme : GET /api/categories est une route publique, et "
        + "l'absence d'identité n'est pas une absence d'abonnement. Ne pas s'en servir "
        + "comme source de vérité hors session.")
    Boolean subscribed
) {

    /** Rendu imbriqué : ni compteur ni état, faute de contexte pour les calculer. */
    public static CategoryDto nested(UUID id, String name, String icon, String colorRamp) {
        return new CategoryDto(id, name, icon, colorRamp, null, null);
    }
}
