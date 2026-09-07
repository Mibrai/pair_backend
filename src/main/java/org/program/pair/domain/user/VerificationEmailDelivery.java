package org.program.pair.domain.user;

import org.program.pair.domain.outbox.OutboxDelivery;

/**
 * Le sort du dernier e-mail de vérification envoyé à ce compte.
 *
 * <p><b>Un état, pas un journal.</b> Le client demandait « celui du dernier
 * envoi », sans historique ni horodatage : un renvoi remet donc le compteur à
 * {@link #PENDING} et efface ce qu'on savait du précédent, ce qui est le
 * comportement attendu — c'est le dernier envoi qui décide de ce que l'écran a
 * le droit d'affirmer.
 *
 * <p><b>Pourquoi {@link #DELIVERED} existe.</b> Le contrat proposé par le client
 * ne le portait pas, et c'était son seul défaut : sans lui, on ne sait dire que
 * « accepté par le fournisseur », c'est-à-dire exactement ce que le ticket du
 * 07/09 reprochait. C'est le seul état qui autorise l'écran à dire « il est
 * arrivé, regardez vos indésirables » comme une certitude et non une politesse.
 *
 * <p><b>Le vocabulaire est celui d'{@code alertDelivery}</b>
 * ({@code WatchService.deliveryOf}), à dessein : les deux champs répondent à la
 * même question sur deux canaux, et deux vocabulaires auraient fini par
 * diverger. {@code COMPLAINED} y est replié sur {@link #BOUNCED} pour la même
 * raison qu'ailleurs — du point de vue de qui attend son e-mail, un message
 * classé indésirable par le destinataire n'est pas arrivé.
 */
public enum VerificationEmailDelivery {

    /** Aucun envoi tenté — compte créé sans e-mail, ou envoi désactivé (développement). */
    NONE,

    /** Déposé, pas encore remis au fournisseur. C'est l'état juste après l'inscription. */
    PENDING,

    /** Le fournisseur a pris le message. Il ne dit pas qu'il est arrivé. */
    SENT,

    /** Arrivé dans la boîte, l'accusé de remise le dit. */
    DELIVERED,

    /**
     * L'adresse a refusé le message — rebond dur, ou classement en indésirable.
     * C'est l'état qui rend un changement d'adresse nécessaire, et c'est pour lui
     * que {@code POST /api/users/me/change-email} existe.
     */
    BOUNCED,

    /**
     * Nous n'avons pas pu le remettre au fournisseur : refus à la soumission, ou
     * essais épuisés. Ce n'est <b>pas</b> un défaut de l'adresse — c'est l'état
     * que produirait, entre autres, un compte d'envoi resté en mode d'essai.
     */
    FAILED;

    /**
     * Ce qu'un accusé de remise apprend, traduit dans ce vocabulaire.
     *
     * <p>{@code UNKNOWN} et {@code DELAYED} ne rendent rien : le premier ne dit
     * rien de neuf, le second dit « pas encore abandonné », et faire reculer un
     * compte de {@code SENT} à un état d'attente afficherait un doute là où il
     * n'y a qu'un retard.
     */
    public static VerificationEmailDelivery depuis(OutboxDelivery etat) {
        return switch (etat) {
            case DELIVERED -> DELIVERED;
            case BOUNCED, COMPLAINED -> BOUNCED;
            case UNKNOWN, DELAYED -> null;
        };
    }

    /**
     * Ce fait doit-il céder la place à {@code candidat} ?
     *
     * <p>Deux règles, les mêmes que pour l'outbox : un rebond est terminal et ne
     * se laisse pas écraser par un « délivré » venu d'ailleurs, et on ne
     * redescend pas d'un « arrivé » vers un simple « parti ». Les accusés de
     * remise n'arrivent pas dans l'ordre où les faits se produisent.
     */
    public boolean cedeLaPlaceA(VerificationEmailDelivery candidat) {
        if (candidat == null || candidat == this) {
            return false;
        }
        if (this == BOUNCED) {
            return false;
        }
        return !(this == DELIVERED && candidat == SENT);
    }
}
