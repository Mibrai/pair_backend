package org.program.pair.domain.program;

/**
 * Ce que l'organisateur annonce sur les conditions d'accueil de sa séance.
 *
 * <p><b>Déclaratif, jamais vérifié.</b> Personne ne contrôle qu'une salle
 * annoncée accessible en fauteuil l'est réellement. Le contrat d'API le dit, et
 * l'interface doit le dire aussi : présenter ces étiquettes comme des faits
 * établis ferait porter au produit une promesse qu'il ne tient pas, et le coût
 * de l'erreur retomberait sur la personne qui s'est déplacée pour rien.
 *
 * <p>Ajouter une valeur est additif ; en renommer une est une rupture de
 * contrat, comme pour {@code ErrorCode}.
 */
public enum AccessibilityTag {

    /** Le lieu est annoncé accessible en fauteuil roulant. */
    WHEELCHAIR_ACCESSIBLE,

    /** Les enfants sont les bienvenus. */
    FAMILY_FRIENDLY,

    /** Les débutants sont attendus, pas tolérés. */
    BEGINNER_WELCOME,

    /**
     * Séance réservée aux femmes.
     *
     * <p>D'une autre nature que les autres : ce n'est pas une facilité d'accueil
     * mais une restriction d'audience, souvent motivée par la sécurité. Elle
     * figure ici parce que le client la présente au même endroit ; l'interface
     * gagnerait à la distinguer visuellement.
     */
    WOMEN_ONLY,

    /** Environnement calme, sans musique forte ni foule. */
    QUIET_ENVIRONMENT,

    /** Pas d'alcool. */
    NO_ALCOHOL,

    /** Desservi par les transports en commun. */
    PUBLIC_TRANSPORT_NEARBY,

    /** Gratuit. */
    FREE_OF_CHARGE
}
