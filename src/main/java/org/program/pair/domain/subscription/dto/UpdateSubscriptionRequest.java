package org.program.pair.domain.subscription.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/**
 * Corps du {@code PATCH} d'un abonnement. Modification <b>partielle</b> : seuls
 * les champs présents sont appliqués, les autres restent inchangés.
 *
 * <p><b>Pourquoi {@code clearScope} existe.</b> En JSON, un champ absent et un
 * champ explicitement {@code null} arrivent tous deux à {@code null} dans un
 * record — rien ne les distingue sans machinerie supplémentaire. Retirer la
 * portée géographique d'un abonnement était donc inexprimable, et l'alternative
 * — un {@code PATCH} qui remplace la portée en bloc à chaque appel — aurait fait
 * qu'un simple changement de niveau efface silencieusement un rayon réglé.
 * Un drapeau explicite ne se déclenche pas par accident.
 *
 * <p>C'est un ajout à la demande client, et le seul du lot A.
 */
public record UpdateSubscriptionRequest(

    @Schema(description = "ALL | NEW_ONLY | MUTED. Absent : niveau inchangé.",
        example = "NEW_ONLY")
    @Pattern(regexp = "ALL|NEW_ONLY|MUTED",
        message = "Le niveau doit valoir ALL, NEW_ONLY ou MUTED.")
    String level,

    @Schema(description = "Nouveau centre de la portée. Les trois champs de portée "
        + "s'appliquent ensemble ; absents, la portée reste inchangée.")
    @DecimalMin(value = "-90", message = "La latitude doit être comprise entre -90 et 90.")
    @DecimalMax(value = "90", message = "La latitude doit être comprise entre -90 et 90.")
    Double lat,

    @DecimalMin(value = "-180", message = "La longitude doit être comprise entre -180 et 180.")
    @DecimalMax(value = "180", message = "La longitude doit être comprise entre -180 et 180.")
    Double lng,

    @Schema(description = "Rayon en MÈTRES.")
    @Min(value = 1, message = "Le rayon doit valoir au moins 1 mètre.")
    @Max(value = 200_000, message = "Le rayon ne peut dépasser 200000 mètres.")
    Integer radiusMeters,

    @Schema(description = "Vrai pour retirer la portée géographique : l'abonnement "
        + "notifie de nouveau sans contrainte de distance. Incompatible avec lat/lng/"
        + "radiusMeters dans le même appel — poser et retirer une portée d'un même geste "
        + "n'a pas de sens, et le silence sur l'ordre d'application serait un piège.",
        defaultValue = "false")
    Boolean clearScope
) {

    /** Vrai si l'appel demande à poser une portée (les trois champs présents). */
    public boolean setsScope() {
        return lat != null && lng != null && radiusMeters != null;
    }

    /** Vrai si l'appel mentionne la portée, complètement ou non. */
    public boolean mentionsScope() {
        return lat != null || lng != null || radiusMeters != null;
    }

    /** Vrai si l'appel demande explicitement le retrait de la portée. */
    public boolean clearsScope() {
        return Boolean.TRUE.equals(clearScope);
    }
}
