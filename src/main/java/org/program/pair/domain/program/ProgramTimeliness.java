package org.program.pair.domain.program;

import java.time.Instant;
import java.util.Collection;

/**
 * Est-ce que ce programme a encore quelque chose devant lui — la seule réponse,
 * pour toutes les routes qui la rendent.
 *
 * <p>Le verdict était écrit une seule fois, en SQL, dans
 * {@code UserActivityRepository.browse} : {@code isExpired} vaut vrai quand
 * l'entrée est <b>datée</b> — au moins un créneau — et qu'<b>aucune occurrence
 * non terminée</b> n'existe. {@code POST /api/search} devait le rendre à son
 * tour, sur la maille programme, et par <b>deux chemins de code différents</b> :
 * un mapping d'entités JPA et quatre requêtes natives. Recopier la règle à
 * chaque endroit en aurait fait quatre définitions, dont la divergence ne se
 * serait vue nulle part — un programme grisé sur un écran, vivant sur l'autre,
 * sans qu'aucune erreur ne soit levée.
 *
 * <p>Les deux fabriques ci-dessous sont donc les deux seules portes d'entrée :
 * {@link #of(Collection, Instant)} pour qui tient déjà les créneaux en mémoire,
 * {@link #fromAgenda(long, Instant)} pour qui a laissé la base les agréger.
 * Elles produisent la même chose et, surtout, elles tiennent ensemble
 * l'invariant que le client a demandé nommément : <b>expiré ⇒
 * {@code nextSessionAt} nul, sans exception</b>.
 *
 * <p><b>Trois pièges, tous déjà tombés ailleurs dans ce dépôt :</b>
 *
 * <ul>
 *   <li><b>« Non terminée » se mesure sur la fin, jamais sur le début.</b> Un
 *       programme dont l'unique séance est <i>en cours</i> n'est pas expiré : il
 *       est en train de prouver le contraire. C'est
 *       {@link ProgramCycle#nextUnfinishedStart} qui porte cette frontière, et
 *       {@link SlotTiming#endOf} la convention de fin.</li>
 *   <li><b>Les créneaux annulés ne comptent pas comme occurrence future</b> —
 *       sans quoi un créneau annulé mais futur ferait passer pour vivant un
 *       programme qui n'a plus rien.</li>
 *   <li><b>… mais ils comptent comme date.</b> {@code scheduleCount} porte sur
 *       <i>tous</i> les créneaux, annulés compris, exactement comme le
 *       {@code COUNT(*)} de {@code browse}. Un programme dont la seule séance a
 *       été annulée a bien eu une date : le dire non daté le rendrait
 *       éternellement vivant.</li>
 * </ul>
 *
 * <p>À ne pas confondre avec {@link ProgramCycle}, qui répond à une autre
 * question — où en est l'auteur dans son cycle de relances, grâce de 24 h
 * comprise. Ici il n'y a pas de grâce : la question est ce que l'écran doit
 * griser, et une seconde après la fin, c'est fini.
 *
 * <p>Même raison d'être que {@link SlotTiming}, {@link SlotAudience} et
 * {@link SlotAddressVisibility}.
 *
 * @param nextSessionAt début de la prochaine séance non terminée, ou {@code null}
 * @param isExpired     daté, et plus aucune séance devant soi
 */
public record ProgramTimeliness(Instant nextSessionAt, boolean isExpired) {

    /**
     * Le verdict d'un programme sans aucun créneau : vivant, et sans date.
     *
     * <p>Ce n'est pas un cas dégénéré, c'est une règle du client, citée mot pour
     * mot : « un programme qu'on vient de créer et dont l'horaire n'est pas
     * encore posé reste vivant — le griser punirait son auteur pour une étape
     * qu'il n'a pas faite ».
     */
    public static final ProgramTimeliness UNDATED = new ProgramTimeliness(null, false);

    /** Depuis les créneaux, quand l'appelant les tient déjà. */
    public static ProgramTimeliness of(Collection<Schedule> schedules, Instant moment) {
        if (schedules == null || schedules.isEmpty()) {
            return UNDATED;
        }
        return fromAgenda(schedules.size(), ProgramCycle.nextUnfinishedStart(schedules, moment));
    }

    /**
     * Depuis l'agrégat que la base a calculé : le nombre de créneaux et le début
     * de la prochaine séance non terminée.
     *
     * <p>Les deux colonnes que produit la jointure latérale {@code agenda} —
     * celle de {@code UserActivityRepository.browse} comme celle de
     * {@code FullTextSearchService}. Les recombiner ici plutôt qu'en SQL est ce
     * qui garantit que les quatre requêtes natives rendent le même verdict que
     * le mapping d'entités.
     */
    public static ProgramTimeliness fromAgenda(long scheduleCount, Instant nextSessionAt) {
        if (nextSessionAt != null) {
            return new ProgramTimeliness(nextSessionAt, false);
        }
        return new ProgramTimeliness(null, scheduleCount > 0);
    }
}
