package org.program.pair.domain.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Une séance où l'on était, et dont on l'a dit.
 *
 * <p>Le pendant de {@link PendingAttendanceDto} : celle-ci pose la question,
 * celle-là garde la réponse. L'une décrit ce qu'on n'a pas encore confirmé,
 * l'autre l'histoire que la confirmation a écrite.
 *
 * <p><b>Cette liste est l'histoire elle-même, pas son reflet.</b> Elle se lit
 * dans {@code attendances} et ne touche jamais {@code slot_recaps} — c'est
 * toute sa raison d'être. {@code GET /api/recaps/mine} ne rend que les cartes
 * portant <i>au moins une contribution</i> : une séance vécue où personne n'a
 * voté d'ambiance, écrit un mot ni partagé de photo n'y figure nulle part. Lire
 * son propre passé à travers ces cartes le fait donc dépendre de ce que des
 * tiers ont bien voulu y déposer, et cette dépendance a deux conséquences que
 * le client ne peut pas rattraper seul :
 *
 * <ul>
 *   <li><b>l'histoire change dans le passé</b> — un tiers ajoute une ambiance à
 *       une séance ancienne, la carte naît, et une séance qui n'existait pas
 *       hier apparaît aujourd'hui au milieu de la liste ;</li>
 *   <li><b>une présence confirmée tardivement sort une affiche à froid</b> —
 *       quelqu'un confirme lundi une séance de la semaine précédente, une carte
 *       ancienne et déjà contributive entre d'un coup, et le motif qu'elle
 *       déclenche n'avait jamais été servi : il est donc parfaitement légitime,
 *       et il parle d'une séance dont plus personne ne se souvient.</li>
 * </ul>
 *
 * <p>Ici, ni l'un ni l'autre n'est possible. Une contribution de tiers ne crée
 * aucune présence, et une confirmation tardive fait apparaître son entrée
 * <b>à l'instant où la personne confirme</b> — le seul instant où elle y pense.
 *
 * <p><b>Ce que ce DTO ne porte pas</b> : ni le titre du programme, ni le lieu,
 * ni la moindre trace des autres participants. Il sert à décider quel motif une
 * séance déclenche, pas à l'afficher ; ce qui n'existe pas au contrat ne peut
 * pas être montré par erreur demain.
 */
@Schema(description = "Une séance passée dont la présence a été confirmée.")
public record ConfirmedAttendanceDto(

    UUID scheduleId,

    @Schema(description = "Début de la SÉANCE vécue, et non de celle que porte aujourd'hui "
        + "la ligne de créneau, qu'un rollover a pu avancer d'une semaine. C'est la valeur "
        + "de attendances.attended_at, la même que SlotRecapDto.slotStartedAt et que "
        + "AfficheDto.slotStartedAt, et la même qu'accepte PUT /api/affiches/{scheduleId}. "
        + "C'est aussi la colonne que lit le module affiche pour décider qui a le droit de "
        + "publier : cette liste est donc exactement l'histoire qui vous y autorise.",
        example = "2026-09-05T18:00:00Z")
    Instant slotStartedAt,

    @Schema(description = "L'activité du programme dont relève la séance.")
    UUID activityId,

    String activityName,

    @Schema(description = "Nom de rampe de la catégorie, jamais un hexadécimal — la même "
        + "valeur que SlotRecapDto.categoryColorRamp, résolue par le client dans sa palette.",
        example = "green-teal")
    String categoryColorRamp
) {}
