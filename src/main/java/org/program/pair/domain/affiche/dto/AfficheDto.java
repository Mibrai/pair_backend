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
 * <p><b>{@code categoryName} et {@code cityLabel} sont entrés ensuite, et ils
 * n'ont pas le même statut que les deux précédents.</b> Ils rendent dessinable
 * l'affiche <i>de quelqu'un d'autre</i> : sans eux, une affiche de première
 * catégorie ou de première ville n'entre pas dans la bande du fil, faute de la
 * variable dont sa phrase a besoin — et le client ne fabrique aucune variable
 * manquante, ce qui est la bonne règle.
 *
 * <p><b>La ville n'est pas le lieu, et la distinction fait tout le travail.</b>
 * {@code placeName} n'est pas ici et n'y sera pas : le nom d'une salle, répété
 * sur une série d'affiches publiques, dessine un emploi du temps — c'est la
 * raison pour laquelle le motif dont le lieu est le sujet est sorti de la
 * sélection du client, et la garde de publication le refuse de toute façon. Une
 * ville ne dessine pas cela ; elle dit où l'on vit, ce que dit déjà toute la
 * surface publique de l'application. C'est la même valeur que
 * {@code SlotRecapDto.cityLabel}, et la carte-souvenir la rend depuis toujours à
 * des gens qui n'étaient pas là.
 *
 * <p><b>Ce que ce DTO ne porte toujours pas</b> : ni le titre du programme, ni
 * le nom du lieu, ni la moindre photo, ni rien de la carte-souvenir de l'hôte,
 * qui reste privée. En publiant, l'auteur décide de dire « j'ai fait de
 * l'escalade, à Grenoble, pour la première fois » — c'est <b>lui</b> qui le
 * décide, et il ne publie pas pour autant la fiche de la séance. Ce qui n'existe
 * pas au contrat ne peut pas être affiché par erreur demain : c'est la même
 * raison d'être que la liste courte de {@code SlotRecapDto}.
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

    @Schema(description = "Le NOM de la catégorie, sans quoi l'affiche d'un tiers portant "
        + "un motif de catégorie n'a pas la variable de sa phrase et n'entre pas dans la "
        + "bande. Ne se déduit pas de categoryColorRamp et ne s'y remplace pas : deux "
        + "catégories peuvent partager une rampe, et une rampe ne se dit pas. Même valeur "
        + "que SlotRecapDto.categoryName.",
        example = "Sports de montagne")
    String categoryName,

    @Schema(description = "La rampe de couleur de la catégorie de l'activité — un nom de "
        + "rampe résolu dans la palette du client, jamais un hexadécimal. Même valeur que "
        + "SlotRecapDto.categoryColorRamp, et lue par la même chaîne : deux chemins pour "
        + "la même teinte finiraient par diverger.",
        example = "red-orange")
    String categoryColorRamp,

    @Schema(description = "La ville de la séance, jamais le nom du lieu ni l'adresse : "
        + "celle-ci reste soumise aux règles de visibilité du créneau, et une affiche se "
        + "lit par des gens qui n'y étaient pas. Même valeur que SlotRecapDto.cityLabel. "
        + "Nulle quand la ville n'est pas renseignée — elle n'est jamais devinée à partir "
        + "des coordonnées.",
        nullable = true, example = "Grenoble")
    String cityLabel,

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
