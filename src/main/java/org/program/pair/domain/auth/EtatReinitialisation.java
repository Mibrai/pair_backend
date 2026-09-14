package org.program.pair.domain.auth;

/**
 * Ce que vaut un jeton de réinitialisation <b>au moment où on le regarde</b>,
 * sans rien consommer.
 *
 * <p>Distinct de {@link ResultatVerification} parce que la question n'est pas
 * la même : la vérification d'adresse agit au clic, la réinitialisation attend
 * un formulaire. La page doit donc savoir, avant tout geste, si elle propose ce
 * formulaire ou si elle explique pourquoi le lien ne sert plus.
 */
public enum EtatReinitialisation {

    /** Le formulaire peut être proposé. */
    VALIDE,

    /** Jeton authentique, mais passé ses 30 minutes. */
    EXPIRE,

    /**
     * Déjà consommé — par une réinitialisation réussie, ou parce qu'un lien plus
     * récent a été demandé depuis ({@code emettre} clôt les jetons ouverts).
     * La page doit couvrir les deux lectures.
     */
    UTILISE,

    /** Absent de la base : lien tronqué, altéré, ou inventé. */
    INCONNU
}
