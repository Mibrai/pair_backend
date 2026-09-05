package org.program.pair.domain.program;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;

/**
 * Où en est un programme dans son cycle — la seule réponse, pour tous ceux qui
 * la posent.
 *
 * <p><b>Le mot « à venir » n'apparaît nulle part dans cette classe, et c'est
 * délibéré.</b> Trois surfaces du dépôt bornent le futur sur le <i>début</i>
 * d'une séance : {@code SlotService.getMySlots}, le calcul de
 * {@code nextSessionAt} dans {@code ProgramService.toDto}, et l'{@code isExpired}
 * de {@code UserActivityRepository}. Toutes les trois considèrent une séance
 * comme passée à la seconde où elle commence. Une relance « ton cycle est
 * bouclé » écrite sur ce modèle partirait <b>au milieu du cours, à quelqu'un qui
 * y est</b>.
 *
 * <p>D'où l'{@link #horizon(Collection) horizon} : la dernière minute que le
 * programme décrit. Une séance à venir <i>ou en cours</i> a une fin dans le
 * futur, donc un horizon dans le futur — « plus aucune séance devant soi »
 * devient une conséquence du calcul plutôt qu'un second test qu'il faudrait
 * penser à écrire correctement. Le piège n'est pas évité, il est inexprimable.
 *
 * <p><b>Ce que « terminée » veut dire ne se redéfinit pas ici</b> : l'horizon
 * appelle {@link SlotTiming#endOf}, la convention unique du dépôt ({@code endsAt}
 * déclarée, sinon deux heures). {@code SlotTiming} existe précisément parce que
 * cette convention avait été recopiée trois fois et allait diverger ; une
 * quatrième copie ici aurait rendu le module incohérent avec la confirmation de
 * présence et le rollover, sans qu'aucune erreur ne le signale.
 *
 * <p><b>Les créneaux annulés ne comptent pas.</b> Un programme dont le seul
 * créneau est {@code CANCELLED} n'a pas d'horizon du tout : il n'a plus de pin
 * sur la carte, et il relève de l'étape 2 du cycle — le programme qui attend sa
 * date — jamais de l'étape 7. Les deux étapes sont ainsi disjointes par
 * construction, sans garde croisée.
 *
 * <p><b>Programmes récurrents.</b> Rien de spécial à écrire :
 * {@code RecurringSlotRolloverJob} maintient la ligne dans le futur tant que la
 * série vit, donc l'horizon reste futur et le cycle n'est jamais dit clos. Quand
 * {@code UNTIL} est dépassé ou {@code COUNT} épuisé, le job cesse d'avancer la
 * ligne, elle reste au passé, et l'horizon devient celui de la dernière séance
 * réellement vécue.
 *
 * <p>Même raison d'être que {@link SlotTiming}, {@link SlotAudience} et
 * {@link SlotAddressVisibility}.
 */
public final class ProgramCycle {

    /**
     * Délai après la fin de la dernière séance avant de dire qu'un cycle est
     * clos.
     *
     * <p>Il ne sert pas qu'au confort produit. {@code RecurringSlotRolloverJob}
     * ne passe que toutes les dix minutes : entre la fin d'une séance récurrente
     * et son tick, l'horizon est brièvement au passé alors que la série vit
     * encore. Vingt-quatre heures rendent cette fenêtre — et toute latence de
     * job du même ordre — sans effet.
     */
    public static final Duration CLOSING_GRACE = Duration.ofHours(24);

    private ProgramCycle() {}

    /**
     * La dernière minute que ce programme décrit : la plus tardive des fins de
     * ses créneaux non annulés.
     *
     * <p>{@code null} quand il n'en a aucun — ce qui n'est pas « son cycle est
     * fini » mais « il n'a jamais eu de date ». Les deux se distinguent ici, et
     * nulle part ailleurs.
     */
    public static Instant horizon(Collection<Schedule> schedules) {
        Instant horizon = null;
        for (Schedule slot : schedules) {
            if (!counts(slot)) {
                continue;
            }
            Instant end = SlotTiming.endOf(slot);
            if (horizon == null || end.isAfter(horizon)) {
                horizon = end;
            }
        }
        return horizon;
    }

    /**
     * Le cycle est-il refermé à cet instant, grâce comprise ?
     *
     * <p>Faux pour un horizon nul : un programme sans date n'a pas de cycle à
     * refermer.
     */
    public static boolean closedBy(Instant horizon, Instant moment) {
        return horizon != null && !horizon.plus(CLOSING_GRACE).isAfter(moment);
    }

    /**
     * Le programme a-t-il encore un pin sur la carte — au moins un créneau non
     * annulé, quelle que soit sa date ?
     *
     * <p>C'est la frontière de l'étape 2, et elle porte sur l'existence, jamais
     * sur le temps : un programme dont l'unique créneau est passé a eu sa date,
     * il n'attend plus la sienne.
     */
    public static boolean hasLiveSlot(Collection<Schedule> schedules) {
        return schedules.stream().anyMatch(ProgramCycle::counts);
    }

    /**
     * Début de la prochaine séance <b>non encore terminée</b> — celle qui vient,
     * ou celle en cours.
     *
     * <p>Rendre la séance en cours plutôt que de l'ignorer est ce qui rend
     * l'étape 4 sûre : « la prochaine séance est dans plus de 48 h » devient faux
     * pendant qu'une séance a lieu, au lieu de devenir vrai en sautant par-dessus
     * elle.
     */
    public static Instant nextUnfinishedStart(Collection<Schedule> schedules, Instant moment) {
        Instant next = null;
        for (Schedule slot : schedules) {
            if (!counts(slot) || !SlotTiming.endOf(slot).isAfter(moment)) {
                continue;
            }
            Instant start = slot.getStartsAt();
            if (next == null || start.isBefore(next)) {
                next = start;
            }
        }
        return next;
    }

    /**
     * Instant où le programme a paru : la création de son plus ancien créneau
     * non annulé.
     *
     * <p>Ni {@code Program.createdAt}, qui date l'envie et non la publication, ni
     * {@code subscribersNotifiedAt}, qui n'est posé que par
     * {@code ProgramService.addSchedule} et reste nul sur les autres chemins de
     * création. C'est le moment où le programme a eu une date, donc un pin, donc
     * une chance d'être rejoint — la seule origine dont l'étape 4 puisse compter
     * quarante-huit heures sans mentir.
     */
    public static Instant publishedAt(Collection<Schedule> schedules) {
        Instant first = null;
        for (Schedule slot : schedules) {
            if (!counts(slot) || slot.getCreatedAt() == null) {
                continue;
            }
            if (first == null || slot.getCreatedAt().isBefore(first)) {
                first = slot.getCreatedAt();
            }
        }
        return first;
    }

    /** Un créneau qui compte : non annulé, et daté. */
    private static boolean counts(Schedule slot) {
        return slot.getStatus() != SlotStatus.CANCELLED && slot.getStartsAt() != null;
    }
}
