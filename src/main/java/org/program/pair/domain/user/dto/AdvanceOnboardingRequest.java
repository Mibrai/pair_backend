package org.program.pair.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.program.pair.domain.user.OnboardingStep;

public record AdvanceOnboardingRequest(

    @Schema(description = "Étape que la personne vient de franchir, parmi ACTIVITIES, "
        + "LEVELS, LOCATION et PREVIEW — les quatre écrans du parcours, dans cet "
        + "ordre. Franchir le dernier referme l'accueil : il n'existe pas d'étape "
        + "« terminé », la fin se lisant sur onboardingCompletedAt.\n\n"
        + "Rejouer une étape déjà enregistrée, ou en annoncer une antérieure, répond "
        + "200 sans rien changer : le réseau mobile double les requêtes et les livre "
        + "parfois dans le désordre, et aucun de ces deux cas ne doit faire reculer "
        + "un parcours.\n\n"
        + "DÉPRÉCIÉ, accepté en transition : l'ancien vocabulaire WELCOME, DISCOVERY "
        + "et DONE reste relu et traduit (WELCOME→ACTIVITIES, DISCOVERY et "
        + "DONE→PREVIEW), pour ne pas casser une version publiée du client. Il perd "
        + "la distinction entre les deux premiers écrans ; envoyez les vrais noms.")
    @NotNull OnboardingStep step
) {}
