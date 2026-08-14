package org.program.pair.domain.program;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Quand un créneau se termine — la seule réponse, pour tous ceux qui la posent.
 *
 * <p>{@code endsAt} est nullable en base. La convention de repli
 * ({@code startsAt + 2h}) était déjà écrite en trois exemplaires — dans la
 * confirmation de présence, dans la liste des créneaux à confirmer, et dans le
 * job de relance. Une quatrième copie, pour la fenêtre de contribution aux
 * cartes-souvenirs, aurait fini par diverger : deux définitions de « c'est
 * fini » ne se manifestent pas par une erreur, mais par un créneau qu'on peut
 * commenter alors qu'on ne peut pas encore y confirmer sa présence.
 *
 * <p>Même raison d'être que {@link SlotAddressVisibility} et {@link SlotAudience}.
 */
public final class SlotTiming {

    /** Durée conventionnelle d'une séance dont la fin n'est pas déclarée. */
    private static final int DEFAULT_DURATION_HOURS = 2;

    private SlotTiming() {}

    /** Fin déclarée du créneau, ou fin conventionnelle à défaut. */
    public static Instant endOf(Schedule slot) {
        return slot.getEndsAt() != null
            ? slot.getEndsAt()
            : slot.getStartsAt().plus(DEFAULT_DURATION_HOURS, ChronoUnit.HOURS);
    }

    /** Le créneau est-il terminé à cet instant ? */
    public static boolean hasEndedBy(Schedule slot, Instant moment) {
        return endOf(slot).isBefore(moment);
    }
}
