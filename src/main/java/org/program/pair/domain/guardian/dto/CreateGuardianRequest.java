package org.program.pair.domain.guardian.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Désigner un contact d'urgence : soit un membre meetDo, soit quelqu'un du dehors.
 *
 * <p>La règle « l'un ou l'autre, pas les deux » n'est pas exprimable proprement
 * par des annotations seules — elle est vérifiée dans le service, qui rend un 422
 * lisible plutôt qu'une contrainte de base opaque. Ici, on ne borne que les
 * formats.
 */
@Schema(description = "Un contact d'urgence à désigner : un membre meetDo, ou un contact externe.")
public record CreateGuardianRequest(

    @Schema(description = "Identifiant du compte meetDo du contact, s'il en a un. "
        + "Exclusif des trois champs suivants.")
    UUID memberId,

    @Schema(description = "Nom du contact externe, tel que vous le connaissez.")
    @Size(max = 120, message = "Le nom ne peut pas dépasser 120 caractères.")
    String name,

    @Schema(description = "Téléphone du contact externe. Sera normalisé ; un numéro "
        + "qui a déjà refusé d'être sollicité est refusé.")
    @Size(max = 30, message = "Le numéro est trop long.")
    String phone,

    @Schema(description = "E-mail du contact externe.")
    @Email(message = "L'adresse e-mail n'est pas valide.")
    @Size(max = 255, message = "L'adresse e-mail est trop longue.")
    String email
) {}
