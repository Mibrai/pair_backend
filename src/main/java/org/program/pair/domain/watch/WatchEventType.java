package org.program.pair.domain.watch;

/**
 * Ce qui s'est passé sur une veille, tel que la chronologie le retient.
 *
 * <p>Cet ensemble ne contient que ce qu'un geste écrit <b>aujourd'hui</b>. Les
 * priorités suivantes du module ajouteront leurs propres entrées — arrivée,
 * rappels, escalade, levée, renvoi de code — quand le code qui les produit
 * existera. Ajouter ici une valeur que rien n'inscrit encore ferait une
 * chronologie qui promet des lignes qu'elle n'écrit jamais.
 */
public enum WatchEventType {

    /** La veille a été armée. Toujours la première ligne de la chronologie. */
    ARMED,

    /** Désarmée avant tout départ, sans message et sans compter d'absence. */
    DISARMED_BEFORE_DEPARTURE
}
