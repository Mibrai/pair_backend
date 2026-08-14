package org.program.pair.domain.recap.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.program.pair.domain.user.dto.UserPublicDto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * La carte-souvenir telle qu'elle se lit.
 *
 * <p>Elle porte sur le <b>moment collectif</b>, jamais sur les individus qui y
 * étaient : aucune donnée de performance, aucune note, aucun classement, aucun
 * compteur de réactions. Ce qui n'existe pas au contrat ne peut pas être
 * affiché par erreur demain — c'est la raison d'être de cette liste courte.
 */
public record SlotRecapDto(

    UUID scheduleId,
    String programTitle,
    String activityName,
    String categoryName,
    String categoryColorRamp,
    Instant slotStartedAt,

    String placeName,

    @Schema(description = "Ville du créneau, jamais l'adresse exacte : celle-ci reste "
        + "soumise aux règles de visibilité du lieu, et une carte-souvenir est lisible "
        + "par des gens qui n'y étaient pas. Nulle quand la ville n'est pas renseignée — "
        + "elle n'est jamais devinée à partir des coordonnées.")
    String cityLabel,

    @Schema(description = "Nombre de personnes ayant confirmé leur présence, hôte compris. "
        + "Un effectif, pas un score : rien ne le compare à celui d'un autre créneau.")
    int attendeeCount,

    @Schema(description = "Trois ambiances au maximum, de la plus choisie à la moins "
        + "choisie. Le plafond est appliqué ici, pas seulement à l'écran.")
    List<VibeCountDto> topVibes,

    @Schema(description = "Trois photos au maximum, uniquement celles que leur auteur a "
        + "explicitement rendues publiques.")
    List<String> photoUrls,

    String hostNote,
    UserPublicDto host,

    @Schema(description = "Uniquement les personnes ayant explicitement accepté d'être "
        + "nommées. Les autres sont comptées dans attendeeCount et n'apparaissent nulle "
        + "part ailleurs.")
    List<UserPublicDto> visibleAttendees,

    @Schema(description = "Prochaine séance ouverte du même programme — ce qui transforme "
        + "un lecteur en participant. Nulle quand il n'y en a pas : franchement nulle, "
        + "pour que le client propose l'abonnement au programme plutôt qu'un cul-de-sac.")
    NextSlotDto nextSlot,

    String visibility,

    @Schema(description = "La fenêtre de sept jours est-elle encore ouverte pour moi, et "
        + "y étais-je ? Faux dès que l'une des deux conditions manque.")
    boolean canContribute,

    @Schema(description = "Les ambiances que j'ai déjà choisies, pour que le client les "
        + "repasse en surbrillance sans les redemander.")
    List<String> myVibes
) {}
