package org.program.pair.domain.user;

/**
 * « Qui peut me suivre », réglage de confidentialité du profil.
 *
 * <p>Jumeau de {@link MessagePermission} : même écran, même geste. Il porte sur
 * les abonnements de type {@code AUTHOR} — suivre une activité ou une catégorie
 * ne passe pas par le profil d'une personne.
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
