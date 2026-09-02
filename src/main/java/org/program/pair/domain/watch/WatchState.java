package org.program.pair.domain.watch;

import java.util.EnumSet;
import java.util.Set;

/**
 * Où en est une veille retour.
 *
 * <p>Huit états, du plus vivant au terminal. Leur ordre dans la vie d'une veille :
 *
 * <ul>
 *   <li>{@link #ARMED} — armée, rien n'est encore parti. On attend soit un départ,
 *       soit l'échéance.</li>
 *   <li>{@link #EN_ROUTE} — la personne a dit qu'elle était en chemin (boucle
 *       aller).</li>
 *   <li>{@link #ON_SITE} — arrivée sur place et validée : le code de retour existe.</li>
 *   <li>{@link #REMINDING} — l'échéance est passée, les rappels partent.</li>
 *   <li>{@link #ESCALATED} — sans réponse après les rappels, le contact a été
 *       prévenu.</li>
 *   <li>{@link #NOT_ARRIVED} — jamais arrivée, et personne n'a été prévenu.</li>
 *   <li>{@link #RESOLVED} — après une alerte, la personne a fini par confirmer :
 *       la levée est partie.</li>
 *   <li>{@link #CLOSED} — refermée normalement, ou désarmée avant tout départ.</li>
 * </ul>
 *
 * <p><b>Trois états sont terminaux</b> : {@link #RESOLVED}, {@link #CLOSED} et
 * {@link #NOT_ARRIVED}. Une veille dans l'un des trois ne bouge plus et n'empêche
 * pas d'en armer une nouvelle pour le même créneau. Tous les autres sont vivants.
 *
 * <p><b>« Terminal » ne veut pas dire « invisible ».</b> Une non-arrivée reste
 * rendue par « mes veilles actives » pendant 24 h après sa clôture — voir
 * {@code WatchService.listActive}. C'est le seul endroit où la personne concernée
 * apprend que sa soirée a été classée perdue en chemin ; l'organisateur, lui, est
 * prévenu par une notification.
 */
public enum WatchState {
    ARMED,
    EN_ROUTE,
    ON_SITE,
    REMINDING,
    ESCALATED,

    /**
     * Perdue en chemin : trois demandes d'arrivée sans réponse, et l'arrivée n'a
     * jamais été validée.
     *
     * <p><b>Un état distinct d'{@link #ESCALATED}, et ce n'est pas une question de
     * vocabulaire.</b> {@code ESCALATED} signifie « un message est parti à un
     * tiers », ce qui n'est plus vrai ici depuis la décision du 02/09. Mais surtout,
     * {@code ESCALATED} est balayé par {@code WatchReturnLoopJob} : une non-arrivée
     * qui y serait restée aurait vu partir l'alerte retour à l'échéance, une heure
     * plus tard. Ce qui protège cette branche est donc l'état lui-même — elle sort
     * du champ de vision de la boucle — et non un garde-fou qu'on pourrait oublier.
     *
     * <p>Terminal, parce qu'il n'y a plus rien à surveiller : personne n'est parti.
     * Et parce que sans cela la veille ne se refermerait jamais — la boucle aller ne
     * balaie que {@link #ARMED} et {@link #EN_ROUTE}, donc plus rien ne l'avancerait
     * après T+45, et elle bloquerait l'armement d'une nouvelle veille sur le même
     * créneau jusqu'à ce que la personne pense à l'abandonner.
     */
    NOT_ARRIVED,

    RESOLVED,
    CLOSED;

    /** Les états terminaux : la veille est finie, elle ne bouge plus. */
    public static final Set<WatchState> TERMINAUX = EnumSet.of(RESOLVED, CLOSED, NOT_ARRIVED);

    /** Vrai tant que la veille est vivante — ni close, ni résolue. */
    public boolean estActive() {
        return !TERMINAUX.contains(this);
    }
}
