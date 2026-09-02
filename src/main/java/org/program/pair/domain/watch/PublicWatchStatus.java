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
     * <p>L'ordre des tests compte : la non-arrivée d'abord, puis un état terminal
     * qui l'emporte sur tout (rentrée), puis l'alerte, puis l'interruption (repartie
     * plus tôt), puis la distinction « sur place » / « retour à confirmer » selon
     * que l'échéance est franchie.
     *
     * <p><b>La non-arrivée se teste en premier, et c'est vital.</b> {@code
     * NOT_ARRIVED} est terminal ; sans ce test placé avant, il tomberait dans la
     * branche terminale et la page annoncerait <b>« Bien rentrée »</b> pour quelqu'un
     * qui n'est jamais arrivé — le pire résultat possible, et celui que personne ne
     * viendrait vérifier puisqu'il annonce une bonne nouvelle. La page dit « En
     * trajet » : c'est vrai, c'est le dernier état connu, et cela ne promet rien.
     *
     * <p>Le second test couvre les liens <b>hérités</b> : une veille armée avant la
     * décision du 02/09 a pu passer {@code ESCALATED} sans arrivée validée, avec un
     * jeton déjà distribué. Elle ne doit pas non plus afficher l'alerte. Le critère
     * est celui du contrat côté app — l'arrivée est-elle validée — et il est sans
     * ambiguïté depuis que {@code panic} refuse avant l'arrivée : un {@code
     * ESCALATED} sans {@code arrivalConfirmedAt} ne peut plus être qu'une
     * non-arrivée. Aucun jeton neuf n'est créé sur cette branche, donc ce cas
     * s'éteindra de lui-même.
     */
    public static PublicWatchStatus of(Watch watch, Instant now) {
        if (watch.getState() == WatchState.NOT_ARRIVED) {
            return EN_TRAJET;
        }
        if (watch.getState() == WatchState.ESCALATED && watch.getArrivalConfirmedAt() == null) {
            return EN_TRAJET;
        }
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
