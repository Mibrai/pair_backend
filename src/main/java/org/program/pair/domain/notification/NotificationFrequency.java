package org.program.pair.domain.notification;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Fréquence d'envoi des e-mails d'un type de notification.
 *
 * <p><b>Seule {@link #IMMEDIATE} existe réellement</b> (P-BA-18 option B, P-BL-20,
 * décision du 13/09). Aucun résumé quotidien ou hebdomadaire n'a jamais été
 * envoyé : {@code notify} n'envoyait l'e-mail qu'en immédiat, et les deux autres
 * valeurs le faisaient disparaître en silence. Elles restent acceptées pour que
 * l'app publiée n'échoue pas, et sont enregistrées comme {@code IMMEDIATE}.
 */
@Schema(description = "Seule IMMEDIATE a un effet. DAILY_DIGEST et WEEKLY sont dépréciées : "
    + "acceptées, enregistrées et appliquées comme IMMEDIATE — aucun résumé n'est envoyé.")
public enum NotificationFrequency {
    IMMEDIATE,
    /** Dépréciée : traitée et enregistrée comme {@link #IMMEDIATE}. */
    @Deprecated
    DAILY_DIGEST,
    /** Dépréciée : traitée et enregistrée comme {@link #IMMEDIATE}. */
    @Deprecated
    WEEKLY
}
