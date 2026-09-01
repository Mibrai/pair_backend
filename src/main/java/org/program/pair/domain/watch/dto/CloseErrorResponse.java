package org.program.pair.domain.watch.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Le corps d'un {@code 409} sur la clôture par code : la même forme que les autres
 * erreurs, plus un {@code attemptsLeft} entier.
 *
 * <p><b>Pourquoi un entier à part, et pas seulement le message.</b> L'écran doit
 * dire « 2 essais restants — au 3ᵉ échec, votre contact est prévenu », et
 * l'annoncer <i>avant</i> le dernier essai. En extraire le nombre du message
 * traduit demanderait de parser une chaîne qui change à la première reformulation,
 * en silence, sur l'écran le moins pardonnant du module. Le message reste ce qu'il
 * est ; l'entier est là pour être lu directement.
 */
@Schema(description = "Erreur de clôture par code, avec le nombre d'essais restants.")
public record CloseErrorResponse(
    String code,
    String message,
    @Schema(description = "Essais restants avant blocage du code. 0 quand le code est verrouillé.")
    int attemptsLeft,
    Instant timestamp
) {}
