package org.program.pair.domain.incident;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.incident.dto.CreateIncidentRequest;
import org.program.pair.domain.incident.dto.IncidentDto;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Les incidents de sécurité de l'appelant.
 *
 * <p>Un registre séparé de {@code /reports} : la pièce jointe optionnelle passe
 * par le pipeline média existant (téléversée via {@code /api/media}, l'incident
 * n'en garde que l'URL). Seule une cible {@code PERSON} rejoint en plus la
 * modération.
 */
@RestController
@RequestMapping("/api/incidents")
@RequiredArgsConstructor
@Tag(name = "Incidents", description = "Registre des incidents de sécurité")
@SecurityRequirement(name = "bearerAuth")
public class IncidentController {

    private final IncidentService incidentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Signaler un incident",
        description = "Cible PERSON, PLACE, ORGANISATION, TRANSIT ou SELF. PERSON rejoint aussi la modération.")
    public IncidentDto create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateIncidentRequest request) {
        return incidentService.create(principal.getId(), request);
    }

    @GetMapping("/me")
    @Operation(summary = "Mes incidents")
    public List<IncidentDto> mine(@AuthenticationPrincipal UserPrincipal principal) {
        return incidentService.mine(principal.getId());
    }
}
