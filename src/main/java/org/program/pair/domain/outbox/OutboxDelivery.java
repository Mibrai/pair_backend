package org.program.pair.domain.outbox;

/**
 * Ce que le fournisseur rapporte sur la remise d'un message déjà envoyé.
 *
 * <p>Un axe distinct de {@link OutboxStatus}. {@code SENT} dit « le fournisseur a
 * pris le message » ; il ne dit pas s'il est arrivé. C'est cet axe-ci qui le dit,
 * quand le webhook du fournisseur le rappelle :
 *
 * <ul>
 *   <li>{@link #UNKNOWN} — remis au fournisseur, aucune nouvelle encore.</li>
 *   <li>{@link #DELIVERED} — arrivé.</li>
 *   <li>{@link #DELAYED} — retardé, pas encore abandonné.</li>
 *   <li>{@link #BOUNCED} — a rebondi : l'adresse est en faute, le proche n'a
 *       jamais reçu. Avec un seul canal, c'est l'échec qu'il fallait pouvoir voir.</li>
 *   <li>{@link #COMPLAINED} — marqué comme indésirable par le destinataire.</li>
 * </ul>
 */
public enum OutboxDelivery {
    UNKNOWN,
    DELIVERED,
    DELAYED,
    BOUNCED,
    COMPLAINED
}
