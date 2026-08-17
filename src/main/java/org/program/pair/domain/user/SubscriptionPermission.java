package org.program.pair.domain.user;

/**
 * « Qui peut me suivre », réglage de confidentialité du profil.
 *
 * <p>Jumeau de {@link MessagePermission} : même écran, même geste.
 *
 * <p><b>Il vaut pour les deux chemins qui mènent à une personne</b> : son profil
 * ({@code AUTHOR}) et chacune de ses activités ({@code USER_ACTIVITY}). Réservé
 * au seul profil, il se contournait par n'importe laquelle des activités de la
 * personne — et l'abonné ainsi arrivé recevait bien ses nouveaux programmes, ce
 * qui vidait le réglage de son sens tout en le laissant afficher « fermé ».
 * Suivre ce que quelqu'un propose, c'est le suivre.
 *
 * <p>Les catégories échappent à la règle, et c'est délibéré : elles
 * n'appartiennent à personne, et s'abonner à « Yoga » n'est pas suivre
 * quelqu'un.
 *
 * <p><b>Le réglage ne vaut que pour l'avenir.</b> Passer à {@link #NOBODY}
 * refuse les nouveaux abonnements ; il ne supprime pas les lignes existantes et
 * ne les fait pas taire. Les trois comportements étaient défendables — nous
 * retenons celui-ci parce que la suppression est irréversible (rebasculer en
 * {@link #OPEN} ne rendrait pas ses abonnés) et parce que « garder mais taire »
 * serait pire : l'abonné conserverait {@code subscribed: true} sans plus rien
 * recevoir, sans moyen de comprendre pourquoi, pendant que {@code
 * subscriberCount} continuerait d'annoncer une audience que rien n'atteint.
 *
 * <p>Le libellé du réglage doit donc dire « empêcher de nouveaux abonnements »
 * et non « personne ne peut me suivre », qui promettrait un effet rétroactif.
 */
public enum SubscriptionPermission {

    /** N'importe qui peut s'abonner. Valeur par défaut. */
    OPEN,

    /** Aucun nouvel abonnement n'est accepté : le {@code POST} rend {@code 403}. */
    NOBODY
}
