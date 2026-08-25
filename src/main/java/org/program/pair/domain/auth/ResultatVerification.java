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
    INCONNU
}
