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

    // Facultatif : une recommandation est un geste binaire, pas une note
    // comparative. Quand fournie, reste bornée à 1..5.
    @Min(value = 1, message = "La note doit être entre 1 et 5")
    @Max(value = 5, message = "La note doit être entre 1 et 5")
    private Integer rating;

    // Facultatif : pas de minimum de longueur imposé, un mot suffit.
    @Size(max = 500, message = "Le commentaire ne doit pas dépasser 500 caractères")
    private String comment;

    private UUID activityContext;

    private UUID programContext;
}
