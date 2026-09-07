package org.program.pair.domain.program;

import java.time.Instant;

/**
 * Quand un créneau se termine, et de quelle séance on parle — les deux seules
 * réponses, pour tous ceux qui les posent.
 *
 * <p>{@code endsAt} est nullable en base. La convention de repli
 * ({@code startsAt + 2h}) était déjà écrite en trois exemplaires — dans la
 * confirmation de présence, dans la liste des créneaux à confirmer, et dans le
 * job de relance. Une quatrième copie, pour la fenêtre de contribution aux
 * cartes-souvenirs, aurait fini par diverger : deux définitions de « c'est
 * fini » ne se manifestent pas par une erreur, mais par un créneau qu'on peut
 * commenter alors qu'on ne peut pas encore y confirmer sa présence.
 *
 * <p><b>Et de quelle séance parle-t-on ?</b> Sur un créneau récurrent, la
 * ligne {@code schedules} ne décrit jamais un moment passé : le rollover l'a
 * déjà avancée. Demander « quand ce créneau s'est-il terminé ? » n'a donc de
 * sens qu'en nommant l'occurrence, ce que fait {@link SlotOccurrence}. C'est
 * la raison d'être de {@link #lastEndedOccurrence}, par où passent désormais la
 * confirmation de présence et toute contribution à une carte-souvenir.
 *
 * <p>Même raison d'être que {@link SlotAddressVisibility} et {@link SlotAudience}.
 */
public final class SlotTiming {

    /**
     * Durée conventionnelle d'une séance dont la fin n'est pas déclarée.
     *
     * <p><b>Publique, et c'est le point.</b> Un appelant qui doit présélectionner
     * en SQL — {@code ProgramRepository.findStage7Candidates} — ne peut pas y
     * écrire {@code endsAt}, faute de quoi la convention existerait en deux
     * langages. Il élargit donc ses bornes de cette durée-ci, lue ici, et laisse
     * la décision exacte au code Java. La valeur garde ainsi un seul domicile.
     */
    public static final java.time.Duration DEFAULT_DURATION = java.time.Duration.ofHours(2);

    private SlotTiming() {}

    /** Fin déclarée du créneau, ou fin conventionnelle à défaut. */
    public static Instant endOf(Schedule slot) {
        return endOf(slot.getStartsAt(), slot.getEndsAt());
    }

    private static Instant endOf(Instant startsAt, Instant endsAt) {
        return endsAt != null ? endsAt : startsAt.plus(DEFAULT_DURATION);
    }

    /**
     * L'occurrence que la ligne porte en ce moment : celle qui vient, ou celle
     * en cours. Pour un créneau non récurrent, c'est la seule qui existera
     * jamais.
     */
    public static SlotOccurrence currentOccurrence(Schedule slot) {
        return new SlotOccurrence(slot.getStartsAt(), endOf(slot));
    }

    /**
     * L'occurrence que le rollover a retirée en avançant le créneau, ou
     * {@code null} s'il ne l'a jamais fait.
     *
     * <p>Une seule est conservée. Au-delà de sept jours plus rien n'est
     * contribuable, et un moment qui a laissé une trace a déjà sa carte.
     */
    public static SlotOccurrence lastRetiredOccurrence(Schedule slot) {
        Instant start = slot.getLastOccurrenceStart();
        if (start == null) {
            return null;
        }
        return new SlotOccurrence(start, endOf(start, slot.getLastOccurrenceEnd()));
    }

    /**
     * La dernière séance terminée à cet instant, {@code null} si aucune ne
     * l'est encore.
     *
     * <p>L'ordre compte : si l'occurrence courante est terminée, c'est elle —
     * le rollover ne passe que toutes les dix minutes, et pendant ce délai
     * c'est bien elle qu'on vient de vivre. Sinon la ligne a déjà été avancée,
     * et le moment recherché est celui qu'elle a retiré.
     */
    public static SlotOccurrence lastEndedOccurrence(Schedule slot, Instant moment) {
        SlotOccurrence current = currentOccurrence(slot);
        if (current.hasEndedBy(moment)) {
            return current;
        }
        return lastRetiredOccurrence(slot);
    }

    /**
     * Durée d'une séance de ce créneau : sa durée déclarée, ou la durée
     * conventionnelle quand la fin ne l'est pas.
     *
     * <p>C'est ce qui se <b>répète</b> d'une occurrence à l'autre — la ligne ne
     * garde ni le début ni la fin des séances anciennes, mais leur durée est la
     * même. Une durée nulle ou négative en base (deux dates saisies à l'envers)
     * retombe sur la convention plutôt que de produire une séance qui se termine
     * avant d'avoir commencé.
     */
    public static java.time.Duration durationOf(Schedule slot) {
        if (slot.getEndsAt() == null) {
            return DEFAULT_DURATION;
        }
        java.time.Duration declared = java.time.Duration.between(slot.getStartsAt(), slot.getEndsAt());
        return declared.isZero() || declared.isNegative() ? DEFAULT_DURATION : declared;
    }

    /**
     * La fin d'une séance nommée par son début, y compris pour une séance
     * ancienne que la ligne ne décrit plus.
     *
     * <p>{@link #currentOccurrence} et {@link #lastRetiredOccurrence} ne couvrent
     * que les deux séances que la ligne connaît encore — celle qu'elle porte et
     * celle que le rollover vient de retirer. Au-delà, plus rien : une affiche
     * publiée un mois après un cours hebdomadaire, ou une carte relue dans un an,
     * demandent une fin que personne ne conserve.
     *
     * <p>La réponse est donc exacte pour ces deux séances-là — on lit la vraie
     * fin — et reconstruite par la durée au-delà. L'ordre compte : reconstruire
     * en premier ferait perdre une fin déclarée qu'on avait sous la main.
     */
    public static Instant occurrenceEndOf(Schedule slot, Instant occurrenceStart) {
        SlotOccurrence current = currentOccurrence(slot);
        if (current.startsAt().equals(occurrenceStart)) {
            return current.endsAt();
        }
        SlotOccurrence retired = lastRetiredOccurrence(slot);
        if (retired != null && retired.startsAt().equals(occurrenceStart)) {
            return retired.endsAt();
        }
        return occurrenceStart.plus(durationOf(slot));
    }

    /**
     * Le créneau a-t-il une séance terminée à cet instant ? Vrai dès qu'une
     * occurrence est derrière nous, y compris quand le rollover a déjà
     * repositionné la ligne dans le futur.
     */
    public static boolean hasEndedBy(Schedule slot, Instant moment) {
        return lastEndedOccurrence(slot, moment) != null;
    }
}
