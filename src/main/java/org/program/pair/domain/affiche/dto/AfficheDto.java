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
 * <p><b>{@code activityName} et {@code categoryColorRamp} disent de quoi
 * l'affiche parle</b>, et ils ont été ajoutés après coup, sur une décision
 * produit qui n'appartenait pas au serveur. Le motif seul ne suffit pas : sur
 * une galerie réelle de quinze affiches, trois séances partageaient le même
 * motif <i>et</i> la même catégorie — donc le même emoji et le même dégradé — et
 * donnaient trois carreaux rigoureusement identiques. Le nom de l'activité n'est
 * pas de l'ornement : c'est ce qui distingue une affiche d'une autre.
 *
 * <p><b>Ce que ce DTO ne porte pas, et ne portera pas par inadvertance</b> : ni
 * le titre du programme, ni le nom du lieu, ni la moindre photo. La phrase reste
 * vraie mot pour mot après l'ajout, et c'est ce qui confirme que les deux
 * nouveaux champs sont du bon côté de la ligne : en publiant, l'auteur décide de
 * dire « j'ai fait de l'escalade ». C'est <b>lui</b> qui le décide, et il ne
 * publie pas pour autant la fiche de la séance — la carte-souvenir de l'hôte
 * reste privée, et rien de ce qu'elle porte d'autre ne sort. Ce qui n'existe pas
 * au contrat ne peut pas être affiché par erreur demain — c'est la même raison
 * d'être que la liste courte de {@code SlotRecapDto}.
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

    @Schema(description = "Le nom de l'activité vécue, sans quoi deux affiches de même "
        + "motif et de même catégorie sont deux carreaux identiques : le visuel seul ne "
        + "les distingue pas. Publié parce que l'auteur, en publiant, décide de dire ce "
        + "qu'il a pratiqué — jamais le titre du programme ni le lieu, qui appartiennent "
        + "à la séance et non à lui. Même valeur que SlotRecapDto.activityName, lue par "
        + "la même chaîne.",
        example = "Escalade")
    String activityName,

    @Schema(description = "La rampe de couleur de la catégorie de l'activité — un nom de "
        + "rampe résolu dans la palette du client, jamais un hexadécimal. Même valeur que "
        + "SlotRecapDto.categoryColorRamp, et lue par la même chaîne : deux chemins pour "
        + "la même teinte finiraient par diverger.",
        example = "red-orange")
    String categoryColorRamp,

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
