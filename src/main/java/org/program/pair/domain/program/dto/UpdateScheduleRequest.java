package org.program.pair.domain.program.dto;

import jakarta.validation.constraints.*;
import org.program.pair.domain.program.PlaceType;

import java.time.Instant;

public record UpdateScheduleRequest(
    @Size(max = 200) String placeName,
    PlaceType placeType,
    @DecimalMin("-90") @DecimalMax("90") Double lat,
    @DecimalMin("-180") @DecimalMax("180") Double lng,
    String addressPublic,
    Boolean showExactAddress,

    // Voir CreateScheduleRequest.city : nulle laisse la valeur en place.
    @Size(max = 120) String city,

    Instant startsAt,
    Instant endsAt,
    String recurrenceRule,
    @Min(1) Integer maxParticipants,
    Boolean isOpenToPartners,
    @Size(max = 300) String welcomeNote
) {}
