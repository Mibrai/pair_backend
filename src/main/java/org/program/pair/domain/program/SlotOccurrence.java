package org.program.pair.domain.program;

import java.time.Instant;

/**
 * Une occurrence de créneau : un moment qui a eu lieu une fois, et une seule.
 *
 * <p>Un {@link Schedule} récurrent n'est pas un moment — c'est une règle. Sa
 * ligne en base ne porte à un instant donné qu'une seule occurrence bookable,
 * que {@code RecurringSlotRolloverJob} avance à chaque passage. Confondre les
 * deux, ce que faisait tout le code de lecture jusqu'ici, revient à dater un
 * souvenir de la prochaine séance.
 *
 * <p>Une occurrence est identifiée par son début. C'est la clé de
 * {@code slot_recaps} et de {@code attendances}, et rien d'autre n'a besoin
 * d'exister : les moments qui ont laissé une trace ont leur ligne, les autres
 * n'ont rien à identifier.
 */
public record SlotOccurrence(Instant startsAt, Instant endsAt) {

    /**
     * Ce moment est-il terminé ? La comparaison est large — un créneau qui
     * s'achève à l'instant est terminé — pour coïncider avec la condition que
     * la confirmation de présence applique déjà.
     */
    public boolean hasEndedBy(Instant moment) {
        return !moment.isBefore(endsAt);
    }
}
