package org.program.pair.domain.watch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Valider son arrivée sur place.
 *
 * <p>Le corps est vide dans le cas courant : le code de retour est tiré par le
 * serveur et rendu une seule fois. Un champ facultatif, {@code duressCode},
 * permet à l'utilisateur de fixer <b>son</b> code de contrainte — un code
 * mémorisable qu'il choisit, distinct du code de retour tiré par le serveur, et
 * qui, présenté à la clôture, répond comme un succès tout en déclenchant l'alerte
 * en silence.
 *
 * <p><b>Extension de contrat à signaler au chantier mobile.</b> La demande
 * décrivait {@code arrival} avec un corps vide et laissait le mécanisme de
 * création du code de contrainte implicite. Le rendre facultatif ici est la façon
 * la plus simple de le rendre réel sans inventer d'écran : un code de contrainte
 * doit être mémorisable, donc choisi par la personne, pas tiré au hasard.
 */
@Schema(description = "Validation d'arrivée sur place.")
public record ArrivalRequest(

    @Schema(description = "Code de contrainte, facultatif. Choisi par l'utilisateur ; "
        + "présenté à la clôture, il déclenche l'alerte en silence.")
    @Size(min = 4, max = 32, message = "Le code de contrainte doit faire de 4 à 32 caractères.")
    String duressCode
) {}
