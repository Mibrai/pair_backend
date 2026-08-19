package org.program.pair.domain.language.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.program.pair.domain.language.LanguageProficiency;

@Schema(description = "Une langue parlée, telle que la personne la déclare.")
public record UserLanguageDto(

    @Schema(description = "Étiquette courte : fr, en, de… Normalisée en minuscules par "
        + "le serveur, pour que « FR » et « fr » ne fassent pas deux langues.")
    @NotBlank @Size(min = 2, max = 5)
    @Pattern(regexp = "[A-Za-z]{2,3}(-[A-Za-z]{2,4})?", message = "Étiquette de langue invalide.")
    String language,

    @Schema(description = "Déclaratif, jamais vérifié — le contrat le dit, et l'interface "
        + "doit le dire aussi.")
    @NotNull LanguageProficiency proficiency
) {}
