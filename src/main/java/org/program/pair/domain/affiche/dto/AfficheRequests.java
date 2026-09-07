package org.program.pair.domain.affiche.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Les corps de requête du module « affiche », en camelCase comme le reste de
 * l'API.
 */
public final class AfficheRequests {

    private AfficheRequests() {}

    /**
     * Publication — ou republication — d'une affiche.
     *
     * <p>{@code String} pour l'audience et non {@code AfficheAudience} : une
     * valeur hors vocabulaire doit ressortir en {@code 422 AFFICHE_INVALID_AUDIENCE},
     * nommé et traduisible. Typée, elle échouerait à la désérialisation et
     * produirait un {@code 400 INVALID_JSON} dont le client ne saurait pas faire
     * une phrase. Même raison que {@code RecapRequests.VibesRequest}.
     */
    public record PublishRequest(

        @Schema(description = "Clé de motif, opaque pour le serveur. Obligatoire. "
            + "Lettres, chiffres, tiret, tiret bas et point, 40 caractères au plus.",
            example = "PREMIERE_FOIS")
        @Size(max = 40) String motif,

        @Schema(description = "NOBODY, SUBSCRIBERS ou EVERYONE. Absente ou nulle vaut "
            + "NOBODY : une affiche dont l'audience n'a pas été choisie n'est vue de "
            + "personne.")
        String audience,

        @Schema(description = "Début de la séance affichée, quand le créneau est "
            + "récurrent et qu'on y est venu plusieurs fois. Absent, c'est la présence "
            + "confirmée la plus récente sur ce créneau qui est prise — le cas de "
            + "l'écrasante majorité des créneaux, qui n'ont qu'une séance.",
            example = "2026-09-05T18:00:00Z", nullable = true)
        Instant slotStartedAt
    ) {}
}
