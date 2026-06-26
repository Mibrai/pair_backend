package org.program.pair.domain.program.dto;

import jakarta.validation.constraints.*;
import org.program.pair.domain.program.PlaceType;

import java.time.Instant;

public record CreateScheduleRequest(
    @NotBlank @Size(max = 200) String placeName,
    @NotNull PlaceType placeType,
    @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
    @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
    String addressPublic,
    Boolean showExactAddress,
    @NotNull Instant startsAt,
    Instant endsAt,
    String recurrenceRule,
    @Min(1) Integer maxParticipants
) {}
