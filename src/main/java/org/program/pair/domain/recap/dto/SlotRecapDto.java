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

    @Schema(description = "Début de la SÉANCE dont cette carte est la trace, et non de "
        + "celle que porte aujourd'hui le créneau. Sur une série récurrente, la ligne de "
        + "créneau est avancée à sa prochaine occurrence dès qu'une séance se termine : "
        + "la lire aurait daté ce souvenir de la semaine à venir.")
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

    @Schema(description = "Quand la carte se fige : sept jours après la fin de la séance. "
        + "Nulle quand la fenêtre est déjà refermée. Le client ne pouvait pas la calculer "
        + "— la fenêtre court depuis la FIN du créneau, que le contrat ne porte pas, et "
        + "un compte à rebours approximatif sur une décision irréversible vaut moins que "
        + "pas de compte à rebours. C'est une date, pas une durée : elle ne se périme pas "
        + "en transit.",
        example = "2026-08-19T20:00:00Z", nullable = true)
    Instant recapWindowClosesAt,

    @Schema(description = "Les ambiances que j'ai déjà choisies, pour que le client les "
        + "repasse en surbrillance sans les redemander.")
    List<String> myVibes
) {}
