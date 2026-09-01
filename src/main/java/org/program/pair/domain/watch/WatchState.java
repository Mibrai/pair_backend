package org.program.pair.domain.watch;

import java.util.EnumSet;
import java.util.Set;

/**
 * Où en est une veille retour.
 *
 * <p>Sept états, du plus vivant au terminal. Leur ordre dans la vie d'une veille :
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
 *   <li>{@link #RESOLVED} — après une alerte, la personne a fini par confirmer :
 *       la levée est partie.</li>
 *   <li>{@link #CLOSED} — refermée normalement, ou désarmée avant tout départ.</li>
 * </ul>
 *
 * <p><b>Deux états sont terminaux</b> : {@link #RESOLVED} et {@link #CLOSED}. Une
 * veille dans l'un des deux ne bouge plus, ne figure pas dans « mes veilles
 * actives », et n'empêche pas d'en armer une nouvelle pour le même créneau. Tous
 * les autres sont vivants.
 */
public enum WatchState {
    ARMED,
    EN_ROUTE,
    ON_SITE,
    REMINDING,
    ESCALATED,
    RESOLVED,
    CLOSED;

    /** Les états terminaux : la veille est finie, elle ne bouge plus. */
    public static final Set<WatchState> TERMINAUX = EnumSet.of(RESOLVED, CLOSED);

    /** Vrai tant que la veille est vivante — ni close, ni résolue. */
    public boolean estActive() {
        return !TERMINAUX.contains(this);
    }
}
