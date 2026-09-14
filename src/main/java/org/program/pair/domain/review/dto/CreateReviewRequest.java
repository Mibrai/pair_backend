package org.program.pair.domain.review.dto;

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
public class CreateReviewRequest {

    @NotNull(message = "L'ID du programme est requis")
    private UUID programId;

    // Facultative, et ignorée (P-BL-10, D4, 14/09) : plus aucune note n'est
    // enregistrée. Encore acceptée, bornes comprises, parce que les versions
    // 1.1.0+16 et +17 de l'app l'envoient toujours ; la refuser ferait échouer
    // leur avis.
    @io.swagger.v3.oas.annotations.media.Schema(deprecated = true, nullable = true,
        description = "Facultative et ignorée depuis le 14/09 : aucune note n'est plus enregistrée. "
            + "Encore acceptée entre 1 et 5 pour les versions d'app qui l'envoient.")
    @DecimalMin(value = "1.0", message = "La note doit être entre 1 et 5")
    @DecimalMax(value = "5.0", message = "La note doit être entre 1 et 5")
    private Float score;

    @NotBlank(message = "Le commentaire est requis")
    @Size(min = 30, max = 1000, message = "Le commentaire doit contenir entre 30 et 1000 caractères")
    private String comment;
}
