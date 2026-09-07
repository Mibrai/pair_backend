package org.program.pair.domain.affiche.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Une affiche telle qu'elle se lit.
 *
 * <p>Quatre champs demandés au contrat — {@code scheduleId}, {@code motif},
 * {@code publishedAt}, {@code audience} — et deux ajoutés, chacun pour une
 * raison que le client ne pouvait pas contourner seul : {@code slotStartedAt},
 * sans quoi deux affiches d'un même cours hebdomadaire seraient
 * indiscernables, et {@code featuredUntil}, qui est la réponse à la demande 3.
 *
 * <p><b>Ce que ce DTO ne porte pas, et ne portera pas par inadvertance</b> : ni
 * le titre du programme, ni le nom du lieu, ni la moindre photo. L'auteur a
 * consenti à publier <i>une affiche</i>, pas la fiche de la séance. Ce qui
 * n'existe pas au contrat ne peut pas être affiché par erreur demain — c'est la
 * même raison d'être que la liste courte de {@code SlotRecapDto}.
 */
public record AfficheDto(

    @Schema(description = "Le créneau dont l'affiche est la trace. Clé du contrat côté "
        + "client, à lire toujours avec slotStartedAt sur un créneau récurrent.")
    UUID scheduleId,

    @Schema(description = "Début de la SÉANCE affichée — la même valeur que "
        + "SlotRecapDto.slotStartedAt, et non le début que porte aujourd'hui la ligne de "
        + "créneau, qu'un rollover a pu avancer. C'est ce champ qui distingue deux "
        + "affiches d'un même cours hebdomadaire.")
    Instant slotStartedAt,

    @Schema(description = "La clé de motif transmise à la publication, rendue telle quelle. "
        + "Le serveur ne l'interprète pas : le texte et le visuel restent côté client.",
        example = "PREMIERE_FOIS")
    String motif,

    @Schema(description = "Quand cette affiche est devenue visible sous son audience "
        + "actuelle. Rafraîchie quand l'audience s'OUVRE (NOBODY → SUBSCRIBERS → "
        + "EVERYONE), jamais sur un simple changement de motif ni sur un "
        + "resserrement : c'est la date que GET /api/affiches/updates compare à "
        + "since, donc celle qui allume l'anneau.")
    Instant publishedAt,

    @Schema(description = "NOBODY, SUBSCRIBERS ou EVERYONE. Appliquée par le serveur, pas "
        + "seulement déclarée. Rendue aussi aux lecteurs autorisés : elle ne leur "
        + "apprend rien qu'ils ne sachent déjà — ils voient l'affiche.")
    String audience,

    @Schema(description = "Fin de la fenêtre de mise en avant : sept jours après la FIN de "
        + "la séance, que le client ne connaissait pas. C'est une date, pas une durée — "
        + "elle ne se périme pas en transit. Passée, l'affiche descend en galerie ; le "
        + "champ reste renseigné, il ne s'annule pas, pour qu'un tri par ancienneté "
        + "reste possible.",
        example = "2026-09-14T20:00:00Z")
    Instant featuredUntil
) {}
