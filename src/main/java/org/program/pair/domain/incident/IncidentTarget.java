package org.program.pair.domain.incident;

/**
 * Ce que vise un incident.
 *
 * <p>Un registre à part de {@code /reports}, et c'est le cœur de la décision.
 * Mêler « perdue en chemin » à « comportement inapproprié » polluerait la
 * modération et mettrait la victime dans la colonne des signalés.
 *
 * <p><b>Seule {@link #PERSON} bascule vers le flux de signalement existant.</b>
 * Les autres cibles décrivent une situation, pas une personne à modérer : un lieu
 * mal éclairé, une organisation douteuse, un trajet où quelqu'un s'est perdu, ou
 * un incident qui ne concerne que soi. « Perdu en chemin » est un
 * {@link #TRANSIT} — un incident de sécurité, jamais une absence ni un reproche.
 */
public enum IncidentTarget {
    PERSON,
    PLACE,
    ORGANISATION,
    TRANSIT,
    SELF
}
