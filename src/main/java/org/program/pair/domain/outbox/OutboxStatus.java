package org.program.pair.domain.outbox;

/**
 * Où en est un message de l'outbox.
 *
 * <p>{@link #PENDING} tant qu'il n'est pas parti — c'est l'état sous lequel il
 * survit à un redémarrage. {@link #SENDING} pendant qu'un balayage s'en occupe :
 * il l'a réclamé, l'appel au fournisseur est en cours, et aucun autre balayage ne
 * le reprendra avant l'expiration de son verrou ({@code locked_until}).
 * {@link #SENT} une fois pris par le fournisseur. {@link #FAILED} quand les
 * tentatives sont épuisées : il ne repartira plus seul, et cet état est fait pour
 * être vu, pas ignoré.
 *
 * <p><b>{@code SENDING} n'est pas un état où l'on s'installe.</b> Il vaut
 * « quelqu'un a la main dessus, pour deux minutes au plus ». Un conteneur tué en
 * plein envoi laisse des lignes dans cet état ; c'est l'expiration du verrou, et
 * non un nettoyage, qui les rend éligibles au balayage suivant. Une ligne
 * {@code SENDING} dont le verrou est dépassé depuis plusieurs minutes n'existe
 * que si plus aucun balayage ne tourne — c'est pour cela que la vérification
 * après livraison la compte.
 *
 * <p><b>Pas de {@code DEAD} à côté de {@code FAILED}.</b> {@code FAILED} est déjà
 * l'état terminal que {@code VerificationEmailDelivery.FAILED} et le webhook de
 * remise nomment ; un second mot pour la même chose ferait deux vocabulaires à
 * tenir d'accord.
 */
public enum OutboxStatus {
    PENDING,
    SENDING,
    SENT,
    FAILED
}
