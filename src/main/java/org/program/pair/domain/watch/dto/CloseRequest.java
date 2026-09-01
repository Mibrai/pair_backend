package org.program.pair.domain.watch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Refermer une veille avec son code.
 *
 * <p><b>{@code enteredAt} fait foi, pas l'heure de réception.</b> Une saisie faite
 * hors ligne à 23:32 et transmise à 23:58 doit lever la veille comme si elle
 * arrivait à 23:32 — sinon rentrer chez soi dans un immeuble mal couvert
 * déclencherait une alerte chez un proche. C'est l'heure saisie qui date la
 * clôture dans la chronologie.
 */
@Schema(description = "Clôture d'une veille par le code de retour.")
public record CloseRequest(

    @Schema(description = "Le code de retour, tel que l'utilisateur le saisit.")
    @NotBlank(message = "Le code est obligatoire.")
    String code,

    @Schema(description = "L'heure à laquelle l'utilisateur a saisi le code. Fait foi, "
        + "même transmise plus tard.")
    @NotNull(message = "L'heure de saisie est obligatoire.")
    Instant enteredAt
) {}
