package org.program.pair.domain.watch;

import java.time.Instant;

/**
 * Les six états qu'un proche lit sur la page publique — et rien de l'état interne.
 *
 * <p>La page ne montre pas {@link WatchState} : elle montre l'un de ces six mots,
 * projetés depuis l'état interne. La projection est le seul endroit qui décide ce
 * qu'un proche a le droit de savoir.
 *
 * <p><b>« Rentrée » compte autant que les autres.</b> Quelqu'un réveillé par
 * l'alerte doit pouvoir recharger la page et lire « bien rentrée » sans attendre
 * le message de levée. C'est l'état d'arrivée qui manque le plus souvent à ce
 * genre de page, et le plus important.
 */
public enum PublicWatchStatus {

    EN_TRAJET("En trajet"),
    SUR_PLACE("Sur place"),
    REPARTIE_PLUS_TOT("Repartie plus tôt"),
    RETOUR_A_CONFIRMER("Retour à confirmer"),
    ALERTE_ENVOYEE("Alerte envoyée"),
    RENTREE("Bien rentrée");

    private final String libelle;

    PublicWatchStatus(String libelle) {
        this.libelle = libelle;
    }

    public String libelle() {
        return libelle;
    }

    /**
     * Ce que la page affiche, projeté depuis l'état interne de la veille.
     *
     * <p>L'ordre des tests compte : un état terminal l'emporte sur tout (rentrée),
     * puis l'alerte, puis l'interruption (repartie plus tôt), puis la distinction
     * « sur place » / « retour à confirmer » selon que l'échéance est franchie.
     */
    public static PublicWatchStatus of(Watch watch, Instant now) {
        if (WatchState.TERMINAUX.contains(watch.getState())) {
            return RENTREE;
        }
        if (watch.getState() == WatchState.ESCALATED) {
            return ALERTE_ENVOYEE;
        }
        if (watch.getInterruptedAt() != null) {
            return REPARTIE_PLUS_TOT;
        }
        if (watch.getState() == WatchState.REMINDING
                || (watch.getState() == WatchState.ON_SITE && now.isAfter(watch.getDeadlineAt()))) {
            return RETOUR_A_CONFIRMER;
        }
        if (watch.getState() == WatchState.ON_SITE) {
            return SUR_PLACE;
        }
        return EN_TRAJET; // ARMED, EN_ROUTE
    }
}
