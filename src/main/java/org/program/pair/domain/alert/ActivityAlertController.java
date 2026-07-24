package org.program.pair.domain.alert;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.alert.dto.ActivityAlertDto;
import org.program.pair.domain.alert.dto.CreateActivityAlertRequest;
import org.program.pair.domain.alert.dto.UpdateActivityAlertRequest;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class ActivityAlertController {

    private final ActivityAlertService alertService;

    @GetMapping
    public List<ActivityAlertDto> getMyAlerts(@AuthenticationPrincipal UserPrincipal principal) {
        return alertService.getMyAlerts(principal.getId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ActivityAlertDto create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateActivityAlertRequest request) {
        return alertService.createAlert(principal.getId(), request);
    }

    @PatchMapping("/{alertId}")
    public ActivityAlertDto update(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID alertId,
            @RequestBody UpdateActivityAlertRequest request) {
        return alertService.updateAlert(principal.getId(), alertId, request);
    }

    @DeleteMapping("/{alertId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID alertId) {
        alertService.deleteAlert(principal.getId(), alertId);
    }
}
