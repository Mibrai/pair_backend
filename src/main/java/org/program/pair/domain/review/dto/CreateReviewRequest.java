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

    @NotNull(message = "La note est requise")
    @DecimalMin(value = "1.0", message = "La note doit être entre 1 et 5")
    @DecimalMax(value = "5.0", message = "La note doit être entre 1 et 5")
    private Float score;

    @NotBlank(message = "Le commentaire est requis")
    @Size(min = 30, max = 1000, message = "Le commentaire doit contenir entre 30 et 1000 caractères")
    private String comment;
}
