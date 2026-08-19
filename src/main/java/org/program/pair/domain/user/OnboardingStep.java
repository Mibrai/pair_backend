package org.program.pair.domain.user;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;
import java.util.Map;

/**
 * Les quatre écrans du parcours d'accueil, dans l'ordre où ils se franchissent.
 *
 * <p><b>Ces valeurs décrivent le parcours réel, pas un parcours souhaité.</b> La
 * première version de cette énumération ({@code WELCOME, LOCATION, ACTIVITIES,
 * DISCOVERY, DONE}) avait été écrite d'après la spécification, sans confrontation
 * avec l'application : deux valeurs seulement existaient des deux côtés, et
 * <i>dans l'ordre inverse</i>. Combiné à la règle « un parcours ne recule pas »,
 * cela produisait un échec qu'aucun des deux camps ne pouvait voir — l'étape
 * « position » était acceptée puis ignorée, en {@code 200}, et la personne qui
 * fermait l'application entre la position et l'aperçu reprenait au premier écran.
 *
 * <p><b>L'ordre de déclaration est le contrat.</b> C'est lui qui permet de dire
 * qu'une étape en vaut une autre ou la dépasse, et donc de rendre l'avancement
 * insensible aux doublons du réseau mobile. Insérer une étape au milieu est un
 * changement de comportement, pas une addition ; l'ajouter à la fin déplace en
 * outre le point de clôture, {@link #isFinal()} étant défini par position.
 *
 * <p>Le serveur ne pilote pas le parcours, il l'enregistre. C'est le client qui
 * décide de l'écran suivant ; ces valeurs existent pour qu'il puisse reprendre là
 * où la personne s'est arrêtée, y compris sur un autre appareil.
 */
public enum OnboardingStep {

    /** « Qu'est-ce que tu aimes faire ? » */
    ACTIVITIES,

    /** « À quel niveau ? » */
    LEVELS,

    /** « Où cherches-tu ? » */
    LOCATION,

    /**
     * « Voilà ce qui se passe autour de toi. » Dernier écran, et le seul qui
     * interroge le serveur pour autre chose que s'enregistrer — donc le seul dont
     * le contenu peut être vide si l'on n'y prend pas garde. Le franchir referme
     * le parcours.
     */
    PREVIEW;

    /**
     * L'ancien vocabulaire, accepté en entrée et traduit vers le vrai parcours.
     *
     * <p>Il existe une version publiée du client qui parle encore cette langue.
     * La refuser l'aurait cassée d'un déploiement à l'autre, alors que la
     * traduction, elle, remet même ses étapes dans le bon ordre : la séquence
     * qu'il émet ({@code ACTIVITIES, ACTIVITIES, LOCATION, DISCOVERY}) devient
     * croissante une fois relue ici, ce qu'elle n'était pas avant.
     *
     * <p>Ce que la traduction ne peut pas rendre, c'est la distinction entre les
     * deux premiers écrans : l'ancien vocabulaire n'avait qu'un mot pour les deux.
     * C'est la raison pour laquelle ce repli est une transition et non un acquis.
     */
    private static final Map<String, OnboardingStep> LEGACY_NAMES = Map.of(
        "WELCOME", ACTIVITIES,
        "DISCOVERY", PREVIEW,
        "DONE", PREVIEW
    );

    /**
     * Relit une étape reçue du client, ancien vocabulaire compris.
     *
     * <p>Une valeur inconnue reste une erreur de requête : accepter n'importe
     * quoi reviendrait à enregistrer un avancement qui ne veut rien dire, et le
     * client n'aurait aucun moyen d'apprendre qu'il se trompe.
     */
    @JsonCreator
    public static OnboardingStep fromClient(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.trim().toUpperCase(Locale.ROOT);
        OnboardingStep legacy = LEGACY_NAMES.get(name);
        return legacy != null ? legacy : OnboardingStep.valueOf(name);
    }

    /** Vrai si cette étape est au moins aussi avancée que l'autre. */
    public boolean reaches(OnboardingStep other) {
        return other == null || ordinal() >= other.ordinal();
    }

    /**
     * Vrai pour le dernier écran du parcours, celui dont le franchissement
     * referme l'accueil.
     *
     * <p>Défini par position et non par une valeur écrite en dur : le jour où un
     * écran s'ajoute à la fin, c'est lui qui refermera le parcours, sans qu'on
     * ait à se souvenir de venir corriger le service.
     */
    public boolean isFinal() {
        return ordinal() == values().length - 1;
    }
}
