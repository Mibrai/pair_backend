package org.program.pair.domain.outbox;

/**
 * Où en est un message de l'outbox.
 *
 * <p>{@link #PENDING} tant qu'il n'est pas parti — c'est l'état sous lequel il
 * survit à un redémarrage. {@link #SENT} une fois pris par le fournisseur.
 * {@link #FAILED} quand les tentatives sont épuisées : il ne repartira plus seul,
 * et cet état est fait pour être vu, pas ignoré.
 */
public enum OutboxStatus {
    PENDING,
    SENT,
    FAILED
}
