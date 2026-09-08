package org.program.pair.domain.affiche.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Une personne qui a publié, quand, et de quoi la dessiner.
 *
 * <p><b>Ce DTO ne portait qu'un identifiant et une date, et c'était juste — pour
 * un anneau.</b> Un anneau est une décoration posée sur un avatar qu'une liste
 * hôte a déjà chargé : la page « qui me suit », les contacts mutuels, un profil
 * ouvert. Quelqu'un d'autre apportait le visage, l'identifiant suffisait à savoir
 * chez qui allumer.
 *
 * <p><b>Une bande d'affiches n'a pas de liste hôte : elle <i>est</i> la
 * liste.</b> Elle part de cette réponse et de rien d'autre, et une réponse qui ne
 * porte que des identifiants ne peut dessiner personne. Croiser avec les listes
 * déjà chargées côté client couvre les amis et les abonnements, et rien du rang
 * « les autres » — exactement les gens que l'audience {@code EVERYONE} rend
 * visibles. Résoudre chaque identifiant par un {@code GET /users/{id}} serait la
 * requête par personne que cette route existe pour éviter.
 *
 * <p>Le critère, lui, n'a pas bougé d'un mot : <i>tout champ supplémentaire
 * serait une information sur quelqu'un livrée sans qu'un écran l'ait
 * demandée</i>. Un écran la demande maintenant, et il en demande deux — un nom et
 * une image, exactement ce qu'un visage réclame. Ni bio, ni badges, ni compte
 * d'abonnés : ceux-là n'ont toujours aucun écran derrière eux.
 *
 * <p><b>Pourquoi cela n'expose rien de neuf.</b> La route est <i>déjà</i> filtrée
 * par l'audience — c'est sa propriété fondatrice — donc elle ne nomme que des
 * personnes qui ont elles-mêmes décidé de rendre leur affiche visible au lecteur.
 * Et il n'existe aucun réglage de confidentialité de profil dans ce dépôt : ni
 * {@code isPrivate}, ni {@code profileVisibility} sur {@code User}. Le nom et
 * l'avatar sont inconditionnellement publics sur toutes les surfaces qui listent
 * des personnes ; il n'y a donc rien à protéger ici que le filtre d'audience ne
 * protège déjà, et ce filtre est dans le {@code WHERE}.
 */
public record AfficheUpdateDto(

    UUID userId,

    @Schema(description = "La plus récente des publications de cette personne que "
        + "j'ai le droit de voir. Strictement postérieure au since demandé.")
    Instant latestPublishedAt,

    @Schema(description = "Le nom affiché de cette personne. Sert à dessiner la bande "
        + "d'affiches, qui n'a aucune autre source pour les gens qu'aucune de mes listes "
        + "ne connaît. Toujours renseigné : la colonne est NOT NULL.")
    String displayName,

    @Schema(description = "L'avatar de cette personne, ou null quand elle n'en a pas — "
        + "le client a déjà son repli, le même que partout ailleurs.",
        nullable = true)
    String avatarUrl
) {}
