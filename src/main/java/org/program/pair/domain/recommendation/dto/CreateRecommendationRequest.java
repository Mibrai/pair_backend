package org.program.pair.domain.recommendation.dto;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateRecommendationRequest {

    @NotNull(message = "L'utilisateur recommandé est requis")
    private UUID recommendedId;

    @NotNull(message = "La note est requise")
    @Min(value = 1, message = "La note doit être entre 1 et 5")
    @Max(value = 5, message = "La note doit être entre 1 et 5")
    private Integer rating;

    @NotBlank(message = "Le commentaire est requis")
    @Size(min = 20, max = 500, message = "Le commentaire doit contenir entre 20 et 500 caractères")
    private String comment;

    private UUID activityContext;

    private UUID programContext;
}
