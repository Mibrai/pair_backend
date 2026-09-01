package org.program.pair.domain.watch;

import java.time.Instant;

/**
 * Tout ce que la page de statut publique affiche — et rien d'autre.
 *
 * <p>Une liste <b>fermée</b>, comme {@code SafetyShareView}. Ce qui n'y figure pas
 * ne doit jamais y figurer : l'adresse exacte (le {@code displayAddress} d'un
 * créneau porte le numéro et la rue quand l'hôte a gardé le lieu privé), un
 * téléphone, un e-mail, la liste des participants, tout identifiant interne. On ne
 * publie que le prénom, l'état, le nom du lieu et la ville, et les heures.
 *
 * <p>{@code lastUpdateAt} est affiché parce qu'une page qui feint la fraîcheur est
 * pire qu'une page honnête : quand le téléphone est mort, « actualisé il y a
 * 47 min » est plus utile qu'un état qui prétend être à jour.
 */
public record PublicWatchView(

    PublicWatchStatus status,
    String personGivenName,
    String activityName,
    String placeName,
    String city,
    Instant startsAt,
    Instant endsAt,
    Instant deadlineAt,
    Instant lastUpdateAt,

    /** Vrai si la veille est terminée : la page peut dire « rien à faire ». */
    boolean terminal
) {}
