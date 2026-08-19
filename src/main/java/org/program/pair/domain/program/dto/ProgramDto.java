package org.program.pair.domain.program.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProgramDto(
    UUID id,
    String title,
    String description,
    String status,
    Boolean isPublic,
    // L'auteur accepte-t-il les messages de ses participants ? Défaut : true.
    Boolean allowParticipantMessages,
    // organisateur
    UUID organizerId,
    String organizerName,
    String organizerAvatarUrl,
    String imageUrl,
    // activité / catégorie
    UUID userActivityId,
    String activityName,
    String activityIcon,
    UUID categoryId,
    String categoryName,
    // timing
    Instant nextSessionAt,
    Instant createdAt,
    Instant updatedAt,
    // médias & créneaux
    List<ScheduleDto> schedules,
    List<ProgramMediaDto> media,
    // agrégats
    Float averageScore,
    Integer reviewCount,
    Integer enrolledCount,
    // champs V26
    Integer durationWeeks,
    Integer sessionsPerWeek,
    Integer sessionDurationMinutes,
    int[] preferredDays,
    String preferredTime,
    Integer maxParticipants,
    String privacy,
    String goals,
    String prerequisites,
    String locationType,

    @Schema(description = "Par quel chemin le programme a été créé : FULL pour le "
        + "formulaire complet, QUICK pour le chemin court. Un programme QUICK n'a ni "
        + "description ni objectifs parce qu'on ne les lui a jamais demandés, et non "
        + "parce que son auteur les a laissés vides. Tolérer une valeur inconnue.")
    String createdVia
) {}
