package org.program.pair.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.program.pair.domain.user.OnboardingStep;

public record AdvanceOnboardingRequest(

    @Schema(description = "Étape que la personne vient de franchir. Rejouer une étape "
        + "déjà enregistrée, ou en annoncer une antérieure, répond 200 sans rien "
        + "changer : le réseau mobile double les requêtes et les livre parfois dans "
        + "le désordre, et aucun de ces deux cas ne doit faire reculer un parcours.")
    @NotNull OnboardingStep step
) {}
