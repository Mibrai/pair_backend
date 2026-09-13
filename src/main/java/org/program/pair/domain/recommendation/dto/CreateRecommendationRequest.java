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

    // Ignorée (P-BL-10, décision du 13/09) : on recommande quelqu'un, on ne le
    // note plus. Acceptée sans validation pour qu'un client ancien qui
    // l'enverrait ne reçoive pas de 400 ; jamais écrite. Retirée ensuite.
    @io.swagger.v3.oas.annotations.media.Schema(deprecated = true,
        description = "Ignorée depuis le 13/09 (P-BL-10) : une recommandation ne porte plus de note.")
    @Deprecated
    private Integer rating;

    // Facultatif : pas de minimum de longueur imposé, un mot suffit.
    @Size(max = 500, message = "Le commentaire ne doit pas dépasser 500 caractères")
    private String comment;

    private UUID activityContext;

    private UUID programContext;
}
