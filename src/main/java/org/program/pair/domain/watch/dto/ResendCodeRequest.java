package org.program.pair.domain.watch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Demander le renvoi du code de retour.
 *
 * <p><b>Le mot de passe du compte est exigé.</b> Le code oublié ne peut pas être
 * « renvoyé » à l'identique — le serveur ne le connaît plus, il n'en a que
 * l'empreinte — alors on en génère un nouveau. Redonner un code sans réauthentifier
 * ferait de cette route un contournement du secret : quelqu'un qui a le téléphone
 * déverrouillé pourrait se fabriquer un code et lever la veille à la place de son
 * propriétaire. Le mot de passe est ce qui rend « connu de lui seul » encore vrai
 * ici.
 */
@Schema(description = "Renvoi du code de retour, sous mot de passe.")
public record ResendCodeRequest(

    @Schema(description = "Le mot de passe du compte.")
    @NotBlank(message = "Le mot de passe est obligatoire.")
    String password
) {}
