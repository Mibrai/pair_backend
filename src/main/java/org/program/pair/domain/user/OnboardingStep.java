package org.program.pair.domain.user;

/**
 * Les étapes du parcours d'accueil, dans l'ordre où elles se franchissent.
 *
 * <p><b>L'ordre de déclaration est le contrat.</b> C'est lui qui permet de dire
 * qu'une étape en vaut une autre ou la dépasse, et donc de rendre l'avancement
 * insensible aux doublons du réseau mobile : une requête retardée qui annonce une
 * étape déjà franchie ne fait pas reculer la personne. Insérer une étape au
 * milieu est un changement de comportement, pas une addition — l'ajouter à la
 * fin, avant {@link #DONE}, ne l'est pas.
 *
 * <p>Le serveur ne pilote pas le parcours, il l'enregistre. C'est le client qui
 * décide de l'écran suivant ; ces valeurs existent pour qu'il puisse reprendre
 * là où la personne s'est arrêtée, y compris sur un autre appareil.
 */
public enum OnboardingStep {

    /** Écran d'ouverture, avant toute demande. */
    WELCOME,

    /** Demande d'accès à la position — la condition de tout ce qui suit. */
    LOCATION,

    /** Choix des premières activités. */
    ACTIVITIES,

    /**
     * Découverte : le premier écran qui montre des données réelles autour de la
     * position tout juste autorisée. C'est le seul qui interroge le serveur pour
     * autre chose que s'enregistrer, et le seul dont le contenu peut être vide
     * si l'on n'y prend pas garde.
     */
    DISCOVERY,

    /** Parcours terminé, quelle qu'en soit la façon. */
    DONE;

    /** Vrai si cette étape est au moins aussi avancée que l'autre. */
    public boolean reaches(OnboardingStep other) {
        return other == null || ordinal() >= other.ordinal();
    }
}
