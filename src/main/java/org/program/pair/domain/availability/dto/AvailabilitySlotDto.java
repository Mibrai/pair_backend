package org.program.pair.domain.availability.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.program.pair.domain.availability.TimeSlot;

@Schema(description = "Une case de disponibilité habituelle.")
public record AvailabilitySlotDto(

    @Schema(description = "1 = lundi … 7 = dimanche, numérotation ISO.")
    @NotNull @Min(1) @Max(7) Short dayOfWeek,

    @NotNull TimeSlot timeSlot
) {}
