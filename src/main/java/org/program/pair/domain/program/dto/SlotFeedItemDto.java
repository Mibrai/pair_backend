package org.program.pair.domain.program.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.program.pair.domain.user.dto.UserPublicDto;

import java.time.Instant;
import java.util.UUID;

public record SlotFeedItemDto(
    UUID scheduleId,
    UUID programId,
    String programTitle,

    @Schema(description = "Activité pratiquée. Même identifiant que le filtre activityId "
        + "de GET /slots/feed.")
    UUID activityId,
    String activityName,

    @Schema(description = "Catégorie de l'activité. Un identifiant, là où categoryColorRamp "
        + "n'est qu'une intention de teinte : c'est celui-ci qui permet de filtrer, de "
        + "recouper avec /categories, ou de rattraper un filtre côté client.")
    UUID categoryId,
    String categoryColorRamp,

    @Schema(description = "Niveau attendu, tel que l'organisateur l'a déclaré sur ce "
        + "créneau. Null quand il ne l'a pas précisé — dont tous les créneaux publiés avant "
        + "que ce champ existe. Jamais le niveau personnel de l'hôte.")
    String level,
    String format,
    UserPublicDto host,
    String placeName,
    String displayAddress,   // null si lieu privé non partagé
    Double lat,              // null si lieu privé non partagé
    Double lng,
    Double distanceMeters,   // null hors contexte de feed géolocalisé
    Instant startsAt,
    Instant endsAt,

    @Schema(description = "Fin effective de la séance : endsAt quand il est déclaré, sinon "
        + "startsAt + 2 h (P-BA-19). C'est la frontière à utiliser pour « terminé ». Toujours "
        + "renseignée.")
    Instant effectiveEndsAt,

    @Schema(description = "Vrai quand endsAt a été déclaré. Faux seulement pour d'anciens "
        + "créneaux : toute écriture exige désormais une fin.")
    boolean endsAtDeclared,

    @Schema(description = "Règle de récurrence RFC 5545, sans le préfixe RRULE:, au même "
        + "format que ScheduleDto.recurrenceRule. Nulle pour une séance unique. "
        + "startsAt/endsAt ne décrivent que la *prochaine* occurrence : sans cette règle, "
        + "un engagement hebdomadaire est indiscernable d'une séance unique, et un conflit "
        + "d'agenda à cinq semaines passe inaperçu.")
    String recurrenceRule,

    @Schema(description = "Durée d'une séance en minutes. Vaut endsAt - startsAt quand les "
        + "deux sont connus, sinon la durée déclarée sur le programme. Nulle quand ni l'une "
        + "ni l'autre n'existe : l'API ne devine pas de durée, à charge de l'appelant de "
        + "décider ce qu'il fait de l'inconnu.")
    Integer sessionDurationMinutes,

    @Schema(description = "Instant de publication du créneau, en UTC. C'est la date sur "
        + "laquelle porte le filtre createdSince — celle qui répond à « qu'y a-t-il de "
        + "neuf ? », que startsAt ne sait pas exprimer.")
    Instant createdAt,

    @Schema(description = "État du créneau : OPEN, FULL, CANCELLED ou PAST. **C'est la "
        + "seule chose que les dates ne savent pas dire : l'annulation.** Un créneau "
        + "annulé restait indiscernable d'un créneau normal dans « Mes créneaux » comme "
        + "sur sa fiche — startsAt et endsAt continuaient d'annoncer une séance qui "
        + "n'aurait pas lieu. Une annulation ne peut venir que du serveur : ce champ "
        + "prime sur tout calcul de statut fait à partir des dates.",
        allowableValues = {"OPEN", "FULL", "CANCELLED", "PAST"})
    String status,

    @Schema(description = "Instant de l'annulation, en UTC. Nul tant que le créneau n'est "
        + "pas annulé.")
    Instant cancelledAt,

    @Schema(description = "Motif donné par l'organisateur, à montrer tel quel aux "
        + "inscrits. Nul si le créneau n'est pas annulé, ou si l'organisateur n'a pas "
        + "donné de motif — l'annulation reste valable sans lui.")
    String cancellationReason,

    Integer maxParticipants,
    Integer participantCount,
    Boolean isOpenToPartners,
    String welcomeNote,
    String myParticipationStatus, // null si je n'ai pas rejoint

    @Schema(description = "Mon rang dans la liste d'attente, à partir de 1. Nul si je "
        + "n'y suis pas. C'est le serveur qui le tient : le déduire côté client "
        + "supposerait de connaître toute la file, qui n'est visible que de l'hôte.")
    Integer myWaitlistPosition,

    @Schema(description = "Langue principale annoncée pour la séance, ou null. Nulle dans "
        + "le cas normal : la plupart des créneaux n'en déclarent pas, et un créneau sans "
        + "langue n'est jamais écarté par le filtre du fil.")
    String primaryLanguage,

    @Schema(description = "Étiquettes d'accueil annoncées par l'organisateur. "
        + "**Déclaratives, jamais vérifiées** : personne ne contrôle qu'une salle "
        + "annoncée accessible en fauteuil l'est réellement. L'interface doit les "
        + "présenter comme des annonces, pas comme des faits établis — le coût de "
        + "l'erreur retombe sur la personne qui s'est déplacée. Vide dans le cas normal.")
    java.util.List<String> accessibilityTags,

    @Schema(description = "Le programme du créneau annonce des frais à prévoir. False ne "
        + "veut pas dire gratuit : seulement que rien n'a été annoncé.")
    boolean costToShare,

    @Schema(description = "Précision libre sur les frais, reprise du programme, ou null. "
        + "Toujours null quand costToShare est false.")
    String costNote
) {}
