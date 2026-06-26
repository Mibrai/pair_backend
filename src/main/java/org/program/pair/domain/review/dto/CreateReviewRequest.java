package org.program.pair.domain.review.dto;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateReviewRequest {

    @NotNull(message = "L'ID du programme est requis")
    private UUID programId;

    @NotNull(message = "La note globale est requise")
    @Min(value = 1, message = "La note doit être entre 1 et 5")
    @Max(value = 5, message = "La note doit être entre 1 et 5")
    private Integer overallRating;

    @NotNull(message = "Les scores par critère sont requis")
    @Size(min = 5, max = 5, message = "Les 5 critères doivent être évalués")
    private Map<String, Integer> criteriaScores;
    // Keys: ORGANIZATION, COMMUNICATION, ATMOSPHERE, DIFFICULTY, RECOMMENDATION
    // Values: 1-5

    @NotBlank(message = "Le commentaire est requis")
    @Size(min = 30, max = 1000, message = "Le commentaire doit contenir entre 30 et 1000 caractères")
    private String comment;
}
