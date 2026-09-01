package org.program.pair.domain.sms;

/**
 * Envoyer un SMS, et rendre compte de ce qui s'est passé.
 *
 * <p>Une interface, deux implémentations, sur le même patron que les notifications
 * push : {@code NoOpSmsService} quand aucun fournisseur n'est configuré (il
 * journalise et rend un échec explicite), et une implémentation réelle (Twilio,
 * région UE — §7.2) activée par configuration. Le reste du module ne connaît que
 * cette interface : la boucle d'escalade écrit un message dans l'outbox, un
 * balayage l'en sort et appelle {@code send}, sans savoir qui envoie.
 *
 * <p><b>Ce que le résultat porte, et pourquoi.</b> {@link SmsSendResult} distingue
 * l'échec de l'envoi de l'échec de la remise. Un {@code accepted=false} dit que le
 * fournisseur a refusé la requête — on réessaiera. Un {@code accepted=true} avec un
 * {@code providerMessageId} dit que le fournisseur a pris le message ; c'est cet
 * identifiant qui permettra, plus tard, de recouper l'accusé de remise (DLR) pour
 * mesurer le SLO. Le canal alphanumérique français ne permet pas de recevoir de
 * réponse — le gabarit ② renvoie donc toujours vers la page de statut, jamais vers
 * une réponse au SMS.
 */
public interface SmsService {

    SmsSendResult send(String toE164, String body);

    /** Vrai si un vrai fournisseur est branché — pour ne pas promettre un SLO qu'on ne tient pas. */
    boolean isEnabled();

    /**
     * L'issue d'un envoi.
     *
     * @param accepted          le fournisseur a-t-il pris le message en charge
     * @param providerMessageId identifiant du message chez le fournisseur, pour
     *                          recouper l'accusé de remise ; nul si non accepté
     * @param error             cause du refus, pour le journal ; nul si accepté
     */
    record SmsSendResult(boolean accepted, String providerMessageId, String error) {

        public static SmsSendResult accepted(String providerMessageId) {
            return new SmsSendResult(true, providerMessageId, null);
        }

        public static SmsSendResult refused(String error) {
            return new SmsSendResult(false, null, error);
        }
    }
}
