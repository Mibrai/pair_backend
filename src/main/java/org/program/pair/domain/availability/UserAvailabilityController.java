package org.program.pair.domain.availability;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.availability.dto.AvailabilitySlotDto;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users/me/availability")
@RequiredArgsConstructor
public class UserAvailabilityController {

    private final UserAvailabilityService availabilityService;

    @GetMapping
    @Operation(summary = "Mes disponibilités habituelles.")
    public List<AvailabilitySlotDto> list(@AuthenticationPrincipal UserPrincipal principal) {
        return availabilityService.list(principal.getId());
    }

    @PutMapping
    @Operation(summary = "Remplace la grille complète.",
        description = "Ces cases font remonter les créneaux qui tombent bien ; elles "
            + "n'écartent jamais ceux qui tombent mal. Une disponibilité déclarée est "
            + "une habitude, pas un engagement : qui a coché « mardi soir » peut très "
            + "bien vouloir un samedi matin.")
    public List<AvailabilitySlotDto> replace(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody List<AvailabilitySlotDto> slots) {
        return availabilityService.replace(principal.getId(), slots);
    }
}
