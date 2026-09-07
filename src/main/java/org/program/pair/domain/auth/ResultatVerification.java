package org.program.pair.domain.auth;

/**
 * Issue d'une tentative de vérification d'adresse e-mail.
 *
 * <p>Quatre états et non deux, parce qu'ils appellent quatre messages
 * différents dans la page rendue au navigateur. Confondre « déjà vérifié » et
 * « inconnu » — ce que faisait la version à `ConcurrentHashMap`, qui effaçait
 * le jeton consommé — fait croire à une panne à quelqu'un dont le compte
 * fonctionne. C'est le pire cas décrit par le ticket du 25 août.
 */
public enum ResultatVerification {

    /** Le compte vient d'être vérifié. */
    VERIFIE,

    /** Jeton déjà utilisé : le compte est actif, il n'y a rien à faire. */
    DEJA_VERIFIE,

    /** Jeton authentique mais échu : il faut en redemander un. */
    EXPIRE,

    /** Jeton absent de la base : lien tronqué, altéré, ou purgé depuis longtemps. */
    INCONNU,

    /**
     * Le lien portait un <b>changement</b> d'adresse, et il vient de prendre
     * effet : le compte répond désormais à la nouvelle adresse (V105).
     *
     * <p>Un état à part de {@link #VERIFIE}, parce que la page doit dire quelque
     * chose de différent — quelqu'un qui vient de corriger une faute de frappe
     * n'a pas besoin d'apprendre que son compte est « actif », il a besoin de
     * lire l'adresse qui vaut désormais.
     */
    ADRESSE_CHANGEE,

    /**
     * Le lien de changement était bon, mais l'adresse demandée a été inscrite
     * par quelqu'un d'autre entre la demande et le clic.
     *
     * <p>Rare, et pourtant le seul cas où refuser est obligatoire : l'adresse est
     * l'identifiant de connexion, et deux comptes ne peuvent pas la partager. La
     * demande est abandonnée, l'ancienne adresse reste en place — ce qui laisse
     * le compte utilisable, et permet d'en redemander une autre.
     */
    ADRESSE_INDISPONIBLE
}
