package org.program.pair.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * La nouvelle adresse demandée pour un compte.
 *
 * <p>Pas de mot de passe courant, contrairement à {@code ChangePasswordRequest} :
 * la route est déjà authentifiée, et la bascule ne se fait qu'après un clic dans
 * un e-mail envoyé à la nouvelle adresse. Le facteur de confirmation est ce
 * clic, et le demander deux fois n'ajouterait rien qu'un obstacle sur un chemin
 * qu'on emprunte précisément parce que quelque chose ne marche pas.
 */
public record ChangeEmailRequest(

    @Schema(description = "La nouvelle adresse. Normalisée en minuscules côté serveur. "
        + "Le compte n'en change qu'au clic du lien envoyé à cette adresse.",
        example = "nouvelle@example.org")
    @NotBlank @Email @Size(max = 255) String email
) {}
