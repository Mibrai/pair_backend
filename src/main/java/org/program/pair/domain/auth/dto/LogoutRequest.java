package org.program.pair.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Corps de {@code POST /api/auth/logout} (P-BS-03, P-BS-10). Les deux champs sont
 * facultatifs, et le corps lui-même aussi : la déconnexion répond toujours 204.
 */
@Schema(description = "Ce que la déconnexion ferme. Tout est facultatif, et la réponse est "
    + "toujours 204 — même pour un jeton inconnu, pour ne rien apprendre à qui essaie.")
public record LogoutRequest(

    @Schema(description = "Le jeton de rafraîchissement de l'appareil. Sa session est révoquée : "
        + "ni lui ni aucun jeton de la même session ne s'échangera plus.")
    @Size(max = 4096) String refreshToken,

    @Schema(description = "Le jeton de notification de l'appareil. Détaché du compte, pour "
        + "que plus aucune push ne parte vers un téléphone déconnecté. Pris en compte seulement "
        + "si le jeton de rafraîchissement désigne bien son propriétaire.")
    @Size(max = 4096) String deviceToken
) {}
