package org.program.pair.domain.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
    @NotBlank(message = "Le mot de passe actuel est requis")
    String currentPassword,

    @NotBlank(message = "Le nouveau mot de passe est requis")
    @Size(min = 8, max = 128, message = "Le mot de passe doit contenir entre 8 et 128 caractères")
    @Pattern(
        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).*$",
        message = "Le mot de passe doit contenir au moins une minuscule, une majuscule et un chiffre"
    )
    String newPassword
) {}
