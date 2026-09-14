package org.program.pair.domain.visibility;

/**
 * Les canaux par lesquels quelqu'un peut observer une personne, tels que « Qui me
 * voit » les liste et que « tout couper » les ferme (demande mobile du 14/09/2026,
 * modules/tracabilite).
 *
 * <p><b>Le contrat : on en ajoute, on n'en retire jamais.</b> L'app affiche un
 * canal inconnu sous son nom brut plutôt que de le taire ; retirer une valeur
 * ferait disparaître une ligne de la page sans que rien ne dise pourquoi.
 */
public enum VisibilityChannel {

    /** Les affiches publiées pour mes abonnés ou pour tout le monde. */
    AFFICHES,

    /** Les pages publiques de statut de mes veilles, encore ouvrables. */
    WATCH_LINKS,

    /** Mes partages de position non échus dans les conversations. */
    CHAT_LOCATION,

    /** Ma présence sur la carte et dans la recherche de personnes par distance. */
    MAP_PRESENCE,

    /**
     * Le statut live ami par ami. Le serveur ne le sert à personne aujourd'hui
     * ({@code safety.liveAudience} est rangé, pas interprété) : toujours coupé.
     * Présent pour que l'app n'ait pas à le deviner le jour où il existera.
     */
    LIVE_STATUS
}
