package org.program.pair.domain.guardian;

/**
 * Où en est l'accord d'un contact d'urgence.
 *
 * <p>Trois états, et un seul rend le contact sélectionnable pour armer une veille.
 * L'ordre compte moins que ce que chacun autorise :
 *
 * <ul>
 *   <li>{@link #PENDING} — désigné, pas encore répondu. Le message ① lui a été
 *       envoyé, une seule fois, jamais relancé.</li>
 *   <li>{@link #ACCEPTED} — a dit oui. C'est le seul état depuis lequel une veille
 *       peut le prendre pour contact.</li>
 *   <li>{@link #REFUSED} — a dit non. <b>Définitif.</b> Pour un contact hors
 *       meetDo, le refus vaut aussi pour le numéro entier et pour tout meetDo,
 *       quel que soit le compte qui le redésigne — voir la liste de blocage.</li>
 * </ul>
 *
 * <p>Il n'y a pas d'état « relancé » ni de retour de {@code REFUSED} vers autre
 * chose : un seul message part, et un non ne se re-sollicite pas.
 */
public enum ConsentState {
    PENDING,
    ACCEPTED,
    REFUSED
}
