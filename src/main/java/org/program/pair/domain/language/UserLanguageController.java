package org.program.pair.domain.language;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.language.dto.UserLanguageDto;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users/me/languages")
@RequiredArgsConstructor
public class UserLanguageController {

    private final UserLanguageService languageService;

    @GetMapping
    @Operation(summary = "Les langues que je déclare parler.")
    public List<UserLanguageDto> list(@AuthenticationPrincipal UserPrincipal principal) {
        return languageService.list(principal.getId());
    }

    @PutMapping
    @Operation(summary = "Remplace la liste complète.",
        description = "Le client envoie la liste telle qu'il veut la voir ; le serveur "
            + "l'aligne. Pas de verbes séparés pour ajouter et retirer : l'écran est une "
            + "liste de cases à cocher, pas un journal de modifications.")
    public List<UserLanguageDto> replace(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody List<UserLanguageDto> languages) {
        return languageService.replace(principal.getId(), languages);
    }
}
