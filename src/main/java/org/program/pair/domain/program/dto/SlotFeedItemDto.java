package org.program.pair.domain.program.dto;

import org.program.pair.domain.user.dto.UserPublicDto;

import java.time.Instant;
import java.util.UUID;

public record SlotFeedItemDto(
    UUID scheduleId,
    UUID programId,
    String programTitle,
    String activityName,
    String categoryColorRamp,
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
    Integer maxParticipants,
    Integer participantCount,
    Boolean isOpenToPartners,
    String welcomeNote,
    String myParticipationStatus // null si je n'ai pas rejoint
) {}
