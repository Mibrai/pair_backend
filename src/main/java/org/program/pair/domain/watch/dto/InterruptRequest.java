package org.program.pair.domain.watch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Interrompre une séance en cours : on repart plus tôt.
 *
 * <p>La branche qui compte est {@code alreadyHome=false}. Refermer la veille en
 * <b>quittant</b> le lieu l'éteindrait juste avant le trajet de retour — celui-là
 * même qu'on voulait couvrir, et d'autant plus si la personne part parce que ça se
 * passait mal. On recale donc l'échéance sur le trajet, et le code reste demandé à
 * l'arrivée.
 *
 * <p>{@code travelMinutes} est <b>envoyé par l'app</b> (45 par défaut, ajusté par
 * l'utilisateur) : le serveur applique la durée reçue, sans la calculer ni
 * l'estimer — cela supposerait de connaître le domicile, qu'on refuse de stocker.
 */
@Schema(description = "Interruption d'une séance en cours.")
public record InterruptRequest(

    @Schema(description = "Motif, facultatif — journalisé, jamais diffusé au contact.")
    @Size(max = 200, message = "Le motif est trop long.")
    String reason,

    @Schema(description = "Vrai si la personne est déjà rentrée ; faux si elle prend le trajet de retour.")
    boolean alreadyHome,

    @Schema(description = "Durée estimée du trajet de retour, en minutes (15 à 240). "
        + "Ignorée si alreadyHome. Défaut : 45.")
    Integer travelMinutes
) {}
