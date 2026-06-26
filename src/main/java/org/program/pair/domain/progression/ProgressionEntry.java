package org.program.pair.domain.progression;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProgressionEntry {
    private UUID id;
    private UUID userId;
    private UUID programId;
    private String programTitle;
    private Instant loggedAt;
    private String notes;
    private Boolean isPublic;
}
