package org.program.pair.domain.availability;

/**
 * Les trois moments d'une journée, du point de vue de quelqu'un qui cherche
 * quand il est libre.
 *
 * <p>Trois tranches et pas douze : la question posée est « quand êtes-vous
 * généralement disponible », pas « remplissez votre agenda ». Un découpage plus
 * fin ferait passer une habitude pour un engagement.
 *
 * <p>Les bornes horaires ne vivent pas ici mais dans la requête du fil, qui est
 * le seul endroit à en avoir besoin — et le seul à savoir dans quel fuseau les
 * appliquer. Elles y sont : matin 6 h–12 h, après-midi 12 h–18 h, soir à partir
 * de 18 h. Une séance nocturne ne tombe dans aucune tranche : elle n'est jamais
 * favorisée, et jamais écartée non plus.
 */
public enum TimeSlot {
    MORNING,
    AFTERNOON,
    EVENING
}
