package org.program.pair.domain.subscription.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Portée géographique d'un abonnement {@code CATEGORY}, au {@code POST}.
 *
 * <p>Le corps entier est <b>optionnel</b> : absent, l'abonnement notifie sans
 * contrainte géographique, comme avant. Présent, les trois champs sont requis
 * ensemble — un rayon sans centre, ou un centre sans rayon, ne décrit rien.
 *
 * <p>Les catégories sont un référentiel mondial : sans portée, un abonnement
 * « yoga » notifie un Parisien d'une séance à Berlin.
 */
public record SubscriptionScopeRequest(

    @Schema(description = "Latitude du centre de la portée.", example = "48.8566")
    @DecimalMin(value = "-90", message = "La latitude doit être comprise entre -90 et 90.")
    @DecimalMax(value = "90", message = "La latitude doit être comprise entre -90 et 90.")
    Double lat,

    @Schema(description = "Longitude du centre.", example = "2.3522")
    @DecimalMin(value = "-180", message = "La longitude doit être comprise entre -180 et 180.")
    @DecimalMax(value = "180", message = "La longitude doit être comprise entre -180 et 180.")
    Double lng,

    @Schema(description = "Rayon en MÈTRES, borné entre 1 et 200000 comme sur "
        + "/activities/browse et /map/activities.", example = "20000")
    @Min(value = 1, message = "Le rayon doit valoir au moins 1 mètre.")
    @Max(value = 200_000, message = "Le rayon ne peut dépasser 200000 mètres.")
    Integer radiusMeters
) {

    /** Vrai si aucun des trois champs n'est renseigné : pas de portée demandée. */
    public boolean isEmpty() {
        return lat == null && lng == null && radiusMeters == null;
    }

    /** Vrai si les trois champs sont renseignés : portée complète. */
    public boolean isComplete() {
        return lat != null && lng != null && radiusMeters != null;
    }
}
